/* 验证模型窗口溢出时仅进行一次有界的上下文缩减重试。 */

package com.pony.knowledge.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pony.knowledge.agent.context.ContextBudget;
import com.pony.knowledge.agent.context.ContextBudgetManager;
import com.pony.knowledge.agent.context.ContextBuildRequest;
import com.pony.knowledge.agent.context.ContextBuildResult;
import com.pony.knowledge.agent.llm.AgentLlm;
import com.pony.knowledge.agent.model.AgentDecision;
import com.pony.knowledge.agent.model.AgentExecutionContext;
import com.pony.knowledge.agent.model.AgentMessage;
import com.pony.knowledge.agent.model.AgentResponse;
import com.pony.knowledge.agent.model.StopReason;
import com.pony.knowledge.agent.observability.AgentMetrics;
import com.pony.knowledge.agent.tool.ToolExecutor;
import com.pony.knowledge.agent.tool.ToolRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 上下文溢出恢复（P3-3）。
 *
 * <p>要守住的边界是「只救该救的、只救一次」：
 * 熔断打开或模型 500 时压上下文毫无用处，重试只是再花一次钱；
 * 压不动时重试会得到一模一样的降级，同样只该老实收场。
 */
class ContextOverflowRecoveryTest {

    private static final String OVERFLOW_REASON = "prompt 超预算直接降级";

    /** 在该类型内部准备后续运行所需的数据。 */
    private AgentExecutionContext ctx() {
        return AgentExecutionContext.builder()
                .answerPolicy("general").memoryReadEnabled(false)
                .userId(1L).accessibleKnowledgeBaseIds(Set.of(1L)).sessionId("s1")
                .systemPrompt("SYS").currentQuery("问题").build();
    }

    /** 通过 {@code bm.build} 驱动用例，布尔状态符合断言。 */
    private ContextBudgetManager budgetManager(boolean shrinkable) {
        ContextBudgetManager bm = mock(ContextBudgetManager.class);
        when(bm.budget()).thenReturn(ContextBudget.builder()
                .maxTokens(8000).reservedOutputTokens(1200).reservedToolTokens(400).build());
        when(bm.build(any())).thenAnswer(inv -> {
            ContextBuildRequest req = inv.getArgument(0);
            org.junit.jupiter.api.Assertions.assertFalse(req.isMemoryReadEnabled(), "超窗重建不得重新开启记忆读取");
            boolean squeezed = req.getBudget() != null && req.getBudget().getMaxTokens() < 8000;
            List<AgentMessage> messages = new ArrayList<>();
            messages.add(AgentMessage.builder().role("system").content("SYS").build());
            messages.add(AgentMessage.builder().role("user").content("问题").build());
            if (!squeezed || !shrinkable) {
                messages.add(AgentMessage.builder().role("tool").content("证据".repeat(2000)).build());
            }
            return ContextBuildResult.builder().messages(messages).build();
        });
        return bm;
    }

    /**
     * 协调 {@code Runnable::run} 完成当前阶段，供后续处理使用。
     * 下游调用 {@code Runnable::run}。
     *
     * @param llm 本次调用传入的 {@code AgentLlm}
     * @param bm 本次调用传入的 {@code ContextBudgetManager}
     * @param metrics 记录本模块用量和计时的指标组件
     * @return 调用方使用的 {@code DefaultAgentRuntime}
     */
    private DefaultAgentRuntime runtime(AgentLlm llm, ContextBudgetManager bm, AgentMetrics metrics) {
        return new DefaultAgentRuntime(llm, mock(ToolExecutor.class), new ToolRegistry(List.of()),
                Runnable::run, new ObjectMapper(), 5, 10, 4, true, 60000L, bm, metrics);
    }

    /** 首次模型调用返回上下文溢出，验证缩减后的消息仅重试一次。 */
    @Test
    void overflow_squeezesAndRetriesOnce() {
        AgentLlm llm = mock(AgentLlm.class);
        when(llm.respond(any(), any())).thenReturn(
                AgentDecision.builder().degraded(true).degradeReason(OVERFLOW_REASON).build(),
                AgentDecision.builder().content("恢复后的答案").build());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        AgentResponse resp = runtime(llm, budgetManager(true), new AgentMetrics(registry))
                .run(ctx(), List.of(AgentMessage.builder().role("user").content("问题").build()));

        assertEquals(StopReason.FINAL_ANSWER, resp.getStopReason());
        assertEquals("恢复后的答案", resp.getAnswer());
        verify(llm, times(2)).respond(any(), any());
        assertEquals(1.0, registry.counter("agent.context.overflow_retry").count());
        assertEquals(1.0, registry.counter("agent.context.overflow_recovered").count());
    }

    /** 输入无法进一步缩减且模型返回溢出时，验证直接降级终止。 */
    @Test
    void overflow_notShrinkable_degradesWithoutRetry() {
        AgentLlm llm = mock(AgentLlm.class);
        when(llm.respond(any(), any())).thenReturn(
                AgentDecision.builder().degraded(true).degradeReason(OVERFLOW_REASON).build());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        AgentResponse resp = runtime(llm, budgetManager(false), new AgentMetrics(registry))
                .run(ctx(), List.of(AgentMessage.builder().role("user").content("问题").build()));

        verify(llm, times(1)).respond(any(), any());
        assertEquals(0.0, registry.counter("agent.context.overflow_retry").count());
        assertTrue(resp.isDegraded(), "压不动时应保持降级语义");
    }

    /** 通过 {@code llm.respond} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void nonOverflowDegrade_neverRetries() {
        AgentLlm llm = mock(AgentLlm.class);
        when(llm.respond(any(), any())).thenReturn(AgentDecision.builder()
                .degraded(true).degradeReason("circuit open（主 provider 熔断，降级也失败）").build());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        runtime(llm, budgetManager(true), new AgentMetrics(registry))
                .run(ctx(), List.of(AgentMessage.builder().role("user").content("问题").build()));

        verify(llm, times(1)).respond(any(), any());
        assertEquals(0.0, registry.counter("agent.context.overflow_retry").count(),
                "熔断 / 模型故障压上下文无用，重试只会再花一次钱");
    }

    /** 构造提供商的窗口超限错误，验证它会进入上下文缩减恢复分支。 */
    @Test
    void providerContextLengthError_isRecognised() {
        AgentLlm llm = mock(AgentLlm.class);
        when(llm.respond(any(), any())).thenReturn(
                AgentDecision.builder().degraded(true)
                        .degradeReason("HTTP 400: This model's maximum context length is 8192 tokens").build(),
                AgentDecision.builder().content("恢复后的答案").build());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        AgentResponse resp = runtime(llm, budgetManager(true), new AgentMetrics(registry))
                .run(ctx(), List.of(AgentMessage.builder().role("user").content("问题").build()));

        assertEquals("恢复后的答案", resp.getAnswer());
        assertEquals(1.0, registry.counter("agent.context.overflow_retry").count());
    }

    /** 通过 {@code llm.respond} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void retriesAtMostOncePerTurn() {
        AgentLlm llm = mock(AgentLlm.class);
        when(llm.respond(any(), any())).thenReturn(
                AgentDecision.builder().degraded(true).degradeReason(OVERFLOW_REASON).build());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        runtime(llm, budgetManager(true), new AgentMetrics(registry))
                .run(ctx(), List.of(AgentMessage.builder().role("user").content("问题").build()));

        verify(llm, times(2)).respond(any(), any());        // 第一次 + 一次重试
        assertEquals(1.0, registry.counter("agent.context.overflow_retry").count());
        assertEquals(0.0, registry.counter("agent.context.overflow_recovered").count(),
                "重试后仍降级 → 记 retry 不记 recovered，两个指标一起读才看得出「救了没用」");
    }
}
