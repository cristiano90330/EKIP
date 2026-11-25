/* 验证 RAG 服务为同步问答记录阶段、终态和业务追踪。 */

package com.pony.knowledge.service.rag;

import com.pony.knowledge.observability.RagMetrics;
import com.pony.knowledge.observability.RagPerformanceBudget;
import com.pony.knowledge.observability.RagStageTracer;
import com.pony.knowledge.observability.RagTrace;
import com.pony.knowledge.observability.RagTraceRecorder;
import com.pony.knowledge.observability.TraceStore;
import com.pony.knowledge.service.KnowledgeBaseAccessService;
import com.pony.knowledge.service.ai.AiServiceClient;
import com.pony.knowledge.utils.CurrentUserUtil;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RagService 只剩 python 一条路径后，ask 的**根观测**仍完整：
 * 请求计数保留；整条管线由 PythonRagPipeline 承载（生成计时/token 已随管线搬到 Python，
 * 由 Python 回传 stages[] 经 RagTraceRecorder 还原，见 PythonRagPipelineTest）。
 */
class RagServiceObservabilityTest {

    private static final long USER_ID = 7L;

    /**
     * 协调 {@code ObservationRegistry.create} 完成当前阶段，供后续处理使用。
     * 下游调用 {@code ObservationRegistry.create}。
     *
     * @param registry 本次调用传入的 {@code SimpleMeterRegistry}
     * @param traceStore 本次调用传入的 {@code TraceStore}
     * @return 调用方使用的 {@code RagStageTracer}
     */
    private RagStageTracer newStageTracer(SimpleMeterRegistry registry, TraceStore traceStore) {
        RagMetrics metrics = new RagMetrics(registry, "hybrid");
        RagPerformanceBudget budget = new RagPerformanceBudget(metrics, 1000L, 1000L, 1000L, 1000L, 1000L, 10L, 0.01);
        return new RagStageTracer(ObservationRegistry.create(), registry, metrics, budget,
                new RagTraceRecorder(), null, 2.0, traceStore);
    }

    /** 通过 {@code registry.get} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void askRecordsRequestCounterAndDelegatesToPythonPipeline() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TraceStore traceStore = mock(TraceStore.class);
        RagStageTracer stageTracer = newStageTracer(registry, traceStore);

        KnowledgeBaseAccessService accessService = mock(KnowledgeBaseAccessService.class);
        when(accessService.resolveReadableIds(USER_ID)).thenReturn(Set.of(1L, 2L));
        CurrentUserUtil currentUserUtil = mock(CurrentUserUtil.class);
        when(currentUserUtil.getUserId()).thenReturn(USER_ID);

        AiServiceClient aiClient = mock(AiServiceClient.class);
        AiServiceClient.RagQuerySource source = new AiServiceClient.RagQuerySource();
        source.setChunkId(1L);
        source.setDocumentId(10L);
        source.setKnowledgeBaseId(1L);
        source.setChunkIndex(0);
        source.setContent("相关内容");
        AiServiceClient.RagQueryResponse aiResponse = new AiServiceClient.RagQueryResponse();
        aiResponse.setOutcome("success");
        aiResponse.setOriginalQuery("问题");
        aiResponse.setAnswer("答案");
        aiResponse.setSources(List.of(source));
        aiResponse.setEvidenceSources(List.of(source));
        when(aiClient.ragQuery(any())).thenReturn(aiResponse);

        PythonRagPipeline pipeline = new PythonRagPipeline(aiClient, "hybrid", 20, 60,
                "none", 1500, false, 0.7, false, 35_000, new RagTraceRecorder());

        RagService service = new RagService(accessService, currentUserUtil,
                mock(ConversationService.class), stageTracer, pipeline);

        service.ask("问题", 5);

        // 根请求计数保留（endpoint=ask），观测入口未因管线搬迁而丢失
        assertEquals(1.0, registry.get("rag.requests.total").tag("endpoint", "ask").counter().count());

        // 一次调用 Python /rag/query，且 Java 授权解析出的可访问集合原样传入（default-deny 不变）
        ArgumentCaptor<AiServiceClient.RagQueryRequest> requestCaptor =
                ArgumentCaptor.forClass(AiServiceClient.RagQueryRequest.class);
        verify(aiClient).ragQuery(requestCaptor.capture());
        assertEquals("问题", requestCaptor.getValue().getQuestion());
        assertEquals(Set.of(1L, 2L), Set.copyOf(requestCaptor.getValue().getKnowledgeBaseIds()));

        ArgumentCaptor<RagTrace> traceCaptor = ArgumentCaptor.forClass(RagTrace.class);
        verify(traceStore).save(traceCaptor.capture(), eq("ask"));
        assertEquals("问题", traceCaptor.getValue().getOriginalQuery(),
                "ask 的根 Trace 必须保存调用方原始问题");
        assertNull(traceCaptor.getValue().getRewrittenQuery(),
                "没有改写时不得把原始问题伪装成改写结果");
    }
}
