/* 验证普通问答的前置校验、准入来源与失败语义。 */

package com.pony.knowledge.service.rag;

import com.pony.knowledge.dto.RAGResponseDTO;
import com.pony.knowledge.observability.RagMetrics;
import com.pony.knowledge.observability.RagPerformanceBudget;
import com.pony.knowledge.observability.RagStageTracer;
import com.pony.knowledge.observability.RagTraceRecorder;
import com.pony.knowledge.observability.TraceStore;
import com.pony.knowledge.service.KnowledgeBaseAccessService;
import com.pony.knowledge.service.rag.model.RagQueryOutcome;
import com.pony.knowledge.utils.CurrentUserUtil;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * `/knowledge/ask`（全局检索）的**授权负侧**：无可访问知识库的用户不得读到任何东西。
 *
 * <p>为什么单独一个文件（CLAUDE.md「重切边界留下的债」第 1 条）：改造前有
 * {@code RetrievalServiceAuthzTest} / {@code VectorRetrieverAuthzTest} 断言「越权的 kbId 读不到」，
 * 两者随旧分段路径一起删除了，**没有对应的新测试**。改造后 Python 首次直连 PG 读
 * {@code document_chunk}，`knowledgeBaseIds` 又由调用方自带 —— 「Java 只把授权后的集合传下去」
 * 这条前提必须由测试钉住，而不是靠代码注释。
 *
 * <p>分工：{@code KnowledgeBaseAccessServiceImplTest} 钉「谁能访问哪些库」；
 * {@code RagServiceChatTest.chatRejectsNonMemberWith403} 钉 chat 的 403；
 * 本文件钉 **ask 这条没有显式 403 的路径** —— 它的 default-deny 靠「解析出空集合 → 传给 Python
 * 的就是空集合 → Python 空集合短路返回空」实现，任何一环把空集合换成 null 或全库，都是越权。</p>
 */
class RagServiceAskTest {

    private static final long USER_ID = 42L;

    /**
     * 协调 {@code ObservationRegistry.create} 完成当前阶段，供后续处理使用。
     * 下游调用 {@code ObservationRegistry.create}。
     *
     * @return 调用方使用的 {@code RagStageTracer}
     */
    private RagStageTracer stageTracer() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RagMetrics metrics = new RagMetrics(registry, "hybrid");
        RagPerformanceBudget budget = new RagPerformanceBudget(
                metrics, 1000L, 1000L, 1000L, 1000L, 1000L, 10L, 0.01);
        return new RagStageTracer(ObservationRegistry.create(), registry, metrics, budget,
                new RagTraceRecorder(), null, 2.0, mock(TraceStore.class));
    }

    /**
     * 返回当前计算采用的 {@code util}。
     *
     * @return 当前记录中的 {@code util}
     */
    private CurrentUserUtil currentUser() {
        CurrentUserUtil util = mock(CurrentUserUtil.class);
        when(util.getUserId()).thenReturn(USER_ID);
        return util;
    }

    /**
     * 协调 {@code accessService.resolveReadableIds} 完成当前阶段，供后续处理使用。
     * 下游调用 {@code accessService.resolveReadableIds}、{@code currentUser}、{@code stageTracer}。
     *
     * @param accessibleIds 本次调用传入的 {@code Set<Long>}
     * @param pipeline 本次调用传入的 {@code PythonRagPipeline}
     * @return 调用方使用的 {@code RagService}
     */
    private RagService newService(Set<Long> accessibleIds, PythonRagPipeline pipeline) {
        KnowledgeBaseAccessService accessService = mock(KnowledgeBaseAccessService.class);
        when(accessService.resolveReadableIds(USER_ID)).thenReturn(accessibleIds);
        return new RagService(accessService, currentUser(),
                mock(ConversationService.class), stageTracer(), pipeline);
    }

    /** 通过 {@code pipeline.query}、{@code service.ask} 驱动该场景，布尔状态符合断言，返回的数据、边界或顺序符合断言。 */
    @Test
    void strangerGetsEmptyAccessibleSetForwarded_andNoSources() {
        PythonRagPipeline pipeline = mock(PythonRagPipeline.class);
        when(pipeline.query(any(), any(), any(), anyInt())).thenReturn(
                RagQueryOutcome.builder().originalQuery("问题").evidences(List.of()).build());

        RagService service = newService(Set.of(), pipeline);

        RAGResponseDTO response = service.ask("问题", 5);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> idsCaptor = ArgumentCaptor.forClass(List.class);
        verify(pipeline).query(idsCaptor.capture(), any(), any(), anyInt());

        List<Long> forwarded = idsCaptor.getValue();
        assertNotNull(forwarded, "传给 Python 的集合不得为 null");
        assertTrue(forwarded.isEmpty(),
                "无可访问知识库时必须传空集合（传 null 或全库都是越权），实际=" + forwarded);

        assertEquals(0, response.getSources().size(), "空授权不得带出任何来源");
        assertEquals("知识库中没有找到足够相关的信息。", response.getAnswer());
    }

    /** 通过 {@code pipeline.query}、{@code service.ask} 驱动该场景，实际结果与固定预期一致。 */
    @Test
    void onlyResolvedIdsAreForwarded() {
        PythonRagPipeline pipeline = mock(PythonRagPipeline.class);
        when(pipeline.query(any(), any(), any(), anyInt())).thenReturn(
                RagQueryOutcome.builder().originalQuery("问题").evidences(List.of()).build());

        RagService service = newService(Set.of(3L, 5L), pipeline);

        service.ask("问题", 5);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> idsCaptor = ArgumentCaptor.forClass(List.class);
        verify(pipeline).query(idsCaptor.capture(), any(), any(), anyInt());
        assertEquals(Set.of(3L, 5L), Set.copyOf(idsCaptor.getValue()),
                "只能传授权解析出的集合，实际=" + idsCaptor.getValue());
    }
}
