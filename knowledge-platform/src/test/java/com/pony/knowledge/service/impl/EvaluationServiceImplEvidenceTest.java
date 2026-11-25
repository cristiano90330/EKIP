/* 验证检索评测如何区分排序候选、公开证据及实际执行策略。 */

package com.pony.knowledge.service.impl;

import com.pony.knowledge.dto.EvaluationBatchRequestDTO;
import com.pony.knowledge.dto.EvaluationBatchResultDTO;
import com.pony.knowledge.dto.EvaluationRequestDTO;
import com.pony.knowledge.dto.EvaluationResultDTO;
import com.pony.knowledge.service.KnowledgeBaseAccessService;
import com.pony.knowledge.service.ai.AiServiceClient;
import com.pony.knowledge.utils.CurrentUserUtil;
import com.pony.knowledge.service.rag.PythonRagPipeline;
import com.pony.knowledge.service.rag.model.RagEvidence;
import com.pony.knowledge.service.rag.model.RagEvidenceDecision;
import com.pony.knowledge.service.rag.model.RagQueryOutcome;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EvaluationServiceImplEvidenceTest {

    /** 对同一请求返回候选来源与获准证据，核对检索统计与裁判采用各自的数据口径。 */
    @Test
    void retrievalMetricsUseRawSourcesWhileJudgeUsesOnlyAcceptedEvidence() {
        KnowledgeBaseAccessService knowledgeBases = mock(KnowledgeBaseAccessService.class);
        AiServiceClient ai = mock(AiServiceClient.class);
        PythonRagPipeline pipeline = mock(PythonRagPipeline.class);
        RagQueryOutcome outcome = outcome();
        when(pipeline.query(eq(List.of(3L)), eq("q"), anyList(), eq(5))).thenReturn(outcome);
        AiServiceClient.JudgeResponse judge = new AiServiceClient.JudgeResponse();
        judge.setFaithfulness(0.8);
        when(ai.evaluateGenerate(eq("q"), eq("answer"), anyList(), eq("truth"))).thenReturn(judge);

        EvaluationServiceImpl service = service(knowledgeBases, ai, pipeline);
        EvaluationRequestDTO request = request();

        EvaluationResultDTO result = service.evaluateRetrieval(request);

        assertEquals(1.0, result.getRecallAtK(), "retrieval recall must use raw candidate id=1");
        assertEquals("dependency_unavailable", result.getOutcome());
        assertEquals("evidence_scorer_unavailable", result.getReasonCode());
        assertEquals("ev-gate-3", result.getEvidencePolicyVersion());
        assertEquals("unavailable", result.getEvidenceDecision().getState());
        ArgumentCaptor<List<String>> contexts = ArgumentCaptor.forClass(List.class);
        verify(ai).evaluateGenerate(eq("q"), eq("answer"), contexts.capture(), eq("truth"));
        assertEquals(List.of("accepted context"), contexts.getValue(),
                "faithfulness context must exclude raw candidates rejected by the gate");
        assertEquals(List.of(1L, 2L), result.getTrace().getRetrievedChunks().stream()
                .map(com.pony.knowledge.dto.RagTraceDTO.TraceChunk::getChunkId).toList());
    }

    /** 通过 {@code pipeline.retrieveOnly}、{@code service.evaluateBatch} 驱动该场景，实际结果与固定预期一致。 */
    @Test
    void batchAggregateRecordsOutcomeDecisionAndPolicyVersionCounts() {
        KnowledgeBaseAccessService knowledgeBases = mock(KnowledgeBaseAccessService.class);
        AiServiceClient ai = mock(AiServiceClient.class);
        PythonRagPipeline pipeline = mock(PythonRagPipeline.class);
        when(pipeline.retrieveOnly(eq(List.of(3L)), eq("q"), anyList(), eq(5))).thenReturn(outcome());

        EvaluationServiceImpl service = service(knowledgeBases, ai, pipeline);
        EvaluationBatchRequestDTO request = new EvaluationBatchRequestDTO();
        request.setCases(List.of(request()));
        request.setK(5);
        request.setEvaluateGeneration(false);

        EvaluationBatchResultDTO batch = service.evaluateBatch(request);

        assertNotNull(batch.getTotal());
        assertEquals(1, batch.getTotal().getOutcomeCounts().get("dependency_unavailable"));
        assertEquals(1, batch.getTotal().getEvidenceDecisionCounts().get("unavailable"));
        assertEquals(List.of("ev-gate-3"), batch.getTotal().getEvidencePolicyVersions());
    }

    /**
     * 协调 {@code knowledgeBases.canEvaluate} 完成当前阶段，供后续处理使用。
     * 下游调用 {@code knowledgeBases.canEvaluate}。
     *
     * @param knowledgeBases 本次调用传入的 {@code KnowledgeBaseAccessService}
     * @param ai 本次调用传入的 {@code AiServiceClient}
     * @param pipeline 本次调用传入的 {@code PythonRagPipeline}
     * @return 调用方使用的 {@code EvaluationServiceImpl}
     */
    private static EvaluationServiceImpl service(KnowledgeBaseAccessService knowledgeBases, AiServiceClient ai,
                                                   PythonRagPipeline pipeline) {
        CurrentUserUtil currentUser = mock(CurrentUserUtil.class);
        when(currentUser.getUserId()).thenReturn(9L);
        when(knowledgeBases.canEvaluate(9L, 3L)).thenReturn(true);
        return new EvaluationServiceImpl(knowledgeBases, currentUser, ai, pipeline, "hybrid-rerank", 2.0);
    }

    /**
     * 返回当前计算采用的 {@code request}。
     *
     * @return 当前记录中的 {@code request}
     */
    private static EvaluationRequestDTO request() {
        EvaluationRequestDTO request = new EvaluationRequestDTO();
        request.setKnowledgeBaseId(3L);
        request.setQuestion("q");
        request.setGroundTruth("truth");
        request.setRelevantChunkIds(List.of(1L));
        return request;
    }

    /**
     * 提供带准入证据和诊断的问答管线结果。
     *
     * @return 带准入证据和诊断的问答管线结果
     */
    private static RagQueryOutcome outcome() {
        RagEvidence rawRelevant = RagEvidence.builder().chunkId(1L).documentId(11L)
                .knowledgeBaseId(3L).chunkIndex(0).content("raw rejected source").build();
        RagEvidence rawAccepted = RagEvidence.builder().chunkId(2L).documentId(12L)
                .knowledgeBaseId(3L).chunkIndex(1).content("accepted context").build();
        return RagQueryOutcome.builder()
                .answer("answer")
                .outcome("dependency_unavailable")
                .reasonCode("evidence_scorer_unavailable")
                .failedStage("evidence_gate")
                .requestId("rag-request-1")
                .sources(List.of(rawRelevant, rawAccepted))
                .evidences(List.of(rawAccepted))
                .evidenceDecision(RagEvidenceDecision.builder()
                        .state("unavailable")
                        .policyVersion("ev-gate-3")
                        .reasonCode("scorer_unavailable")
                        .retrievedCount(2)
                        .acceptedCount(1)
                        .rejectedCount(1)
                        .build())
                .stages(List.of())
                .build();
    }
}
