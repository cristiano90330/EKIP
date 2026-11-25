/* 用会话、工具和参数摘要标识具有副作用的工具审批记录。 */

package com.pony.knowledge.agent.tool.approval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * 工具审批门（HITL）的审批状态存储。
 *
 * <p>语义：只有本次 (session, 工具, 参数) 被显式批准过才放行，其余一律拒绝。
 * 状态放 Redis（跨实例共享），键为：
 * <pre>agent:approval:{sessionId}:{toolName}:{argsHash}</pre>
 * 值 = {@code APPROVED:<approverUserId>} 或 {@code REJECTED:<reason>}。</p>
 *
 * <p>argsHash = 参数 JSON 原文的 SHA-256。键里带参数哈希，审批被精确绑定到这一次调用：
 * LLM 改了哪怕一个空格，哈希不同 → 不匹配 → fail-close。审批人批准的是某一份具体参数，
 * 执行时参数被改动就核验不过。</p>
 *
 * <p>降级方向单一且严格 fail-closed（同 {@code RefreshTokenStore.isSessionDead}）：
 * 读 Redis 失败按「未批准」处理。审批门是写操作的安全承诺，核验不了绝不能放行。</p>
 */
@Component
public class ToolApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ToolApprovalService.class);

    private static final String KEY_PATTERN = "agent:approval:%s:%s:%s";
    private static final String APPROVED_PREFIX = "APPROVED:";
    private static final String REJECTED_PREFIX = "REJECTED:";

    private final StringRedisTemplate redis;

    private final Duration ttl;

    /**
     * 用注入依赖和构造参数建立此实例执行时使用的状态。
     * 下游调用 {@code Duration.ofMinutes}。
     *
     * @param redis 当前模块使用的 Redis 客户端
     * @param ttlMinutes 本次调用传入的 {@code long}
     */
    public ToolApprovalService(StringRedisTemplate redis,
                               @Value("${agent.tool.approval.ttl-minutes:30}") long ttlMinutes) {
        this.redis = redis;
        this.ttl = Duration.ofMinutes(ttlMinutes);
    }

    /**
     * 为当前会话和原始参数保存已批准状态及其到期时间。
     * 下游调用 {@code redis.opsForValue}、{@code key}、{@code argsHash}。
     *
     * @param sessionId 受当前用户与策略约束的服务端会话 ID
     * @param toolName 工具在注册表中的公开名称
     * @param argsJson 模型提交的原始工具参数 JSON
     * @param approverUserId 对工具调用作出决定的认证用户 ID
     */
    public void approve(String sessionId, String toolName, String argsJson, Long approverUserId) {
        String key = key(sessionId, toolName, argsHash(argsJson));
        try {
            redis.opsForValue().set(key, APPROVED_PREFIX + (approverUserId == null ? "" : approverUserId), ttl);
        } catch (Exception e) {
            log.error("工具审批批准写入失败（不阻断，但本次调用仍不会放行）: key={}", key, e);
        }
    }

    /**
     * 为当前会话和原始参数记录已拒绝状态及处理诊断。
     * 下游调用 {@code redis.opsForValue}、{@code key}、{@code argsHash}。
     *
     * @param sessionId 受当前用户与策略约束的服务端会话 ID
     * @param toolName 工具在注册表中的公开名称
     * @param argsJson 模型提交的原始工具参数 JSON
     * @param approverUserId 对工具调用作出决定的认证用户 ID
     * @param reason 诊断或终止原因
     */
    public void reject(String sessionId, String toolName, String argsJson, Long approverUserId, String reason) {
        String key = key(sessionId, toolName, argsHash(argsJson));
        try {
            redis.opsForValue().set(key, REJECTED_PREFIX + (reason == null ? "" : reason), ttl);
        } catch (Exception e) {
            log.error("工具审批拒绝写入失败: key={}", key, e);
        }
    }

    /**
     * 按会话、工具和参数摘要查找已批准信息，存储故障时按拒绝处理。
     * 上游由 {@code ToolExecutor.execute} 调用。
     * 下游调用 {@code redis.opsForValue}、{@code key}、{@code argsHash}。
     *
     * @param sessionId 受当前用户与策略约束的服务端会话 ID
     * @param toolName 工具在注册表中的公开名称
     * @param argsJson 模型提交的原始工具参数 JSON
     * @return 条件成立时为 true，其他状态按 false 处理
     */
    public boolean isApproved(String sessionId, String toolName, String argsJson) {
        if (sessionId == null || toolName == null) {
            return false;
        }
        String key = key(sessionId, toolName, argsHash(argsJson));
        String value;
        try {
            value = redis.opsForValue().get(key);
        } catch (Exception e) {
            log.error("工具审批状态核验失败，按未批准处理（fail-closed）: key={}", key, e);
            return false;
        }
        return value != null && value.startsWith(APPROVED_PREFIX);
    }

    /**
     * 为本次工具调用的原始参数计算稳定摘要，绑定批准的参数集合。
     * 上游由 {@code ToolApprovalService.approve}、{@code ToolApprovalService.isApproved}、{@code ToolApprovalService.reject} 调用。
     * 下游调用 {@code md.digest}。
     *
     * @param argsJson 模型提交的原始工具参数 JSON
     * @return 供后续处理使用的文本或标识
     */
    public static String argsHash(String argsJson) {
        if (argsJson == null) {
            return "null";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(argsJson.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // JDK 必有 SHA-256；走到这里纯属环境异常，抛出去暴露而非静默降级
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 按会话、工具名和参数摘要构造只服务该调用的审批键。
     * 上游由 {@code ToolApprovalService.approve}、{@code ToolApprovalService.isApproved}、{@code ToolApprovalService.reject} 调用。
     *
     * @param sessionId 受当前用户与策略约束的服务端会话 ID
     * @param toolName 工具在注册表中的公开名称
     * @param argsHash 本次调用传入的 {@code String}
     * @return 供后续处理使用的文本或标识
     */
    private static String key(String sessionId, String toolName, String argsHash) {
        return String.format(KEY_PATTERN, sessionId, toolName, argsHash);
    }
}
