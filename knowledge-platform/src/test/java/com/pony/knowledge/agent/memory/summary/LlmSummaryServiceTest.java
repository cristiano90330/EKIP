/* 验证直接模型摘要提示词、有效输出和失败条件。 */

package com.pony.knowledge.agent.memory.summary;

import com.pony.knowledge.agent.exception.AgentException;
import com.pony.knowledge.agent.llm.AgentLlmProperties;
import com.pony.knowledge.agent.llm.LlmClient;
import com.pony.knowledge.agent.llm.LlmResponse;
import com.pony.knowledge.agent.model.AgentMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmSummaryServiceTest {

    private final AgentLlmProperties props = AgentLlmProperties.builder().model("qwen").build();

    /** 通过 {@code svc.summarize} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void summarize_usesLlm_andReturnsSummary() {
        LlmClient client = mock(LlmClient.class);
        when(client.chatCompletions(eq("qwen"), anyList(), isNull()))
                .thenReturn(LlmResponse.builder().content("浓缩要点").build());
        LlmSummaryService svc = new LlmSummaryService(client, props);

        MemorySnapshot snap = MemorySnapshot.builder()
                .sessionId("A")
                .messages(List.of(AgentMessage.builder().role("user").content("问题").build()))
                .build();
        ConversationSummary cs = svc.summarize(snap);

        assertEquals("A", cs.getSessionId());
        assertEquals("浓缩要点", cs.getSummary());
    }

    /** 通过 {@code svc.summarize} 驱动用例，布尔状态符合断言。 */
    @Test
    void prompt_includesExistingSummaryAndMessages() {
        LlmClient client = mock(LlmClient.class);
        when(client.chatCompletions(eq("qwen"), anyList(), isNull()))
                .thenReturn(LlmResponse.builder().content("融合摘要").build());
        LlmSummaryService svc = new LlmSummaryService(client, props);

        MemorySnapshot snap = MemorySnapshot.builder()
                .sessionId("A")
                .summary(ConversationSummary.builder().summary("旧摘要").build())
                .messages(List.of(AgentMessage.builder().role("user").content("问题").build()))
                .build();
        svc.summarize(snap);

        ArgumentCaptor<List<Map<String, Object>>> cap = ArgumentCaptor.forClass(List.class);
        verify(client).chatCompletions(eq("qwen"), cap.capture(), isNull());
        String userContent = (String) cap.getValue().get(1).get("content");
        assertTrue(userContent.contains("旧摘要"), "prompt 应带上已有摘要做融合");
        assertTrue(userContent.contains("问题"), "prompt 应包含待折消息");
    }

    /** 通过 {@code svc.summarize} 驱动用例，抛出 {@code AgentException}。 */
    @Test
    void truncatedSummary_throws() {
        LlmClient client = mock(LlmClient.class);
        when(client.chatCompletions(eq("qwen"), anyList(), isNull()))
                .thenReturn(LlmResponse.builder().content("半截摘要").finishReason("length").build());
        LlmSummaryService svc = new LlmSummaryService(client, props);

        MemorySnapshot snap = MemorySnapshot.builder()
                .sessionId("A")
                .messages(List.of(AgentMessage.builder().role("user").content("问题").build()))
                .build();

        assertThrows(AgentException.class, () -> svc.summarize(snap));
    }

    /** 通过 {@code svc.summarize} 驱动用例，抛出 {@code AgentException}。 */
    @Test
    void blankContent_throws() {
        LlmClient client = mock(LlmClient.class);
        when(client.chatCompletions(eq("qwen"), anyList(), isNull()))
                .thenReturn(LlmResponse.builder().content("  ").build());
        LlmSummaryService svc = new LlmSummaryService(client, props);

        MemorySnapshot snap = MemorySnapshot.builder()
                .sessionId("A")
                .messages(List.of(AgentMessage.builder().role("user").content("问题").build()))
                .build();

        assertThrows(AgentException.class, () -> svc.summarize(snap));
    }
}
