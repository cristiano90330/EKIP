/* 检查检索接口记录的原始问题和改写 Trace 字段。 */

package com.pony.knowledge.controller;

import com.pony.knowledge.observability.RagMetrics;
import com.pony.knowledge.observability.RagPerformanceBudget;
import com.pony.knowledge.observability.RagStageTracer;
import com.pony.knowledge.observability.RagTrace;
import com.pony.knowledge.observability.RagTraceRecorder;
import com.pony.knowledge.observability.TraceStore;
import com.pony.knowledge.service.KnowledgeBaseAccessService;
import com.pony.knowledge.service.ai.AiServiceClient;
import com.pony.knowledge.service.rag.PythonRagPipeline;
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

class KnowledgeSearchControllerObservabilityTest {

    /** 模拟未执行改写的 Python 响应，确认 Trace 保留原问题且改写字段为空。 */
    @Test
    void searchTraceRecordsOriginalQueryAndLeavesRewriteAbsentWhenPythonDidNotRewrite() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TraceStore traceStore = mock(TraceStore.class);
        RagMetrics metrics = new RagMetrics(registry, "hybrid-rerank");
        RagPerformanceBudget budget = new RagPerformanceBudget(
                metrics, 1000L, 1000L, 1000L, 1000L, 1000L, 10L, 0.01);
        RagStageTracer tracer = new RagStageTracer(ObservationRegistry.create(), registry, metrics, budget,
                new RagTraceRecorder(), null, 2.0, traceStore);

        KnowledgeBaseAccessService accessService = mock(KnowledgeBaseAccessService.class);
        when(accessService.resolveReadableIds(7L)).thenReturn(Set.of(11L));
        CurrentUserUtil currentUserUtil = mock(CurrentUserUtil.class);
        when(currentUserUtil.getUserId()).thenReturn(7L);

        AiServiceClient aiClient = mock(AiServiceClient.class);
        AiServiceClient.RagQueryResponse response = new AiServiceClient.RagQueryResponse();
        response.setOutcome("success");
        response.setSources(List.of());
        response.setEvidenceSources(List.of());
        when(aiClient.ragQuery(any())).thenReturn(response);

        PythonRagPipeline pipeline = new PythonRagPipeline(aiClient, "hybrid-rerank", 20, 60,
                "none", 1500, false, 0.7, false, 35_000, new RagTraceRecorder());
        KnowledgeSearchController controller = new KnowledgeSearchController(
                pipeline, accessService, currentUserUtil, tracer);

        assertEquals(List.of(), controller.search("未改写的检索问题", 5));

        ArgumentCaptor<RagTrace> traceCaptor = ArgumentCaptor.forClass(RagTrace.class);
        verify(traceStore).save(traceCaptor.capture(), eq("search"));
        assertEquals("未改写的检索问题", traceCaptor.getValue().getOriginalQuery());
        assertNull(traceCaptor.getValue().getRewrittenQuery(),
                "无改写结果时必须保留 null，不能伪造为原始问题");
    }
}
