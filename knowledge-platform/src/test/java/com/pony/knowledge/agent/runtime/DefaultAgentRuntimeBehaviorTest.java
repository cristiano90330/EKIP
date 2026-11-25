/* 验证模型工具调用、正文输出与权限失败的运行时边界。 */

package com.pony.knowledge.agent.runtime;

import com.pony.knowledge.agent.llm.AgentLlm;
import com.pony.knowledge.agent.model.AgentDecision;
import com.pony.knowledge.agent.model.AgentExecutionContext;
import com.pony.knowledge.agent.model.AgentMessage;
import com.pony.knowledge.agent.model.AgentResponse;
import com.pony.knowledge.agent.model.StopReason;
import com.pony.knowledge.agent.model.ToolCall;
import com.pony.knowledge.agent.tool.AgentTool;
import com.pony.knowledge.agent.tool.ToolDefinition;
import com.pony.knowledge.agent.tool.ToolExecutor;
import com.pony.knowledge.agent.tool.ToolRegistry;
import com.pony.knowledge.agent.tool.input.ToolInput;
import com.pony.knowledge.agent.tool.output.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent Runtime 循环的确定性行为门禁（4.2 收口：Agent 代表性验证用例，非打分层）。
 *
 * 与 {@link RuntimeContextBudgetRebuildTest}（测「工具结果进入下一轮上下文」）互补，这里的 4 条
 * 断言验证编排语义：该调/不该调、失败收尾、权限失败不泄漏的呈现。内存连续/裁剪/摘要行为由
 * ContextBudgetManagerTest / AgentMemoryTest / DefaultContextBuilderTest / SummaryCoordinatorTest 覆盖——
 * 本测试只补 Agent Runtime 循环层缺口。权限的「真实不泄漏」由 ToolExecutorTest（越权 knowledgeBaseId
 * 被忽略 → scope 只取 context）验证；这里断言 runtime 把权限失败呈现为无内容、可收尾的结果。
 */
class DefaultAgentRuntimeBehaviorTest {

    /**
     * 协调 {@code Runnable::run} 完成当前阶段，供后续处理使用。
     * 下游调用 {@code Runnable::run}。
     *
     * @param llm 本次调用传入的 {@code AgentLlm}
     * @param executor 本次调用传入的 {@code ToolExecutor}
     * @param registry 本次调用传入的 {@code ToolRegistry}
     * @return 调用方使用的 {@code AgentRuntime}
     */
    private AgentRuntime newRuntime(AgentLlm llm, ToolExecutor executor, ToolRegistry registry) {
        return new DefaultAgentRuntime(llm, executor, registry, Runnable::run, 5, 10, 60000L);
    }

    /**
     * 协调 {@code queue.poll} 完成当前阶段，供后续处理使用。
     * @param decisions 本次调用传入的 {@code AgentDecision[]}
     * @return 调用方使用的 {@code AgentLlm}
     */
    private AgentLlm scripted(AgentDecision... decisions) {
        Deque<AgentDecision> queue = new ArrayDeque<>(List.of(decisions));
        return (messages, tools) -> queue.isEmpty()
                ? AgentDecision.builder().content("done").build()
                : queue.poll();
    }

    /** 在该类型内部准备后续运行所需的数据。 */
    private AgentExecutionContext ctx() {
        return AgentExecutionContext.builder()
                .answerPolicy("general")
                .userId(1L).accessibleKnowledgeBaseIds(Set.of(1L)).sessionId("s1")
                .currentQuery("问题").build();
    }

    /** 提供指定角色和内容的模型消息。 */
    private AgentMessage userMsg() {
        return AgentMessage.builder().role("user").content("问题").build();
    }

    /** 通过 {@code executor.execute} 驱动用例，布尔状态符合断言，实际结果与固定预期一致。 */
    @Test
    void callsTool_whenLlmRequestsIt_andRecordsInvocation() {
        ToolCall call = ToolCall.builder().callId("c1").toolName("knowledge_search")
                .arguments("{\"query\":\"报销\"}").build();
        AgentLlm llm = scripted(
                AgentDecision.builder().content("我去查").toolCalls(List.of(call)).build(),
                AgentDecision.builder().content("报销流程见知识库").build());
        ToolExecutor executor = mock(ToolExecutor.class);
        when(executor.execute(any(), any())).thenReturn(
                ToolResult.builder().toolName("knowledge_search").callId("c1")
                        .success(true).data(java.util.Map.of("chunk", "需部门审批")).build());

        AgentResponse resp = newRuntime(llm, executor, new ToolRegistry(List.of())).run(ctx(), List.of(userMsg()));

        assertEquals(StopReason.FINAL_ANSWER, resp.getStopReason());
        assertEquals(1, resp.getToolInvocations().size(), "应有 1 次工具调用记录");
        assertTrue(resp.getToolInvocations().get(0).isSuccess());
        verify(executor, times(1)).execute(any(), any());
    }

    /** 通过 {@code scripted}、{@code newRuntime} 驱动该场景，跳过受保护的调用，布尔状态符合断言。 */
    @Test
    void noToolCall_whenLlmGivesDirectAnswer_neverExecutesTool() {
        AgentLlm llm = scripted(AgentDecision.builder().content("你好，我是助手").build());
        ToolExecutor executor = mock(ToolExecutor.class);

        AgentResponse resp = newRuntime(llm, executor, new ToolRegistry(List.of())).run(ctx(), List.of(userMsg()));

        assertEquals(StopReason.FINAL_ANSWER, resp.getStopReason());
        assertEquals("你好，我是助手", resp.getAnswer());
        assertTrue(resp.getToolInvocations().isEmpty(), "直接回答不应有工具调用");
        verify(executor, never()).execute(any(), any());
    }

    /** 通过 {@code executor.execute} 驱动用例，布尔状态符合断言，实际结果与固定预期一致。 */
    @Test
    void toolFailure_wrapsUpToFinalAnswer_withoutException() {
        ToolCall call = ToolCall.builder().callId("c1").toolName("knowledge_search")
                .arguments("{\"query\":\"x\"}").build();
        AgentLlm llm = scripted(
                AgentDecision.builder().content("去查").toolCalls(List.of(call)).build(),
                AgentDecision.builder().content("抱歉，知识库查询失败，请稍后再试").build());
        ToolExecutor executor = mock(ToolExecutor.class);
        when(executor.execute(any(), any())).thenReturn(
                ToolResult.builder().toolName("knowledge_search").callId("c1")
                        .success(false).errorMessage("网络超时").retryable(true).build());

        AgentResponse resp = newRuntime(llm, executor, new ToolRegistry(List.of())).run(ctx(), List.of(userMsg()));

        assertEquals(StopReason.FINAL_ANSWER, resp.getStopReason());
        assertEquals("抱歉，知识库查询失败，请稍后再试", resp.getAnswer(), "失败后应能收尾给出答案");
        assertFalse(resp.getToolInvocations().get(0).isSuccess());
        assertEquals("网络超时", resp.getToolInvocations().get(0).getErrorMessage());
    }

    /** 通过 {@code executor.execute} 驱动用例，布尔状态符合断言，实际结果与固定预期一致。 */
    @Test
    void permissionFailure_surfacesAsBoundedResult_noLeak() {
        ToolCall call = ToolCall.builder().callId("c1").toolName("knowledge_search")
                .arguments("{\"query\":\"机密\"}").build();
        AgentLlm llm = scripted(
                AgentDecision.builder().content("查资料").toolCalls(List.of(call)).build(),
                AgentDecision.builder().content("你没有权限访问该知识库").build());
        ToolExecutor executor = mock(ToolExecutor.class);
        // 真实 ToolExecutor 会把 ForbiddenException 转成 success=false + "权限不足" + 无 data（见 ToolExecutorTest）
        when(executor.execute(any(), any())).thenReturn(
                ToolResult.builder().toolName("knowledge_search").callId("c1")
                        .success(false).errorMessage("权限不足: 无权访问知识库 2").retryable(false).build());

        AgentResponse resp = newRuntime(llm, executor, new ToolRegistry(List.of())).run(ctx(), List.of(userMsg()));

        AgentResponse.ToolInvocation inv = resp.getToolInvocations().get(0);
        assertFalse(inv.isSuccess());
        assertNull(inv.getDataSummary(), "权限失败不应有内容摘要（不泄漏）");
        assertTrue(inv.getErrorMessage().contains("权限"));
        assertEquals("你没有权限访问该知识库", resp.getAnswer());
    }

    /** 通过 {@code executor.execute} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void nativeToolCallExecutesWithoutTextRecovery() {
        AgentLlm llm = scripted(
                AgentDecision.builder().content("我来查询").toolCalls(List.of(ToolCall.builder()
                        .callId("c1").toolName("knowledge_search").arguments("{\"query\":\"报销流程\"}").build())).build(),
                AgentDecision.builder().content("报销流程见知识库第三章").build());
        ToolExecutor executor = mock(ToolExecutor.class);
        when(executor.execute(any(), any())).thenReturn(ToolResult.builder().success(true).build());
        AgentResponse response = newRuntime(llm, executor, registryWith("knowledge_search"))
                .run(ctx(), List.of(userMsg()));
        verify(executor, times(1)).execute(any(), any());
        assertEquals(1, response.getToolCalls());
        assertEquals("报销流程见知识库第三章", response.getAnswer());
    }

    /** 通过 {@code newRuntime}、{@code scripted} 驱动该场景，跳过受保护的调用，实际结果与固定预期一致。 */
    @Test
    void textualToolIntentNeverExecutesTool() {
        String text = "<tools>{\"name\":\"knowledge_search\",\"arguments\":{}}</tools>";
        ToolExecutor executor = mock(ToolExecutor.class);
        AgentResponse response = newRuntime(scripted(AgentDecision.builder().content(text).build()),
                executor, registryWith("knowledge_search")).run(ctx(), List.of(userMsg()));
        assertEquals(text, response.getAnswer());
        assertEquals(0, response.getToolCalls());
        verify(executor, never()).execute(any(), any());
    }

    /** 通过 {@code newRuntime}、{@code scripted} 驱动该场景，跳过受保护的调用，实际结果与固定预期一致。 */
    @Test
    void jsonAnswerRemainsTextWithoutNativeCalls() {
        String text = "{\"name\":\"example\",\"arguments\":{}}";
        ToolExecutor executor = mock(ToolExecutor.class);
        AgentResponse response = newRuntime(scripted(AgentDecision.builder().content(text).build()),
                executor, registryWith("knowledge_search")).run(ctx(), List.of(userMsg()));
        assertEquals(text, response.getAnswer());
        verify(executor, never()).execute(any(), any());
    }

    /** 通过 {@code llm.respondStream}、{@code runtime.run} 驱动用例，跳过受保护的调用，实际结果与固定预期一致。 */
    @Test
    void streamingJsonTextIsForwardedWithoutToolIntentFiltering() {
        String text="{\"name\":\"example\",\"arguments\":{}}";
        AgentLlm llm=mock(AgentLlm.class);
        when(llm.respondStream(any(),any(),any())).thenAnswer(invocation -> {
            java.util.function.Consumer<String> delta=invocation.getArgument(2);
            delta.accept(text.substring(0,8)); delta.accept(text.substring(8));
            return AgentDecision.builder().content(text).build();
        });
        ToolExecutor executor=mock(ToolExecutor.class);
        List<com.pony.knowledge.agent.stream.AgentEvent> events=new java.util.ArrayList<>();
        DefaultAgentRuntime runtime=(DefaultAgentRuntime)newRuntime(llm,executor,registryWith("knowledge_search"));
        AgentResponse response=runtime.run(ctx(),List.of(userMsg()),events::add);
        assertEquals(text,response.getAnswer());
        assertEquals(text,events.stream().filter(event -> "delta".equals(event.getType()))
                .map(event -> (String)event.getData().get("text")).reduce("",String::concat));
        verify(executor,never()).execute(any(),any());
    }

    /**
     * 在该类型内部准备后续运行所需的数据。
     * @param names 本次调用传入的 {@code String[]}
     * @return 调用方使用的 {@code ToolRegistry}
     */
    private ToolRegistry registryWith(String... names) {
        List<AgentTool> tools = java.util.Arrays.stream(names)
                .map(name -> (AgentTool) new AgentTool() {
                    /** 提供公开给模型或页面目录的工具定义。 */
                    @Override
                    public ToolDefinition definition() {
                        return ToolDefinition.builder()
                                .name(name)
                                .description("stub")
                                .parameters(java.util.Map.of("type", "object"))
                                .build();
                    }

                    /**
                     * 执行当前方法封装的业务操作。
                     * @param input 本次操作的结构化输入
                     * @param context 可信执行上下文，包含身份、范围和本轮执行状态
                     * @return 带载荷或可识别错误状态的工具结果
                     */
                    @Override
                    public ToolResult execute(ToolInput input, AgentExecutionContext context) {
                        return ToolResult.builder().toolName(name).success(true).build();
                    }
                })
                .toList();
        return new ToolRegistry(tools);
    }
}
