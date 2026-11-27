/* 检查同步、检索和流式入口选取管线与策略时对授权范围的传递。 */

package com.pony.knowledge.controller;

import com.pony.knowledge.exception.GlobalExceptionHandler;
import com.pony.knowledge.observability.*;
import com.pony.knowledge.service.KnowledgeBaseAccessService;
import com.pony.knowledge.service.ai.AiServiceClient;
import com.pony.knowledge.service.rag.ConversationService;
import com.pony.knowledge.service.rag.PythonRagPipeline;
import com.pony.knowledge.service.rag.RagService;
import com.pony.knowledge.service.rag.RagSessionService;
import com.pony.knowledge.service.rag.RagStreamClient;
import com.pony.knowledge.service.rag.query.ChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pony.knowledge.utils.CurrentUserUtil;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RagPipelineSelectionControllerTest {
    private final AiServiceClient client = mock(AiServiceClient.class);
    private final KnowledgeBaseAccessService access = mock(KnowledgeBaseAccessService.class);
    private final CurrentUserUtil user = mock(CurrentUserUtil.class);
    private final ConversationService conversations = mock(ConversationService.class);
    private final TraceStore traceStore = mock(TraceStore.class);
    private final RagStreamClient streamClient = mock(RagStreamClient.class);
    private final RagSessionService sessions = mock(RagSessionService.class);

    /**
     * 构造 {@code MockMvc} 供测试使用，使用 {@code user.getUserId}、{@code access.resolveReadableIds}。
     * 由 {@code askOmissionUsesServerDefaultAndEchoesBothAxes}、{@code askExplicitEngineKeepsExactlyAuthorizedScope} 调用。
     * @param defaultEngine 本用例传入的{@code defaultEngine}
     * 返回 {@code response}。
     */
    private MockMvc mvc(String defaultEngine) {
        when(user.getUserId()).thenReturn(42L);
        when(access.resolveReadableIds(42L)).thenReturn(Set.of(7L));
        when(access.canRead(42L, 7L)).thenReturn(true);
        when(conversations.getHistory(any())).thenReturn(List.of());
        AiServiceClient.RagQueryResponse response = new AiServiceClient.RagQueryResponse();
        response.setOutcome("no_evidence");
        response.setReasonCode("no_match");
        response.setSources(List.of());
        response.setEvidenceSources(List.of());
        when(client.ragQuery(any())).thenAnswer(invocation -> {
            AiServiceClient.RagQueryRequest request = invocation.getArgument(0);
            response.setPipelineEngine(request.getPipelineEngine());
            response.setRetrievalVersion(request.getVersion());
            response.setEvidenceMode(request.getEvidenceMode());
            return response;
        });
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RagMetrics metrics = new RagMetrics(registry, "hybrid-rerank-rewrite");
        RagPerformanceBudget budget = new RagPerformanceBudget(metrics,
                1000L, 1000L, 1000L, 1000L, 1000L, 10L, 0.01);
        RagStageTracer tracer = new RagStageTracer(ObservationRegistry.create(), registry, metrics,
                budget, new RagTraceRecorder(), null, 2.0, traceStore);
        PythonRagPipeline pipeline = new PythonRagPipeline(client, defaultEngine,
                "hybrid-rerank-rewrite", 20, 60, "none", 1500,
                false, 0.7, false, 35_000, new RagTraceRecorder());
        RagService service = new RagService(access, user, conversations, tracer, pipeline);
        return MockMvcBuilders.standaloneSetup(new KnowledgeChatController(service),
                new RagController(service), new KnowledgeSearchController(pipeline, access, user, tracer),
                new RagSessionController(sessions, service, new ObjectMapper(), user),
                new KnowledgeChatStreamController(user, access, pipeline, streamClient, new ObjectMapper(), Runnable::run, 285000))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    /**
     * 取出模拟 AI 客户端收到的请求，供测试核对字段。
     * 由 {@code askOmissionUsesServerDefaultAndEchoesBothAxes}、{@code askExplicitEngineKeepsExactlyAuthorizedScope} 调用。
     * 返回 {@code request.getValue()}。
     */
    private AiServiceClient.RagQueryRequest capturedRequest() {
        ArgumentCaptor<AiServiceClient.RagQueryRequest> request = ArgumentCaptor.forClass(AiServiceClient.RagQueryRequest.class);
        verify(client).ragQuery(request.capture());
        return request.getValue();
    }

    /** 同步全库追问把租约范围和已完成历史送到所选引擎。 */
    @ParameterizedTest
    @ValueSource(strings = {"fixed", "langchain"})
    void allLibrarySessionUsesAuthorizedScopeAndSavedHistory(String engine) throws Exception {
        UUID id = UUID.randomUUID();
        var scope = List.of(7L, 9L);
        var lease = new RagSessionService.TurnLease(id, 42L, UUID.randomUUID(), scope, false);
        var previous = new ChatMessage();
        previous.setRole("user");
        previous.setContent("previous question");
        when(sessions.begin(42L, id)).thenReturn(lease);
        when(sessions.context(lease)).thenReturn(List.of(previous));

        mvc("fixed").perform(post("/rag/sessions/{id}/ask", id).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"follow up","pipelineEngine":"%s"}
                                """.formatted(engine)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pipelineEngine").value(engine));

        var request = capturedRequest();
        assertEquals(scope, request.getKnowledgeBaseIds());
        assertEquals(engine, request.getPipelineEngine());
        assertEquals("previous question", request.getHistory().get(0).getContent());
        verify(sessions).end(lease);
    }

    /** 核对 HTTP 200，并确认{@code $.pipelineEngine} 为 {@code "langchain"}、{@code $.pipelineEngine} 为 {@code "langchain"}。 */
    @Test
    void askOmissionUsesServerDefaultAndEchoesBothAxes() throws Exception {
        mvc("langchain").perform(post("/knowledge/ask").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"q\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pipelineEngine").value("langchain"))
                .andExpect(jsonPath("$.retrievalVersion").value("hybrid-rerank-rewrite"));
        assertEquals("langchain", capturedRequest().getPipelineEngine());
        verify(access).resolveReadableIds(42L);
    }

    /** 核对 HTTP 200。调用路径经过 {@code access.resolveReadableIds}。 */
    @Test
    void askExplicitEngineKeepsExactlyAuthorizedScope() throws Exception {
        MockMvc mvc = mvc("fixed");
        when(access.resolveReadableIds(42L)).thenReturn(Set.of());
        mvc.perform(post("/knowledge/ask").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"q\",\"pipelineEngine\":\"langchain\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sources").isEmpty());
        AiServiceClient.RagQueryRequest request = capturedRequest();
        assertEquals("langchain", request.getPipelineEngine());
        assertEquals(List.of(), request.getKnowledgeBaseIds());
    }

    /** 在单库会话问答中核对库的读取权限、所选管线与实际返回的检索版本。 */
    @Test
    void chatSelectionIsForwardedAfterAuthorizationAndReturnsVersion() throws Exception {
        mvc("fixed").perform(post("/rag/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"knowledgeBaseId\":7,\"conversationId\":\"c1\",\"question\":\"q\",\"pipelineEngine\":\"langchain\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pipelineEngine").value("langchain"))
                .andExpect(jsonPath("$.retrievalVersion").value("hybrid-rerank-rewrite"));
        assertEquals(List.of(7L), capturedRequest().getKnowledgeBaseIds());
        verify(access).canRead(42L, 7L);
    }

    /** 核对 HTTP 200，并确认{@code $.pipelineEngine} 为 {@code "langchain"}。调用路径经过 {@code request.getEvidenceMode}。 */
    @Test
    void lightStrategyOverridesDeploymentForLangchainAsk() throws Exception {
        mvc("fixed").perform(post("/knowledge/ask").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"q","pipelineEngine":"langchain","strategy":"light"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pipelineEngine").value("langchain"))
                .andExpect(jsonPath("$.retrievalVersion").value("hybrid"))
                .andExpect(jsonPath("$.evidenceMode").value("support"));
        var request = capturedRequest();
        assertEquals("support", request.getEvidenceMode());
        assertEquals(List.of(7L), request.getKnowledgeBaseIds());
    }

    /** 核对 HTTP 200，并确认{@code $.retrievalVersion} 为 {@code "hybrid-rerank"}。 */
    @Test
    void chatForwardsExplicitScoreModeAndKeepsTheAuthorizedScope() throws Exception {
        mvc("fixed").perform(post("/rag/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"knowledgeBaseId":7,"conversationId":"c1","question":"q","strategy":"rerank_score"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retrievalVersion").value("hybrid-rerank"))
                .andExpect(jsonPath("$.evidenceMode").value("score"));
        assertEquals(List.of(7L), capturedRequest().getKnowledgeBaseIds());
        verify(access).canRead(42L, 7L);
    }

    /** 给流式问答指定检索预设，确认实际引擎、授权范围和策略随请求正确转发。 */
    @Test
    void streamForwardsExplicitPresetWithoutChangingEngineOrScope() throws Exception {
        mvc("langchain").perform(post("/knowledge/ask/stream").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"q","strategy":"rerank_support"}
                                """))
                .andExpect(request().asyncStarted());
        var capture = ArgumentCaptor.forClass(AiServiceClient.RagQueryRequest.class);
        verify(streamClient).stream(capture.capture(), any());
        assertEquals("fixed", capture.getValue().getPipelineEngine());
        assertEquals("hybrid-rerank", capture.getValue().getVersion());
        assertEquals("support", capture.getValue().getEvidenceMode());
        assertEquals(List.of(7L), capture.getValue().getKnowledgeBaseIds());
    }

    /** 核对单轮 SSE 按授权范围发送 LangChain 请求，并转发同一引擎的增量和终态。 */
    @Test
    void explicitLangchainStreamsWithinTheAuthorizedScope() throws Exception {
        MockMvc mvc = mvc("fixed");
        doAnswer(call -> {
            var output = call.<RagStreamClient.FrameHandler>getArgument(1);
            output.onFrame("delta", """
                    {"text":"draft","provisional":true}
                    """);
            output.onFrame("done", """
                    {"outcome":"success","answer":"complete","pipelineEngine":"langchain"}
                    """);
            return null;
        }).when(streamClient).stream(any(), any());

        var result = mvc.perform(post("/knowledge/ask/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"q","pipelineEngine":"langchain"}
                                """))
                .andExpect(request().asyncStarted()).andReturn();

        var capture = ArgumentCaptor.forClass(AiServiceClient.RagQueryRequest.class);
        verify(streamClient).stream(capture.capture(), any());
        assertEquals("langchain", capture.getValue().getPipelineEngine());
        assertEquals(List.of(7L), capture.getValue().getKnowledgeBaseIds());
        String events = result.getResponse().getContentAsString();
        assertTrue(events.indexOf("event:delta") >= 0, events);
        assertTrue(events.indexOf("event:done") > events.indexOf("event:delta"), events);
        assertTrue(events.contains("\"provisional\":true"), events);
        assertTrue(events.contains("\"pipelineEngine\":\"langchain\""), events);
    }

    /** 核对 HTTP 400。 */
    @Test
    void invalidStrategyDoesNotReachPython() throws Exception {
        mvc("fixed").perform(post("/knowledge/ask").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"q\",\"strategy\":\"bypass\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(client, streamClient, access);
    }

    /** 用未授权知识库发起问答，确认 Python 未被调用且 Trace 保留请求选择的策略。 */
    @Test
    void forbiddenChatCannotCallPythonAndTraceStillRecordsSelection() throws Exception {
        MockMvc mvc = mvc("fixed");
        when(access.canRead(42L, 7L)).thenReturn(false);
        mvc.perform(post("/rag/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"knowledgeBaseId\":7,\"conversationId\":\"c1\",\"question\":\"q\",\"pipelineEngine\":\"langchain\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(client, conversations);
        ArgumentCaptor<RagTrace> trace = ArgumentCaptor.forClass(RagTrace.class);
        verify(traceStore).save(trace.capture(), eq("chat"));
        assertEquals("langchain", trace.getValue().getRequestedPipelineEngine());
        assertEquals("hybrid-rerank-rewrite", trace.getValue().getRequestedRetrievalVersion());
        assertNull(trace.getValue().getPipelineEngine());
        assertNull(trace.getValue().getRetrievalVersion());
        assertNull(trace.getValue().getProviderUsed());
    }

    /** 核对 HTTP 200。调用路径经过 {@code request.getPipelineEngine}。 */
    @Test
    void searchSelectionUsesRetrievalOnlyAndAuthorizedScope() throws Exception {
        mvc("fixed").perform(get("/knowledge/search").param("query", "q")
                        .param("pipelineEngine", "langchain"))
                .andExpect(status().isOk());
        AiServiceClient.RagQueryRequest request = capturedRequest();
        assertEquals("langchain", request.getPipelineEngine());
        assertEquals(List.of(7L), request.getKnowledgeBaseIds());
        assertFalse(request.isGenerate());
    }

    /** 核对 HTTP 400。调用路径经过 {@code mvc.perform}。 */
    @Test
    void invalidEnginesAre400ForAllPublicEntryPoints() throws Exception {
        MockMvc mvc = mvc("fixed");
        mvc.perform(post("/knowledge/ask").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"q\",\"pipelineEngine\":\"auto\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/rag/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"knowledgeBaseId\":7,\"conversationId\":\"c1\",\"question\":\"q\",\"pipelineEngine\":\"\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/knowledge/search").param("query", "q").param("pipelineEngine", "auto"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(client, access, conversations);
    }
}
