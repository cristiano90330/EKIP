/* 验证摘要模型的上下文来源及执行边界。 */

package com.pony.knowledge.modelmanagement;

import com.pony.knowledge.agent.llm.AgentGatewayClient;
import com.pony.knowledge.agent.memory.summary.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ManagedSummaryContextTest {
    /** 核对 {@code execution.get("budgetMs")} 与预期 {@code 30000} 一致。调用路径经过 {@code summaries.setModelRegistry}。 */
    @Test void asynchronousSummaryResolvesItsExplicitOwnerAndModel() {
        ModelRegistryService registry=mock(ModelRegistryService.class);AgentGatewayClient gateway=mock(AgentGatewayClient.class);
        GatewaySummaryService summaries=new GatewaySummaryService(gateway);summaries.setModelRegistry(registry);
        when(registry.resolveOwned(91L,"summary-model")).thenReturn(Map.of("id","summary-model","apiKey","test-only-secret"));
        GatewaySummaryService.SummarizeResponse response=new GatewaySummaryService.SummarizeResponse();
        response.content="summary";response.finish_reason="stop";
        when(gateway.post(eq("/agent/summarize"),anyMap(),eq(GatewaySummaryService.SummarizeResponse.class))).thenReturn(response);
        summaries.summarize(MemorySnapshot.builder().ownerId(91L).summaryModelId("summary-model")
                .sessionId("s1").messages(java.util.List.of()).messageCount(0).build());
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String,Object>> payload=ArgumentCaptor.forClass(Map.class);
        verify(gateway).post(eq("/agent/summarize"),payload.capture(),eq(GatewaySummaryService.SummarizeResponse.class));
        @SuppressWarnings("unchecked") Map<String,Object> execution=(Map<String,Object>)payload.getValue().get("modelExecution");
        assertEquals(30000,execution.get("budgetMs"));
        @SuppressWarnings("unchecked") Map<String,Object> bindings=(Map<String,Object>)execution.get("bindings");
        assertEquals(java.util.Set.of("summarize"),bindings.keySet());
        verify(registry).resolveOwned(91L,"summary-model");
        assertNull(ModelExecutionContext.currentOwnerId());
    }

    /** 断言抛出 {@code ModelManagementException}。调用路径经过 {@code summaries.setModelRegistry}。 */
    @Test void missingBackgroundOwnershipFailsBeforeModelIo() {
        ModelRegistryService registry=mock(ModelRegistryService.class);AgentGatewayClient gateway=mock(AgentGatewayClient.class);
        GatewaySummaryService summaries=new GatewaySummaryService(gateway);summaries.setModelRegistry(registry);
        assertThrows(ModelManagementException.class,() -> summaries.summarize(MemorySnapshot.builder()
                .sessionId("s1").messages(java.util.List.of()).build()));
        verifyNoInteractions(registry,gateway);
    }
}
