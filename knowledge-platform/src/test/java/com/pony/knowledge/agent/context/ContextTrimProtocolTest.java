/* 验证上下文裁减时工具调用与结果始终成组处理。 */

package com.pony.knowledge.agent.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pony.knowledge.agent.memory.DefaultMemoryManager;
import com.pony.knowledge.agent.memory.store.InMemoryMemoryStore;
import com.pony.knowledge.agent.model.AgentMessage;
import com.pony.knowledge.agent.model.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 裁剪协议验收：工具往返必须整组进出，单条证据超预算时先截断、最后才丢弃。
 *
 * <p>这些用例守的不是「省了多少 token」，而是「模型收到的消息序列是否合法」：
 * 孤儿 assistant(tool_calls) 与孤儿 tool 应答在严格实现的端点上直接 400，
 * 在宽松实现上会被静默丢证据——后者更难发现，因为症状看起来像「检索没查到」。
 */
class ContextTrimProtocolTest {

    private final DefaultContextBuilder builder = new DefaultContextBuilder(
            new DefaultMemoryManager(new InMemoryMemoryStore()), new ObjectMapper());

    /**
     * 提供本轮模型消息可占用的令牌预算。
     * @param maxTokens 允许使用的最大令牌数
     * @return 本轮模型消息可占用的令牌预算
     */
    private static ContextBudget budget(int maxTokens) {
        return ContextBudget.builder()
                .maxTokens(maxTokens).reservedOutputTokens(0).reservedToolTokens(0).build();
    }

    /**
     * 提供指定角色和内容的模型消息。
     * @param callId 本次调用传入的 {@code String}
     * @return 指定角色和内容的模型消息
     */
    private static AgentMessage assistantCalling(String callId) {
        return AgentMessage.builder()
                .role("assistant")
                .toolCalls(List.of(ToolCall.builder()
                        .callId(callId).toolName("knowledge_search").arguments("{}").build()))
                .build();
    }

    /**
     * 提供指定角色和内容的模型消息。
     * @param callId 本次调用传入的 {@code String}
     * @param content 待处理的正文内容
     * @return 指定角色和内容的模型消息
     */
    private static AgentMessage toolResult(String callId, String content) {
        return AgentMessage.builder()
                .role("tool").toolCallId(callId).toolName("knowledge_search").content(content).build();
    }

    /**
     * 通过 {@code budget} 装配后续操作需要的输入或结果。
     * @param tools 本次调用传入的 {@code List<AgentMessage>}
     * @param maxTokens 允许使用的最大令牌数
     * @return 模型消息及其用量和裁减诊断
     */
    private ContextBuildResult build(List<AgentMessage> tools, int maxTokens) {
        return builder.build(ContextBuildRequest.builder()
                .systemPrompt("SYS").sessionId("A").currentQuery("Q")
                .toolMessages(tools).budget(budget(maxTokens))
                .build());
    }

    /** 通过 {@code assistantCalling}、{@code toolResult} 驱动该场景，布尔状态符合断言，返回的数据、边界或顺序符合断言。 */
    @Test
    void oversizedSingleResult_isTruncated_notDropped() {
        String evidence = "证据".repeat(2000);   // 4000 字符 ≈ 4000 token
        ContextBuildResult r = build(List.of(assistantCalling("c1"), toolResult("c1", evidence)), 1000);

        assertEquals(1, r.getTruncatedMessages(), "应恰好截断一条工具结果");
        assertEquals(0, r.getDroppedMessages(), "不应整条丢弃");
        assertTrue(r.getTokensUsed() <= 1000, "截断后必须落回预算内，实际=" + r.getTokensUsed());

        assertTrue(r.getMessages().stream().anyMatch(m -> "assistant".equals(m.getRole())
                        && m.getToolCalls() != null && !m.getToolCalls().isEmpty()),
                "assistant tool_calls 必须保留，否则 tool 应答成孤儿");
        AgentMessage kept = r.getMessages().stream()
                .filter(m -> "tool".equals(m.getRole())).findFirst().orElse(null);
        assertNotNull(kept, "tool 应答必须保留");
        assertTrue(evidence.startsWith(kept.getContent().substring(0, 20)), "保留的是头部而不是尾部");
        assertTrue(kept.getContent().endsWith("已截断]"), "尾部要留截断标记，模型才知道证据不全");
    }

    /** 将预算压到不足以保留工具交互，验证成对移除调用和结果。 */
    @Test
    void budgetTooSmall_dropsWholeRoundTrip_noOrphans() {
        ContextBuildResult r = build(
                List.of(assistantCalling("c1"), toolResult("c1", "证据".repeat(2000))), 60);

        assertTrue(r.getDroppedMessages() >= 2, "整组两条一起丢");
        assertTrue(r.getMessages().stream().noneMatch(m -> "tool".equals(m.getRole())),
                "不得残留孤儿 tool 应答");
        assertTrue(r.getMessages().stream().noneMatch(m -> m.getToolCalls() != null && !m.getToolCalls().isEmpty()),
                "不得残留孤儿 assistant tool_calls");
        assertTrue(r.getTokensUsed() <= 60, "仍须落回预算内，实际=" + r.getTokensUsed());
        assertEquals(0, r.getOverBudgetTokens(), "工具消息可裁，超额量只应来自 Required 段");
    }

    /** 通过 {@code assistantCalling}、{@code toolResult} 驱动该场景，布尔状态符合断言。 */
    @Test
    void multipleRoundTrips_dropsOldestGroupFirst() {
        ContextBuildResult r = build(List.of(
                assistantCalling("c1"), toolResult("c1", "旧证据".repeat(100)),
                assistantCalling("c2"), toolResult("c2", "新证据")), 50);

        assertFalse(r.getMessages().stream().anyMatch(m -> m.getContent() != null
                && m.getContent().contains("旧证据")), "最旧一组整组裁掉");
        assertTrue(r.getMessages().stream().anyMatch(m -> "新证据".equals(m.getContent())), "最新证据保留");
        assertTrue(r.getMessages().stream().noneMatch(m -> m.getToolCalls() != null
                        && m.getToolCalls().stream().anyMatch(tc -> "c1".equals(tc.getCallId()))),
                "旧组的 assistant tool_calls 必须随之消失");
    }

    /** 通过 {@code toolResult}、{@code assistantCalling} 驱动该场景，实际结果与固定预期一致。 */
    @Test
    void truncation_doesNotMutateCallerMessages() {
        String evidence = "证据".repeat(2000);
        AgentMessage original = toolResult("c1", evidence);

        build(List.of(assistantCalling("c1"), original), 1000);

        assertEquals(evidence, original.getContent(), "原对象内容必须保持不变");
    }

    /** 通过 {@code r.isTrimmed}、{@code assistantCalling} 驱动该场景，布尔状态符合断言，实际结果与固定预期一致。 */
    @Test
    void withinBudget_noTrim() {
        ContextBuildResult r = build(List.of(assistantCalling("c1"), toolResult("c1", "短证据")), 8192);

        assertFalse(r.isTrimmed());
        assertEquals(0, r.getDroppedMessages());
        assertEquals(0, r.getTruncatedMessages());
        assertEquals(0, r.getOverBudgetTokens());
    }
}
