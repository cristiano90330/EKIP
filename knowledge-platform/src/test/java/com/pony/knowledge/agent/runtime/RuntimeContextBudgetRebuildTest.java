/* 验证工具结果会经过上下文预算构建后进入下一轮模型决策。 */

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
import com.pony.knowledge.agent.model.ToolCall;
import com.pony.knowledge.agent.tool.ToolExecutor;
import com.pony.knowledge.agent.tool.ToolRegistry;
import com.pony.knowledge.agent.tool.output.ToolResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
 * 3.8e 验收：Runtime 在一次工具执行后「重新 Build Context」——
 *   本轮 assistant tool_calls + tool 结果被攒成 turnTool 交给 ContextBudgetManager，
 *   管理者重新装配出的上下文（含 Tool Result）进入下一轮 LLM 调用。
 * 只 mock LLM / ToolExecutor / ContextBudgetManager，验证编排逻辑。
 */
class RuntimeContextBudgetRebuildTest {

    /** 通过 {@code llm.respond}、{@code executor.execute} 驱动用例，布尔状态符合断言，实际结果与固定预期一致。 */
    @Test
    void toolResult_entersNextRound_viaRebuiltContext() {
        ToolCall call = ToolCall.builder().callId("c1").toolName("knowledge_search").arguments("{}").build();

        AgentLlm llm = mock(AgentLlm.class);
        when(llm.respond(any(), any()))
                .thenReturn(AgentDecision.builder().content("").toolCalls(List.of(call)).build(),
                        AgentDecision.builder().content("最终答案").toolCalls(List.of()).build());

        ToolExecutor executor = mock(ToolExecutor.class);
        when(executor.execute(any(), any()))
                .thenReturn(ToolResult.builder().toolName("knowledge_search").callId("c1")
                        .success(true).data(MapData.of("k", "v")).build());

        ContextBudgetManager bm = mock(ContextBudgetManager.class);
        when(bm.budget()).thenReturn(ContextBudget.builder().maxTokens(5000).reservedOutputTokens(0).build());
        // build() 返回「本轮工具消息」作为下一轮上下文（模拟管理者把 Tool Result 装进上下文）
        when(bm.build(any())).thenAnswer(inv -> {
            ContextBuildRequest req = inv.getArgument(0);
            return ContextBuildResult.builder()
                    .messages(new ArrayList<>(req.getToolMessages()))
                    .build();
        });

        AgentExecutionContext ctx = AgentExecutionContext.builder()
                .answerPolicy("general").memoryReadEnabled(false)
                .userId(1L).accessibleKnowledgeBaseIds(Set.of(1L)).sessionId("s1")
                .systemPrompt("SYS").currentQuery("问题").build();

        AgentRuntime runtime = new DefaultAgentRuntime(
                llm, executor, new ToolRegistry(List.of()), Runnable::run, new ObjectMapper(),
                5, 10, 4, true, 20000L, bm);

        AgentResponse resp = runtime.run(ctx, List.of(AgentMessage.builder().role("user").content("问题").build()));

        assertEquals(StopReason.FINAL_ANSWER, resp.getStopReason());
        assertEquals("最终答案", resp.getAnswer());

        // 第二次 LLM 调用应看到 role=tool 的结果（经重建上下文进入下一轮）
        ArgumentCaptor<List<AgentMessage>> msgCaptor = ArgumentCaptor.forClass(List.class);
        verify(llm, times(2)).respond(msgCaptor.capture(), any());
        List<AgentMessage> secondHistory = msgCaptor.getAllValues().get(1);
        assertTrue(secondHistory.stream().anyMatch(m -> "tool".equals(m.getRole())
                        && m.getContent() != null && m.getContent().contains("success=true")),
                "Tool Result 应进入下一轮上下文: " + secondHistory);

        // rebuild 请求应携带本轮 assistant tool_calls + tool 结果
        ArgumentCaptor<ContextBuildRequest> reqCaptor = ArgumentCaptor.forClass(ContextBuildRequest.class);
        verify(bm, times(1)).build(reqCaptor.capture());
        ContextBuildRequest req = reqCaptor.getValue();
        assertTrue(req.getToolMessages().stream().anyMatch(m -> "assistant".equals(m.getRole())),
                "重建请求应含 assistant tool_calls");
        assertTrue(req.getToolMessages().stream().anyMatch(m -> "tool".equals(m.getRole())),
                "重建请求应含 tool 结果");
        org.junit.jupiter.api.Assertions.assertFalse(req.isMemoryReadEnabled());
        assertEquals("s1", req.getSessionId());
        assertEquals("问题", req.getCurrentQuery());
        assertEquals("SYS", req.getSystemPrompt());
    }

    /** 简单 Map 数据载体，避免引入真实工具输出类型。 */
    private static class MapData {
        private final java.util.Map<String, String> data;

        /**
         * 用注入依赖和构造参数建立此实例执行时使用的状态。
         *
         * @param data 本次调用传入的 {@code java.util.Map<String, String>}
         */
        private MapData(java.util.Map<String, String> data) {
            this.data = data;
        }

        /**
         * 从输入构造当前记录。
         *
         * @param k 本次调用传入的 {@code String}
         * @param v 本次调用传入的 {@code String}
         * @return 调用方使用的 {@code MapData}
         */
        static MapData of(String k, String v) {
            java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
            m.put(k, v);
            return new MapData(m);
        }

        /** 返回当前记录保存的 {@code data}。 */
        public java.util.Map<String, String> getData() {
            return data;
        }
    }
}
