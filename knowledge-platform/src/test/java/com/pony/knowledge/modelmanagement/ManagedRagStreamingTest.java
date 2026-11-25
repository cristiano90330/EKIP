/* 验证受管模型在 RAG 流式请求的执行配置和终态中得到正确传递。 */

package com.pony.knowledge.modelmanagement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pony.knowledge.resilience.AiAvailabilityGuard;
import com.pony.knowledge.service.ai.AiServiceClient;
import com.pony.knowledge.service.rag.RagStreamClient;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class ManagedRagStreamingTest {
    /** 通过 {@code Executors.newSingleThreadExecutor} 执行本例并核对输出或记录。 */
    @Test void terminalFramesFinishWithoutWaitingForUpstreamEof() throws Exception {
        for (String terminal : List.of("done", "error")) {
            CountDownLatch release = new CountDownLatch(1);
            HttpServer server = server("event: delta\ndata: {\"text\":\"draft\"}\n\n"
                    + "event: " + terminal + "\ndata: {}\n\n", release);
            ExecutorService caller = Executors.newSingleThreadExecutor();
            try {
                List<String> events = new ArrayList<>();
                Future<?> result = caller.submit(() -> client(server).stream(request(), (event, data) -> events.add(event)));
                result.get(2, TimeUnit.SECONDS);
                assertEquals(List.of("delta", terminal), events);
            } finally {
                release.countDown(); caller.shutdownNow(); server.stop(0);
            }
        }
    }

    /** 断言抛出 {@code RagStreamClient.RagStreamException}。调用路径经过 {@code events.add}。 */
    @Test void eofAfterPartialContentIsAProtocolFailure() throws Exception {
        HttpServer server = server("event: delta\ndata: {\"text\":\"partial\"}\n\n", null);
        try {
            List<String> events = new ArrayList<>();
            RagStreamClient.RagStreamException error = assertThrows(RagStreamClient.RagStreamException.class,
                    () -> client(server).stream(request(), (event, data) -> events.add(event)));
            assertEquals(503, error.getStatus());
            assertEquals(List.of("delta"), events);
        } finally { server.stop(0); }
    }

    /** 核对 {@code events} 与预期 {@code List.of("done")} 一致。调用路径经过 {@code events.add}。 */
    @Test void terminalFrameAtEofWithoutBlankSeparatorRemainsValid() throws Exception {
        HttpServer server = server("event: done\ndata: {\"outcome\":\"success\"}", null);
        try {
            List<String> events = new ArrayList<>();
            client(server).stream(request(), (event, data) -> events.add(event));
            assertEquals(List.of("done"), events);
        } finally { server.stop(0); }
    }

    /**
     * 构造 {@code HttpServer} 供测试使用，使用 {@code HttpServer.create}、{@code server.createContext}。
     * 由 {@code terminalFramesFinishWithoutWaitingForUpstreamEof}、{@code eofAfterPartialContentIsAProtocolFailure} 调用。
     * @param frames 本用例传入的{@code frames}
     * @param release 本用例传入的{@code release}
     * 返回 {@code server}。
     */
    private static HttpServer server(String frames, CountDownLatch release) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rag/query/stream", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try {
                exchange.getResponseBody().write(frames.getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                if (release != null) release.await(4, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start(); return server;
    }

    /**
     * 构造 {@code RagStreamClient} 供测试使用，使用 {@code server.getAddress}、{@code AiAvailabilityGuard.disabled}。
     * 由 {@code terminalFramesFinishWithoutWaitingForUpstreamEof}、{@code eofAfterPartialContentIsAProtocolFailure} 调用。
     * @param server 本用例传入的{@code server}
     * 返回 {@code RagStreamClient} 结果。
     */
    private static RagStreamClient client(HttpServer server) {
        return new RagStreamClient("http://127.0.0.1:" + server.getAddress().getPort(), 1000, 40000,
                "test-internal-token", new ObjectMapper(), AiAvailabilityGuard.disabled(new SimpleMeterRegistry()));
    }

    /**
     * 构造发往测试入口的请求，使用 {@code value.setQuestion}、{@code value.setKnowledgeBaseIds}。
     * 由 {@code terminalFramesFinishWithoutWaitingForUpstreamEof}、{@code eofAfterPartialContentIsAProtocolFailure} 调用。
     * 返回 {@code value}。
     */
    private static AiServiceClient.RagQueryRequest request() {
        AiServiceClient.RagQueryRequest value = new AiServiceClient.RagQueryRequest();
        value.setQuestion("q"); value.setKnowledgeBaseIds(List.of()); return value;
    }
}
