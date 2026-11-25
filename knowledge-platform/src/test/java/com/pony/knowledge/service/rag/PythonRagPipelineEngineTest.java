/* 验证用户或默认选定的管线引擎如何进入 Python 执行请求。 */

package com.pony.knowledge.service.rag;

import com.pony.knowledge.exception.InvalidRagPipelineException;
import com.pony.knowledge.exception.RagQueryFailureException;
import com.pony.knowledge.observability.RagTrace;
import com.pony.knowledge.observability.RagTraceContext;
import com.pony.knowledge.observability.RagTraceRecorder;
import com.pony.knowledge.service.ai.AiServiceClient;
import com.pony.knowledge.service.rag.model.RagQueryOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PythonRagPipelineEngineTest {
    /**
     * 在该类型内部准备后续运行所需的数据。
     * @param client 本次调用传入的 {@code AiServiceClient}
     * @param engine 本次调用传入的 {@code String}
     * @return 调用方使用的 {@code PythonRagPipeline}
     */
    private PythonRagPipeline pipeline(AiServiceClient client, String engine) {
        return new PythonRagPipeline(client, engine, "hybrid-rerank-rewrite", 20, 60,
                "none", 1500, false, 0.7, false, 35_000, new RagTraceRecorder());
    }

    /**
     * 返回当前计算采用的 {@code response}。
     *
     * @return 当前记录中的 {@code response}
     */
    private AiServiceClient.RagQueryResponse emptyResponse() {
        AiServiceClient.RagQueryResponse response = new AiServiceClient.RagQueryResponse();
        response.setOutcome("no_evidence");
        response.setReasonCode("no_match");
        response.setSources(List.of());
        response.setEvidenceSources(List.of());
        return response;
    }

    /** 通过 {@code client.ragQuery}、{@code ArgumentCaptor.forClass} 驱动该场景，实际结果与固定预期一致。 */
    @Test
    void omittedFixedSelectionUsesConfiguredEngineAndBackfillsOlderResponses() {
        AiServiceClient client = mock(AiServiceClient.class);
        when(client.ragQuery(any())).thenReturn(emptyResponse());
        RagQueryOutcome outcome = pipeline(client, "fixed").query(List.of(7L), "问题", List.of(), 5);
        ArgumentCaptor<AiServiceClient.RagQueryRequest> request = ArgumentCaptor.forClass(AiServiceClient.RagQueryRequest.class);
        verify(client).ragQuery(request.capture());
        assertEquals("fixed", request.getValue().getPipelineEngine());
        assertEquals("hybrid-rerank-rewrite", request.getValue().getVersion());
        assertEquals("fixed", outcome.getPipelineEngine());
        assertEquals("hybrid-rerank-rewrite", outcome.getRetrievalVersion());
    }

    /** 通过 {@code client.ragQuery}、{@code emptyResponse} 驱动该场景，抛出 {@code RagQueryFailureException}，实际结果与固定预期一致。 */
    @Test
    void explicitPresetRequiresTheActualEvidenceModeFromPython() {
        AiServiceClient client = mock(AiServiceClient.class);
        AiServiceClient.RagQueryResponse response = emptyResponse();
        response.setPipelineEngine("fixed");
        response.setRetrievalVersion("hybrid");
        response.setEvidenceMode("score");
        when(client.ragQuery(any())).thenReturn(response);

        var error = assertThrows(RagQueryFailureException.class,
                () -> pipeline(client, "fixed").query(List.of(7L), "q", List.of(), 5, "fixed", RagStrategy.LIGHT));
        assertEquals("ai_contract_mismatch", error.getReasonCode());
    }

    /** 通过 {@code client.ragQuery}、{@code RagTraceContext.clear} 驱动该场景，实际结果与固定预期一致。 */
    @Test
    void explicitSelectionOverridesConfigAndReturnsActualVersionAndMetadata() {
        AiServiceClient client = mock(AiServiceClient.class);
        AiServiceClient.RagQueryResponse response = emptyResponse();
        response.setPipelineEngine("langchain");
        response.setRetrievalVersion("hybrid-rerank-rewrite");
        response.setProviderUsed("ollama");
        AiServiceClient.RagEvidenceDecision decision = new AiServiceClient.RagEvidenceDecision();
        decision.setScorer("embed_sim");
        response.setEvidenceDecision(decision);
        when(client.ragQuery(any())).thenReturn(response);
        RagTrace trace = RagTrace.builder().build();
        RagTraceContext.set(trace);
        try {
            RagQueryOutcome out = pipeline(client, "fixed").query(List.of(7L), "q", List.of(), 5, "langchain");
            assertEquals("langchain", out.getPipelineEngine());
            assertEquals("hybrid-rerank-rewrite", out.getRetrievalVersion());
        } finally {
            RagTraceContext.clear();
        }

        assertEquals("langchain", trace.getPipelineEngine());
        assertEquals("hybrid-rerank-rewrite", trace.getRetrievalVersion());
        assertEquals("ollama", trace.getProviderUsed());
        assertEquals("embed_sim", trace.getEvidenceScorer());
        assertEquals("no_evidence", trace.getOutcome());
        assertEquals("no_match", trace.getReasonCode());
        ArgumentCaptor<AiServiceClient.RagQueryRequest> request = ArgumentCaptor.forClass(AiServiceClient.RagQueryRequest.class);
        verify(client).ragQuery(request.capture());
        assertEquals("langchain", request.getValue().getPipelineEngine());
    }

    /** 通过 {@code client.ragQuery}、{@code ArgumentCaptor.forClass} 驱动该场景，布尔状态符合断言，实际结果与固定预期一致。 */
    @Test
    void selectionAlsoAppliesToRetrievalAndEmptyAuthorization() {
        AiServiceClient client = mock(AiServiceClient.class);
        AiServiceClient.RagQueryResponse response = emptyResponse();
        response.setPipelineEngine("langchain");
        response.setRetrievalVersion("hybrid-rerank-rewrite");
        when(client.ragQuery(any())).thenReturn(response);
        pipeline(client, "fixed").retrieveOnly(null, "q", List.of(), 5, "langchain");
        ArgumentCaptor<AiServiceClient.RagQueryRequest> request = ArgumentCaptor.forClass(AiServiceClient.RagQueryRequest.class);
        verify(client).ragQuery(request.capture());
        assertEquals("langchain", request.getValue().getPipelineEngine());
        assertEquals(List.of(), request.getValue().getKnowledgeBaseIds());
        assertFalse(request.getValue().isGenerate());
    }

    /** 通过 {@code pipeline} 驱动该场景，抛出 {@code InvalidRagPipelineException}，跳过受保护的调用。 */
    @ParameterizedTest
    @ValueSource(strings = {"", "unknown", "LANGCHAIN", " langchain"})
    void rejectsInvalidRequestBeforeHttp(String invalid) {
        AiServiceClient client = mock(AiServiceClient.class);
        assertThrows(InvalidRagPipelineException.class,
                () -> pipeline(client, "fixed").query(List.of(7L), "q", List.of(), 5, invalid));
        verifyNoInteractions(client);
    }

    /** 通过 {@code pipeline} 驱动该场景，抛出 {@code IllegalStateException}。 */
    @Test
    void invalidDeploymentConfigurationFailsAtConstruction() {
        assertThrows(IllegalStateException.class, () -> pipeline(mock(AiServiceClient.class), "auto"));
    }

    /** 通过 {@code client.ragQuery}、{@code emptyResponse} 驱动该场景，抛出 {@code RagQueryFailureException}，实际结果与固定预期一致。 */
    @ParameterizedTest
    @ValueSource(strings = {"missing_engine", "missing_version", "empty_version", "wrong_version", "missing_both"})
    void langchainRequiresExplicitMatchingResponseMetadata(String missing) {
        AiServiceClient client = mock(AiServiceClient.class);
        AiServiceClient.RagQueryResponse response = emptyResponse();
        response.setPipelineEngine("langchain");
        response.setRetrievalVersion("hybrid-rerank-rewrite");
        switch (missing) {
            case "missing_engine" -> response.setPipelineEngine(null);
            case "missing_version" -> response.setRetrievalVersion(null);
            case "empty_version" -> response.setRetrievalVersion("");
            case "wrong_version" -> response.setRetrievalVersion("hybrid");
            default -> { response.setPipelineEngine(null); response.setRetrievalVersion(null); }
        }

        when(client.ragQuery(any())).thenReturn(response);
        RagQueryFailureException failure = assertThrows(RagQueryFailureException.class,
                () -> pipeline(client, "langchain").query(List.of(7L), "q", List.of(), 5));
        assertEquals(503, failure.getStatus().value());
        assertEquals("ai_contract_mismatch", failure.getReasonCode());
    }

    /** 通过 {@code client.ragQuery}、{@code emptyResponse} 驱动该场景，实际结果与固定预期一致。 */
    @Test
    void fixedRequestCanExplicitlyOverrideLangchainServerDefaultWithOlderResponse() {
        AiServiceClient client = mock(AiServiceClient.class);
        when(client.ragQuery(any())).thenReturn(emptyResponse());
        RagQueryOutcome outcome = pipeline(client, "langchain").query(List.of(7L), "q", List.of(), 5, "fixed");
        assertEquals("fixed", outcome.getPipelineEngine());
        assertEquals("hybrid-rerank-rewrite", outcome.getRetrievalVersion());
    }

    /** 通过 {@code client.ragQuery}、{@code emptyResponse} 驱动该场景，抛出 {@code RagQueryFailureException}，实际结果与固定预期一致。 */
    @Test
    void fixedResponseCannotExplicitlyClaimADifferentRetrievalVersion() {
        AiServiceClient client = mock(AiServiceClient.class);
        AiServiceClient.RagQueryResponse response = emptyResponse();
        response.setPipelineEngine("fixed");
        response.setRetrievalVersion("baseline");
        when(client.ragQuery(any())).thenReturn(response);
        RagQueryFailureException failure = assertThrows(RagQueryFailureException.class,
                () -> pipeline(client, "fixed").query(List.of(7L), "q", List.of(), 5));
        assertEquals(503, failure.getStatus().value());
        assertEquals("ai_contract_mismatch", failure.getReasonCode());
    }

    /** 通过 {@code client.ragQuery}、{@code RagTraceContext.clear} 驱动该场景，抛出 {@code RagQueryFailureException}，实际结果与固定预期一致。 */
    @Test
    void refusesExplicitEngineMismatchInsteadOfReportingRequestedEngineAsActual() {
        AiServiceClient client = mock(AiServiceClient.class);
        AiServiceClient.RagQueryResponse response = emptyResponse();
        response.setPipelineEngine("fixed");
        response.setRetrievalVersion("baseline");
        when(client.ragQuery(any())).thenReturn(response);
        RagTrace trace = RagTrace.builder().build();
        RagTraceContext.set(trace);
        try {
            RagQueryFailureException failure = assertThrows(RagQueryFailureException.class,
                    () -> pipeline(client, "fixed").query(List.of(7L), "q", List.of(), 5, "langchain"));
            assertEquals("ai_contract_mismatch", failure.getReasonCode());
            assertEquals(503, failure.getStatus().value());
        } finally {
            RagTraceContext.clear();
        }

        assertEquals("langchain", trace.getRequestedPipelineEngine());
        assertEquals("hybrid-rerank-rewrite", trace.getRequestedRetrievalVersion());
        assertEquals("fixed", trace.getPipelineEngine());
        assertEquals("baseline", trace.getRetrievalVersion());
        assertEquals("ai_contract_mismatch", trace.getReasonCode());
    }

    /** 通过 {@code client.ragQuery}、{@code RagTraceContext.clear} 驱动该场景，抛出 {@code RagQueryFailureException}，实际结果与固定预期一致。 */
    @Test
    void failureTraceKeepsRequestedEngineAndDoesNotInventProvider() {
        AiServiceClient client = mock(AiServiceClient.class);
        when(client.ragQuery(any())).thenThrow(new RestClientResponseException(
                "Unavailable", 503, "Unavailable", null, new byte[0], null));
        RagTrace trace = RagTrace.builder().build();
        RagTraceContext.set(trace);
        try {
            assertThrows(RagQueryFailureException.class,
                    () -> pipeline(client, "fixed").query(List.of(7L), "q", List.of(), 5, "langchain"));
        } finally {
            RagTraceContext.clear();
        }

        assertEquals("langchain", trace.getRequestedPipelineEngine());
        assertEquals("hybrid-rerank-rewrite", trace.getRequestedRetrievalVersion());
        assertNull(trace.getPipelineEngine());
        assertNull(trace.getRetrievalVersion());
        assertEquals("dependency_unavailable", trace.getOutcome());
        assertNull(trace.getProviderUsed());
        assertNull(trace.getEvidenceScorer());
    }
}
