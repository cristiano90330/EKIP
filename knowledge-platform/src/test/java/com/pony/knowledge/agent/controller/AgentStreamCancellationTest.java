/* 验证 SSE 出错或断开会关闭 Agent 执行并释放工作线程。 */

package com.pony.knowledge.agent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pony.knowledge.agent.model.AgentResponse;
import com.pony.knowledge.agent.model.StopReason;
import com.pony.knowledge.agent.service.AgentService;
import com.pony.knowledge.agent.stream.SseAgentEventSink;
import jakarta.servlet.AsyncEvent;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class AgentStreamCancellationTest {
    /** 模拟 Agent 运行异常，检查终态错误帧结束 SSE 且无需容器再次分发异常。 */
    @Test
    void terminalErrorFrameCompletesWithoutAnotherExceptionDispatch() throws Exception {
        AgentService service = mock(AgentService.class);
        when(service.chatStream(any(), any())).thenThrow(new IllegalStateException("dependency failed"));
        var mvc = MockMvcBuilders.standaloneSetup(new AgentController(service,
                new ObjectMapper(), Runnable::run, 40000, 0)).build();

        MvcResult result = mvc.perform(post("/agent/chat/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"test\",\"sessionId\":\"as_123e4567-e89b-12d3-a456-426614174000\"}")).andReturn();

        assertTrue(result.getResponse().getContentAsString().contains("event:error"));
        assertNull(result.getAsyncResult(1000), "an error frame must terminate the SSE response itself");
    }

    /** 在活跃回合中模拟连接终止，检查工具执行收到取消且线程池工作位释放。 */
    @ParameterizedTest
    @ValueSource(strings = {"error", "timeout", "completion"})
    void containerTerminationCancelsAnActiveTurnAndFreesItsWorker(String kind) throws Exception {
        AgentService service = mock(AgentService.class);
        CountDownLatch entered = new CountDownLatch(1), cancelled = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        when(service.chatStream(any(), any())).thenAnswer(call -> {
            SseAgentEventSink sink = call.getArgument(1);
            sink.onDisconnect(cancelled::countDown);
            entered.countDown();
            assertTrue(cancelled.await(3, TimeUnit.SECONDS), "container termination must cancel the active turn");
            return AgentResponse.builder().stopReason(StopReason.CANCELLED).build();
        });
        try {
            var mvc = MockMvcBuilders.standaloneSetup(new AgentController(service,
                    new ObjectMapper(), executor, 40000, 0)).build();
            MvcResult result = mvc.perform(post("/agent/chat/stream")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"question\":\"test\",\"sessionId\":\"as_123e4567-e89b-12d3-a456-426614174000\"}")).andReturn();
            assertEquals(200, result.getResponse().getStatus());
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            MockAsyncContext async = (MockAsyncContext) result.getRequest().getAsyncContext();
            AsyncEvent event = new AsyncEvent(async, new IOException("client gone"));
            for (var listener : async.getListeners()) {
                switch (kind) {
                    case "error" -> listener.onError(event);
                    case "timeout" -> listener.onTimeout(event);
                    default -> listener.onComplete(event);
                }
            }
            assertTrue(cancelled.await(1, TimeUnit.SECONDS), "missing cancellation for " + kind);
            assertEquals("reusable", executor.submit(() -> "reusable").get(2, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
        }
    }
}
