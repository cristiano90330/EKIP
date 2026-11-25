/* 验证工具审批的参数摘要、有效期与缺失或异常时的拒绝行为。 */

package com.pony.knowledge.agent.tool.approval;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolApprovalServiceTest {

    /**
     * 协调 {@code redis.opsForValue} 完成当前阶段，供后续处理使用。
     * 下游调用 {@code redis.opsForValue}。
     *
     * @param redis 当前模块使用的 Redis 客户端
     * @return 当前记录中的 {@code ops}
     */
    @SuppressWarnings("unchecked")
    private static ValueOperations<String, String> ops(StringRedisTemplate redis) {
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        return ops;
    }

    /** 通过 {@code ToolApprovalService.argsHash} 驱动该场景，核对 {@code h1} 等于 {@code h2}。 */
    @Test
    void argsHash_isStableAndDistinct() {
        String h1 = ToolApprovalService.argsHash("{\"q\":\"a\"}");
        String h2 = ToolApprovalService.argsHash("{\"q\":\"a\"}");
        String h3 = ToolApprovalService.argsHash("{\"q\":\"b\"}");
        assertThat(h1).isEqualTo(h2);              // 同参同哈希
        assertThat(h1).isNotEqualTo(h3);           // 参数哪怕差一个字符，哈希不同 → 审批不匹配
        assertThat(ToolApprovalService.argsHash(null)).isEqualTo("null");
    }

    /** 通过 {@code ops.get} 驱动用例，布尔状态符合断言。 */
    @Test
    void isApproved_true_onlyWhenApprovedPrefix() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = ops(redis);
        ToolApprovalService svc = new ToolApprovalService(redis, 30);

        when(ops.get(anyString())).thenReturn("APPROVED:42");
        assertThat(svc.isApproved("u1:s1", "danger", "{}")).isTrue();

        when(ops.get(anyString())).thenReturn("REJECTED:太危险");
        assertThat(svc.isApproved("u1:s1", "danger", "{}")).isFalse();

        when(ops.get(anyString())).thenReturn(null);
        assertThat(svc.isApproved("u1:s1", "danger", "{}")).isFalse();
    }

    /** 通过 {@code ops.get} 驱动用例，布尔状态符合断言。 */
    @Test
    void isApproved_failClosed_onRedisError() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = ops(redis);
        ToolApprovalService svc = new ToolApprovalService(redis, 30);
        when(ops.get(anyString())).thenThrow(new RuntimeException("redis down"));
        // fail-close：核验不了就拒绝
        assertThat(svc.isApproved("u1:s1", "danger", "{}")).isFalse();
    }

    /** 通过 {@code svc.isApproved}、{@code ops} 驱动该场景，布尔状态符合断言。 */
    @Test
    void isApproved_nullSession_denied() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ops(redis);
        ToolApprovalService svc = new ToolApprovalService(redis, 30);
        assertThat(svc.isApproved(null, "danger", "{}")).isFalse();
    }

    /** 通过 {@code svc.approve}、{@code ops} 驱动该场景，核对输出和依赖调用。 */
    @Test
    void approve_writesApprovedPrefixWithTtl() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = ops(redis);
        ToolApprovalService svc = new ToolApprovalService(redis, 30);

        svc.approve("u1:s1", "danger", "{}", 42L);

        verify(ops).set(anyString(), eq("APPROVED:42"), eq(Duration.ofMinutes(30)));
    }

    /** 通过 {@code ops.get} 驱动用例，布尔状态符合断言。 */
    @Test
    void approveThenIsApproved_roundTrip() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = ops(redis);
        ToolApprovalService svc = new ToolApprovalService(redis, 30);

        // 批准后：同 (session, tool, args) 能查到 APPROVED
        String key = String.format("agent:approval:%s:%s:%s",
                "u1:s1", "danger", ToolApprovalService.argsHash("{}"));
        when(ops.get(key)).thenReturn("APPROVED:42");

        assertThat(svc.isApproved("u1:s1", "danger", "{}")).isTrue();
        // 参数不同 → 键不同 → 不匹配
        assertThat(svc.isApproved("u1:s1", "danger", "{\"q\":1}")).isFalse();
    }
}
