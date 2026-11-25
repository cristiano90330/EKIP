/* 验证 Agent 服务的认证范围、会话互斥、历史与审批交付顺序。 */

package com.pony.knowledge.agent.service;

import com.pony.knowledge.agent.context.ContextBudgetManager;
import com.pony.knowledge.agent.context.ContextBuildRequest;
import com.pony.knowledge.agent.context.ContextBuildResult;
import com.pony.knowledge.agent.memory.summary.SummaryCoordinator;
import com.pony.knowledge.agent.model.AgentExecutionContext;
import com.pony.knowledge.agent.model.AgentMessage;
import com.pony.knowledge.agent.model.AgentPrompts;
import com.pony.knowledge.agent.model.AgentRequest;
import com.pony.knowledge.agent.model.AgentResponse;
import com.pony.knowledge.agent.model.StopReason;
import com.pony.knowledge.agent.observability.AgentTracer;
import com.pony.knowledge.agent.profile.AgentProfileService;
import com.pony.knowledge.agent.profile.ResolvedAgentProfile;
import com.pony.knowledge.agent.runtime.AgentRuntime;
import com.pony.knowledge.agent.runtime.DefaultAgentRuntime;
import com.pony.knowledge.agent.stream.AgentEvent;
import com.pony.knowledge.agent.stream.AgentEventSink;
import com.pony.knowledge.agent.session.AgentPolicyFingerprint;
import com.pony.knowledge.agent.session.AgentSession;
import com.pony.knowledge.agent.session.AgentSessionService;
import com.pony.knowledge.agent.session.InMemoryAgentSessionStore;
import com.pony.knowledge.agent.tool.approval.MemorySaveApproval;
import com.pony.knowledge.agent.tool.approval.MemorySaveApprovalService;
import com.pony.knowledge.modelmanagement.ModelRegistryService;
import com.pony.knowledge.exception.ForbiddenException;
import com.pony.knowledge.service.KnowledgeBaseAccessService;
import com.pony.knowledge.utils.CurrentUserUtil;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 3.7d+3.8e 验收：AgentService 从 Authentication（JWT）+ PermissionService（KnowledgeBaseAccessService.resolveReadableIds）
 * 解析 accessibleKnowledgeBaseIds 构建系统身份，再交给 AgentRuntime；并回填 traceId / systemPrompt / currentQuery。
 */
class AgentServiceTest {

    /** 创建长期记忆申请，核对完成回合保存申请标识，追踪只保留必要诊断。 */
    @Test
    void memoryProposalPersistsAnApprovalReferenceAndOmitsContentFromTrace() {
        Harness h = new Harness(1L);
        String content = "评审记录统一使用中文";
        AgentRequest request = h.request("记住：" + content, null);
        var proposal = new MemorySaveApproval.Proposal(content, "PREFERENCE");
        var approval = new MemorySaveApproval(java.util.UUID.randomUUID().toString(), content, proposal.kind(),
                MemorySaveApproval.State.PENDING, System.currentTimeMillis() + 60_000);
        when(h.runtime.run(any(), any())).thenReturn(AgentResponse.builder()
                .stopReason(StopReason.MEMORY_APPROVAL_REQUIRED).memorySaveProposal(proposal).build());
        when(h.approvals.create(any(), eq(proposal))).thenReturn(approval);

        assertEquals(approval, h.service.chat(request).getMemoryApproval());
        var turns = h.sessions.history(1L, request.getSessionId(), 0, 10).items();
        assertEquals(1, turns.size());
        assertEquals(approval.id(), turns.get(0).id());
        assertEquals(StopReason.MEMORY_APPROVAL_REQUIRED, turns.get(0).stopReason());
        verify(h.agentTracer).observeRoot(eq("agent.chat"), eq("长期记忆保存请求"), any());
    }

    /** 通过 {@code h.contextBudgetManager.build}、{@code h.service.chat} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void evidenceReviewReceivesPriorConversationWithoutSystemOrCurrentQuestion() {
        Harness h = new Harness(1L);
        String question = "刚才的项目材料应在几天内补齐？";
        when(h.contextBudgetManager.build(any())).thenReturn(ContextBuildResult.builder().messages(List.of(
                AgentMessage.builder().role("system").content("system policy").build(),
                AgentMessage.builder().role("user").content("我想了解星河项目").build(),
                AgentMessage.builder().role("assistant").content("请问具体问题").build(),
                AgentMessage.builder().role("user").content(question).build())).build());
        h.service.chat(h.request(question, null));
        ArgumentCaptor<AgentExecutionContext> context = ArgumentCaptor.forClass(AgentExecutionContext.class);
        verify(h.runtime).run(context.capture(), any());
        assertEquals(List.of("user", "assistant"), context.getValue().getQuestionHistory().stream()
                .map(com.pony.knowledge.service.rag.query.ChatMessage::getRole).toList());
        assertEquals("我想了解星河项目", context.getValue().getQuestionHistory().get(0).getContent());
    }

    /** 制造模型解析失败，检查服务未抢占产品会话的回合槽。 */
    @Test
    void modelResolutionFailureDoesNotClaimAnActiveProductSession() {
        Harness h = new Harness(1L);
        AgentSessionService sessions = mock(AgentSessionService.class);
        ModelRegistryService models = mock(ModelRegistryService.class);
        when(models.agentIdentity(1L, null)).thenReturn("configured-model");
        when(models.agentExecution(1L, null)).thenThrow(
                com.pony.knowledge.modelmanagement.ModelManagementException.unavailable("credential unavailable"));
        AgentService service = new AgentService(h.currentUserUtil, h.accessService, h.agentProfileService,
                h.runtime, h.summaryCoordinator, h.contextBudgetManager, h.agentTracer, sessions, models, h.approvals);

        assertThrows(com.pony.knowledge.modelmanagement.ModelManagementException.class, () -> service.chat(
                AgentRequest.builder().question("q").sessionId("as_test").build()));

        verifyNoInteractions(sessions, h.runtime, h.summaryCoordinator);
    }

    /** 模拟完成时的存储异常，确认未发送成功终态且会话回合槽释放。 */
    @Test
    void managedSessionStorageFailure_doesNotPublishCompletion_andReleasesTurn() {
        Harness h = new Harness(1L);
        DefaultAgentRuntime runtime = mock(DefaultAgentRuntime.class);
        AgentSessionService sessions = mock(AgentSessionService.class);
        AgentSession session = new AgentSession("as_test", 1L, null, "policy", "grounded",
                "新会话", 1, 1, Long.MAX_VALUE);
        AgentSessionService.TurnHandle turn = new AgentSessionService.TurnHandle(session, "token");
        stubExecutionTurn(sessions, turn);
        com.pony.knowledge.agent.stream.SseAgentEventSink sink = mock(com.pony.knowledge.agent.stream.SseAgentEventSink.class);
        when(sink.isActive()).thenReturn(true);
        when(runtime.run(any(), any(), any())).thenAnswer(invocation -> {
            AgentEventSink output = invocation.getArgument(2);
            output.emit(AgentEvent.of(AgentEvent.ROUND, java.util.Map.of("round", 1, "phase", "think")));
            output.emit(AgentEvent.of(AgentEvent.ANSWER, java.util.Map.of("text", "答案")));
            output.emit(AgentEvent.of(AgentEvent.DONE, java.util.Map.of("stopReason", "FINAL_ANSWER")));
            return AgentResponse.builder().answer("答案").stopReason(StopReason.FINAL_ANSWER).build();
        });
        org.mockito.Mockito.doThrow(new IllegalStateException("storage unavailable"))
                .when(sessions).complete(eq(turn), any(), any(), any());
        AgentService service = h.serviceWith(runtime, sessions);

        assertThrows(IllegalStateException.class, () -> service.chatStream(
                AgentRequest.builder().question("q").sessionId(session.id()).build(), sink));

        verify(sink).emit(org.mockito.ArgumentMatchers.argThat(event -> AgentEvent.ROUND.equals(event.getType())));
        verify(sink, never()).emit(org.mockito.ArgumentMatchers.argThat(event ->
                AgentEvent.ANSWER.equals(event.getType()) || AgentEvent.DONE.equals(event.getType())));
        verify(sessions).end(turn);
        verifyNoInteractions(h.summaryCoordinator);
    }

    /** 保存回合后让摘要调度失败，核对成功终态仍正常交付，回合槽仍释放。 */
    @Test
    void summarySchedulingFailure_stillPublishesSavedCompletion_andReleasesTurn() {
        Harness h = new Harness(1L);
        DefaultAgentRuntime runtime = mock(DefaultAgentRuntime.class);
        AgentSessionService sessions = mock(AgentSessionService.class);
        AgentSession session = new AgentSession("as_summary", 1L, null, "policy", "grounded",
                "新会话", 1, 1, Long.MAX_VALUE);
        AgentSessionService.TurnHandle turn = new AgentSessionService.TurnHandle(session, "token");
        stubExecutionTurn(sessions, turn);
        com.pony.knowledge.agent.stream.SseAgentEventSink sink = mock(com.pony.knowledge.agent.stream.SseAgentEventSink.class);
        when(sink.isActive()).thenReturn(true);
        when(runtime.run(any(), any(), any())).thenAnswer(invocation -> {
            AgentEventSink output = invocation.getArgument(2);
            output.emit(AgentEvent.of(AgentEvent.ANSWER, java.util.Map.of("text", "已保存")));
            output.emit(AgentEvent.of(AgentEvent.DONE, java.util.Map.of("stopReason", "FINAL_ANSWER")));
            return AgentResponse.builder().answer("已保存").stopReason(StopReason.FINAL_ANSWER).build();
        });
        org.mockito.Mockito.doThrow(new java.util.concurrent.RejectedExecutionException("summary queue full"))
                .when(h.summaryCoordinator).maybeSummarize(session.memoryId(), 1L, null);
        AgentService service = h.serviceWith(runtime, sessions);
        assertEquals("已保存", service.chatStream(AgentRequest.builder().question("q").sessionId(session.id()).build(), sink).getAnswer());
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(sessions, sink);
        order.verify(sessions).complete(eq(turn), eq("q"), any(), any());
        order.verify(sink).emit(org.mockito.ArgumentMatchers.argThat(event -> AgentEvent.ANSWER.equals(event.getType())));
        order.verify(sink).emit(org.mockito.ArgumentMatchers.argThat(event -> AgentEvent.DONE.equals(event.getType())));
        order.verify(sessions).end(turn);
    }

    /** 通过 {@code h.service.chat} 驱动用例，布尔状态符合断言。 */
    @Test
    void memoryPolicyIsPassedToInitialContextAndRuntime() {
        Harness h = new Harness(1L);
        when(h.agentProfileService.resolveForExecution(1L, 9L, Set.of(1L)))
                .thenReturn(new ResolvedAgentProfile(9L, "p", Set.of(1L), Set.of("memory_save", "memory_search"), "general", false, false));
        h.service.chat(h.request("q", 9L));
        ArgumentCaptor<AgentExecutionContext> context = ArgumentCaptor.forClass(AgentExecutionContext.class);
        verify(h.runtime).run(context.capture(), any());
        assertFalse(context.getValue().isMemoryReadEnabled());
        assertFalse(context.getValue().isMemoryWriteEnabled());
        ArgumentCaptor<ContextBuildRequest> request = ArgumentCaptor.forClass(ContextBuildRequest.class);
        verify(h.contextBudgetManager).build(request.capture());
        assertFalse(request.getValue().isMemoryReadEnabled());
    }

    /** 通过 {@code h.approvals.create}、{@code h.service.chat} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void enabledPreferenceExtractionSuggestsApprovalAlongsideTheAnswer() {
        Harness h = new Harness(1L);
        when(h.agentProfileService.resolveForExecution(1L, 9L, Set.of(1L)))
                .thenReturn(new ResolvedAgentProfile(9L, "p", Set.of(1L), Set.of(), "general", true, true, null, true));
        var proposal = new com.pony.knowledge.agent.tool.approval.MemorySaveApproval.Proposal("以后请用中文回答", "PREFERENCE");
        var approval = new com.pony.knowledge.agent.tool.approval.MemorySaveApproval("test", proposal.content(),
                proposal.kind(), com.pony.knowledge.agent.tool.approval.MemorySaveApproval.State.PENDING, 0L);
        when(h.approvals.create(any(), eq(proposal))).thenReturn(approval);

        var response = h.service.chat(h.request("以后请用中文回答。文档怎么上传？", 9L));
        assertEquals("答案", response.getAnswer());
        assertEquals(StopReason.FINAL_ANSWER, response.getStopReason());
        assertEquals(approval, response.getMemoryApproval());
        verify(h.approvals).create(any(), eq(proposal));
    }

    /** 通过 {@code h.service.chat}、{@code h.store.get} 驱动用例，布尔状态符合断言，实际结果与固定预期一致。 */
    @Test
    void chat_resolvesPermissionsFromAuth_andDelegatesToRuntime() {
        Harness h = new Harness(1L);
        AgentRequest request = h.request("你好", null);
        assertEquals("答案", h.service.chat(request).getAnswer());

        ArgumentCaptor<AgentExecutionContext> context = ArgumentCaptor.forClass(AgentExecutionContext.class);
        verify(h.runtime).run(context.capture(), any());
        assertEquals(1L, context.getValue().getUserId());
        assertEquals(Set.of(1L), context.getValue().getAccessibleKnowledgeBaseIds());
        assertEquals("trace-1", context.getValue().getTraceId());
        assertEquals(request.getQuestion(), context.getValue().getCurrentQuery());
        assertTrue(context.getValue().getSystemPrompt().startsWith(AgentPrompts.GENERAL_ASSISTANT_SYSTEM));
        assertEquals("general", context.getValue().getAnswerPolicy());

        String memoryId = h.store.get(request.getSessionId()).orElseThrow().memoryId();
        assertEquals(memoryId, context.getValue().getSessionId());
        ArgumentCaptor<ContextBuildRequest> build = ArgumentCaptor.forClass(ContextBuildRequest.class);
        verify(h.contextBudgetManager).build(build.capture());
        assertEquals(memoryId, build.getValue().getSessionId());
        verify(h.summaryCoordinator).maybeSummarize(memoryId, 1L, null);
    }

    /** 通过 {@code h.runtime.run}、{@code h.service.chat} 驱动用例，布尔状态符合断言，实际结果与固定预期一致。 */
    @Test
    void chat_nonSuccessfulStop_appendsAssistantPlaceholder_soHistoryStaysPaired() {
        Harness h = new Harness(1L);
        when(h.runtime.run(any(), any())).thenReturn(AgentResponse.builder()
                .stopReason(StopReason.MAX_ITERATIONS).iterations(5).build());

        AgentRequest request = h.request("你好", null);
        h.service.chat(request);

        List<AgentMessage> appended = h.store.messages(request.getSessionId(), 0, 2);
        assertEquals("user", appended.get(0).getRole());
        assertEquals("你好", appended.get(0).getContent());
        assertEquals("assistant", appended.get(1).getRole(), "失败也要落 assistant → 历史成对");
        assertNotNull(appended.get(1).getContent());
        String placeholder = appended.get(1).getContent();
        assertTrue(placeholder.contains("未能") && placeholder.contains("结论"),
                "占位文案要让模型看出这轮没答成，实际=" + placeholder);
    }

    /** 通过 {@code h.runtime.run}、{@code h.service.chat} 驱动用例，布尔状态符合断言，实际结果与固定预期一致。 */
    @Test
    void chat_persistsToolStepsOnAssistantMessage() {
        Harness h = new Harness(1L);
        when(h.runtime.run(any(), any())).thenReturn(AgentResponse.builder()
                .answer("住宿上限 500 元")
                .stopReason(StopReason.FINAL_ANSWER)
                .toolInvocations(List.of(
                        AgentResponse.ToolInvocation.builder().toolName("knowledge_search")
                                .success(true).dataSummary("命中 5 条差旅制度").build(),
                        AgentResponse.ToolInvocation.builder().toolName("knowledge_ask")
                                .success(false).errorMessage("工具超时（60s）").build()))
                .build());

        AgentRequest request = h.request("差旅标准", null);
        h.service.chat(request);

        List<AgentMessage> appended = h.store.messages(request.getSessionId(), 0, 2);
        assertNull(appended.get(0).getSteps(), "user 消息不挂步骤");

        List<com.pony.knowledge.agent.model.ToolStep> steps = appended.get(1).getSteps();
        assertNotNull(steps, "assistant 消息要带本轮工具步骤");
        assertEquals(2, steps.size());
        assertEquals("knowledge_search", steps.get(0).getTool());
        assertTrue(steps.get(0).isOk());
        assertEquals("命中 5 条差旅制度", steps.get(0).getSummary());
        assertFalse(steps.get(1).isOk(), "失败步骤同样入史：否则下一轮会把「工具报错」读成「没有」");
        assertTrue(steps.get(1).getSummary().contains("超时"));
    }

    /** 通过 {@code h.runtime.run}、{@code h.service.chat} 驱动用例，布尔状态符合断言，实际结果与固定预期一致。 */
    @Test
    void chat_degradedTurn_appendsTheSameFallbackTextUserSees() {
        Harness h = new Harness(1L);
        String fallback = "AI 服务暂时不可用，本轮未能检索知识库，请稍后重试。";
        when(h.runtime.run(any(), any())).thenReturn(AgentResponse.builder()
                .answer(fallback).stopReason(StopReason.LLM_DEGRADED).iterations(1)
                .degraded(true).degradeReason("circuit open（主 provider 熔断，降级也失败）")
                .providerUsed("ollama").build());

        AgentRequest request = h.request("你好", null);
        AgentResponse resp = h.service.chat(request);
        assertTrue(resp.isDegraded());
        assertEquals("circuit open（主 provider 熔断，降级也失败）", resp.getDegradeReason());

        List<AgentMessage> appended = h.store.messages(request.getSessionId(), 0, 2);
        assertEquals("assistant", appended.get(1).getRole());
        assertEquals(fallback, appended.get(1).getContent(),
                "落进历史的兜底文案必须与用户看到的逐字相同");
        assertTrue(appended.get(1).getContent().contains("未能检索"),
                "下一轮的模型要能看出这轮没查库");
    }

    /** 通过 {@code h.service.chat} 驱动用例，抛出 {@code ForbiddenException}，跳过受保护的调用。 */
    @Test
    void rejectsLegacyIdBeforeContextBuildOrRuntime() {
        Harness h = new Harness(1L);
        assertThrows(ForbiddenException.class, () -> h.service.chat(
                AgentRequest.builder().question("q").sessionId("legacy-client-id").build()));
        verifyNoInteractions(h.runtime, h.summaryCoordinator, h.contextBudgetManager, h.agentTracer);
    }

    /** 改变会话可读范围后发起续聊，检查拒绝发生在历史读取和模型运行之前。 */
    @Test
    void permissionChangeRejectsContinuationBeforeReadingHistory() {
        Harness h = new Harness(1L);
        AgentRequest request = h.request("q", null);
        when(h.accessService.resolveReadableIds(1L)).thenReturn(Set.of());
        assertThrows(ForbiddenException.class, () -> h.service.chat(request));
        verifyNoInteractions(h.runtime, h.summaryCoordinator, h.contextBudgetManager);
    }

    /** 通过 {@code h.service.chat} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void chat_emptyPermissionSetStaysEmptyInTrustedContext() {
        Harness h = new Harness(1L);
        when(h.accessService.resolveReadableIds(1L)).thenReturn(Set.of());

        h.service.chat(h.request("q", null));

        ArgumentCaptor<AgentExecutionContext> context = ArgumentCaptor.forClass(AgentExecutionContext.class);
        verify(h.runtime).run(context.capture(), any());
        assertEquals(1L, context.getValue().getUserId());
        assertEquals(Set.of(), context.getValue().getAccessibleKnowledgeBaseIds());
    }

    /** 通过 {@code h.service.chat} 驱动用例，抛出 {@code IllegalStateException}，跳过受保护的调用。 */
    @Test
    void chat_permissionLookupFailureStopsBeforeRuntimeOrMemory() {
        Harness h = new Harness(1L);
        when(h.accessService.resolveReadableIds(1L)).thenThrow(new IllegalStateException("permission unavailable"));

        assertThrows(IllegalStateException.class,
                () -> h.service.chat(AgentRequest.builder().question("q").sessionId("s1").build()));

        verifyNoInteractions(h.runtime, h.summaryCoordinator, h.contextBudgetManager, h.agentTracer);
    }

    // ==================== Agent 配置选择（B 工作包）====================

    /** 通过 {@code h.service.chat} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void chat_withAgentId_usesResolvedScopePromptAndToolWhitelist() {
        Harness h = new Harness(1L);
        when(h.agentProfileService.resolveForExecution(1L, 9L, Set.of(1L)))
                .thenReturn(new ResolvedAgentProfile(9L, "自定义提示词",
                        Set.of(2L), Set.of("knowledge_search")));

        h.service.chat(h.request("q", 9L));

        ArgumentCaptor<AgentExecutionContext> ctx = ArgumentCaptor.forClass(AgentExecutionContext.class);
        verify(h.runtime).run(ctx.capture(), any());
        assertEquals(Set.of(2L), ctx.getValue().getAccessibleKnowledgeBaseIds());
        assertEquals(Set.of("knowledge_search"), ctx.getValue().getAllowedToolNames());
        assertEquals(groundedPrompt("自定义提示词"), ctx.getValue().getSystemPrompt());

        ArgumentCaptor<ContextBuildRequest> buildReq = ArgumentCaptor.forClass(ContextBuildRequest.class);
        verify(h.contextBudgetManager).build(buildReq.capture());
        assertEquals(groundedPrompt("自定义提示词"), buildReq.getValue().getSystemPrompt(),
                "上下文装配必须使用配置解析后的提示词");
    }

    /** 通过 {@code h.service.chat} 驱动用例，跳过受保护的调用，布尔状态符合断言。 */
    @Test
    void chat_withoutAgentId_allowsGeneralAnswersWithoutKnowledgeBases() {
        Harness h = new Harness(1L);
        when(h.accessService.resolveReadableIds(1L)).thenReturn(Set.of());

        h.service.chat(h.request("你好", null));

        ArgumentCaptor<AgentExecutionContext> ctx = ArgumentCaptor.forClass(AgentExecutionContext.class);
        verify(h.runtime).run(ctx.capture(), any());
        assertTrue(ctx.getValue().getAccessibleKnowledgeBaseIds().isEmpty());
        assertNull(ctx.getValue().getAllowedToolNames(), "默认 Agent 不应被白名单限制");
        assertEquals("general", ctx.getValue().getAnswerPolicy());
        assertFalse(ctx.getValue().getSystemPrompt().contains(AgentPrompts.GROUNDED_INSTRUCTION));
        verifyNoInteractions(h.agentProfileService);
    }

    /** 通过 {@code h.store.create}、{@code h.service.chat} 驱动用例，抛出 {@code ForbiddenException}，实际结果与固定预期一致。 */
    @Test
    void legacyDefaultSessionRemainsReadableAndContinuesWithGroundedPolicy() {
        Harness h = new Harness(1L);
        var previous = AgentPolicyFingerprint.resolve(1L, null, Set.of(1L), true, "grounded");
        long now = System.currentTimeMillis();
        AgentSession session = new AgentSession("as_00000000-0000-0000-0000-000000000001", 1L, null,
                previous.fingerprint(), "grounded", "旧会话", now, now, now + 60_000);
        h.store.create(session);

        h.service.chat(AgentRequest.builder().question("请查知识库").sessionId(session.id()).build());
        ArgumentCaptor<AgentExecutionContext> context = ArgumentCaptor.forClass(AgentExecutionContext.class);
        verify(h.runtime).run(context.capture(), any());
        assertEquals("grounded", context.getValue().getAnswerPolicy());
        assertEquals(groundedPrompt(AgentPrompts.KNOWLEDGE_ASSISTANT_SYSTEM), context.getValue().getSystemPrompt());
        assertEquals("grounded", h.sessions.history(1L, session.id(), 0, 20).session().answerPolicy());
        when(h.accessService.resolveReadableIds(1L)).thenReturn(Set.of());
        assertThrows(ForbiddenException.class, () -> h.sessions.history(1L, session.id(), 0, 20));
    }

    /** 通过 {@code h.service.chat} 驱动用例，抛出 {@code ForbiddenException}，跳过受保护的调用。 */
    @Test
    void chat_agentProfileResolutionFailure_stopsBeforeRuntimeOrMemory() {
        Harness h = new Harness(1L);
        when(h.agentProfileService.resolveForExecution(1L, 9L, Set.of(1L)))
                .thenThrow(new ForbiddenException("Agent 配置不存在或无权访问"));

        assertThrows(ForbiddenException.class,
                () -> h.service.chat(AgentRequest.builder().question("q").agentId(9L).build()));

        verifyNoInteractions(h.runtime, h.summaryCoordinator,
                h.contextBudgetManager, h.agentTracer);
    }

    /**
     * 协调 {@code AgentPolicyFingerprint.resolve} 完成当前阶段，供后续处理使用。
     * @param prompt 本次调用传入的 {@code String}
     * @return 供后续处理使用的文本或标识
     */
    private static String groundedPrompt(String prompt) {
        return AgentPolicyFingerprint.resolve(1L,
                new ResolvedAgentProfile(9L, prompt, Set.of(), Set.of(), "grounded"),
                Set.of(), true).systemPrompt();
    }

    /**
     * 协调 {@code sessions.beginExecution} 完成当前阶段，供后续处理使用。
     * @param sessions 本次调用传入的 {@code AgentSessionService}
     * @param turn 已完成回合及其交付摘要
     */
    private static void stubExecutionTurn(AgentSessionService sessions, AgentSessionService.TurnHandle turn) {
        when(sessions.beginExecution(eq(1L), eq(turn.session().id()), any(), any())).thenAnswer(invocation -> {
            Function<String, AgentPolicyFingerprint.Policy> resolvePolicy = invocation.getArgument(3);
            return new AgentSessionService.ExecutionTurn(turn, resolvePolicy.apply(turn.session().answerPolicy()));
        });
    }

    /** 通过 {@code h.service.chat} 驱动用例，抛出 {@code ForbiddenException}，布尔状态符合断言。 */
    @Test
    void chat_answerPolicyChangeRequiresNewSession_andPassesGeneralToRuntime() {
        Harness h = new Harness(1L);
        when(h.agentProfileService.resolveForExecution(1L, 9L, Set.of(1L))).thenReturn(
                new ResolvedAgentProfile(9L, "同一提示词", Set.of(1L), Set.of(), "grounded"));
        AgentRequest original = h.request("q", 9L);
        h.service.chat(original);
        when(h.agentProfileService.resolveForExecution(1L, 9L, Set.of(1L))).thenReturn(
                new ResolvedAgentProfile(9L, "同一提示词", Set.of(1L), Set.of(), "general"));

        assertThrows(ForbiddenException.class, () -> h.service.chat(original));
        h.service.chat(h.request("q", 9L));

        ArgumentCaptor<AgentExecutionContext> contexts = ArgumentCaptor.forClass(AgentExecutionContext.class);
        verify(h.runtime, times(2)).run(contexts.capture(), any());
        assertNotEquals(contexts.getAllValues().get(0).getSessionId(), contexts.getAllValues().get(1).getSessionId());
        assertEquals("general", contexts.getAllValues().get(1).getAnswerPolicy());
        assertFalse(contexts.getAllValues().get(1).getSystemPrompt().contains(AgentPrompts.GROUNDED_INSTRUCTION));
    }

    /** 运行中取消已认领回合，验证部分消息未写入历史且占用槽释放。 */
    @Test
    void managedSessionCancelledDuringRuntime_doesNotPersistPartialTurn_andReleasesActiveSlot() {
        Harness h = new Harness(1L);
        InMemoryAgentSessionStore store = new InMemoryAgentSessionStore();
        AgentSessionService sessions = new AgentSessionService(store, h.agentProfileService, h.accessService, 30);
        String id = sessions.create(1L, null).id();
        AgentService service = h.serviceWith(h.runtime, sessions);
        when(h.runtime.run(any(), any())).thenAnswer(invocation -> {
            AgentExecutionContext context = invocation.getArgument(0);
            context.cancel();
            return AgentResponse.builder().answer("尚未交付的文本").stopReason(StopReason.FINAL_ANSWER).build();
        });

        service.chat(AgentRequest.builder().question("q").sessionId(id).build());

        assertTrue(sessions.history(1L, id, 0, 20).items().isEmpty());
        verifyNoInteractions(h.summaryCoordinator);
        sessions.delete(1L, id);
        assertTrue(store.get(id).isEmpty());
    }

    /** 通过 {@code sessions.create}、{@code h.runtime.run} 驱动用例，抛出 {@code IllegalStateException}，布尔状态符合断言。 */
    @Test
    void managedSessionRuntimeException_releasesActiveSlotWithoutSavingAnAnswer() {
        Harness h = new Harness(1L);
        InMemoryAgentSessionStore store = new InMemoryAgentSessionStore();
        AgentSessionService sessions = new AgentSessionService(store, h.agentProfileService, h.accessService, 30);
        String id = sessions.create(1L, null).id();
        AgentService service = h.serviceWith(h.runtime, sessions);
        when(h.runtime.run(any(), any())).thenThrow(new IllegalStateException("model failed"));

        assertThrows(IllegalStateException.class,
                () -> service.chat(AgentRequest.builder().question("q").sessionId(id).build()));

        assertTrue(sessions.history(1L, id, 0, 20).items().isEmpty());
        sessions.delete(1L, id);
        assertTrue(store.get(id).isEmpty());
    }

    /** 通过 {@code sessions.create}、{@code runtime.run} 驱动用例，跳过受保护的调用，布尔状态符合断言。 */
    @Test
    void downstreamWriteFailureCancelsRuntimeWithoutSavingPartialHistory() throws Exception {
        Harness h = new Harness(1L);
        InMemoryAgentSessionStore store = new InMemoryAgentSessionStore();
        AgentSessionService sessions = new AgentSessionService(store, h.agentProfileService, h.accessService, 30);
        String id = sessions.create(1L, null).id();
        DefaultAgentRuntime runtime = mock(DefaultAgentRuntime.class);
        var emitter = mock(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.class);
        org.mockito.Mockito.doThrow(new java.io.IOException("Broken pipe")).when(emitter)
                .send(any(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder.class));
        var sink = new com.pony.knowledge.agent.stream.SseAgentEventSink(
                emitter, new com.fasterxml.jackson.databind.ObjectMapper(), 0, null);
        when(runtime.run(any(), any(), any())).thenAnswer(call -> {
            AgentExecutionContext context = call.getArgument(0);
            AgentEventSink output = call.getArgument(2);
            output.emit(AgentEvent.of("delta", "text", "partial"));
            assertTrue(context.isCancelled());
            return AgentResponse.builder().answer("uncommitted partial answer")
                    .stopReason(StopReason.FINAL_ANSWER).build();
        });
        AgentService service = h.serviceWith(runtime, sessions);

        service.chatStream(AgentRequest.builder().question("q").sessionId(id).build(), sink);

        assertTrue(sessions.history(1L, id, 0, 20).items().isEmpty());
        assertEquals(0, store.countMessages(id));
        verifyNoInteractions(h.summaryCoordinator);
        verify(emitter, never()).completeWithError(any());
        sessions.delete(1L, id);
        assertTrue(store.get(id).isEmpty());
    }

    /** 使用 mock 依赖验证身份、范围及会话键，不访问真实模型或存储。 */
    private static final class Harness {

        private final CurrentUserUtil currentUserUtil = mock(CurrentUserUtil.class);

        private final KnowledgeBaseAccessService accessService = mock(KnowledgeBaseAccessService.class);

        private final AgentProfileService agentProfileService = mock(AgentProfileService.class);

        private final AgentRuntime runtime = mock(AgentRuntime.class);

        private final SummaryCoordinator summaryCoordinator = mock(SummaryCoordinator.class);

        private final ContextBudgetManager contextBudgetManager = mock(ContextBudgetManager.class);

        private final AgentTracer agentTracer = mock(AgentTracer.class);

        private final InMemoryAgentSessionStore store = new InMemoryAgentSessionStore();
        private final AgentSessionService sessions = new AgentSessionService(store, agentProfileService, accessService, 30);
        private final ModelRegistryService models = mock(ModelRegistryService.class);
        private final MemorySaveApprovalService approvals = mock(MemorySaveApprovalService.class);
        private final AgentService service = serviceWith(runtime, sessions);
        private final long userId;

        /**
         * 用注入依赖和构造参数建立此实例执行时使用的状态。
         * 下游调用 {@code accessService.resolveReadableIds}、{@code runtime.run}、{@code agentTracer.currentTraceId}。
         *
         * @param userId 当前用户 ID
         */
        private Harness(long userId) {
            this.userId = userId;
            when(currentUserUtil.getUserId()).thenReturn(userId);
            when(accessService.resolveReadableIds(userId)).thenReturn(Set.of(1L));
            when(runtime.run(any(), any())).thenReturn(AgentResponse.builder()
                    .answer("答案").stopReason(StopReason.FINAL_ANSWER).build());
            when(contextBudgetManager.build(any())).thenReturn(ContextBuildResult.builder()
                    .messages(List.of(AgentMessage.builder().role("user").content("你好").build())).build());
            when(agentTracer.currentTraceId()).thenReturn("trace-1");
            when(agentTracer.observeRoot(any(), any(), any())).thenAnswer(inv ->
                    ((java.util.function.Supplier<?>) inv.getArgument(2)).get());
        }

        /**
         * 协调 {@code sessions.create} 完成当前阶段，供后续处理使用。
         * @param question 本轮用户提问
         * @param agentId 自建 Agent ID，空值表示默认 Agent
         * @return 调用方使用的 {@code AgentRequest}
         */
        private AgentRequest request(String question, Long agentId) {
            return AgentRequest.builder().question(question).agentId(agentId)
                    .sessionId(sessions.create(userId, agentId).id()).build();
        }

        /**
         * 在该类型内部准备后续运行所需的数据。
         * @param runtime 本次调用传入的 {@code AgentRuntime}
         * @param sessions 本次调用传入的 {@code AgentSessionService}
         * @return 调用方使用的 {@code AgentService}
         */
        private AgentService serviceWith(AgentRuntime runtime, AgentSessionService sessions) {
            return new AgentService(currentUserUtil, accessService, agentProfileService, runtime,
                    summaryCoordinator, contextBudgetManager, agentTracer, sessions, models, approvals);
        }
    }
}
