/* 验证模型结束原因、空白回复、工具参数修复和循环预算的处理。 */

package com.pony.knowledge.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ReAct 循环的终止路径表（一个类覆盖全部 10 条路径，不按类建测试）。
 *
 * <p>覆盖：自然停止、输出截断、内容过滤、空回复（nudge 成功 / 用尽）、卡死重复、
 * 轮次上限兜底合成、工具调用超限、参数修复、参数不可修。
 * 并行/屏障与并发封顶由 {@code DefaultAgentRuntimeTest} 既有用例覆盖，此处不重复。
 */
class ReactLoopTest {

    /** 在该类型内部准备后续运行所需的数据。 */
    private static AgentExecutionContext context() {
        return AgentExecutionContext.builder()
                .answerPolicy("general")
                .userId(1L)
                .accessibleKnowledgeBaseIds(Set.of(1L))
                .systemPrompt("sys")
                .currentQuery("q")
                .build();
    }

    /** 提供指定角色和内容的模型消息。 */
    private static AgentMessage userMsg() {
        return AgentMessage.builder().role("user").content("q").build();
    }

    /**
     * 协调 {@code Runnable::run} 完成当前阶段，供后续处理使用。
     * 下游调用 {@code Runnable::run}。
     *
     * @param llm 本次调用传入的 {@code AgentLlm}
     * @param executor 本次调用传入的 {@code ToolExecutor}
     * @param maxIterations 本次调用传入的 {@code int}
     * @param maxToolCalls 本次调用传入的 {@code int}
     * @return 调用方使用的 {@code AgentRuntime}
     */
    private static AgentRuntime runtime(AgentLlm llm, ToolExecutor executor, int maxIterations, int maxToolCalls) {
        return new DefaultAgentRuntime(llm, executor, new ToolRegistry(List.of()),
                Runnable::run, new ObjectMapper(), maxIterations, maxToolCalls, 4, true, 20_000L, null);
    }

    /**
     * 在该类型内部准备后续运行所需的数据。
     * @param id 本次调用传入的 {@code String}
     * @param args 本次调用传入的 {@code String}
     * @return 调用方使用的 {@code ToolCall}
     */
    private static ToolCall call(String id, String args) {
        return ToolCall.builder().callId(id).toolName("knowledge_search").arguments(args).build();
    }

    /** 通过 {@code llm.respond} 驱动用例，布尔状态符合断言，实际结果与固定预期一致。 */
    @Test
    void naturalStop_isFinalAnswerOnFirstRound() {
        AgentLlm llm = mock(AgentLlm.class);
        when(llm.respond(any(), any())).thenReturn(AgentDecision.builder()
                .content("答案").toolCalls(List.of()).finishReason("stop").build());

        AgentResponse resp = runtime(llm, mock(ToolExecutor.class), 5, 10)
                .run(context(), List.of(userMsg()));

        assertEquals(StopReason.FINAL_ANSWER, resp.getStopReason());
        assertEquals("答案", resp.getAnswer());
        assertEquals(1, resp.getIterations());
        assertFalse(resp.isSynthesized());
    }

    /** 通过 {@code llm.respond} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void truncatedAnswer_isDeliveredNotReanswered() {
        AgentLlm llm = mock(AgentLlm.class);
        when(llm.respond(any(), any())).thenReturn(AgentDecision.builder()
                .content("前半段").toolCalls(List.of()).finishReason("length").build());

        AgentResponse resp = runtime(llm, mock(ToolExecutor.class), 5, 10)
                .run(context(), List.of(userMsg()));

        assertEquals(StopReason.FINAL_ANSWER_TRUNCATED, resp.getStopReason());
        assertEquals("前半段", resp.getAnswer());
        // 截断的答案已经交付，不再转圈重答（重答只会从头再撞一次同一上限）。
        verify(llm, times(1)).respond(any(), any());
    }

    /** 通过 {@code llm.respond} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void contentFilter_terminatesImmediately() {
        AgentLlm llm = mock(AgentLlm.class);
        when(llm.respond(any(), any())).thenReturn(AgentDecision.builder()
                .content("").toolCalls(List.of()).finishReason("content_filter").build());

        AgentResponse resp = runtime(llm, mock(ToolExecutor.class), 5, 10)
                .run(context(), List.of(userMsg()));

        assertEquals(StopReason.CONTENT_FILTERED, resp.getStopReason());
        assertNotNull(resp.getAnswer());
    }

    /** 通过 {@code llm.respond} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void emptyResponse_isNudgedThenAnswered_withoutBurningRounds() {
        AgentLlm llm = mock(AgentLlm.class);
        when(llm.respond(any(), any())).thenReturn(
                AgentDecision.builder().content("").toolCalls(List.of()).finishReason("stop").build(),
                AgentDecision.builder().content("补上的答案").toolCalls(List.of()).finishReason("stop").build());

        AgentResponse resp = runtime(llm, mock(ToolExecutor.class), 5, 10)
                .run(context(), List.of(userMsg()));

        assertEquals(StopReason.FINAL_ANSWER, resp.getStopReason());
        assertEquals("补上的答案", resp.getAnswer());
        assertEquals(1, resp.getIterations(), "nudge 不应消耗轮次");
        verify(llm, times(2)).respond(any(), any());
    }

    /** 通过 {@code llm.respond} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void emptyResponse_exhausted_usesFallbackText() {
        AgentLlm llm = mock(AgentLlm.class);
        when(llm.respond(any(), any())).thenReturn(AgentDecision.builder()
                .content("").toolCalls(List.of()).finishReason("stop").build());

        AgentResponse resp = runtime(llm, mock(ToolExecutor.class), 5, 10)
                .run(context(), List.of(userMsg()));

        assertEquals(StopReason.EMPTY_RESPONSE, resp.getStopReason());
        assertEquals(ResponseAnalyzer.EMPTY_RESPONSE_FALLBACK, resp.getAnswer());
        // 首次 + 2 次 nudge 重试 = 3 次决策，之后收尾。
        verify(llm, times(3)).respond(any(), any());
    }

    /** 通过 {@code llm.respond}、{@code executor.execute} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void consecutiveTruncatedToolCalls_stopBeforeBurningAllRounds() {
        AgentLlm llm = mock(AgentLlm.class);
        // 每轮都被输出上限截断，且都在发工具调用：重构前会一路烧到轮次上限。
        when(llm.respond(any(), any())).thenReturn(AgentDecision.builder()
                .content("我再查一下").toolCalls(List.of(call("c", "{}"))).finishReason("length").build());
        ToolExecutor executor = mock(ToolExecutor.class);
        when(executor.execute(any(), any())).thenReturn(ToolResult.builder().success(true).build());

        AgentResponse resp = runtime(llm, executor, 10, 20).run(context(), List.of(userMsg()));

        assertEquals(StopReason.STUCK_REPEATED, resp.getStopReason());
        assertEquals(3, resp.getIterations(), "默认连续 3 轮截断即止损");
    }

    /** 通过 {@code llm.respond}、{@code executor.execute} 驱动用例，布尔状态符合断言，实际结果与固定预期一致。 */
    @Test
    void maxIterations_synthesizesAnswerFromToolResults() {
        AgentLlm llm = mock(AgentLlm.class);
        when(llm.respond(any(), any()))
                // 前两轮只会调工具
                .thenReturn(AgentDecision.builder().content("").toolCalls(List.of(call("c1", "{}"))).build(),
                        AgentDecision.builder().content("").toolCalls(List.of(call("c2", "{}"))).build(),
                        // 兜底合成（不给工具）才给正文
                        AgentDecision.builder().content("基于检索结果的结论").toolCalls(List.of()).build());
        ToolExecutor executor = mock(ToolExecutor.class);
        when(executor.execute(any(), any())).thenReturn(ToolResult.builder().success(true).data("ev").build());

        AgentResponse resp = runtime(llm, executor, 2, 10).run(context(), List.of(userMsg()));

        assertEquals(StopReason.MAX_ITERATIONS, resp.getStopReason());
        assertEquals(2, resp.getIterations());
        assertEquals("基于检索结果的结论", resp.getAnswer());
        assertTrue(resp.isSynthesized(), "答案应标记为兜底合成");
    }

    /** 通过 {@code llm.respond} 驱动用例，跳过受保护的调用，实际结果与固定预期一致。 */
    @Test
    void maxToolCalls_stopsBeforeExecuting() {
        AgentLlm llm = mock(AgentLlm.class);
        when(llm.respond(any(), any())).thenReturn(AgentDecision.builder().content("").toolCalls(List.of(
                call("a", "{}"), call("b", "{}"), call("c", "{}"))).build());
        ToolExecutor executor = mock(ToolExecutor.class);

        AgentResponse resp = runtime(llm, executor, 5, 2).run(context(), List.of(userMsg()));

        assertEquals(StopReason.MAX_TOOL_CALLS, resp.getStopReason());
        verify(executor, never()).execute(any(), any());
    }

    /** 通过 {@code llm.respond}、{@code executor.execute} 驱动用例，布尔状态符合断言，实际结果与固定预期一致。 */
    @Test
    void brokenJson_isRepairedBeforeExecution() {
        AgentLlm llm = mock(AgentLlm.class);
        when(llm.respond(any(), any())).thenReturn(
                AgentDecision.builder().content("").toolCalls(List.of(
                        call("c1", "```json\n{\"query\":\"年假\",}\n```"))).build(),
                AgentDecision.builder().content("答案").toolCalls(List.of()).finishReason("stop").build());
        ToolExecutor executor = mock(ToolExecutor.class);
        when(executor.execute(any(), any())).thenReturn(ToolResult.builder().success(true).build());

        AgentResponse resp = runtime(llm, executor, 5, 10).run(context(), List.of(userMsg()));

        assertEquals(StopReason.FINAL_ANSWER, resp.getStopReason());
        ArgumentCaptor<ToolCall> captor = ArgumentCaptor.forClass(ToolCall.class);
        verify(executor, times(1)).execute(captor.capture(), any());
        String repaired = captor.getValue().getArguments();
        assertFalse(repaired.contains("```"), "代码块包裹应被剥掉，实际=" + repaired);
        assertTrue(repaired.contains("\"query\""), "参数应保留，实际=" + repaired);
    }

    /** 通过 {@code llm.respond} 驱动用例，跳过受保护的调用，布尔状态符合断言。 */
    @Test
    void unrepairableJson_isRefusedWithRetryHint() {
        AgentLlm llm = mock(AgentLlm.class);
        when(llm.respond(any(), any())).thenReturn(
                AgentDecision.builder().content("").toolCalls(List.of(
                        call("c1", "这不是 JSON"))).build(),
                AgentDecision.builder().content("换个说法后的答案").toolCalls(List.of())
                        .finishReason("stop").build());
        ToolExecutor executor = mock(ToolExecutor.class);

        AgentResponse resp = runtime(llm, executor, 5, 10).run(context(), List.of(userMsg()));

        assertEquals(StopReason.FINAL_ANSWER, resp.getStopReason());
        verify(executor, never()).execute(any(), any());
        assertEquals(1, resp.getToolInvocations().size());
        assertFalse(resp.getToolInvocations().get(0).isSuccess());
        assertTrue(resp.getToolInvocations().get(0).getErrorMessage().contains("换一种方式"));
    }
}
