/* 在 RAG 流式入口核对会话历史、获准证据、超时和客户端断开。 */

package com.pony.knowledge.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pony.knowledge.service.KnowledgeBaseAccessService;
import com.pony.knowledge.service.ai.AiServiceClient;
import com.pony.knowledge.service.rag.PythonRagPipeline;
import com.pony.knowledge.service.rag.RagSessionService;
import com.pony.knowledge.service.rag.RagStreamClient;
import com.pony.knowledge.service.rag.RagStrategy;
import com.pony.knowledge.service.rag.query.ChatMessage;
import com.pony.knowledge.utils.CurrentUserUtil;
import jakarta.servlet.AsyncEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class KnowledgeChatStreamCancellationTest {
    private final RagStreamClient stream = mock(RagStreamClient.class);
    private final RagSessionService sessions = mock(RagSessionService.class);
    private final PythonRagPipeline pipeline = mock(PythonRagPipeline.class);

    /**
     * 构造 {@code MockMvc} 供测试使用，使用 {@code users.getUserId}、{@code access.resolveReadableIds}。
     * 由 {@code allLibraryStreamUsesSavedHistoryAndRecordsOnlyAdmittedEvidence}、{@code transportTimeoutRemainsTimeoutAndNeverEchoesAnUpstreamMessage} 调用。
     * @param executor 本用例传入的{@code executor}
     * 返回 {@code MockMvcBuilders.standaloneSetup(controller).build()}。
     */
    private MockMvc mvc(Executor executor) {
        CurrentUserUtil users = mock(CurrentUserUtil.class);
        KnowledgeBaseAccessService access = mock(KnowledgeBaseAccessService.class);
        when(users.getUserId()).thenReturn(7L);
        when(access.resolveReadableIds(7L)).thenReturn(Set.of(10L));
        when(pipeline.buildRequest(eq(List.of(10L)), anyString(), anyList(), eq(5), eq(true), eq("fixed")))
                .thenReturn(new AiServiceClient.RagQueryRequest());
        var controller = new KnowledgeChatStreamController(users, access, pipeline,
                stream, new ObjectMapper(), executor, 40000);
        controller.setRagSessions(sessions);
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    /** 向全库会话发送后续问题，确认两种引擎都使用已保存历史且只持久化获准证据。 */
    @ParameterizedTest
    @ValueSource(strings = {"fixed", "langchain"})
    void allLibraryStreamUsesSavedHistoryAndRecordsOnlyAdmittedEvidence(String engine) throws Exception {
        UUID id = UUID.randomUUID();
        var scope = List.of(10L, 11L);
        var lease = new RagSessionService.TurnLease(id, 7L, UUID.randomUUID(), scope, false);
        var previous = new ChatMessage();
        previous.setRole("user");
        previous.setContent("previous question");
        var query = new AiServiceClient.RagQueryRequest();
        query.setQuestion("follow up");
        when(sessions.begin(7L, id)).thenReturn(lease);
        when(sessions.context(lease)).thenReturn(List.of(previous));
        when(pipeline.buildRequest(scope, "follow up", List.of(previous), 5, engine, RagStrategy.DEFAULT))
                .thenReturn(query);
        doAnswer(call -> {
            RagStreamClient.FrameHandler output = call.getArgument(1);
            output.onFrame("done", """
                    {"outcome":"success","answer":"yes","sources":[{"chunkId":1}],"evidenceSources":[{"chunkId":2}]}
                    """);
            return null;
        }).when(stream).stream(eq(query), any());

        MvcResult result = mvc(Runnable::run).perform(post("/knowledge/sessions/{id}/ask/stream", id)
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"question":"follow up","pipelineEngine":"%s"}
                        """.formatted(engine))).andReturn();

        assertTrue(result.getResponse().getContentAsString().contains("event:done"));
        var saved = ArgumentCaptor.forClass(JsonNode.class);
        verify(sessions).complete(eq(lease), eq("follow up"), saved.capture());
        assertEquals(2, saved.getValue().path("sources").get(0).path("chunkId").asInt());
        assertFalse(saved.getValue().has("evidenceSources"));
        verify(sessions).end(lease);
    }

    /** 核对 {@code events.contains("sensitive upstream response")} 为假。 */
    @Test void transportTimeoutRemainsTimeoutAndNeverEchoesAnUpstreamMessage() throws Exception {
        doThrow(new RagStreamClient.RagStreamException(504, "sensitive upstream response"))
                .when(stream).stream(any(), any());
        MvcResult result = mvc(Runnable::run).perform(post("/knowledge/ask/stream")
                .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"test\"}")).andReturn();
        String events = result.getResponse().getContentAsString();
        assertTrue(events.contains("timeout"), events);
        assertFalse(events.contains("sensitive upstream response"));
        assertNull(result.getAsyncResult(1000));
    }

    /** 核对 {@code events.contains("safe transport message")} 为假。 */
    @Test void nonSuccessTransportFieldsReachTheTerminalSseErrorFrame() throws Exception {
        doThrow(new RagStreamClient.RagStreamException(503, "dependency_unavailable",
                "retrieval_unavailable", "retrieval", "req-safe-123", "safe transport message"))
                .when(stream).stream(any(), any());

        MvcResult result = mvc(Runnable::run).perform(post("/knowledge/ask/stream")
                .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"test\"}")).andReturn();

        String events = result.getResponse().getContentAsString();
        assertTrue(events.contains("\"outcome\":\"dependency_unavailable\""), events);
        assertTrue(events.contains("\"reasonCode\":\"retrieval_unavailable\""), events);
        assertTrue(events.contains("\"failedStage\":\"retrieval\""), events);
        assertTrue(events.contains("\"requestId\":\"req-safe-123\""), events);
        assertFalse(events.contains("safe transport message"));
        assertNull(result.getAsyncResult(1000));
    }

    /** 在尚未收到事件帧时模拟客户端断开，确认后台请求和工作线程及时结束。 */
    @Test void downstreamDisconnectInterruptsTheWorkerEvenWhenNoFrameArrives() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1), interrupted = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            try { new CountDownLatch(1).await(10, TimeUnit.SECONDS); }
            catch (InterruptedException cancelled) { interrupted.countDown(); Thread.currentThread().interrupt(); }
            return null;
        }).when(stream).stream(any(), any());
        try {
            MvcResult result = mvc(executor).perform(post("/knowledge/ask/stream")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"test\"}")).andReturn();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            MockAsyncContext async = (MockAsyncContext) result.getRequest().getAsyncContext();
            for (var listener : async.getListeners()) listener.onError(new AsyncEvent(async, new IOException("client gone")));
            assertTrue(interrupted.await(2, TimeUnit.SECONDS), "disconnect must cancel a blocked upstream wait");
        } finally { executor.shutdownNow(); }
    }

    /** 读取部分正文后断开客户端，确认上游流停止并释放工作线程。 */
    @Test void disconnectAfterPartialOutputStopsUpstreamAndReleasesTheWorker() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch partial = new CountDownLatch(1), interrupted = new CountDownLatch(1);
        doAnswer(call -> {
            RagStreamClient.FrameHandler output = call.getArgument(1);
            output.onFrame("delta", "{\"text\":\"partial\"}");
            partial.countDown();
            try { new CountDownLatch(1).await(10, TimeUnit.SECONDS); }
            catch (InterruptedException cancelled) { interrupted.countDown(); Thread.currentThread().interrupt(); }
            return null;
        }).when(stream).stream(any(), any());
        try {
            MvcResult result = mvc(executor).perform(post("/knowledge/ask/stream")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"test\"}")).andReturn();
            assertTrue(partial.await(2, TimeUnit.SECONDS));
            assertTrue(result.getResponse().getContentAsString().contains("partial"));
            MockAsyncContext async = (MockAsyncContext) result.getRequest().getAsyncContext();
            for (var listener : async.getListeners()) listener.onError(new AsyncEvent(async, new IOException("client gone")));
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            assertEquals("reusable", executor.submit(() -> "reusable").get(2, TimeUnit.SECONDS));
            assertFalse(result.getResponse().getContentAsString().contains("event:error"));
        } finally { executor.shutdownNow(); }
    }

    /** 显式中止含临时正文的 LangChain 回合，在取消确认后重发且只保存新回合。 */
    @Test void cancelledLangchainSessionCanBeRetriedWithoutSavingTheDraft() throws Exception {
        UUID id = UUID.randomUUID();
        var lease = new RagSessionService.TurnLease(id, 7L, UUID.randomUUID(), List.of(10L), true);
        var retryLease = new RagSessionService.TurnLease(id, 7L, UUID.randomUUID(), List.of(10L), true);
        var query = new AiServiceClient.RagQueryRequest();
        query.setQuestion("test");
        query.setPipelineEngine("langchain");
        when(sessions.begin(7L, id)).thenReturn(lease, retryLease);
        when(sessions.context(lease)).thenReturn(List.of());
        when(sessions.context(retryLease)).thenReturn(List.of());
        when(pipeline.buildRequest(List.of(10L), "test", List.of(), 5, "langchain", RagStrategy.DEFAULT))
                .thenReturn(query);
        CountDownLatch partial = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        doAnswer(call -> {
            var output = call.<RagStreamClient.FrameHandler>getArgument(1);
            output.onFrame("delta", """
                    {"text":"draft","provisional":true}
                    """);
            partial.countDown();
            try {
                new CountDownLatch(1).await(10, TimeUnit.SECONDS);
            } catch (InterruptedException cancelled) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return null;
        }).doAnswer(call -> {
            var output = call.<RagStreamClient.FrameHandler>getArgument(1);
            output.onFrame("done", """
                    {"outcome":"success","answer":"complete","pipelineEngine":"langchain",
                    "sources":[{"chunkId":1}],"evidenceSources":[{"chunkId":2}]}
                    """);
            return null;
        }).when(stream).stream(eq(query), any());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            MockMvc mvc = mvc(executor);
            MvcResult first = mvc.perform(post("/knowledge/sessions/{id}/ask/stream", id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"question\":\"test\",\"pipelineEngine\":\"langchain\"}")).andReturn();
            assertTrue(partial.await(2, TimeUnit.SECONDS));
            assertTrue(first.getResponse().getContentAsString().contains("\"provisional\":true"));
            verify(sessions, never()).complete(eq(lease), anyString(), any());
            assertEquals(lease.token().toString(), first.getResponse().getHeader("X-Rag-Turn-Id"));
            MvcResult cancel = mvc.perform(post("/knowledge/sessions/{id}/turns/{turnId}/cancel", id, lease.token()))
                    .andReturn();
            assertEquals(204, cancel.getResponse().getStatus());
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            verify(sessions).end(lease);
            verify(sessions).end(7L, id, lease.token());

            MvcResult retry = mvc.perform(post("/knowledge/sessions/{id}/ask/stream", id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"question\":\"test\",\"pipelineEngine\":\"langchain\"}")).andReturn();
            assertNull(retry.getAsyncResult(2000));
            assertTrue(retry.getResponse().getContentAsString().contains("event:done"));
            var saved = ArgumentCaptor.forClass(JsonNode.class);
            verify(sessions).complete(eq(retryLease), eq("test"), saved.capture());
            assertEquals("complete", saved.getValue().path("answer").asText());
            assertEquals("langchain", saved.getValue().path("pipelineEngine").asText());
            assertEquals(2, saved.getValue().path("sources").get(0).path("chunkId").asInt());
            verify(sessions, never()).complete(eq(lease), anyString(), any());
        } finally {
            executor.shutdownNow();
        }
    }
}
