/* 验证知识库会话聊天的授权、历史写入和错误处理。 */

package com.pony.knowledge.service.rag;

import com.pony.knowledge.dto.RagChatResponseDTO;
import com.pony.knowledge.dto.SourceDTO;
import com.pony.knowledge.exception.ForbiddenException;
import com.pony.knowledge.observability.RagMetrics;
import com.pony.knowledge.observability.RagPerformanceBudget;
import com.pony.knowledge.observability.RagStageTracer;
import com.pony.knowledge.observability.RagTraceRecorder;
import com.pony.knowledge.observability.TraceStore;
import com.pony.knowledge.service.KnowledgeBaseAccessService;
import com.pony.knowledge.service.rag.model.RagEvidence;
import com.pony.knowledge.service.rag.model.RagQueryOutcome;
import com.pony.knowledge.utils.CurrentUserUtil;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * /rag/chat 只有一条查询路径（一次 Python /rag/query）：权限校验（读侧口径 creator ∪ member，
 * 3.1 P1-7）、把库 id + 历史 + topK 一次性交给 PythonRagPipeline、会话回写、sources 完整
 * （knowledgeBaseId/distance 不丢）。
 */
class RagServiceChatTest {

    private static final long USER_ID = 42L;

    /**
     * 协调 {@code ObservationRegistry.create} 完成当前阶段，供后续处理使用。
     * @param registry 本次调用传入的 {@code SimpleMeterRegistry}
     * @param metrics 记录本模块用量和计时的指标组件
     * @return 调用方使用的 {@code RagStageTracer}
     */
    private RagStageTracer newStageTracer(SimpleMeterRegistry registry, RagMetrics metrics) {
        RagPerformanceBudget budget = new RagPerformanceBudget(metrics, 1000L, 1000L, 1000L, 1000L, 1000L, 10L, 0.01);
        return new RagStageTracer(ObservationRegistry.create(), registry, metrics, budget,
                new RagTraceRecorder(), null, 2.0, mock(TraceStore.class));
    }

    /**
     * 提供带准入证据和诊断的问答管线结果。
     * @param evidences 本次调用传入的 {@code List<RagEvidence>}
     * @return 带准入证据和诊断的问答管线结果
     */
    private RagQueryOutcome outcomeWith(List<RagEvidence> evidences) {
        return RagQueryOutcome.builder()
                .originalQuery("问题")
                .rewrittenQuery("改写后问题")
                .answer("答案")
                .evidences(evidences)
                .build();
    }

    /** 在该类型内部准备后续运行所需的数据。 */
    private RagEvidence defaultEvidence() {
        return RagEvidence.builder()
                .documentId(10L).chunkId(1L).knowledgeBaseId(7L).chunkIndex(0)
                .content("相关内容").score(0.9).distance(0.2).rank(1)
                .build();
    }

    /**
     * 在该类型内部准备后续运行所需的数据。
     *
     * @param accessService 本次调用传入的 {@code KnowledgeBaseAccessService}
     * @param currentUserUtil 本次调用传入的 {@code CurrentUserUtil}
     * @param conversationService 本次调用传入的 {@code ConversationService}
     * @param stageTracer 本次调用传入的 {@code RagStageTracer}
     * @param pipeline 本次调用传入的 {@code PythonRagPipeline}
     * @return 调用方使用的 {@code RagService}
     */
    private RagService newService(KnowledgeBaseAccessService accessService,
                                  CurrentUserUtil currentUserUtil,
                                  ConversationService conversationService,
                                  RagStageTracer stageTracer,
                                  PythonRagPipeline pipeline) {
        return new RagService(accessService, currentUserUtil, conversationService, stageTracer, pipeline);
    }

    /**
     * 协调 {@code newStageTracer} 完成当前阶段，供后续处理使用。
     * 下游调用 {@code newStageTracer}。
     *
     * @return 调用方使用的 {@code RagStageTracer}
     */
    private RagStageTracer stageTracer() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RagMetrics metrics = new RagMetrics(registry, "hybrid");
        return newStageTracer(registry, metrics);
    }

    /**
     * 协调 {@code accessService.canRead} 完成当前阶段，供后续处理使用。
     * 下游调用 {@code accessService.canRead}。
     *
     * @return 当前记录中的 {@code accessService}
     */
    private KnowledgeBaseAccessService allowingAccess() {
        KnowledgeBaseAccessService accessService = mock(KnowledgeBaseAccessService.class);
        when(accessService.canRead(USER_ID, 7L)).thenReturn(true);
        return accessService;
    }

    /**
     * 返回当前计算采用的 {@code currentUserUtil}。
     *
     * @return 当前记录中的 {@code currentUserUtil}
     */
    private CurrentUserUtil currentUser() {
        CurrentUserUtil currentUserUtil = mock(CurrentUserUtil.class);
        when(currentUserUtil.getUserId()).thenReturn(USER_ID);
        return currentUserUtil;
    }

    /** 通过 {@code service.chat} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void chatKeepsBehaviorThroughPythonPipeline() {
        PythonRagPipeline pipeline = mock(PythonRagPipeline.class);
        when(pipeline.query(eq(List.of(7L)), eq("问题"), any(), eq(5)))
                .thenReturn(outcomeWith(List.of(defaultEvidence())));
        ConversationService conversationService = mock(ConversationService.class);
        when(conversationService.getHistory(any(ConversationKey.class))).thenReturn(List.of());
        RagService service = newService(allowingAccess(), currentUser(), conversationService,
                stageTracer(), pipeline);

        RagChatResponseDTO response = service.chat(7L, "conv-1", "问题", 5);

        // 权限校验（P1-7 后走读侧口径 canRead）→ 一次调用 Python → 会话回写
        ArgumentCaptor<ConversationKey> keyCaptor = ArgumentCaptor.forClass(ConversationKey.class);
        verify(conversationService).getHistory(keyCaptor.capture());
        assertEquals(new ConversationKey(USER_ID, 7L, "conv-1"), keyCaptor.getValue());
        verify(conversationService).append(eq(keyCaptor.getValue()), eq("问题"), eq("答案"));

        // 响应：改写前后查询 + 完整 sources（知识库 id 与距离不丢）
        assertEquals("答案", response.getAnswer());
        assertEquals("问题", response.getOriginalQuery());
        assertEquals("改写后问题", response.getRewrittenQuery());
        assertEquals(1, response.getSources().size());
        SourceDTO source = response.getSources().get(0);
        assertEquals(10L, source.getDocumentId());
        assertEquals(1L, source.getChunkId());
        assertEquals(7L, source.getKnowledgeBaseId());
        assertEquals(0, source.getChunkIndex());
        assertEquals("相关内容", source.getContent());
        assertEquals(0.2, source.getDistance());
    }

    /** 通过 {@code service.chat} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void chatShortCircuitsOnEmptyEvidences() {
        PythonRagPipeline pipeline = mock(PythonRagPipeline.class);
        when(pipeline.query(eq(List.of(7L)), eq("问题"), any(), eq(5)))
                .thenReturn(outcomeWith(List.of()));
        ConversationService conversationService = mock(ConversationService.class);
        when(conversationService.getHistory(any(ConversationKey.class))).thenReturn(List.of());
        RagService service = newService(allowingAccess(), currentUser(), conversationService,
                stageTracer(), pipeline);

        RagChatResponseDTO response = service.chat(7L, "conv-1", "问题", 5);

        // 固定文案 + 空 sources
        assertEquals("知识库中没有找到足够相关的信息。", response.getAnswer());
        assertEquals(0, response.getSources().size());
        // 改写前后查询照常透出（多轮观察不丢）
        assertEquals("问题", response.getOriginalQuery());
        assertEquals("改写后问题", response.getRewrittenQuery());
        // 关键：空答案也必须回写会话历史，否则下一轮 Query Rewrite 缺上下文
        verify(conversationService).append(any(ConversationKey.class), eq("问题"), eq("知识库中没有找到足够相关的信息。"));
    }

    /** 通过 {@code service.chat} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void chatPassesTopKThroughToPipeline() {
        PythonRagPipeline pipeline = mock(PythonRagPipeline.class);
        when(pipeline.query(any(), any(), any(), anyInt()))
                .thenReturn(outcomeWith(List.of(defaultEvidence())));
        ConversationService conversationService = mock(ConversationService.class);
        when(conversationService.getHistory(any(ConversationKey.class))).thenReturn(List.of());
        RagService service = newService(allowingAccess(), currentUser(), conversationService,
                stageTracer(), pipeline);

        service.chat(7L, "conv-1", "问题", 10);

        ArgumentCaptor<Integer> captor = ArgumentCaptor.forClass(Integer.class);
        verify(pipeline).query(eq(List.of(7L)), eq("问题"), any(), captor.capture());
        assertEquals(10, captor.getValue());
    }

    /** 通过 {@code service.chat} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void chatDefaultsTopKTo5WhenNull() {
        PythonRagPipeline pipeline = mock(PythonRagPipeline.class);
        when(pipeline.query(any(), any(), any(), anyInt()))
                .thenReturn(outcomeWith(List.of(defaultEvidence())));
        ConversationService conversationService = mock(ConversationService.class);
        when(conversationService.getHistory(any(ConversationKey.class))).thenReturn(List.of());
        RagService service = newService(allowingAccess(), currentUser(), conversationService,
                stageTracer(), pipeline);

        service.chat(7L, "conv-1", "问题", null);

        ArgumentCaptor<Integer> captor = ArgumentCaptor.forClass(Integer.class);
        verify(pipeline).query(eq(List.of(7L)), eq("问题"), any(), captor.capture());
        assertEquals(5, captor.getValue());
    }

    /** 通过 {@code service.chat} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void chatAllowsMemberAccess() {
        PythonRagPipeline pipeline = mock(PythonRagPipeline.class);
        when(pipeline.query(any(), any(), any(), anyInt()))
                .thenReturn(outcomeWith(List.of(defaultEvidence())));
        ConversationService conversationService = mock(ConversationService.class);
        when(conversationService.getHistory(any(ConversationKey.class))).thenReturn(List.of());
        RagService service = newService(allowingAccess(), currentUser(), conversationService,
                stageTracer(), pipeline);

        RagChatResponseDTO response = service.chat(7L, "conv-1", "问题", 5);

        // 走读侧口径：canRead（creator ∪ member）而非 checkOwnership（仅 creator）
        assertEquals("答案", response.getAnswer());
    }

    /** 通过 {@code service.chat} 驱动用例，抛出 {@code ForbiddenException}，跳过受保护的调用。 */
    @Test
    void chatRejectsNonMemberWith403() {
        PythonRagPipeline pipeline = mock(PythonRagPipeline.class);
        ConversationService conversationService = mock(ConversationService.class);
        when(conversationService.getHistory(any(ConversationKey.class))).thenReturn(List.of());

        KnowledgeBaseAccessService accessService = mock(KnowledgeBaseAccessService.class);
        when(accessService.canRead(USER_ID, 7L)).thenReturn(false);

        RagService service = newService(accessService, currentUser(), conversationService,
                stageTracer(), pipeline);

        assertThrows(ForbiddenException.class, () -> service.chat(7L, "conv-1", "问题", 5));
        verify(conversationService, never()).getHistory(any(ConversationKey.class));
        verify(pipeline, never()).query(any(), any(), any(), anyInt());
    }

    /** 通过 {@code service.chat} 驱动用例，布尔状态符合断言，实际结果与固定预期一致。 */
    @Test
    void chatCarriesDegradedStateThroughToResponse() {
        PythonRagPipeline pipeline = mock(PythonRagPipeline.class);
        when(pipeline.query(eq(List.of(7L)), eq("问题"), any(), eq(5)))
                .thenReturn(RagQueryOutcome.builder()
                        .originalQuery("问题")
                        .rewrittenQuery("改写后问题")
                        .answer("降级答案")
                        .evidences(List.of(defaultEvidence()))
                        .degraded(true)
                        .degradeReason("prompt 超预算直接降级")
                        .reasonCode("generation_degraded")
                        .providerUsed("ollama")
                        .build());
        ConversationService conversationService = mock(ConversationService.class);
        when(conversationService.getHistory(any(ConversationKey.class))).thenReturn(List.of());
        RagService service = newService(allowingAccess(), currentUser(), conversationService,
                stageTracer(), pipeline);

        RagChatResponseDTO response = service.chat(7L, "conv-1", "问题", 5);

        // 降级状态透出：用户端可提示「这次是降级答的」，监控可对降级比例告警
        assertTrue(response.isDegraded());
        assertEquals("generation_degraded", response.getDegradeReason());
        assertEquals("ollama", response.getProviderUsed());
        assertEquals("降级答案", response.getAnswer());
    }
}
