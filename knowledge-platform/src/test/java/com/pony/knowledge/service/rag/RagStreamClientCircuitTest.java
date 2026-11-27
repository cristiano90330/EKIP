/* 验证 Java 转发 Python RAG SSE 的请求治理、结束语义和失败回收。 */

package com.pony.knowledge.service.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pony.knowledge.resilience.AiAvailabilityGuard;
import com.pony.knowledge.resilience.AiServiceUnavailableException;
import com.pony.knowledge.service.ai.AiServiceClient;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RagStreamClientCircuitTest {
    /** 通过 {@code exercise} 驱动该场景，核对输出和依赖调用。 */
    @Test void serverFailureOpensBeforeTheNextRequestIsSent() throws Exception { exercise(503); }

    /** 通过 {@code exercise} 驱动该场景，核对输出和依赖调用。 */
    @Test void clientErrorDoesNotAffectFailureRate() throws Exception { exercise(400); }

    /** 通过 {@code exercise} 驱动该场景，核对输出和依赖调用。 */
    @Test void successfulStreamRecordsAvailabilityAndDeliversTerminalFrame() throws Exception { exercise(200); }

    /** 核对显式策略和默认 LangChain 策略的执行确认，并拒绝引擎不符的终态。 */
    @ParameterizedTest
    @CsvSource({"fixed,support", "langchain,"})
    void validatesAcknowledgementAndTerminalEngine(String engine, String requestedEvidenceMode) throws Exception {
        String metadata = """
                {"pipelineEngine":"%s","retrievalVersion":"hybrid","evidenceMode":"support"}
                """.formatted(engine).strip();
        String mismatched = metadata.replace(engine, "wrong");
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rag/query/stream", exchange -> {
            int attempt = hits.getAndIncrement();
            String prefix = attempt == 0 ? "" : "event: execution\ndata: " + metadata + "\n\n";
            String terminal = attempt == 1 ? mismatched : metadata;
            String payload = "event: delta\ndata: {\"text\":\"answer\"}\n\nevent: done\ndata: "
                    + terminal + "\n\n";
            byte[] body = (prefix + payload).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var guard = new AiAvailabilityGuard(true, 5, 1, 50, 10000, 1, new SimpleMeterRegistry());
            var client = new RagStreamClient("http://127.0.0.1:" + server.getAddress().getPort(), 1000, 1000,
                    "test-internal-token", new ObjectMapper(), guard);
            var request = new AiServiceClient.RagQueryRequest();
            request.setPipelineEngine(engine);
            request.setVersion("hybrid");
            request.setEvidenceMode(requestedEvidenceMode);
            var error = assertThrows(RagStreamClient.RagStreamException.class,
                    () -> client.stream(request, (event, data) -> fail("unconfirmed strategy must not forward content")));
            assertEquals("ai_contract_mismatch", error.getReasonCode());

            var forwarded = new ArrayList<String>();
            var terminalError = assertThrows(RagStreamClient.RagStreamException.class,
                    () -> client.stream(request, (event, data) -> forwarded.add(event)));
            assertEquals("ai_contract_mismatch", terminalError.getReasonCode());
            assertEquals(List.of("delta"), forwarded);

            forwarded.clear();
            client.stream(request, (event, data) -> forwarded.add(event));
            assertEquals(List.of("delta", "done"), forwarded);
        } finally {
            server.stop(0);
        }
    }

    /** 通过 {@code HttpServer.create} 驱动用例，抛出 {@code RagStreamException}，布尔状态符合断言。 */
    @Test void nonSuccessHttpResponsePreservesSafeDiagnosticFields() throws Exception {
        String diagnosticBody = """
                {"detail":{"outcome":"dependency_unavailable","reason_code":"retrieval_unavailable",
                "failed_stage":"retrieval","request_id":"req-upstream-123","message":"private detail"}}
                """;
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rag/query/stream", exchange -> {
            byte[] body = diagnosticBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(503, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var guard = new AiAvailabilityGuard(true, 5, 1, 50, 10000, 1, new SimpleMeterRegistry());
            var client = new RagStreamClient("http://127.0.0.1:" + server.getAddress().getPort(), 1000, 1000,
                    "test-internal-token", new ObjectMapper(), guard);
            var request = new AiServiceClient.RagQueryRequest();

            RagStreamClient.RagStreamException error = assertThrows(RagStreamClient.RagStreamException.class,
                    () -> client.stream(request, (event, data) -> fail("Non-success HTTP must not emit SSE frames")));

            assertEquals(503, error.getStatus());
            assertEquals("dependency_unavailable", error.getOutcome());
            assertEquals("retrieval_unavailable", error.getReasonCode());
            assertEquals("retrieval", error.getFailedStage());
            assertEquals("req-upstream-123", error.getRequestId());
            assertFalse(error.getMessage().contains("private detail"));
        } finally {
            server.stop(0);
        }
    }

    /** 通过 {@code HttpServer.create}、{@code frames.get} 驱动用例，抛出 {@code RagStreamException}、{@code AiServiceUnavailableException}，实际结果与固定预期一致。 */
    private void exercise(int status) throws Exception {
        AtomicInteger hits = new AtomicInteger();
        AtomicInteger frames = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rag/query/stream", exchange -> {
            hits.incrementAndGet();
            byte[] body = "event: done\ndata: {}\n\n".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var guard = new AiAvailabilityGuard(true, 1, 1, 50, 10000, 1, new SimpleMeterRegistry());
            var client = new RagStreamClient("http://127.0.0.1:" + server.getAddress().getPort(), 1000, 1000,
                    "test-internal-token", new ObjectMapper(), guard);
            var request = new AiServiceClient.RagQueryRequest();
            if (status == 200) {
                client.stream(request, (event, data) -> { assertEquals("done", event); frames.incrementAndGet(); });
                assertEquals(1, frames.get());
            } else {
                var error = assertThrows(RagStreamClient.RagStreamException.class,
                        () -> client.stream(request, (event, data) -> fail("No error body frames")));
                assertEquals(status, error.getStatus());
            }
            if (status >= 500) {
                assertEquals(AiAvailabilityGuard.State.OPEN, guard.state());
                assertThrows(AiServiceUnavailableException.class, () -> client.stream(request, (event, data) -> {}));
                assertEquals(1, hits.get());
            } else assertEquals(AiAvailabilityGuard.State.CLOSED, guard.state());
        } finally { server.stop(0); }
    }
}
