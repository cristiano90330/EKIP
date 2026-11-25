/* 验证直接模型的系统提示词去重、令牌用量和缺省逻辑。 */

package com.pony.knowledge.agent.llm;

import com.pony.knowledge.agent.model.AgentDecision;
import com.pony.knowledge.agent.model.AgentMessage;
import com.pony.knowledge.agent.model.AgentPrompts;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 3.1 P1-5（08 章 2.5 边界 22）验收：system 提示只有 AgentPrompts 一处真相源，
 * 兜底注入的也必须是那份「同时讲到两个工具」的提示。
 * 3.1 P1-2 验收：供应商返回的 usage 透传到 AgentDecision（此前解析出来就丢掉）。
 */
class OpenAiAgentLlmTest {

    private final LlmClient client = mock(LlmClient.class);

    private final OpenAiAgentLlm llm = new OpenAiAgentLlm(
            AgentLlmProperties.builder().model("test-model").build(), client);

    /** 协调 {@code client.chatCompletions} 完成当前阶段，供后续处理使用。 */
    private void stubAnswer() {
        when(client.chatCompletions(anyString(), any(), any()))
                .thenReturn(LlmResponse.builder().content("答案").toolCalls(List.of()).build());
    }

    /**
     * 协调 {@code ArgumentCaptor.forClass} 完成当前阶段，供后续处理使用。
     * 下游调用 {@code ArgumentCaptor.forClass}、{@code captor.capture}、{@code anyString}。
     *
     * @return 调用方使用的 {@code List<Map<String, Object>>}
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> capturedMessages() {
        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(client).chatCompletions(anyString(), captor.capture(), any());
        return captor.getValue();
    }

    /** 通过 {@code llm.respond}、{@code sent.get} 驱动用例，布尔状态符合断言，实际结果与固定预期一致。 */
    @Test
    void noSystemFromCaller_injectsCanonicalPrompt_mentioningBothTools() {
        stubAnswer();

        llm.respond(List.of(AgentMessage.builder().role("user").content("差旅标准是什么").build()), List.of());

        List<Map<String, Object>> sent = capturedMessages();
        assertEquals("system", sent.get(0).get("role"), "缺 system 时应补兜底提示");
        assertEquals(AgentPrompts.KNOWLEDGE_ASSISTANT_SYSTEM, sent.get(0).get("content"),
                "兜底提示必须与 AgentService 用的是同一份常量");
        String prompt = String.valueOf(sent.get(0).get("content"));
        assertTrue(prompt.contains("knowledge_search") && prompt.contains("knowledge_ask"),
                "边界 22 的原始症状就是兜底提示只提到 search —— 两个工具都得讲到");
    }

    /** 通过 {@code llm.respond}、{@code m.get} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void callerSuppliedSystem_reachesTheWire_exactlyOnce() {
        stubAnswer();

        llm.respond(List.of(
                AgentMessage.builder().role("system").content("宿主装配的提示").build(),
                AgentMessage.builder().role("user").content("Q").build()), List.of());

        List<Map<String, Object>> sent = capturedMessages();
        long systemCount = sent.stream().filter(m -> "system".equals(m.get("role"))).count();
        assertEquals(1, systemCount, "system 只应有一条（调用方那条，不重复注入兜底）");
        assertEquals("宿主装配的提示", sent.get(0).get("content"),
                "调用方装配的 system 必须原样发给模型，不能丢");
    }

    /** 通过 {@code llm.respond} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void usageFromProvider_passesThroughToDecision() {
        when(client.chatCompletions(anyString(), any(), any())).thenReturn(LlmResponse.builder()
                .content("答案").toolCalls(List.of())
                .usage(TokenUsage.builder().promptTokens(120).completionTokens(30).build())
                .build());

        AgentDecision decision = llm.respond(
                List.of(AgentMessage.builder().role("user").content("Q").build()), List.of());

        assertEquals(120, decision.getPromptTokens());
        assertEquals(30, decision.getCompletionTokens());
    }

    /** 通过 {@code llm.respond} 驱动用例，布尔状态符合断言，实际结果与固定预期一致。 */
    @Test
    void missingUsage_defaultsToZeroTokens() {
        stubAnswer();

        AgentDecision decision = llm.respond(
                List.of(AgentMessage.builder().role("user").content("Q").build()), List.of());

        assertEquals(0, decision.getPromptTokens());
        assertEquals(0, decision.getCompletionTokens());
        assertFalse(decision.hasToolCalls());
    }
}
