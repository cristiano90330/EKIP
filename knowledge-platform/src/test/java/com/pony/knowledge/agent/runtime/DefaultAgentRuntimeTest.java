/* 验证 Agent 回合的循环限制、证据引用、取消、并行工具和终态。 */

package com.pony.knowledge.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pony.knowledge.agent.llm.AgentLlm;
import com.pony.knowledge.agent.tool.AgentTool;
import com.pony.knowledge.agent.tool.ToolDefinition;
import com.pony.knowledge.agent.tool.input.ToolInput;
import com.pony.knowledge.observability.RagMetrics;
import com.pony.knowledge.observability.RagPerformanceBudget;
import com.pony.knowledge.observability.RagStageTracer;
import com.pony.knowledge.observability.RagTraceRecorder;
import com.pony.knowledge.observability.TraceStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import com.pony.knowledge.agent.model.AgentDecision;
import com.pony.knowledge.agent.model.AgentExecutionContext;
import com.pony.knowledge.agent.model.AgentMessage;
import com.pony.knowledge.agent.model.AgentResponse;
import com.pony.knowledge.agent.model.StopReason;
import com.pony.knowledge.agent.model.ToolCall;
import com.pony.knowledge.agent.observability.AgentMetrics;
import com.pony.knowledge.agent.tool.ToolExecutor;
import com.pony.knowledge.agent.tool.ToolRegistry;
import com.pony.knowledge.agent.tool.output.KnowledgeSearchOutput;
import com.pony.knowledge.agent.tool.output.ReadDocumentOutput;
import com.pony.knowledge.agent.tool.output.ToolResult;
import com.pony.knowledge.agent.stream.AgentEvent;
import com.pony.knowledge.agent.stream.AgentEventSink;
import com.pony.knowledge.service.KnowledgeBaseAccessService;
import com.pony.knowledge.service.rag.model.RagEvidence;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.pony.knowledge.agent.tool.approval.ToolApprovalService;

/**
 * 3.7d 验收：Agent Runtime + Tool Calling Loop 的 5 个场景。
 * LLM 与 ToolExecutor 均 mock——本测试只验证循环编排逻辑（迭代数、工具数、停止原因、失败降级、越权隔离）。
 */
class DefaultAgentRuntimeTest {

    /** 构造没有可交付证据的引用标记，检查终态答案移除无效标记。 */
    @Test
    void finalDeliveryRemovesCitationMarkersWithoutAnActualSource() {
        AgentLlm llm = mock(AgentLlm.class);
        when(llm.respond(any(), any())).thenReturn(AgentDecision.builder()
                .content("无法查询记忆。[[cite:memory_read_disabled]]")
                .toolCalls(List.of()).build());
        AgentResponse response = newRuntime(llm, mock(ToolExecutor.class), 5)
                .run(authContext(), List.of(userMsg("查询长期记忆")));
        assertEquals("无法查询记忆。", response.getAnswer());
        assertTrue(response.getSources().isEmpty());
    }

    /** 通过 {@code llm.respond} 驱动用例，跳过受保护的调用，布尔状态符合断言。 */
    @Test
    void disabledMemoryWriteCommandReturnsAServiceRefusalBeforeModelIo() {
        AgentLlm llm = mock(AgentLlm.class);
        when(llm.respond(any(), any())).thenReturn(AgentDecision.builder()
                .content("已保存记忆").toolCalls(List.of()).build());
        ToolExecutor executor = mock(ToolExecutor.class);
        AgentExecutionContext context = authContext();
        context.setCurrentQuery("请记住：我的联调代号是青柚");
        context.setMemoryWriteEnabled(false);
        AgentResponse response = newRuntime(llm, executor, 5)
                .run(context, List.of(userMsg(context.getCurrentQuery())));
        assertTrue(response.getAnswer().contains("无法保存"));
        assertEquals(0, response.getToolCalls());
        verify(llm, never()).respond(any(), any());
        verify(executor, never()).execute(any(), any());
    }

    /** 通过 {@code llm.respond}、{@code executor.execute} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void directMemoryResultPreservesIdentifiersWithoutASecondGeneration() {
        String content = "已保存的用户自述：我的联调代号是青柚-b1f6d30c，原始文本包含 [[cite:literal]]";
        ToolCall call = ToolCall.builder().callId("memory-1").toolName("memory_search")
                .arguments("{\"query\":\"联调代号\"}").build();
        AgentLlm llm = mock(AgentLlm.class);
        when(llm.respond(any(), any())).thenReturn(
                AgentDecision.builder().content("").toolCalls(List.of(call)).build(),
                AgentDecision.builder().content("错误代号 [[cite:invented]]").toolCalls(List.of()).build());
        ToolExecutor executor = mock(ToolExecutor.class);
        when(executor.execute(any(), any())).thenReturn(ToolResult.builder()
                .toolName("memory_search").callId("memory-1").success(true)
                .directAnswer(content).build());
        AgentResponse response = newRuntime(llm, executor, 5).run(authContext(),
                List.of(userMsg("查询我以前保存的联调代号")));
        assertEquals(content, response.getAnswer());
        assertEquals(1, response.getIterations());
        verify(llm, times(1)).respond(any(), any());
    }

    /** 在证据型 Agent 中提供工具直答的记忆结果，核对它未被当成有效知识库证据。 */
    @Test
    void directMemoryResultDoesNotSatisfyGroundedKnowledgePolicy() {
        ToolCall call = ToolCall.builder().callId("memory-1").toolName("memory_search")
                .arguments("{\"query\":\"制度\"}").build();
        AgentLlm llm = mock(AgentLlm.class);
        when(llm.respond(any(), any())).thenReturn(
                AgentDecision.builder().content("").toolCalls(List.of(call)).build(),
                AgentDecision.builder().content("用户自述的制度").toolCalls(List.of()).build());
        ToolExecutor executor = mock(ToolExecutor.class);
        when(executor.execute(any(), any())).thenReturn(ToolResult.builder()
                .toolName("memory_search").success(true).directAnswer("用户自述的制度").build());
        AgentExecutionContext context = authContext();
        context.setAnswerPolicy("grounded");
        context.setAccessibleKnowledgeBaseIds(Set.of());
        AgentResponse response = newRuntime(llm, executor, 5)
                .run(context, List.of(userMsg("查询企业制度")));
        assertEquals("no_evidence", response.getOutcome());
        assertFalse(response.getAnswer().contains("用户自述的制度"));
    }

    /**
     * 提供指定角色和内容的模型消息。
     * @param content 待处理的正文内容
     * @return 指定角色和内容的模型消息
     */
    private AgentMessage userMsg(String content) {
        return AgentMessage.builder().role("user").content(content).build();
    }

    /** 在该类型内部准备后续运行所需的数据。 */
    private AgentExecutionContext authContext() {
        return AgentExecutionContext.builder()
                .answerPolicy("general")
                .userId(1L)
                .accessibleKnowledgeBaseIds(Set.of(1L))
                .build();
    }

    /**
     * 协调 {@code newRuntime} 完成当前阶段，供后续处理使用。
     * 下游调用 {@code newRuntime}、{@code Runnable::run}。
     *
     * @param llm 本次调用传入的 {@code AgentLlm}
     * @param executor 本次调用传入的 {@code ToolExecutor}
     * @param maxIterations 本次调用传入的 {@code int}
     * @return 调用方使用的 {@code AgentRuntime}
     */
    private AgentRuntime newRuntime(AgentLlm llm, ToolExecutor executor, int maxIterations) {
        return newRuntime(llm, executor, maxIterations, 10, 20000L, Runnable::run);
    }

    /**
     * 在该类型内部准备后续运行所需的数据。
     *
     * @param llm 本次调用传入的 {@code AgentLlm}
     * @param executor 本次调用传入的 {@code ToolExecutor}
     * @param maxIterations 本次调用传入的 {@code int}
     * @param maxToolCalls 本次调用传入的 {@code int}
     * @param budgetTimeMs 本次调用传入的 {@code long}
     * @param toolPool 本次调用传入的 {@code Executor}
     * @return 调用方使用的 {@code AgentRuntime}
     */
    private AgentRuntime newRuntime(AgentLlm llm, ToolExecutor executor, int maxIterations,
            int maxToolCalls, long budgetTimeMs, Executor toolPool) {
        return new DefaultAgentRuntime(
                llm, executor, new ToolRegistry(List.of()), toolPool,
                maxIterations, maxToolCalls, budgetTimeMs);
    }

    /** 通过 {@code llm.respond} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void case1_noTool_directAnswer() {
        AgentLlm llm = mock(AgentLlm.class);
        when(llm.respond(any(), any()))
                .thenReturn(AgentDecision.builder().content("你好").toolCalls(List.of()).build());

        AgentResponse resp = newRuntime(llm, mock(ToolExecutor.class), 5)
                .run(authContext(), List.of(userMsg("你好")));

        assertEquals("你好", resp.getAnswer());
        assertEquals(StopReason.FINAL_ANSWER, resp.getStopReason());
        assertEquals(1, resp.getIterations());
        assertEquals(0, resp.getToolCalls());
    }

    /** 通过 {@code llm.respond}、{@code runtime.run} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void think_onlyExposesToolsAllowedByAgentContext() {
        AgentLlm llm = mock(AgentLlm.class);
        when(llm.respond(any(), any()))
                .thenReturn(AgentDecision.builder().content("答案").toolCalls(List.of()).build());
        ToolRegistry registry = new ToolRegistry(
                List.of(new NamedTool("allowed"), new NamedTool("blocked")));
        AgentRuntime runtime = new DefaultAgentRuntime(
                llm, mock(ToolExecutor.class), registry, Runnable::run, new ObjectMapper(),
                5, 10, 4, true, 20000L, null);
        AgentExecutionContext context = AgentExecutionContext.builder()
                .answerPolicy("general")
                .userId(1L)
                .allowedToolNames(Set.of("allowed"))
                .build();

        runtime.run(context, List.of(userMsg("q")));

        ArgumentCaptor<List<ToolDefinition>> tools = ArgumentCaptor.forClass(List.class);
        verify(llm).respond(any(), tools.capture());
        assertEquals(List.of("allowed"),
                tools.getValue().stream().map(ToolDefinition::getName).toList(),
                "白名单外的工具不应出现在模型 schema 中");
    }

    /** 通过 {@code llm.respond}、{@code executor.execute} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void case2_oneToolCall_thenFinalAnswer() {
        ToolCall call = ToolCall.builder()
                .callId("c1").toolName("knowledge_search")
                .arguments("{\"query\":\"Redis连接池怎么配置\"}")
                .build();
        AgentLlm llm = mock(AgentLlm.class);
        when(llm.respond(any(), any()))
                .thenReturn(AgentDecision.builder().content("").toolCalls(List.of(call)).build(),
                        AgentDecision.builder().content("最终答案").toolCalls(List.of()).build());
        ToolExecutor executor = mock(ToolExecutor.class);
        when(executor.execute(any(), any()))
                .thenReturn(ToolResult.builder().toolName("knowledge_search").callId("c1").success(true).build());

        AgentResponse resp = newRuntime(llm, executor, 5)
                .run(authContext(), List.of(userMsg("Redis连接池怎么配置？")));

        assertEquals("最终答案", resp.getAnswer());
        assertEquals(StopReason.FINAL_ANSWER, resp.getStopReason());
        assertEquals(2, resp.getIterations());
        assertEquals(1, resp.getToolCalls());
        verify(executor).execute(any(ToolCall.class), any(AgentExecutionContext.class));
    }

    /** 通过 {@code llm.respond}、{@code executor.execute} 驱动用例，布尔状态符合断言，实际结果与固定预期一致。 */
    @Test
    void case3_toolFailure_feedsBack_degradedAnswer_noCrash() {
        ToolCall call = ToolCall.builder()
                .callId("c1").toolName("knowledge_search").arguments("{\"query\":\"xxx\"}")
                .build();
        AgentLlm llm = mock(AgentLlm.class);
        when(llm.respond(any(), any()))
                .thenReturn(AgentDecision.builder().content("").toolCalls(List.of(call)).build(),
                        AgentDecision.builder().content("抱歉，检索失败，请稍后再试").toolCalls(List.of()).build());
        ToolExecutor executor = mock(ToolExecutor.class);
        when(executor.execute(any(), any()))
                .thenReturn(ToolResult.builder().toolName("knowledge_search").callId("c1")
                        .success(false).errorMessage("timeout").build());

        AgentResponse resp = newRuntime(llm, executor, 5)
                .run(authContext(), List.of(userMsg("xxx")));

        // 不崩：失败结果回灌 LLM，最终降级回答
        assertEquals(StopReason.FINAL_ANSWER, resp.getStopReason());
        assertEquals("抱歉，检索失败，请稍后再试", resp.getAnswer());
        assertEquals(2, resp.getIterations());
        assertEquals(1, resp.getToolCalls());

        // 第二次 LLM 收到的历史里带有 role=tool 的失败结果
        ArgumentCaptor<List<AgentMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(llm, atLeast(2)).respond(captor.capture(), any());
        AgentMessage toolMsg = captor.getAllValues().get(1).stream()
                .filter(m -> "tool".equals(m.getRole()))
                .findFirst().orElseThrow();
        assertTrue(toolMsg.getContent().contains("timeout"), "失败原因应回灌给 LLM: " + toolMsg.getContent());
    }

    /** 通过 {@code llm.respond}、{@code executor.execute} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void case4_infiniteLoop_stopsAtMaxIterations() {
        ToolCall call = ToolCall.builder().callId("c").toolName("knowledge_search").arguments("{}").build();
        AgentLlm llm = mock(AgentLlm.class);
        when(llm.respond(any(), any()))
                .thenReturn(AgentDecision.builder().content("").toolCalls(List.of(call)).build());
        ToolExecutor executor = mock(ToolExecutor.class);
        when(executor.execute(any(), any()))
                .thenReturn(ToolResult.builder().success(true).build());

        AgentResponse resp = newRuntime(llm, executor, 5)
                .run(authContext(), List.of(userMsg("循环")));

        assertEquals(StopReason.MAX_ITERATIONS, resp.getStopReason());
        assertEquals(5, resp.getIterations());
        assertEquals(5, resp.getToolCalls());
    }

    /** 通过 {@code llm.respond}、{@code executor.execute} 驱动用例，布尔状态符合断言，实际结果与固定预期一致。 */
    @Test
    void case5_privilegeEscalation_identityComesFromAuthNotLlm() {
        String malicious = "{\"query\":\"机密工资数据\",\"topK\":5,\"knowledgeBaseId\":999}";
        ToolCall call = ToolCall.builder().callId("c5").toolName("knowledge_search").arguments(malicious).build();
        AgentLlm llm = mock(AgentLlm.class);
        when(llm.respond(any(), any()))
                .thenReturn(AgentDecision.builder().content("").toolCalls(List.of(call)).build(),
                        AgentDecision.builder().content("答案").toolCalls(List.of()).build());
        ToolExecutor executor = mock(ToolExecutor.class);
        when(executor.execute(any(), any()))
                .thenReturn(ToolResult.builder().success(true).build());

        AgentResponse resp = newRuntime(llm, executor, 5)
                .run(authContext(), List.of(userMsg("机密工资数据")));

        assertEquals(StopReason.FINAL_ANSWER, resp.getStopReason());
        // runtime 原样把 auth 构建的 context 传给 executor；LLM 参数里的 999 从未进入身份
        ArgumentCaptor<AgentExecutionContext> ctxCaptor = ArgumentCaptor.forClass(AgentExecutionContext.class);
        verify(executor).execute(any(ToolCall.class), ctxCaptor.capture());
        assertEquals(1L, ctxCaptor.getValue().getUserId());
        assertEquals(Set.of(1L), ctxCaptor.getValue().getAccessibleKnowledgeBaseIds());
        assertFalse(ctxCaptor.getValue().getAccessibleKnowledgeBaseIds().contains(999L));
    }

    /**
     * 提供模型本轮的正文、工具调用和完成状态。
     * @param callId 本次调用传入的 {@code String}
     * @return 模型本轮的正文、工具调用和完成状态
     */
    private AgentDecision toolCallDecision(String callId) {
        return AgentDecision.builder().content("").toolCalls(List.of(
                ToolCall.builder().callId(callId).toolName("knowledge_search").arguments("{}").build()))
                .build();
    }

    /** 通过 {@code llm.respond}、{@code executor.execute} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void maxToolCalls_exceeded_stopsImmediately() {
        AgentLlm llm = mock(AgentLlm.class);
        when(llm.respond(any(), any())).thenReturn(toolCallDecision("c"));
        ToolExecutor executor = mock(ToolExecutor.class);
        when(executor.execute(any(), any())).thenReturn(ToolResult.builder().success(true).build());

        AgentResponse resp = newRuntime(llm, executor, 5, 1, 20000L, Runnable::run)
                .run(authContext(), List.of(userMsg("x")));

        assertEquals(StopReason.MAX_TOOL_CALLS, resp.getStopReason());
        assertEquals(2, resp.getIterations());
        assertEquals(1, resp.getToolCalls());
        verify(executor, times(1)).execute(any(), any());
    }

    /** 通过 {@code ctx.cancel}、{@code authContext} 驱动该场景，跳过受保护的调用，实际结果与固定预期一致。 */
    @Test
    void cancellation_preCancelled_stopsBeforeLlm() {
        AgentLlm llm = mock(AgentLlm.class);
        AgentExecutionContext ctx = authContext();
        ctx.cancel();

        AgentResponse resp = newRuntime(llm, mock(ToolExecutor.class), 5)
                .run(ctx, List.of(userMsg("x")));

        assertEquals(StopReason.CANCELLED, resp.getStopReason());
        assertEquals(0, resp.getIterations());
        verify(llm, never()).respond(any(), any());
    }

    /** 通过 {@code llm.respond} 驱动用例，跳过受保护的调用，实际结果与固定预期一致。 */
    @Test
    void cancellation_midRun_stopsBeforeTool() {
        AgentExecutionContext ctx = authContext();
        AgentLlm llm = mock(AgentLlm.class);
        when(llm.respond(any(), any())).thenAnswer(inv -> {
            ctx.cancel();
            return toolCallDecision("c");
        });
        ToolExecutor executor = mock(ToolExecutor.class);

        AgentResponse resp = newRuntime(llm, executor, 5).run(ctx, List.of(userMsg("x")));

        assertEquals(StopReason.CANCELLED, resp.getStopReason());
        assertEquals(1, resp.getIterations());
        verify(executor, never()).execute(any(), any());
    }

    /** 通过 {@code llm.respond} 驱动用例，跳过受保护的调用，实际结果与固定预期一致。 */
    @Test
    void budgetExceeded_stopsAgent() {
        AgentLlm llm = mock(AgentLlm.class);
        when(llm.respond(any(), any())).thenAnswer(inv -> {
            Thread.sleep(600);
            return toolCallDecision("c");
        });
        ToolExecutor executor = mock(ToolExecutor.class);

        // 预留类加载时间，稳定覆盖“模型调用后才耗尽预算”的分支。
        AgentResponse resp = newRuntime(llm, executor, 5, 10, 500L, Runnable::run)
                .run(authContext(), List.of(userMsg("x")));

        assertEquals(StopReason.BUDGET_EXCEEDED, resp.getStopReason());
        assertEquals(1, resp.getIterations());
        verify(executor, never()).execute(any(), any());
    }

    /** 通过 {@code llm.respond}、{@code executor.execute} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void toolAFailure_doesNotAffectToolB() {
        ToolCall callA = ToolCall.builder().callId("a").toolName("t_a").arguments("{}").build();
        ToolCall callB = ToolCall.builder().callId("b").toolName("t_b").arguments("{}").build();
        AgentLlm llm = mock(AgentLlm.class);
        when(llm.respond(any(), any()))
                .thenReturn(AgentDecision.builder().content("").toolCalls(List.of(callA, callB)).build(),
                        AgentDecision.builder().content("答案").toolCalls(List.of()).build());
        ToolExecutor executor = mock(ToolExecutor.class);
        when(executor.execute(any(), any()))
                .thenReturn(ToolResult.builder().callId("a").success(false).errorMessage("A失败").build(),
                        ToolResult.builder().callId("b").success(true).build());

        AgentResponse resp = newRuntime(llm, executor, 5).run(authContext(), List.of(userMsg("x")));

        assertEquals(StopReason.FINAL_ANSWER, resp.getStopReason());
        assertEquals(2, resp.getToolCalls());
        ArgumentCaptor<List<AgentMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(llm, times(2)).respond(captor.capture(), any());
        long toolMsgs = captor.getAllValues().get(1).stream()
                .filter(m -> "tool".equals(m.getRole())).count();
        assertEquals(2, toolMsgs, "A 失败不影响 B：两条 tool 结果都应回灌给 LLM");
    }

    /** 通过 {@code llm.respond}、{@code executor.execute} 驱动用例，布尔状态符合断言，实际结果与固定预期一致。 */
    @Test
    void dispatchPoolRejection_becomesFailedToolResult_turnStillCompletes() {
        ToolCall callA = ToolCall.builder().callId("a").toolName("t_a").arguments("{}").build();
        ToolCall callB = ToolCall.builder().callId("b").toolName("t_b").arguments("{}").build();
        AgentLlm llm = mock(AgentLlm.class);
        when(llm.respond(any(), any()))
                .thenReturn(AgentDecision.builder().content("").toolCalls(List.of(callA, callB)).build(),
                        AgentDecision.builder().content("答案").toolCalls(List.of()).build());
        ToolExecutor executor = mock(ToolExecutor.class);
        when(executor.execute(any(), any())).thenAnswer(inv -> ToolResult.builder()
                .callId(((ToolCall) inv.getArgument(0)).getCallId()).success(true).build());

        AtomicInteger submitted = new AtomicInteger();
        Executor dispatchPoolThatRejectsSecondTask = task -> {
            if (submitted.incrementAndGet() == 2) {
                throw new RejectedExecutionException("dispatch pool saturated");
            }
            task.run();
        };

        AgentRuntime runtime = newRuntime(llm, executor, 5, 10, 20000L, dispatchPoolThatRejectsSecondTask);
        AgentResponse resp = assertDoesNotThrow(() -> runtime.run(authContext(), List.of(userMsg("x"))));

        assertEquals(StopReason.FINAL_ANSWER, resp.getStopReason(), "派发被拒不等于整轮失败");
        assertEquals(2, resp.getToolInvocations().size(), "被拒的那条也要留一条记录，不能静默少一个工具");
        assertTrue(resp.getToolInvocations().get(0).isSuccess(), "A 的结果不能因为 B 被拒而丢");
        assertFalse(resp.getToolInvocations().get(1).isSuccess());
        assertNotNull(resp.getToolInvocations().get(1).getErrorMessage());
        assertTrue(resp.getToolInvocations().get(1).getErrorMessage().contains("池"),
                "错误要指名是池饱和，否则运维会去查工具本身");

        ArgumentCaptor<List<AgentMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(llm, times(2)).respond(captor.capture(), any());
        long toolMsgs = captor.getAllValues().get(1).stream()
                .filter(m -> "tool".equals(m.getRole())).count();
        assertEquals(2, toolMsgs, "两条都要回灌给 LLM，它才知道有一个没查到");
    }

    /** 通过 {@code llm.respond}、{@code executor.execute} 驱动用例，布尔状态符合断言。 */
    @Test
    void parallelTools_runConcurrently() throws Exception {
        ToolCall callA = ToolCall.builder().callId("a").toolName("t_a").arguments("{}").build();
        ToolCall callB = ToolCall.builder().callId("b").toolName("t_b").arguments("{}").build();
        AgentLlm llm = mock(AgentLlm.class);
        when(llm.respond(any(), any()))
                .thenReturn(AgentDecision.builder().content("").toolCalls(List.of(callA, callB)).build(),
                        AgentDecision.builder().content("答案").toolCalls(List.of()).build());
        ToolExecutor executor = mock(ToolExecutor.class);
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        when(executor.execute(any(), any())).thenAnswer(inv -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            return ToolResult.builder().success(true).build();
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        AgentRuntime runtime = newRuntime(llm, executor, 5, 10, 20000L, pool);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            var future = caller.submit(() -> runtime.run(authContext(), List.of(userMsg("x"))));
            assertTrue(started.await(1, TimeUnit.SECONDS), "两个独立工具应并行启动");
            release.countDown();
            future.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
            caller.shutdownNow();
        }
    }

    /** 通过 {@code executor.execute}、{@code llm.respond} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void parallelTools_enforcesConcurrencyCap() throws Exception {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        ToolExecutor executor = mock(ToolExecutor.class);
        when(executor.execute(any(), any())).thenAnswer(inv -> {
            int cur = inFlight.incrementAndGet();
            maxConcurrent.accumulateAndGet(cur, Math::max);
            Thread.sleep(60);
            inFlight.decrementAndGet();
            return ToolResult.builder().success(true).build();
        });
        AgentLlm llm = mock(AgentLlm.class);
        List<ToolCall> calls = List.of(
                ToolCall.builder().callId("a").toolName("knowledge_search").arguments("{}").build(),
                ToolCall.builder().callId("b").toolName("knowledge_search").arguments("{}").build(),
                ToolCall.builder().callId("c").toolName("knowledge_search").arguments("{}").build(),
                ToolCall.builder().callId("d").toolName("knowledge_search").arguments("{}").build());
        when(llm.respond(any(), any()))
                .thenReturn(AgentDecision.builder().content("").toolCalls(calls).build(),
                        AgentDecision.builder().content("答案").toolCalls(List.of()).build());

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            AgentRuntime runtime = new DefaultAgentRuntime(
                    llm, executor, new ToolRegistry(List.of()), pool, new ObjectMapper(),
                    5, 10, 2, true, 20000L, null);
            AgentResponse resp = runtime.run(authContext(), List.of(userMsg("x")));
            assertEquals(StopReason.FINAL_ANSWER, resp.getStopReason());
            assertEquals(4, resp.getToolCalls());
            assertEquals(2, maxConcurrent.get(), "并发工具数应被 Semaphore 封顶到 maxConcurrentTools = 2");
        } finally {
            pool.shutdownNow();
        }
    }

    /** 通过 {@code executor.execute}、{@code llm.respond} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void parallelToolsDisabled_runsSequential() throws Exception {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        ToolExecutor executor = mock(ToolExecutor.class);
        when(executor.execute(any(), any())).thenAnswer(inv -> {
            int cur = inFlight.incrementAndGet();
            maxConcurrent.accumulateAndGet(cur, Math::max);
            try {
                Thread.sleep(40);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            inFlight.decrementAndGet();
            return ToolResult.builder().success(true).build();
        });
        AgentLlm llm = mock(AgentLlm.class);
        List<ToolCall> calls = List.of(
                ToolCall.builder().callId("a").toolName("knowledge_search").arguments("{}").build(),
                ToolCall.builder().callId("b").toolName("knowledge_search").arguments("{}").build(),
                ToolCall.builder().callId("c").toolName("knowledge_search").arguments("{}").build(),
                ToolCall.builder().callId("d").toolName("knowledge_search").arguments("{}").build());
        when(llm.respond(any(), any()))
                .thenReturn(AgentDecision.builder().content("").toolCalls(calls).build(),
                        AgentDecision.builder().content("答案").toolCalls(List.of()).build());

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            AgentRuntime runtime = new DefaultAgentRuntime(
                    llm, executor, new ToolRegistry(List.of()), pool, new ObjectMapper(),
                    5, 10, 4, false, 20000L, null);
            AgentResponse resp = runtime.run(authContext(), List.of(userMsg("x")));
            assertEquals(StopReason.FINAL_ANSWER, resp.getStopReason());
            assertEquals(4, resp.getToolCalls());
            assertEquals(1, maxConcurrent.get(), "parallelTools=false 应顺序执行（并发 = 1）");
        } finally {
            pool.shutdownNow();
        }
    }

    /** 通过 {@code ObservationRegistry.create}、{@code llm.respond} 驱动用例，布尔状态符合断言，实际结果与固定预期一致。 */
    @Test
    void parallelTools_realToolExecutor_noSpuriousTimeout() throws Exception {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        ExecutorService toolPool = Executors.newFixedThreadPool(2);
        ExecutorService dispatchPool = Executors.newFixedThreadPool(4);
        try {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            RagMetrics metrics = new RagMetrics(registry, "hybrid");
            RagPerformanceBudget budget = new RagPerformanceBudget(
                    metrics, 1000L, 1000L, 1000L, 1000L, 1000L, 10L, 0.01);
            RagStageTracer tracer = new RagStageTracer(
                    ObservationRegistry.create(), registry, metrics, budget, new RagTraceRecorder(), null, 2.0, mock(TraceStore.class));
            Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
            AgentTool tool = new RecordingTool(inFlight, maxConcurrent, 80);
            ToolExecutor executor = new ToolExecutor(
                    new ToolRegistry(List.of(tool)), new ObjectMapper(), validator, tracer,
                    mock(ToolApprovalService.class), mock(KnowledgeBaseAccessService.class),
                    toolPool, 2000L, 0, 0L);
            ToolRegistry registryWithTool = new ToolRegistry(List.of(tool));

            AgentLlm llm = mock(AgentLlm.class);
            List<ToolCall> calls = List.of(
                    ToolCall.builder().callId("a").toolName("t").arguments("{}").build(),
                    ToolCall.builder().callId("b").toolName("t").arguments("{}").build(),
                    ToolCall.builder().callId("c").toolName("t").arguments("{}").build(),
                    ToolCall.builder().callId("d").toolName("t").arguments("{}").build());
            when(llm.respond(any(), any()))
                    .thenReturn(AgentDecision.builder().content("").toolCalls(calls).build(),
                            AgentDecision.builder().content("答案").toolCalls(List.of()).build());

            AgentRuntime runtime = new DefaultAgentRuntime(
                    llm, executor, registryWithTool, dispatchPool, new ObjectMapper(),
                    5, 10, 4, true, 5000L, null);
            AgentResponse resp = runtime.run(authContext(), List.of(userMsg("x")));

            assertEquals(StopReason.FINAL_ANSWER, resp.getStopReason(), "真实 ToolExecutor 下工具应全部完成，无自锁假超时");
            assertEquals(4, resp.getToolCalls());
            assertTrue(maxConcurrent.get() >= 2, "工具应并行执行，max=" + maxConcurrent.get());
        } finally {
            toolPool.shutdownNow();
            dispatchPool.shutdownNow();
        }
    }

    /**
     * 提供模型本轮的正文、工具调用和完成状态。
     *
     * @param content 待处理的正文内容
     * @param prompt 本次调用传入的 {@code int}
     * @param completion 完成后要交付的终态
     * @return 模型本轮的正文、工具调用和完成状态
     */
    private AgentDecision answerWithUsage(String content, int prompt, int completion) {
        return AgentDecision.builder().content(content).toolCalls(List.of())
                .promptTokens(prompt).completionTokens(completion).build();
    }

    /**
     * 提供模型本轮的正文、工具调用和完成状态。
     *
     * @param callId 本次调用传入的 {@code String}
     * @param prompt 本次调用传入的 {@code int}
     * @param completion 完成后要交付的终态
     * @return 模型本轮的正文、工具调用和完成状态
     */
    private AgentDecision toolCallWithUsage(String callId, int prompt, int completion) {
        return AgentDecision.builder().content("").toolCalls(List.of(
                ToolCall.builder().callId(callId).toolName("knowledge_search").arguments("{}").build()))
                .promptTokens(prompt).completionTokens(completion).build();
    }

    /** 通过 {@code llm.respond}、{@code executor.execute} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void llmUsage_accumulatesAcrossIterations_andRecordedToMetrics() {
        AgentLlm llm = mock(AgentLlm.class);
        when(llm.respond(any(), any()))
                .thenReturn(toolCallWithUsage("c1", 100, 5), answerWithUsage("最终答案", 200, 20));
        ToolExecutor executor = mock(ToolExecutor.class);
        when(executor.execute(any(), any())).thenReturn(ToolResult.builder().success(true).build());

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AgentRuntime runtime = new DefaultAgentRuntime(
                llm, executor, new ToolRegistry(List.of()), Runnable::run, new ObjectMapper(),
                5, 10, 4, true, 20000L, null, new AgentMetrics(meterRegistry));

        AgentResponse resp = runtime.run(authContext(), List.of(userMsg("x")));

        assertEquals(300, resp.getPromptTokens(), "两轮 prompt token 应累加");
        assertEquals(25, resp.getCompletionTokens());
        assertEquals(300.0, meterRegistry.get("agent.llm.tokens.prompt").counter().count());
        assertEquals(25.0, meterRegistry.get("agent.llm.tokens.completion").counter().count());
    }

    /** 通过 {@code llm.respond}、{@code executor.execute} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void llmUsage_recordedOnUnsuccessfulStop() {
        AgentLlm llm = mock(AgentLlm.class);
        when(llm.respond(any(), any())).thenReturn(toolCallWithUsage("c", 10, 1));
        ToolExecutor executor = mock(ToolExecutor.class);
        when(executor.execute(any(), any())).thenReturn(ToolResult.builder().success(true).build());

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AgentRuntime runtime = new DefaultAgentRuntime(
                llm, executor, new ToolRegistry(List.of()), Runnable::run, new ObjectMapper(),
                2, 10, 4, true, 20000L, null, new AgentMetrics(meterRegistry));

        AgentResponse resp = runtime.run(authContext(), List.of(userMsg("循环")));

        assertEquals(StopReason.MAX_ITERATIONS, resp.getStopReason());
        assertEquals(2, resp.getIterations());
        // 2 轮决策（各 10）+ 1 次兜底合成（10）：用尽轮次后仍会拿已有工具结果要一次答案。
        assertEquals(30, resp.getPromptTokens(), "没答出来也花了 3 次调用的 token");
        assertEquals(30.0, meterRegistry.get("agent.llm.tokens.prompt").counter().count(),
                "非成功终止同样要打点：这些 token 已经真实花掉");
    }

    // ---------- 3.1 P1-1：治理层降级的出口 ----------

    /**
     * 提供模型本轮的正文、工具调用和完成状态。
     * @param reason 诊断或终止原因
     * @return 模型本轮的正文、工具调用和完成状态
     */
    private AgentDecision degradedDecision(String reason) {
        return AgentDecision.builder().degraded(true).degradeReason(reason)
                .toolCalls(List.of()).providerUsed("ollama").build();
    }

    /** 通过 {@code llm.respond}、{@code runtime.run} 驱动用例，跳过受保护的调用，布尔状态符合断言。 */
    @Test
    void degradedDecision_terminatesTurnWithoutTools_andCarriesReadableAnswer() {
        AgentLlm llm = mock(AgentLlm.class);
        when(llm.respond(any(), any())).thenReturn(degradedDecision("circuit open（主 provider 熔断，降级也失败）"));
        ToolExecutor executor = mock(ToolExecutor.class);

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AgentRuntime runtime = new DefaultAgentRuntime(
                llm, executor, new ToolRegistry(List.of()), Runnable::run, new ObjectMapper(),
                5, 10, 4, true, 20000L, null, new AgentMetrics(meterRegistry));

        AgentResponse resp = runtime.run(authContext(), List.of(userMsg("年假几天")));

        assertEquals(StopReason.LLM_DEGRADED, resp.getStopReason());
        assertEquals(1, resp.getIterations(), "降级当轮就终止，不进第二轮");
        assertTrue(resp.isDegraded());
        assertEquals("dependency_unavailable",resp.getOutcome());
        assertEquals("circuit open（主 provider 熔断，降级也失败）", resp.getDegradeReason());
        assertEquals("ollama", resp.getProviderUsed());
        assertNotNull(resp.getAnswer(), "降级出口必须给用户一句话，不能是 null → 空回复");
        assertTrue(resp.getAnswer().contains("不可用"), "文案要说明是服务不可用，实际=" + resp.getAnswer());
        verify(executor, never()).execute(any(), any());
        assertEquals(1.0, meterRegistry.get("agent.llm.degraded").counter().count(),
                "降级回合要计数，否则「有多少回合其实没问过模型」不可见");
    }

    /** 通过 {@code llm.respond}、{@code runtime.run} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void degradedDecision_stillCountsTokensAlreadySpent() {
        AgentLlm llm = mock(AgentLlm.class);
        when(llm.respond(any(), any())).thenReturn(AgentDecision.builder()
                .degraded(true).degradeReason("prompt 超预算直接降级")
                .promptTokens(1500).completionTokens(0).toolCalls(List.of()).build());

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AgentRuntime runtime = new DefaultAgentRuntime(
                llm, mock(ToolExecutor.class), new ToolRegistry(List.of()), Runnable::run, new ObjectMapper(),
                5, 10, 4, true, 20000L, null, new AgentMetrics(meterRegistry));

        AgentResponse resp = runtime.run(authContext(), List.of(userMsg("x")));

        assertEquals(StopReason.LLM_DEGRADED, resp.getStopReason());
        assertEquals(1500, resp.getPromptTokens());
        assertEquals(1500.0, meterRegistry.get("agent.llm.tokens.prompt").counter().count(),
                "降级不等于没花钱：预算超限前的估算同样计入");
    }

    /** 通过 {@code llm.respondStream}、{@code runtime.run} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void modelTimeoutRemainsTimeoutInBothResponseAndSseTerminal() {
        AgentLlm llm=mock(AgentLlm.class);
        when(llm.respondStream(any(),any(),any())).thenReturn(AgentDecision.builder().degraded(true)
                .degradeReason("model deadline").outcome("timeout").reasonCode("model_timeout").toolCalls(List.of()).build());
        DefaultAgentRuntime runtime=new DefaultAgentRuntime(llm,mock(ToolExecutor.class),new ToolRegistry(List.of()),
                Runnable::run,5,10,20000L);
        List<AgentEvent> events=new ArrayList<>();
        AgentResponse response=runtime.run(authContext(),List.of(userMsg("q")),events::add);
        assertEquals("timeout",response.getOutcome());assertEquals("model_timeout",response.getReasonCode());
        AgentEvent done=events.stream().filter(event -> AgentEvent.DONE.equals(event.getType())).findFirst().orElseThrow();
        assertEquals("timeout",done.getData().get("outcome"));
        assertEquals("model_timeout",done.getData().get("reasonCode"));
    }

    /** 通过 {@code llm.respond}、{@code executor.execute} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void degradedDecision_afterToolRound_keepsInvocationsAndToolCallCount() {
        AgentLlm llm = mock(AgentLlm.class);
        when(llm.respond(any(), any())).thenReturn(
                toolCallWithUsage("c1", 100, 5), degradedDecision("final err: APIConnectionError: 连不上"));
        ToolExecutor executor = mock(ToolExecutor.class);
        when(executor.execute(any(), any())).thenReturn(ToolResult.builder().success(true).build());

        AgentRuntime runtime = newRuntime(llm, executor, 5);
        AgentResponse resp = runtime.run(authContext(), List.of(userMsg("x")));

        assertEquals(StopReason.LLM_DEGRADED, resp.getStopReason());
        assertEquals(2, resp.getIterations());
        assertEquals(1, resp.getToolCalls());
        assertEquals(1, resp.getToolInvocations().size(), "第一圈的工具记录要留着");
    }

    /** 让工具返回不同完整度的片段，核对来源仅包含完整发送给模型的文本，HTTP 与 SSE 终态一致。 */
    @Test
    void knowledgeSources_includeOnlyCompleteFragmentsSentToModel_andMatchSseFinalFrames() {
        ToolCall call = ToolCall.builder().callId("source-call")
                .toolName("knowledge_search").arguments("{\"query\":\"q\"}").build();
        RagEvidence visible = RagEvidence.builder().documentId(10L).chunkId(11L)
                .knowledgeBaseId(1L).chunkIndex(1).content("完整获准片段").build();
        RagEvidence clipped = RagEvidence.builder().documentId(10L).chunkId(12L)
                .knowledgeBaseId(1L).chunkIndex(2).content("过长片段".repeat(1000)).build();
        KnowledgeSearchOutput output = KnowledgeSearchOutput.builder()
                .outcome("success").evidences(List.of(visible, clipped)).build();
        AgentLlm llm = mock(AgentLlm.class);
        when(llm.respondStream(any(), any(), any())).thenReturn(
                AgentDecision.builder().content("").toolCalls(List.of(call)).build(),
                AgentDecision.builder().content("根据材料回答").toolCalls(List.of()).build());
        ToolExecutor executor = mock(ToolExecutor.class);
        when(executor.execute(any(), any())).thenReturn(ToolResult.builder()
                .toolName("knowledge_search").callId("source-call").success(true)
                .outcome("success").data(output).build());
        DefaultAgentRuntime runtime = new DefaultAgentRuntime(llm, executor,
                new ToolRegistry(List.of()), Runnable::run, new ObjectMapper(),
                5, 10, 4, true, 20000L, null, null,
                new ReactSettings(true, 2, 3, 1000, ""));
        List<AgentEvent> events = new ArrayList<>();
        AgentEventSink sink = events::add;

        AgentResponse response = runtime.run(authContext(), List.of(userMsg("q")), sink);

        assertEquals("success", response.getOutcome());
        assertEquals(1, response.getSources().size());
        assertEquals(11L, response.getSources().get(0).getChunkId());
        assertEquals("S10_11", response.getSources().get(0).getCitationId());
        ArgumentCaptor<List<AgentMessage>> inputs = ArgumentCaptor.forClass(List.class);
        verify(llm, times(2)).respondStream(inputs.capture(), any(), any());
        assertTrue(inputs.getAllValues().get(1).stream().anyMatch(message ->
                "tool".equals(message.getRole()) && message.getContent().contains("[[cite:S10_11]]")
                        && message.getContent().contains("完整获准片段")));
        AgentEvent answer = events.stream().filter(e -> AgentEvent.ANSWER.equals(e.getType())).findFirst().orElseThrow();
        AgentEvent done = events.stream().filter(e -> AgentEvent.DONE.equals(e.getType())).findFirst().orElseThrow();
        assertEquals(response.getSources(), answer.getData().get("sources"));
        assertEquals(response.getSources(), done.getData().get("sources"));
        assertEquals(response.getOutcome(), answer.getData().get("outcome"));
        assertEquals(response.getOutcome(), done.getData().get("outcome"));
    }

    /** 通过 {@code llm.respond}、{@code executor.execute} 驱动用例，布尔状态符合断言，返回的数据、边界或顺序符合断言。 */
    @Test
    void knowledgeSources_areNotPublishedWhenNextDecisionDegradesBeforeModelUse() {
        ToolCall call = ToolCall.builder().callId("source-call")
                .toolName("knowledge_search").arguments("{\"query\":\"q\"}").build();
        RagEvidence admitted = RagEvidence.builder().documentId(10L).chunkId(11L)
                .knowledgeBaseId(1L).content("获准片段").build();
        AgentLlm llm = mock(AgentLlm.class);
        when(llm.respond(any(), any())).thenReturn(
                AgentDecision.builder().content("").toolCalls(List.of(call)).build(),
                degradedDecision("模型预算拒绝发送"));
        ToolExecutor executor = mock(ToolExecutor.class);
        when(executor.execute(any(), any())).thenReturn(ToolResult.builder()
                .toolName("knowledge_search").callId("source-call").success(true)
                .outcome("success")
                .data(KnowledgeSearchOutput.builder().outcome("success")
                        .evidences(List.of(admitted)).build()).build());

        AgentResponse response = newRuntime(llm, executor, 5)
                .run(authContext(), List.of(userMsg("q")));

        assertEquals(StopReason.LLM_DEGRADED, response.getStopReason());
        assertTrue(response.getSources().isEmpty(),
                "模型没有确认消费工具结果时，不能公开来源");
    }

    /** 读取同一切片的连续窗口，核对不同偏移生成各自引用，并保留可续读位置。 */
    @Test
    void sequentialWindowsOfSameChunk_haveDistinctCitationsAndCompleteModelVisibleCursors() throws Exception {
        ToolCall first = ToolCall.builder().callId("window-0").toolName("read_document")
                .arguments("{\"documentId\":10,\"query\":\"q\",\"mode\":\"page\"}").build();
        ToolCall next = ToolCall.builder().callId("window-366").toolName("read_document")
                .arguments("{\"documentId\":10,\"query\":\"q\",\"mode\":\"page\",\"offset\":0,\"contentOffset\":366}").build();
        AgentLlm llm = mock(AgentLlm.class);
        when(llm.respond(any(), any())).thenReturn(
                AgentDecision.builder().toolCalls(List.of(first)).build(),
                AgentDecision.builder().toolCalls(List.of(next)).build(),
                AgentDecision.builder().content("两个窗口 [[cite:S10_11_O0]] [[cite:S10_11_O366]]").build());
        ReadDocumentOutput window0 = ReadDocumentOutput.builder().mode("page").documentId(10L)
                .offset(0).contentOffset(0).nextOffset(0).nextContentOffset(366).hasMore(true).scannedChars(366)
                .outcome("success").snippets(List.of(ReadDocumentOutput.Snippet.builder()
                        .documentId(10L).chunkId(11L).knowledgeBaseId(1L).chunkIndex(0).contentOffset(0)
                        .content(String.valueOf((char) 0).repeat(366)).build())).build();
        ReadDocumentOutput window366 = ReadDocumentOutput.builder().mode("page").documentId(10L)
                .offset(0).contentOffset(366).hasMore(false).scannedChars(4).outcome("success")
                .snippets(List.of(ReadDocumentOutput.Snippet.builder().documentId(10L).chunkId(11L)
                        .knowledgeBaseId(1L).chunkIndex(0).contentOffset(366).content("完整尾段").build())).build();
        ToolExecutor executor = mock(ToolExecutor.class);
        when(executor.execute(any(), any())).thenReturn(
                ToolResult.builder().toolName("read_document").callId("window-0").success(true)
                        .outcome("success").data(window0).build(),
                ToolResult.builder().toolName("read_document").callId("window-366").success(true)
                        .outcome("success").data(window366).build());

        AgentResponse response = newRuntime(llm, executor, 5).run(authContext(), List.of(userMsg("q")));

        assertEquals(List.of("S10_11_O0", "S10_11_O366"), response.getSources().stream()
                .map(com.pony.knowledge.dto.SourceDTO::getCitationId).toList());
        assertEquals(List.of(0, 366), response.getSources().stream()
                .map(com.pony.knowledge.dto.SourceDTO::getContentOffset).toList());
        ArgumentCaptor<List<AgentMessage>> inputs = ArgumentCaptor.forClass(List.class);
        verify(llm, times(3)).respond(inputs.capture(), any());
        String json = new ObjectMapper().writeValueAsString(window0);
        assertTrue(inputs.getAllValues().get(1).stream().anyMatch(message ->
                "tool".equals(message.getRole()) && message.getContent().contains(json)
                        && message.getContent().contains("[[cite:S10_11_O0]]")));
    }

    /** 通过 {@code llm.respond}、{@code executor.execute} 驱动用例，布尔状态符合断言，返回的数据、边界或顺序符合断言。 */
    @Test
    void knowledgeNoEvidence_isExplicitInAgentResponse() {
        ToolCall call = ToolCall.builder().callId("empty-call")
                .toolName("knowledge_search").arguments("{\"query\":\"q\"}").build();
        AgentLlm llm = mock(AgentLlm.class);
        when(llm.respond(any(), any())).thenReturn(
                AgentDecision.builder().content("").toolCalls(List.of(call)).build(),
                AgentDecision.builder().content("没有找到资料").toolCalls(List.of()).build());
        ToolExecutor executor = mock(ToolExecutor.class);
        when(executor.execute(any(), any())).thenReturn(ToolResult.builder()
                .toolName("knowledge_search").callId("empty-call").success(false)
                .outcome("no_evidence").reasonCode("below_threshold")
                .data(KnowledgeSearchOutput.builder().outcome("no_evidence")
                        .evidences(List.of()).build()).build());

        AgentResponse response = newRuntime(llm, executor, 5)
                .run(authContext(), List.of(userMsg("q")));

        assertEquals("no_evidence", response.getOutcome());
        assertEquals("below_threshold", response.getReasonCode());
        assertTrue(response.getSources().isEmpty());
    }

    /** 模型直接作答且尚无来源时，核对运行时补一次有额度的检索，再交付有证据的回答。 */
    @Test
    void groundedDirectAnswer_forcesOneBoundedSearch_thenUsesCompleteEvidence() throws Exception {
        AgentExecutionContext context = AgentExecutionContext.builder().userId(1L)
                .accessibleKnowledgeBaseIds(Set.of(1L)).allowedToolNames(Set.of("knowledge_search"))
                .currentQuery("原始问题".repeat(100)).build();
        AgentLlm llm = mock(AgentLlm.class);
        when(llm.respond(any(), any())).thenReturn(
                AgentDecision.builder().content("未经检索的结论").build(),
                AgentDecision.builder().content("根据材料 [[cite:S10_11]] 回答").build());
        ToolExecutor executor = mock(ToolExecutor.class);
        when(executor.execute(any(), any())).thenReturn(ToolResult.builder().success(true).outcome("success")
                .data(KnowledgeSearchOutput.builder().outcome("success").evidences(List.of(
                        RagEvidence.builder().documentId(10L).chunkId(11L).knowledgeBaseId(1L)
                                .content("获准原文").build())).build()).build());
        AgentRuntime runtime = new DefaultAgentRuntime(llm, executor,
                new ToolRegistry(List.of(new NamedTool("knowledge_search"))), Runnable::run, 5, 10, 20_000L);

        AgentResponse response = runtime.run(context, List.of(userMsg(context.getCurrentQuery())));

        assertEquals("根据材料 [[cite:S10_11]] 回答", response.getAnswer());
        assertEquals(2, response.getIterations());
        assertEquals(1, response.getToolCalls());
        assertEquals("S10_11", response.getSources().get(0).getCitationId());
        ArgumentCaptor<ToolCall> call = ArgumentCaptor.forClass(ToolCall.class);
        verify(executor).execute(call.capture(), any());
        assertEquals("knowledge_search", call.getValue().getToolName());
        assertEquals(200, new ObjectMapper().readTree(call.getValue().getArguments()).get("query").asText().length());
        assertEquals(400, context.getCurrentQuery().length(), "当前问题不可被检索参数截断改写");
    }

    /** 构造强制检索仍未取得证据的回合，确认终止且保留真实依赖状态。 */
    @Test
    void groundedNoEvidence_stopsAfterOneForcedSearch_andKeepsDependencyOutcomes() {
        for (String outcome : List.of("no_evidence", "dependency_unavailable", "timeout")) {
            AgentLlm llm = mock(AgentLlm.class);
            when(llm.respond(any(), any())).thenReturn(AgentDecision.builder().content("凭常识给结论").build());
            ToolExecutor executor = mock(ToolExecutor.class);
            when(executor.execute(any(), any())).thenReturn(ToolResult.builder().success(false)
                    .outcome(outcome).reasonCode("test_reason").build());
            AgentRuntime runtime = new DefaultAgentRuntime(llm, executor,
                    new ToolRegistry(List.of(new NamedTool("knowledge_search"))), Runnable::run, 5, 10, 20_000L);

            AgentResponse response = runtime.run(AgentExecutionContext.builder().userId(1L)
                    .accessibleKnowledgeBaseIds(Set.of(1L)).currentQuery("q").build(), List.of(userMsg("q")));

            assertEquals(outcome, response.getOutcome());
            assertEquals("test_reason", response.getReasonCode());
            assertFalse(response.getAnswer().contains("凭常识"));
            assertTrue(response.getSources().isEmpty());
            verify(executor, times(1)).execute(any(), any());
            verify(llm, times(2)).respond(any(), any());
        }
    }

    /** 通过 {@code llm.respond}、{@code runtime.run} 驱动用例，跳过受保护的调用，实际结果与固定预期一致。 */
    @Test
    void groundedForcedSearch_respectsWhitelistAndRemainingRoundAndToolLimits() {
        for (int scenario = 0; scenario < 3; scenario++) {
            AgentLlm llm = mock(AgentLlm.class);
            when(llm.respond(any(), any())).thenReturn(AgentDecision.builder().content("不应交付").build());
            ToolExecutor executor = mock(ToolExecutor.class);
            AgentRuntime runtime = new DefaultAgentRuntime(llm, executor,
                    new ToolRegistry(List.of(new NamedTool("knowledge_search"))), Runnable::run,
                    scenario == 1 ? 1 : 5, scenario == 2 ? 0 : 10, 20_000L);
            AgentExecutionContext context = AgentExecutionContext.builder().userId(1L)
                    .allowedToolNames(scenario == 0 ? Set.of() : Set.of("knowledge_search"))
                    .accessibleKnowledgeBaseIds(Set.of(1L)).currentQuery("q").build();

            AgentResponse response = runtime.run(context, List.of(userMsg("q")));

            assertEquals("no_evidence", response.getOutcome());
            assertEquals(DefaultAgentRuntime.NO_EVIDENCE_ANSWER, response.getAnswer());
            verify(executor, never()).execute(any(), any());
        }
    }

    /** 通过 {@code llm.respondStream}、{@code runtime.run} 驱动用例，布尔状态符合断言，返回的数据、边界或顺序符合断言。 */
    @Test
    void groundedStreaming_neverEmitsUnverifiedDeltas_andKeepsDegradedOrTruncatedStop() {
        for (boolean degraded : List.of(false, true)) {
            AgentLlm llm = mock(AgentLlm.class);
            when(llm.respondStream(any(), any(), any())).thenAnswer(invocation -> {
                java.util.function.Consumer<String> delta = invocation.getArgument(2);
                delta.accept("未经证据验证的正文");
                return AgentDecision.builder().content("未经证据验证的正文")
                        .finishReason(degraded ? "stop" : "length").degraded(degraded)
                        .degradeReason(degraded ? "依赖不可用" : null).build();
            });
            DefaultAgentRuntime runtime = new DefaultAgentRuntime(llm, mock(ToolExecutor.class),
                    new ToolRegistry(List.of()), Runnable::run, 5, 10, 20_000L);
            List<AgentEvent> events = new ArrayList<>();

            AgentResponse response = runtime.run(AgentExecutionContext.builder().userId(1L).build(),
                    List.of(userMsg("q")), events::add);

            assertEquals(degraded ? StopReason.LLM_DEGRADED : StopReason.FINAL_ANSWER_TRUNCATED,
                    response.getStopReason());
            assertTrue(response.getSources().isEmpty());
            assertTrue(events.stream().noneMatch(event -> AgentEvent.DELTA.equals(event.getType())));
            assertFalse("no_evidence".equals(response.getOutcome()), "原失败终态不能包装为无证据成功");
        }
    }

    /** 记录并发的工具：进入时递增、离开时递减，记录峰值并发。 */
    static class RecordingTool implements AgentTool {

        private final AtomicInteger inFlight;
        private final AtomicInteger maxConcurrent;
        private final long sleepMs;

        /**
         * 用注入依赖和构造参数建立此实例执行时使用的状态。
         *
         * @param inFlight 本次调用传入的 {@code AtomicInteger}
         * @param maxConcurrent 本次调用传入的 {@code AtomicInteger}
         * @param sleepMs 本次调用传入的 {@code long}
         */
        RecordingTool(AtomicInteger inFlight, AtomicInteger maxConcurrent, long sleepMs) {
            this.inFlight = inFlight;
            this.maxConcurrent = maxConcurrent;
            this.sleepMs = sleepMs;
        }

        /** 声明此工具对模型公开的属性，包括用途与输入类型。 */
        @Override
        public ToolDefinition definition() {
            return ToolDefinition.builder().name("t")
                    .inputClass(EmptyInput.class)
                    .parameters(Map.of("type", "object", "properties", Map.of()))
                    .build();
        }

        /**
         * 执行此工具所定义的业务操作，使用上下文中的身份与访问范围。
         * 下游调用 {@code inFlight.incrementAndGet}、{@code maxConcurrent.accumulateAndGet}、{@code Thread.sleep}。
         *
         * @param input 本次操作的结构化输入
         * @param context 可信执行上下文，包含身份、范围和本轮执行状态
         * @return 带载荷或可识别错误状态的工具结果
         */
        @Override
        public ToolResult execute(ToolInput input, AgentExecutionContext context) {
            int cur = inFlight.incrementAndGet();
            maxConcurrent.accumulateAndGet(cur, Math::max);
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            inFlight.decrementAndGet();
            return ToolResult.builder().success(true).build();
        }
    }

    static class EmptyInput implements ToolInput {
    }

    /** 只暴露名字的工具，用于验证白名单过滤。 */
    record NamedTool(String name) implements AgentTool {

        /** 声明此工具对模型公开的属性，包括用途与输入类型。 */
        @Override
        public ToolDefinition definition() {
            return ToolDefinition.builder().name(name)
                    .inputClass(EmptyInput.class)
                    .parameters(Map.of("type", "object", "properties", Map.of()))
                    .build();
        }

        /**
         * 执行此工具所定义的业务操作，使用上下文中的身份与访问范围。
         * @param input 本次操作的结构化输入
         * @param context 可信执行上下文，包含身份、范围和本轮执行状态
         * @return 带载荷或可识别错误状态的工具结果
         */
        @Override
        public ToolResult execute(ToolInput input, AgentExecutionContext context) {
            throw new UnsupportedOperationException("仅用于 schema 过滤测试");
        }
    }
}
