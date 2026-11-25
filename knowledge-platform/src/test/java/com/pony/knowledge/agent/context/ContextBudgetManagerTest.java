/* 验证 Agent 上下文预算随模型窗口变化并正确报告裁减与超限。 */

package com.pony.knowledge.agent.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pony.knowledge.agent.memory.DefaultMemoryManager;
import com.pony.knowledge.agent.memory.MemoryManager;
import com.pony.knowledge.agent.memory.store.InMemoryMemoryStore;
import com.pony.knowledge.agent.model.AgentMessage;
import com.pony.knowledge.agent.observability.AgentMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 3.8e 验收：ContextBudgetManager 在 ContextBuilder 之上叠加「预算口径 + 观测」。
 *   估算 token（tokensBefore）/ 实际 token（tokensUsed）/ 是否裁剪 / 裁掉条数 都打进 AgentMetrics。
 */
class ContextBudgetManagerTest {

    private final InMemoryMemoryStore history = new InMemoryMemoryStore();
    private final MemoryManager mem = new DefaultMemoryManager(history);
    private final ObjectMapper om = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final DefaultContextBudgetManager manager = new DefaultContextBudgetManager(
            new DefaultContextBuilder(mem, om),
            ContextBudget.builder().maxTokens(5000).reservedOutputTokens(0).build(),
            new AgentMetrics(registry));

    /**
     * 提供指定角色和内容的模型消息。
     * @param c 本次调用传入的 {@code String}
     * @return 指定角色和内容的模型消息
     */
    private AgentMessage user(String c) {
        return AgentMessage.builder().role("user").content(c).build();
    }

    /** 通过 {@code manager.build} 驱动用例，布尔状态符合断言，实际结果与固定预期一致。 */
    @Test
    void build_appliesDefaultBudget_andRecordsTokens() {
        ContextBuildResult r = manager.build(ContextBuildRequest.builder()
                .systemPrompt("SYS").sessionId("A").currentQuery("Q").build());

        assertTrue(r.getMessages().size() >= 1, "装配出上下文");
        // estimated = tokensBefore；final = tokensUsed
        assertEquals(r.getTokensBefore(), registry.counter("agent.context.tokens.estimated").count());
        assertEquals(r.getTokensUsed(), registry.counter("agent.context.tokens.final").count());
        assertEquals(0.0, registry.counter("agent.context.truncated").count());
    }

    /** 通过 {@code manager.build} 驱动用例，布尔状态符合断言，实际结果与固定预期一致。 */
    @Test
    void overBudget_recordsTruncatedAndDropped() {
        history.appendMessage("A", user("很长的一串消息".repeat(50)));   // 拉高 token
        ContextBuildResult r = manager.build(ContextBuildRequest.builder()
                .systemPrompt("SYS").sessionId("A").currentQuery("当前问题")
                .budget(ContextBudget.builder().maxTokens(5).reservedOutputTokens(0).build()).build());

        assertTrue(r.isTrimmed(), "超预算应裁剪");
        assertTrue(r.getDroppedMessages() > 0);
        assertTrue(r.getTokensBefore() > r.getTokensUsed(), "估算 > 实际（发生裁剪）");
        // 指标被记录
        assertEquals(1.0, registry.counter("agent.context.truncated").count());
        assertEquals(r.getDroppedMessages(), registry.counter("agent.context.dropped").count());
        // Required 恒在
        assertTrue(r.getMessages().stream().anyMatch(m -> "当前问题".equals(m.getContent())));
    }

    /** 通过 {@code manager.build} 驱动用例，布尔状态符合断言，实际结果与固定预期一致。 */
    @Test
    void requiredSegmentsOverBudget_isCounted_notSilent() {
        ContextBuildResult r = manager.build(ContextBuildRequest.builder()
                .systemPrompt("SYS".repeat(60))          // system 自身就超
                .sessionId("A").currentQuery("当前问题")
                .budget(ContextBudget.builder().maxTokens(5).reservedOutputTokens(0).build()).build());

        assertTrue(r.getOverBudgetTokens() > 0, "Required 撑破预算 → overBudgetTokens 应 > 0");
        assertEquals(0, r.getDroppedMessages(), "没有可裁的 → 一条都没裁（这正是问题所在）");
        assertTrue(r.getTokensUsed() > 5, "返回的上下文实际仍超预算");

        assertEquals(1.0, registry.counter("agent.context.budget_exceeded").count(), "击穿次数被计数");
        assertEquals((double) r.getOverBudgetTokens(),
                registry.counter("agent.context.budget_exceeded.tokens").count(), "击穿量被计数");
    }

    /** 通过 {@code manager.build} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void withinBudget_doesNotCountBudgetExceeded() {
        ContextBuildResult r = manager.build(ContextBuildRequest.builder()
                .systemPrompt("SYS").sessionId("A").currentQuery("Q").build());

        assertEquals(0, r.getOverBudgetTokens());
        assertEquals(0.0, registry.counter("agent.context.budget_exceeded").count());
        assertEquals(0.0, registry.counter("agent.context.budget_exceeded.tokens").count());
    }

    /** 通过 {@code com.pony.knowledge.modelmanagement.ModelExecutionContext.open}、{@code manager.budget} 驱动该场景，实际结果与固定预期一致。 */
    @Test
    void usesSelectedModelWindowAndOutputInsteadOfFixed8k() {
        var execution = java.util.Map.<String,Object>of("bindings", java.util.Map.of("agent",
                java.util.Map.of("contextWindow", 65536, "maxOutputTokens", 2048)));
        try (var ignored = com.pony.knowledge.modelmanagement.ModelExecutionContext.open(execution)) {
            assertEquals(65536, manager.budget().getMaxTokens());
            assertEquals(2048, manager.budget().getReservedOutputTokens());
        }
        assertEquals(5000, manager.budget().getMaxTokens());
    }

    /** 构造必需证据本身超过预算的请求，验证证据完整保留并报告超限。 */
    @Test
    void requiredEvidenceIsNeverClippedToMakeBudgetPass() {
        var call = AgentMessage.builder().role("assistant").content("")
                .toolCalls(java.util.List.of(com.pony.knowledge.agent.model.ToolCall.builder()
                        .callId("call-1").toolName("knowledge_search").arguments("{}").build())).build();
        var evidence = AgentMessage.builder().role("tool").toolCallId("call-1")
                .content("条件：2030年5月，柏川项目，紧急例外。".repeat(50)
                        + "\nsource_reference=[[cite:1]] documentId=1 chunkId=1").build();
        var result = manager.build(ContextBuildRequest.builder().systemPrompt("SYS")
                .sessionId("A").currentQuery("请保留全部条件")
                .toolMessages(java.util.List.of(call, evidence))
                .budget(ContextBudget.builder().maxTokens(100).build()).build());
        assertTrue(result.getOverBudgetTokens() > 0);
        assertTrue(result.getMessages().contains(evidence));
        assertTrue(result.getMessages().contains(call));
    }
}
