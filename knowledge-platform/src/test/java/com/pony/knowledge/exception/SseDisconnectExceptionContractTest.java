/* 用隔离流式端点验证客户端断开异常与普通服务端异常的处理差异。 */

package com.pony.knowledge.exception;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Exercises MVC exception dispatch after a partial SSE response has been committed. */
class SseDisconnectExceptionContractTest {
    private static final String PARTIAL = "event:delta\ndata:{\"text\":\"partial\"}\n\n";
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new BrokenStream())
            .setControllerAdvice(new GlobalExceptionHandler()).build();

    /**
     * 核对 {@code result.getResponse().getStatus()} 与预期 {@code 200} 一致。
     * 调用路径经过 {@code mvc.perform}。
     * @param kind 本用例传入的{@code kind}
     */
    @ParameterizedTest
    @ValueSource(strings = {"async", "io", "wrapped", "server"})
    void committedStreamNeverReceivesASecondJsonResponse(String kind) {
        MvcResult result = assertDoesNotThrow(() -> mvc.perform(get("/test/broken-stream")
                .param("kind", kind).accept(MediaType.TEXT_EVENT_STREAM)).andReturn());
        assertEquals(200, result.getResponse().getStatus());
        assertEquals(MediaType.TEXT_EVENT_STREAM_VALUE, result.getResponse().getContentType());
        assertEquals(PARTIAL, assertDoesNotThrow(() -> result.getResponse().getContentAsString()));
        assertNotNull(result.getResolvedException());
    }

    /** 核对 {@code result.getResponse().getStatus()} 与预期 {@code 500} 一致。调用路径经过 {@code mvc.perform}。 */
    @Test
    void unrelatedIoFailureBeforeCommitKeepsTheSafe500Contract() throws Exception {
        MvcResult result = mvc.perform(get("/test/unrelated-io")).andReturn();
        assertEquals(500, result.getResponse().getStatus());
        assertEquals(MediaType.APPLICATION_JSON_VALUE, result.getResponse().getContentType());
        String body = result.getResponse().getContentAsString();
        assertTrue(body.contains("\"code\":500"), body);
        assertFalse(body.contains("private path"), body);
    }

    @RestController
    static class BrokenStream {
        /** 在测试中处理 {@code broken} 阶段。接收{@code kind}、测试响应。 */
        @GetMapping(value = "/test/broken-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        void broken(@RequestParam String kind, HttpServletResponse response) throws IOException {
            response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
            response.getWriter().write(PARTIAL);
            response.flushBuffer();
            switch (kind) {
                case "async" -> throw new AsyncRequestNotUsableException("disconnected client");
                case "io" -> throw new IOException(
                        "An established connection was aborted by the software in your host machine");
                case "wrapped" -> throw new IllegalStateException("stream failed", new IOException("Broken pipe"));
                default -> throw new IllegalStateException("private server failure");
            }
        }

        /** 在测试中处理 {@code unrelated} 阶段。 */
        @GetMapping("/test/unrelated-io")
        void unrelated() throws IOException {
            throw new IOException("private path could not be read");
        }
    }
}
