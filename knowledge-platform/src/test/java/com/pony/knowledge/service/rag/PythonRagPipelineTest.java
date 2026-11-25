/* 验证 Java RAG 适配器的请求装配、Python 响应映射、来源准入与故障状态。 */

package com.pony.knowledge.service.rag;

import com.pony.knowledge.observability.RagTrace;
import com.pony.knowledge.observability.RagTraceContext;
import com.pony.knowledge.observability.RagTraceRecorder;
import com.pony.knowledge.observability.TraceStage;
import com.pony.knowledge.exception.RagQueryFailureException;
import com.pony.knowledge.service.ai.AiServiceClient;
import com.pony.knowledge.service.rag.model.RagQueryOutcome;
import com.pony.knowledge.service.rag.model.DocumentReadWindow;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「重切边界」新路径（决策文档 §4 第 5 项 / §5.12.10）：PythonRagPipeline 的契约映射、
 * 开关透传、阶段回填与压缩回传。
 *
 * <p>2026-09-27 §5.12.10 第 3 步后有两处变化：① `compression`/`MMR` **不再 fail-fast**
 * （已搬到 Python，改为逐请求透传）；② `AiServiceClient.ragQuery` 改成收 DTO
 * （原先 12 个位置参数里 4 个同型，位置传参是雷），故断言改用 {@link ArgumentCaptor} 捕获请求。</p>
 */
class PythonRagPipelineTest {

    /** 同时给出用户原问题与检索提示，检查 Java 请求将两者分别传给 Python。 */
    @Test
    void admittedQueryKeepsUserQuestionSeparateFromRetrievalHint() {
        AiServiceClient client = mock(AiServiceClient.class);
        AiServiceClient.RagQueryResponse response = new AiServiceClient.RagQueryResponse();
        response.setOutcome("success");
        response.setSources(List.of(source(1L)));
        response.setEvidenceSources(List.of(source(1L)));
        when(client.ragQuery(any())).thenReturn(response);
        pipeline(client).retrieveAdmitted(List.of(7L), "材料缺失应在几天内补齐？",
                List.of(), 5, 42L, "报销制度");
        AiServiceClient.RagQueryRequest request = captureRequest(client);
        assertEquals("材料缺失应在几天内补齐？", request.getQuestion());
        assertEquals("报销制度", request.getRetrievalQuery());
        assertEquals(42L, request.getDocumentId());
        assertTrue(request.isAdmitEvidence());
        assertFalse(request.isGenerate());
    }

    /**
     * 协调 {@code pipeline} 完成当前阶段，供后续处理使用。
     * @param client 本次调用传入的 {@code AiServiceClient}
     * @return 调用方使用的 {@code PythonRagPipeline}
     */
    private static PythonRagPipeline pipeline(AiServiceClient client) {
        return pipeline(client, "baseline", "none", 1500, false, 0.7, false,
                mock(RagTraceRecorder.class));
    }

    /**
     * 在该类型内部准备后续运行所需的数据。
     *
     * @param client 本次调用传入的 {@code AiServiceClient}
     * @param version 本次调用传入的 {@code String}
     * @param compressionMode 本次调用传入的 {@code String}
     * @param tokenBudget 本次调用传入的 {@code int}
     * @param mmrEnabled 本次调用传入的 {@code boolean}
     * @param mmrLambda 本次调用传入的 {@code double}
     * @param smallToBigEnabled 本次调用传入的 {@code boolean}
     * @param recorder 本次调用传入的 {@code RagTraceRecorder}
     * @return 调用方使用的 {@code PythonRagPipeline}
     */
    private static PythonRagPipeline pipeline(AiServiceClient client, String version,
            String compressionMode, int tokenBudget, boolean mmrEnabled, double mmrLambda,
            boolean smallToBigEnabled, RagTraceRecorder recorder) {
        return new PythonRagPipeline(client, version, 20, 60, compressionMode, tokenBudget,
                mmrEnabled, mmrLambda, smallToBigEnabled, 35_000, recorder);
    }

    /**
     * 协调 {@code ArgumentCaptor.forClass} 完成当前阶段，供后续处理使用。
     * 下游调用 {@code ArgumentCaptor.forClass}、{@code captor.capture}。
     *
     * @param client 本次调用传入的 {@code AiServiceClient}
     * @return 调用方使用的 {@code AiServiceClient.RagQueryRequest}
     */
    private static AiServiceClient.RagQueryRequest captureRequest(AiServiceClient client) {
        ArgumentCaptor<AiServiceClient.RagQueryRequest> captor =
                ArgumentCaptor.forClass(AiServiceClient.RagQueryRequest.class);
        verify(client).ragQuery(captor.capture());
        return captor.getValue();
    }

    /**
     * 返回当前计算采用的 {@code response}。
     *
     * @return 当前记录中的 {@code response}
     */
    private static AiServiceClient.RagQueryResponse noEvidenceResponse() {
        AiServiceClient.RagQueryResponse response = new AiServiceClient.RagQueryResponse();
        response.setOutcome("no_evidence");
        response.setSources(List.of());
        response.setEvidenceSources(List.of());
        return response;
    }

    /** 通过 {@code client.ragQuery}、{@code pipeline.query} 驱动该场景，实际结果与固定预期一致。 */
    @Test
    void queryMapsResponseToOutcome() {
        AiServiceClient client = mock(AiServiceClient.class);
        AiServiceClient.RagQueryResponse resp = new AiServiceClient.RagQueryResponse();
        resp.setAnswer("答案");
        resp.setOutcome("success");
        resp.setOriginalQuery("问题");
        resp.setRewrittenQuery("改写后问题");
        resp.setDegraded(false);
        resp.setProviderUsed("ollama");
        resp.setTokenCost(30);

        AiServiceClient.RagQuerySource src = new AiServiceClient.RagQuerySource();
        src.setChunkId(1L);
        src.setDocumentId(10L);
        src.setKnowledgeBaseId(7L);
        src.setChunkIndex(0);
        src.setContent("相关内容");
        src.setDistance(0.2);
        src.setScore(0.9);
        resp.setSources(List.of(src));
        resp.setEvidenceSources(List.of(src));
        when(client.ragQuery(any())).thenReturn(resp);

        PythonRagPipeline pipeline = pipeline(client, "hybrid-rerank-rewrite", "none",
                1500, false, 0.7, false, mock(RagTraceRecorder.class));
        RagQueryOutcome out = pipeline.query(List.of(7L), "问题", List.of(), 5);

        assertEquals("答案", out.getAnswer());
        assertEquals("改写后问题", out.getRewrittenQuery());
        assertEquals("ollama", out.getProviderUsed());
        assertEquals(30, out.getTokenCost().intValue());
        assertEquals(1, out.getEvidences().size());
        assertEquals(1L, out.getEvidences().get(0).getChunkId());
        assertEquals(7L, out.getEvidences().get(0).getKnowledgeBaseId());
        assertEquals(0.2, out.getEvidences().get(0).getDistance());
        assertEquals(0.9, out.getEvidences().get(0).getScore());
    }

    /** 配置有界文档阅读窗口，核对下游扫描游标与通过准入的正文分别映射。 */
    @Test
    void documentReadingPassesBoundedWindowAndMapsScanCursorSeparatelyFromEvidence() {
        AiServiceClient client = mock(AiServiceClient.class);
        AiServiceClient.RagQueryResponse response = noEvidenceResponse();
        AiServiceClient.DocumentReadPage page = new AiServiceClient.DocumentReadPage();
        page.setOffset(2);
        page.setContentOffset(120);
        page.setNextOffset(2);
        page.setNextContentOffset(240);
        page.setScannedChars(120);
        page.setHasMore(true);
        response.setDocumentPage(page);
        when(client.ragQuery(any())).thenReturn(response);
        PythonRagPipeline pipeline = pipeline(client, "hybrid-rerank-rewrite", "extractive", 1500,
                true, 0.7, true, mock(RagTraceRecorder.class));

        RagQueryOutcome result = pipeline.retrieveDocumentPage(List.of(7L), 10L, "阅读目标",
                new DocumentReadWindow(2, 120, null, null, 1, 120));

        AiServiceClient.RagQueryRequest request = captureRequest(client);
        assertEquals(List.of(7L), request.getKnowledgeBaseIds());
        assertEquals(10L, request.getDocumentId());
        assertTrue(request.isAdmitEvidence());
        assertFalse(request.isGenerate());
        assertFalse(request.isSmallToBigEnabled());
        assertFalse(request.isMmrEnabled());
        assertEquals("none", request.getCompressionMode());
        assertEquals(120, request.getDocumentRead().getContentOffset());
        assertEquals(120, request.getDocumentRead().getWindowChars());
        assertEquals(240, result.getDocumentPage().getNextContentOffset());
        assertTrue(result.getEvidences().isEmpty(), "无获准证据时仍保留已扫描进度");
    }

    /** 通过 {@code client.ragQuery}、{@code RagTraceContext.clear} 驱动该场景，实际结果与固定预期一致。 */
    @Test
    void queryRecordsCallerQuestionAndActualRewriteOnCurrentTrace() {
        AiServiceClient client = mock(AiServiceClient.class);
        AiServiceClient.RagQueryResponse response = noEvidenceResponse();
        response.setRewrittenQuery("实际改写后的问题");
        when(client.ragQuery(any())).thenReturn(response);

        RagTrace trace = RagTrace.builder().build();
        RagTraceContext.set(trace);
        try {
            pipeline(client).query(List.of(7L), "调用方原始问题", List.of(), 5);
        } finally {
            RagTraceContext.clear();
        }

        assertEquals("调用方原始问题", trace.getOriginalQuery());
        assertEquals("实际改写后的问题", trace.getRewrittenQuery());
    }

    /** 通过 {@code client.ragQuery}、{@code RagTraceContext.clear} 驱动该场景，实际结果与固定预期一致。 */
    @Test
    void retrieveOnlyRecordsCallerQuestionWithoutInventingRewrite() {
        AiServiceClient client = mock(AiServiceClient.class);
        when(client.ragQuery(any())).thenReturn(noEvidenceResponse());

        RagTrace trace = RagTrace.builder().build();
        RagTraceContext.set(trace);
        try {
            pipeline(client).retrieveOnly(List.of(7L), "未改写的问题", List.of(), 5);
        } finally {
            RagTraceContext.clear();
        }

        assertEquals("未改写的问题", trace.getOriginalQuery());
        assertNull(trace.getRewrittenQuery(), "无改写结果时必须保留 null，不能伪造为原始问题");
    }

    /** 通过 {@code client.ragQuery}、{@code req.isMmrEnabled} 驱动该场景，布尔状态符合断言，实际结果与固定预期一致。 */
    @Test
    void compressionAndMmrAreForwardedNotRejected() {
        // §5.12.10 第 3 步：原先「python + compression≠none / MMR」会装配期抛 IllegalStateException，
        // 现在两样都搬到 Python 了 → 改为**逐请求透传**（装配不再拒绝）。
        AiServiceClient client = mock(AiServiceClient.class);
        when(client.ragQuery(any())).thenReturn(noEvidenceResponse());

        pipeline(client, "baseline", "extractive", 1200, true, 0.5, true,
                mock(RagTraceRecorder.class)).query(List.of(7L), "问题", List.of(), 5);

        AiServiceClient.RagQueryRequest req = captureRequest(client);
        assertEquals("extractive", req.getCompressionMode());
        assertEquals(1200, req.getTokenBudget());
        assertTrue(req.isMmrEnabled());
        assertEquals(0.5, req.getMmrLambda());
        assertTrue(req.isSmallToBigEnabled());
        assertTrue(req.isGenerate(), "问答路径必须显式要答案");
    }

    /** 通过 {@code client.ragQuery}、{@code req.isGenerate} 驱动该场景，布尔状态符合断言，返回的数据、边界或顺序符合断言。 */
    @Test
    void retrieveOnlyTellsPythonNotToGenerate() {
        // 「只检索」与「问答」唯一的区别就是 generate=false；
        // 少了它，/knowledge/search 就会顺带白跑一次 LLM 生成。
        AiServiceClient client = mock(AiServiceClient.class);
        AiServiceClient.RagQueryResponse response = new AiServiceClient.RagQueryResponse();
        response.setOutcome("success");
        response.setSources(List.of(source(5L)));
        response.setEvidenceSources(List.of()); // retrieveOnly deliberately bypasses the gate
        when(client.ragQuery(any())).thenReturn(response);

        RagQueryOutcome outcome = pipeline(client).retrieveOnly(List.of(7L), "问题", List.of(), 5);
        assertEquals(1, outcome.getSources().size(), "retrieveOnly preserves raw ranked candidates");
        assertTrue(outcome.getEvidences().isEmpty(), "raw candidates never become generation evidence");

        AiServiceClient.RagQueryRequest req = captureRequest(client);
        assertEquals(List.of(7L), req.getKnowledgeBaseIds());
        assertEquals("问题", req.getQuestion());
        assertEquals(5, req.getTopK());
        assertEquals("baseline", req.getVersion());
        assertEquals(20, req.getRecallN());
        assertEquals(60, req.getRrfK(), "缺省档的 RRF 常量应与改造前逐位相同（60）");
        assertEquals(35_000, req.getBudgetMs(), "query 整链预算必须传给 Python");
        assertFalse(req.isGenerate(), "只检索不得触发生成");
        assertFalse(req.isAdmitEvidence(), "旧 retrieveOnly 不得静默开启证据准入");
        assertNull(req.getDocumentId());
    }

    /** 通过 {@code client.ragQuery}、{@code req.isGenerate} 驱动该场景，布尔状态符合断言，实际结果与固定预期一致。 */
    @Test
    void retrieveAdmittedForwardsOptInDocumentScopeAndMapsOnlyAdmittedEvidence() {
        AiServiceClient client = mock(AiServiceClient.class);
        AiServiceClient.RagQueryResponse response = new AiServiceClient.RagQueryResponse();
        response.setOutcome("success");
        response.setSources(List.of(source(5L)));
        response.setEvidenceSources(List.of(source(1L)));
        when(client.ragQuery(any())).thenReturn(response);

        RagQueryOutcome outcome = pipeline(client).retrieveAdmitted(
                List.of(7L), "问题", List.of(), 5, 42L);

        AiServiceClient.RagQueryRequest req = captureRequest(client);
        assertFalse(req.isGenerate(), "准入检索不生成答案");
        assertTrue(req.isAdmitEvidence(), "Agent 检索必须显式 opt-in 证据准入");
        assertEquals(42L, req.getDocumentId());
        assertEquals(1, outcome.getSources().size(), "原始候选保留用于诊断");
        assertEquals(5L, outcome.getSources().get(0).getChunkId());
        assertEquals(1, outcome.getEvidences().size(), "Agent 只消费获准证据");
        assertEquals(1L, outcome.getEvidences().get(0).getChunkId());
    }

    /** 通过 {@code client.ragQuery}、{@code source} 驱动该场景，抛出 {@code RagQueryFailureException}，实际结果与固定预期一致。 */
    @Test
    void retrieveAdmittedRejectsResponseWithoutEvidenceAdmissionContract() {
        AiServiceClient client = mock(AiServiceClient.class);
        AiServiceClient.RagQueryResponse legacy = new AiServiceClient.RagQueryResponse();
        legacy.setOutcome("success");
        legacy.setSources(List.of(source(1L)));
        when(client.ragQuery(any())).thenReturn(legacy);

        RagQueryFailureException failure = assertThrows(RagQueryFailureException.class,
                () -> pipeline(client).retrieveAdmitted(List.of(7L), "q", List.of(), 5));

        assertEquals(503, failure.getStatus().value());
        assertEquals("dependency_unavailable", failure.getOutcome());
        assertEquals("ai_contract_mismatch", failure.getReasonCode());
        assertEquals("ai.response_contract", failure.getFailedStage());
    }

    /** 通过 {@code client.ragQuery}、{@code source} 驱动该场景，抛出 {@code RagQueryFailureException}，实际结果与固定预期一致。 */
    @Test
    void retrieveAdmittedRejectsSuccessWithoutAnyAdmittedEvidence() {
        AiServiceClient client = mock(AiServiceClient.class);
        AiServiceClient.RagQueryResponse response = new AiServiceClient.RagQueryResponse();
        response.setOutcome("success");
        response.setSources(List.of(source(1L)));
        response.setEvidenceSources(List.of());
        when(client.ragQuery(any())).thenReturn(response);

        RagQueryFailureException failure = assertThrows(RagQueryFailureException.class,
                () -> pipeline(client).retrieveAdmitted(List.of(7L), "q", List.of(), 5));

        assertEquals("ai_contract_mismatch", failure.getReasonCode());
    }

    /** 通过 {@code client.ragQuery}、{@code noEvidenceResponse} 驱动该场景，实际结果与固定预期一致。 */
    @Test
    void rrfKIsForwardedFromConfig_notHardcodedInPipeline() {
        // C-1（2026-09-30）「检索参数重审」：RRF 常量 k 从 Python 模块常量改为**逐请求透传**。
        // 若这里没真的把配置值传出去，yml 的 retrieval.rrf-k 就是个死旋钮——
        // 评测扫描会「报告标着 k=10、实际按 60 跑」，正是本仓最忌讳的那类失真。
        AiServiceClient client = mock(AiServiceClient.class);
        when(client.ragQuery(any())).thenReturn(noEvidenceResponse());

        new PythonRagPipeline(client, "hybrid", 20, 10, "none", 1500, false, 0.7, false, 35_000,
                mock(RagTraceRecorder.class)).query(List.of(1L), "q", List.of(), 5);

        assertEquals(10, captureRequest(client).getRrfK());
    }

    /** 用有检索候选但无准入证据的响应驱动转换，核对候选未进入公开证据。 */
    @Test
    void preservesRawSourcesSeparatelyAndFailsClosedWhenAcceptedSourcesAreEmpty() {
        AiServiceClient client = mock(AiServiceClient.class);
        AiServiceClient.RagQueryResponse response = new AiServiceClient.RagQueryResponse();
        response.setOutcome("dependency_unavailable");
        response.setReasonCode("scorer_unavailable");
        response.setSources(List.of(source(1L)));
        response.setEvidenceSources(List.of());
        when(client.ragQuery(any())).thenReturn(response);

        RagQueryOutcome outcome = pipeline(client).query(List.of(7L), "q", List.of(), 5);

        assertEquals(1, outcome.getSources().size(), "原始召回仍供 search/evaluation 使用");
        assertTrue(outcome.getEvidences().isEmpty(), "未通过 gate 的候选不得成为生成来源");
        assertEquals("dependency_unavailable", outcome.getOutcome());
        assertEquals("scorer_unavailable", outcome.getReasonCode());
    }

    /** 通过 {@code client.ragQuery}、{@code source} 驱动该场景，抛出 {@code RagQueryFailureException}，实际结果与固定预期一致。 */
    @Test
    void queryRejectsLegacyResponseWithoutEvidenceAdmissionContract() {
        AiServiceClient client = mock(AiServiceClient.class);
        AiServiceClient.RagQueryResponse legacy = new AiServiceClient.RagQueryResponse();
        legacy.setAnswer("legacy answer");
        legacy.setSources(List.of(source(1L)));
        when(client.ragQuery(any())).thenReturn(legacy);

        RagQueryFailureException failure = assertThrows(RagQueryFailureException.class,
                () -> pipeline(client).query(List.of(7L), "q", List.of(), 5));

        assertEquals(503, failure.getStatus().value());
        assertEquals("dependency_unavailable", failure.getOutcome());
        assertEquals("ai_contract_mismatch", failure.getReasonCode());
        assertEquals("ai.response_contract", failure.getFailedStage());
    }

    /** 让上游用 HTTP 200 返回业务超时，核对 Java 将其映射为超时故障。 */
    @Test
    void retrieveOnlyMapsHttp200TimeoutOutcomeToGatewayTimeout() {
        AiServiceClient client = mock(AiServiceClient.class);
        AiServiceClient.RagQueryResponse response = new AiServiceClient.RagQueryResponse();
        response.setOutcome("timeout");
        response.setReasonCode("query_deadline_exceeded");
        response.setFailedStage("retrieval");
        response.setRequestId("req-search-timeout");
        response.setSources(List.of());
        response.setEvidenceSources(List.of());
        when(client.ragQuery(any())).thenReturn(response);

        RagQueryFailureException failure = assertThrows(RagQueryFailureException.class,
                () -> pipeline(client).retrieveOnly(List.of(7L), "q", List.of(), 5));

        assertEquals(504, failure.getStatus().value());
        assertEquals("timeout", failure.getOutcome());
        assertEquals("query_deadline_exceeded", failure.getReasonCode());
        assertEquals("retrieval", failure.getFailedStage());
        assertEquals("req-search-timeout", failure.getRequestId());
    }

    /**
     * 返回当前计算采用的 {@code source}。
     *
     * @param chunkId 本次调用传入的 {@code Long}
     * @return 当前记录中的 {@code source}
     */
    private static AiServiceClient.RagQuerySource source(Long chunkId) {
        AiServiceClient.RagQuerySource source = new AiServiceClient.RagQuerySource();
        source.setChunkId(chunkId);
        source.setDocumentId(10L);
        source.setKnowledgeBaseId(7L);
        source.setContent("raw candidate");
        return source;
    }

    /** 通过 {@code client.ragQuery}、{@code pipeline.query} 驱动该场景，实际结果与固定预期一致。 */
    @Test
    void recordsPythonReportedStagesIntoRagTrace() {
        // 决策文档 §5.12.5：python 档原先 spanCount=0（管线搬走、观测没跟着搬）。
        // 本用例守住「Python 上报的 stages[] 能还原成 RagTrace 的 span」这条回填链路，
        // 并确保**未登记的阶段名被忽略**而不是塞进一个错枚举。
        AiServiceClient client = mock(AiServiceClient.class);
        AiServiceClient.RagQueryResponse resp = new AiServiceClient.RagQueryResponse();
        resp.setAnswer("答案");
        resp.setOutcome("no_evidence");
        resp.setSources(List.of());
        resp.setEvidenceSources(List.of());
        resp.setStages(List.of(
                pythonStage("vector_retrieval", 12),
                pythonStage("generate", 800),
                pythonStage("evidence_gate", 25),
                pythonStage("未登记的名字", 1)));
        when(client.ragQuery(any())).thenReturn(resp);

        PythonRagPipeline pipeline = pipeline(client, "baseline", "none", 1500,
                false, 0.7, false, new RagTraceRecorder());

        RagTrace trace = RagTrace.builder().build();
        RagTraceContext.set(trace);
        try {
            pipeline.query(List.of(1L), "问题", List.of(), 5);
        } finally {
            RagTraceContext.clear();
        }

        assertEquals(3, trace.getSpans().size(), "证据审查必须可见，未登记的阶段名必须被忽略");
        assertEquals(TraceStage.VECTOR_RETRIEVAL, trace.getSpans().get(0).getStage());
        assertEquals(12L, trace.getSpans().get(0).getDurationMs());
        assertEquals(TraceStage.LLM_GENERATION, trace.getSpans().get(1).getStage());
        assertEquals(800L, trace.getSpans().get(1).getDurationMs());
        assertEquals("EVIDENCE_GATE", trace.getSpans().get(2).getStage().name());
        assertEquals(25L, trace.getSpans().get(2).getDurationMs());
    }

    /** 通过 {@code client.ragQuery}、{@code pipeline} 驱动该场景，实际结果与固定预期一致。 */
    @Test
    void carriesCompressionStatsOntoOutcome() {
        // §5.12.10 第 3 步：压缩搬进 Python 后，评测报告的压缩三列靠回传填 —— 不回传那几列就恒空。
        AiServiceClient client = mock(AiServiceClient.class);
        AiServiceClient.RagQueryResponse resp = new AiServiceClient.RagQueryResponse();
        resp.setAnswer("答案");
        resp.setOutcome("no_evidence");
        resp.setSources(List.of());
        resp.setEvidenceSources(List.of());
        AiServiceClient.RagQueryCompression c = new AiServiceClient.RagQueryCompression();
        c.setMode("extractive");
        c.setTokensBefore(2000);
        c.setTokensAfter(1200);
        c.setRatio(0.6);
        resp.setCompression(c);
        when(client.ragQuery(any())).thenReturn(resp);

        RagQueryOutcome out = pipeline(client).query(List.of(1L), "问题", List.of(), 5);

        assertNotNull(out.getCompression());
        assertEquals("extractive", out.getCompression().getMode());
        assertEquals(2000, out.getCompression().getTokensBefore());
        assertEquals(1200, out.getCompression().getTokensAfter());
        assertEquals(0.6, out.getCompression().getRatio());
    }

    /** 通过 {@code client.ragQuery}、{@code pipeline} 驱动该场景，核对输出和依赖调用。 */
    @Test
    void compressionAbsentWhenPythonSendsNone() {
        AiServiceClient client = mock(AiServiceClient.class);
        AiServiceClient.RagQueryResponse resp = new AiServiceClient.RagQueryResponse();
        resp.setOutcome("no_evidence");
        resp.setSources(List.of());
        resp.setEvidenceSources(List.of());
        when(client.ragQuery(any())).thenReturn(resp);

        assertNull(pipeline(client).query(List.of(1L), "问题", List.of(), 5).getCompression());
    }

    /**
     * 返回当前计算采用的 {@code s}。
     *
     * @param name 本次调用传入的 {@code String}
     * @param durationMs 本次调用传入的 {@code long}
     * @return 当前记录中的 {@code s}
     */
    private static AiServiceClient.RagQueryStage pythonStage(String name, long durationMs) {
        AiServiceClient.RagQueryStage s = new AiServiceClient.RagQueryStage();
        s.setStage(name);
        s.setDurationMs(durationMs);
        s.setSuccess(true);
        return s;
    }

    // ---------- 授权边界：null 收敛成空集合（default-deny 的最后一道） ----------

    /** 通过 {@code client.ragQuery}、{@code noEvidenceResponse} 驱动该场景，布尔状态符合断言，返回的数据、边界或顺序符合断言。 */
    @Test
    void nullKnowledgeBaseIdsIsNormalizedToEmptyList_notForwardedAsNull() {
        AiServiceClient client = mock(AiServiceClient.class);
        when(client.ragQuery(any())).thenReturn(noEvidenceResponse());

        pipeline(client).query(null, "问题", List.of(), 5);

        AiServiceClient.RagQueryRequest req = captureRequest(client);
        assertNotNull(req.getKnowledgeBaseIds(), "不得把 null 透传给 Python");
        assertTrue(req.getKnowledgeBaseIds().isEmpty(), "null 必须收敛成空集合（= 读不到任何库）");
    }

    /** 通过 {@code client.ragQuery}、{@code noEvidenceResponse} 驱动该场景，布尔状态符合断言，返回的数据、边界或顺序符合断言。 */
    @Test
    void retrieveOnlyAlsoNormalizesNullKnowledgeBaseIds() {
        AiServiceClient client = mock(AiServiceClient.class);
        when(client.ragQuery(any())).thenReturn(noEvidenceResponse());

        pipeline(client).retrieveOnly(null, "问题", List.of(), 5);

        assertTrue(captureRequest(client).getKnowledgeBaseIds().isEmpty());
    }

    /** 通过 {@code client.ragQuery}、{@code pipeline} 驱动该场景，抛出 {@code RagQueryFailureException}，实际结果与固定预期一致。 */
    @Test
    void internalQueryCapacityRejectionMapsToPublicDependencyFailureWithDiagnostics() {
        AiServiceClient client = mock(AiServiceClient.class);
        String body = "{\"detail\":{\"code\":\"query_capacity_rejected\","
                + "\"failedStage\":\"admission\",\"requestId\":\"req-capacity-7\"}}";
        when(client.ragQuery(any())).thenThrow(new RestClientResponseException(
                "Too Many Requests", 429, "Too Many Requests", null,
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));

        RagQueryFailureException failure = assertThrows(RagQueryFailureException.class,
                () -> pipeline(client).query(List.of(7L), "q", List.of(), 5));

        assertEquals(503, failure.getStatus().value(), "内部 admission 429 对调用方是服务不可用");
        assertEquals("dependency_unavailable", failure.getOutcome());
        assertEquals("query_capacity_rejected", failure.getReasonCode());
        assertEquals("admission", failure.getFailedStage());
        assertEquals("req-capacity-7", failure.getRequestId());
    }

    // ---------- 装配期白名单：档位写错必须启动就崩 ----------

    /** 通过 {@code pipeline} 驱动该场景，抛出 {@code IllegalStateException}，布尔状态符合断言。 */
    @Test
    void illegalRetrievalVersionFailsAtConstruction() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> pipeline(mock(AiServiceClient.class), "vector-only", "none", 1500,
                        false, 0.7, false, mock(RagTraceRecorder.class)));
        assertTrue(ex.getMessage().contains("vector-only"), "错误消息要带上非法值，实际=" + ex.getMessage());
    }

    /** 通过 {@code pipeline} 驱动该场景，抛出 {@code IllegalStateException}，布尔状态符合断言。 */
    @Test
    void illegalCompressionModeFailsAtConstruction() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> pipeline(mock(AiServiceClient.class), "baseline", "llm", 1500,
                        false, 0.7, false, mock(RagTraceRecorder.class)));
        assertTrue(ex.getMessage().contains("llm"), "错误消息要带上非法值，实际=" + ex.getMessage());
    }

    /** 通过 {@code pipeline} 驱动该场景，核对输出和依赖调用。 */
    @Test
    void legalVersionsAndCompressionModesConstructFine() {
        for (String version : List.of("baseline", "hybrid", "hybrid-rerank", "hybrid-rerank-rewrite")) {
            for (String mode : List.of("none", "extractive")) {
                assertNotNull(pipeline(mock(AiServiceClient.class), version, mode, 1500,
                        false, 0.7, false, mock(RagTraceRecorder.class)));
            }
        }
    }
}
