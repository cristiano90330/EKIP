/* 在 Redis 中管理刷新令牌轮换和会话撤销。 */

package com.pony.knowledge.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 为登录、刷新和登出保存刷新票据，以及所属会话的吊销标记。
 * redeem 只原子取走一张旧票据；核对结果和签发新令牌由 UserServiceImpl 继续完成。
 * 登记失败不阻断登录；核验故障拒绝本次刷新，但不作为重放证据吊销整个会话。
 */
@Component
public class RefreshTokenStore {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenStore.class);

    /** 活跃登记键。jti 是 UUID，全局唯一，天然按票据粒度登记与消费。 */
    private static final String KEY_PATTERN = "auth:refresh:%s";

    /** 同一刷新会话的吊销标记键。 */
    private static final String DEAD_SESSION_PATTERN = "auth:family:dead:%s";

    /** 登记值分隔符。userId 是数字、sid 是 UUID，两者都不含竖线，所以取第一个竖线即可。 */
    private static final String VALUE_SEPARATOR = "|";

    private final StringRedisTemplate redis;

    private final Duration ttl;

    /**
     * 保存在 Redis 中管理刷新令牌轮换和会话撤销所需的依赖和初始配置。
     * 上游为 Spring 组件装配。
     *
     * @param redis 访问 Redis 保存和读取状态的客户端
     * @param refreshExpireDays 本方法计算或判断使用的 refreshExpireDays 值
     */
    public RefreshTokenStore(StringRedisTemplate redis,
                             @Value("${jwt.refresh-expire-days:7}") long refreshExpireDays) {
        this.redis = redis;
        this.ttl = Duration.ofDays(refreshExpireDays);
    }

    /** 旧票已消费、旧票缺失、核验故障三种结果，分别交给账户 Service 决策。 */
    public enum RedeemStatus { CONSUMED, MISSING, ERROR }

    /**
     * redeem 的结果：状态 +（仅 CONSUMED 时）登记在册的 userId 与 sid。
     * MISSING / ERROR 不携带任何数据——服务端此刻不该猜。
     */
    public record Redeemed(RedeemStatus status, Long userId, String sessionId) {

        // 必须 public：调用方 UserServiceImpl 在 service.impl 包下，包级私有跨不到那个包
        /**
         * 携带消费结果和存储值，供刷新令牌轮换处理。
         * 上游为 RefreshTokenStore.redeem。
         *
         * @param userId 用户 ID
         * @param sessionId 会话标识
         * @return 本次计算或查询得到的 Redeemed 结果
         */
        public static Redeemed consumed(Long userId, String sessionId) {
            return new Redeemed(RedeemStatus.CONSUMED, userId, sessionId);
        }

        /**
         * 构建 Redis 找不到可消费记录时的兑换结果。
         * 上游为 RefreshTokenStore.redeem。
         *
         * @return 本次计算或查询得到的 Redeemed 结果
         */
        public static Redeemed missing() {
            return new Redeemed(RedeemStatus.MISSING, null, null);
        }

        /**
         * 构建刷新存储操作故障时的兑换结果。
         * 上游为 RefreshTokenStore.redeem。
         *
         * @return 本次计算或查询得到的 Redeemed 结果
         */
        public static Redeemed error() {
            return new Redeemed(RedeemStatus.ERROR, null, null);
        }
    }

    /**
     * 将可兑换的刷新令牌和绑定会话写入 Redis 并设置有效期。
     * 上游为 UserServiceImpl.issueTokenPair。
     * 下游为 RefreshTokenStore.key。
     *
     * @param tokenId 要定位的关联标识
     * @param userId 用户 ID
     * @param sessionId 会话标识
     */
    public void save(String tokenId, Long userId, String sessionId) {
        String key = key(tokenId);
        try {
            redis.opsForValue().set(key, userId + VALUE_SEPARATOR + sessionId, ttl);
        } catch (Exception e) {
            log.error("刷新令牌登记失败，登录仍继续，但该会话下次刷新需重新登录: key={}", key, e);
        }
    }

    /**
     * 原子消费刷新令牌并交回记录，用于检测重复使用。
     * 上游为 UserServiceImpl.refresh。
     * 下游为 Redeemed.error、Redeemed.missing。
     *
     * @param tokenId 要定位的关联标识
     * @return 成功消费、找不到记录或存储出错的区分结果
     */
    public Redeemed redeem(String tokenId) {
        String key = key(tokenId);
        String raw;

        try {
            // Redis 内原子读取并删除旧刷新令牌，避免并发刷新同时取得使用权。
            raw = redis.opsForValue().getAndDelete(key);
        } catch (Exception e) {
            log.error("刷新令牌核验失败，按无效处理、要求重新登录，但不吊销整个会话: key={}", key, e);
            return Redeemed.error();
        }
        if (raw == null) {
            return Redeemed.missing();
        }
        int separator = raw.indexOf(VALUE_SEPARATOR);

        if (separator < 0) {
            log.error("刷新令牌登记值缺少分隔符，格式已损坏，按核验失败处理: key={}, value={}", key, raw);
            return Redeemed.error();
        }

        try {
            Long userId = Long.valueOf(raw.substring(0, separator));
            return Redeemed.consumed(userId, raw.substring(separator + 1));
        } catch (NumberFormatException e) {
            log.error("刷新令牌登记值的 userId 不是数字，按核验失败处理: key={}, value={}", key, raw);
            return Redeemed.error();
        }
    }

    /**
     * 检查刷新令牌对应的会话是否已有撤销记录。
     * 上游为 UserServiceImpl.refresh。
     * 下游为 RefreshTokenStore.deadKey。
     *
     * @param sessionId 会话标识
     * @return 条件是否成立（检查刷新令牌对应的会话是否已有撤销记录）
     */
    public boolean isSessionDead(String sessionId) {
        String key = deadKey(sessionId);
        try {
            return Boolean.TRUE.equals(redis.hasKey(key));
        } catch (Exception e) {
            log.error("会话吊销状态核验失败，按已吊销处理: key={}", key, e);
            return true;
        }
    }

    /**
     * 标记指定会话已经结束，阻止后续刷新。
     * 上游为 UserServiceImpl.refresh、UserServiceImpl.logout。
     * 下游为 RefreshTokenStore.deadKey。
     *
     * @param sessionId 会话标识
     */
    public void killSession(String sessionId) {
        String key = deadKey(sessionId);
        try {
            redis.opsForValue().set(key, "1", ttl);
        } catch (Exception e) {
            log.error("会话吊销标记写入失败，该会话仍可能在下次重放时被清算: key={}", key, e);
        }
    }

    /**
     * 移除指定刷新令牌的可兑换状态。
     * 上游为 UserServiceImpl.logout。
     * 下游为 RefreshTokenStore.key。
     *
     * @param tokenId 要定位的关联标识
     */
    public void revoke(String tokenId) {
        String key = key(tokenId);
        try {
            redis.delete(key);
        } catch (Exception e) {
            log.error("刷新令牌吊销失败，将由其自身过期兜底: key={}", key, e);
        }
    }

    /**
     * 为给定令牌标识生成刷新状态存储键。
     * 上游为 RefreshTokenStore.save、RefreshTokenStore.redeem。
     *
     * @param tokenId 要定位的关联标识
     * @return 本次计算或查询得到的 String 结果
     */
    private static String key(String tokenId) {
        return String.format(KEY_PATTERN, tokenId);
    }

    /**
     * 为给定会话标识生成会话撤销锁定键。
     * 上游为 RefreshTokenStore.isSessionDead、RefreshTokenStore.killSession。
     *
     * @param sessionId 会话标识
     * @return 本次计算或查询得到的 String 结果
     */
    private static String deadKey(String sessionId) {
        return String.format(DEAD_SESSION_PATTERN, sessionId);
    }
}
