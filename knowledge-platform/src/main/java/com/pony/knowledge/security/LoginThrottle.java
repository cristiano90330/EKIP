/* 按账号和来源地址记录失败次数并判断是否需要阻止登录。 */

package com.pony.knowledge.security;

import com.pony.knowledge.config.LoginThrottleProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;

/**
 * 账户登录的失败次数规则，按用户名与来源 IP 的组合计数。
 * UserController 在认证前检查是否锁定，认证成功清除计数，失败累加且不延长已有窗口。
 * 当前 Redis 计数读写异常只记录日志，不阻断本次登录认证。
 */
@Component
public class LoginThrottle {

    private static final Logger log = LoggerFactory.getLogger(LoginThrottle.class);

    /** 计数键：login:fail:{username}:{ip}。用户名统一小写，username 在前便于按用户名 SCAN 排查。 */
    private static final String KEY_PATTERN = "login:fail:%s:%s";

    private final StringRedisTemplate redis;

    private final LoginThrottleProperties properties;

    /**
     * 保存按账号和来源地址记录失败次数并判断是否需要阻止登录所需的依赖和初始配置。
     * 上游为 Spring 组件装配。
     *
     * @param redis 访问 Redis 保存和读取状态的客户端
     * @param properties 供本组件执行时读取的配置约束
     */
    public LoginThrottle(StringRedisTemplate redis, LoginThrottleProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    /**
     * 根据账号与来源地址当前计数确认本次登录是否可继续。
     * 上游为 UserController.login。
     * 下游为 LoginThrottleProperties.isEnabled。
     *
     * @param ip 登录请求识别出的来源地址
     * @param username 登录用户名
     */
    public void assertAllowed(String ip, String username) {
        if (!properties.isEnabled()) {
            return;
        }
        String key = key(username, ip);
        Long failures;

        try {
            failures = readCount(key);
        } catch (Exception e) {
            log.error("登录限流读取失败，本次降级为不限流: key={}", key, e);
            return;
        }
        if (failures != null && failures >= properties.getMaxAttempts()) {
            throw new LoginLockedException(retryAfterSeconds(), username, ip);
        }
    }

    /**
     * 为账号与来源地址递增失败计数并设置锁定窗口。
     * 上游为 UserController.login。
     * 下游为 LoginThrottleProperties.isEnabled。
     *
     * @param ip 登录请求识别出的来源地址
     * @param username 登录用户名
     */
    public void recordFailure(String ip, String username) {
        if (!properties.isEnabled()) {
            return;
        }
        String key = key(username, ip);
        try {
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redis.expire(key, Duration.ofMinutes(properties.getLockMinutes()));
            }
        } catch (Exception e) {
            log.error("登录限流写入失败，本次失败未计入: key={}", key, e);
        }
    }

    /**
     * 清除通过认证的账号与来源地址失败记录。
     * 上游为 UserController.login。
     * 下游为 LoginThrottleProperties.isEnabled。
     *
     * @param ip 登录请求识别出的来源地址
     * @param username 登录用户名
     */
    public void recordSuccess(String ip, String username) {
        if (!properties.isEnabled()) {
            return;
        }
        String key = key(username, ip);
        try {
            redis.delete(key);
        } catch (Exception e) {
            log.error("登录限流清除失败，计数将随 TTL 自然过期: key={}", key, e);
        }
    }

    /**
     * 读取当前窗口中的登录失败次数。
     * 上游为 LoginThrottle.assertAllowed。
     *
     * @param key 本方法计算或判断使用的 key 值
     * @return 当前账号与地址在该时间窗口内的连续失败次数
     */
    private Long readCount(String key) {
        String raw = redis.opsForValue().get(key);
        if (raw == null) {
            return null;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            // 键被写坏（人为 SET 了非数字）：当作没计数，而不是把登录接口整个打死
            log.error("登录限流计数不是数字，按未计数处理: key={}, value={}", key, raw);
            return null;
        }
    }

    /**
     * 读取锁定键剩余时间，转换为客户端可重试的秒数。
     * 上游为 LoginThrottle.assertAllowed。
     * 下游为 LoginThrottleProperties.getLockMinutes。
     *
     * @return 允许重试前剩余的秒数
     */
    private long retryAfterSeconds() {
        return properties.getLockMinutes() * 60L;
    }

    /**
     * 根据规范化用户名与访问来源构造独立的限流键。
     * 上游为 LoginThrottle.assertAllowed、LoginThrottle.recordFailure。
     *
     * @param username 登录用户名
     * @param ip 登录请求识别出的来源地址
     * @return 本次计算或查询得到的 String 结果
     */
    private static String key(String username, String ip) {
        return String.format(KEY_PATTERN, username.toLowerCase(Locale.ROOT), ip);
    }
}
