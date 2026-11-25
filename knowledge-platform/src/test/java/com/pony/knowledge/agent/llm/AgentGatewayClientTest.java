/* 验证 Python 网关客户端的请求 ID、内部鉴权和熔断行为。 */

package com.pony.knowledge.agent.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pony.knowledge.request.RequestIdContext;
import com.pony.knowledge.resilience.AiAvailabilityGuard;
import com.pony.knowledge.resilience.AiUnavailableAssertions;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内部鉴权回归：Agent 的那一跳必须带 {@code X-Internal-Token}。
 *
 * <p>为什么单独立一条、不靠 {@code AiServiceClientRequestIdTest} 一网打尽：两条链路用的是
 * **两个不同的传输类**——AI 客户端（{@code /parser}、{@code /embedding}、{@code /rag/query} …）
 * 与 {@link AgentGatewayClient}（{@code /agent/decide} + {@code /agent/summarize}）。
 * 2026-09-29 新增内部鉴权时，令牌只加在前者的统一拦截器上；本类因「agent 包对项目内其它包零依赖」
 * 刻意不 import 前者，于是在同一次改造里被漏掉——默认 {@code agent.llm.mode=python} 下
 * Agent 决策与摘要恒 401。这条测试钉住令牌真的到了服务端。
 *
 * <p>用 JDK 内置 {@link HttpServer} 顶替 Python 服务（只连 127.0.0.1 随机端口，不发外网），
 * 断言**实际发出去的请求**带了什么头——与 {@code AiServiceClientRequestIdTest} 同一手法。
 */
class AgentGatewayClientTest {

    /** 与 pom.xml 的 surefire 注入值、`ai/tests/conftest.py` 的 `TEST_INTERNAL_TOKEN` 同一字面量。 */
    private static final String TEST_INTERNAL_TOKEN = "test-internal-token";

    /** 在同一入站请求内调用决策和摘要，检查二者的网关请求沿用相同追踪标识。 */
    @Test
    void inboundRequestIdIsSharedByDecisionAndSummaryCalls() throws Exception {
        AtomicReference<String> seenRequestId = new AtomicReference<>();
        AtomicInteger hits = new AtomicInteger();
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/agent", exchange -> {
            seenRequestId.set(exchange.getRequestHeaders().getFirst("X-Request-ID"));
            hits.incrementAndGet();
            byte[] body = "{\"content\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        http.start();
        try (RequestIdContext.Scope ignored = RequestIdContext.open("agent-http-request")) {
            AgentGatewayClient client = new AgentGatewayClient(AgentGatewayProperties.builder()
                    .baseUrl("http://127.0.0.1:" + http.getAddress().getPort())
                    .connectTimeoutMs(2000).readTimeoutMs(5000).build(),
                    new ObjectMapper(), TEST_INTERNAL_TOKEN, null,
                    AiAvailabilityGuard.disabled(new SimpleMeterRegistry()));
            client.post("/agent/decide", Map.of("question", "hi"), Map.class);
            assertEquals("agent-http-request", seenRequestId.get());
            client.post("/agent/summarize", Map.of("messages", "hi"), Map.class);
            assertEquals("agent-http-request", seenRequestId.get());
            assertEquals(2, hits.get());
        } finally {
            http.stop(0);
        }
    }

    /** 分别触发网关调用并检查每次请求都带当前内部认证令牌。 */
    @Test
    void internalToken_isSentOnEveryCall() throws Exception {
        AtomicReference<String> seenToken = new AtomicReference<>();
        AtomicReference<String> seenRequestId = new AtomicReference<>();
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/agent/decide", exchange -> {
            seenToken.set(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
            seenRequestId.set(exchange.getRequestHeaders().getFirst("X-Request-ID"));
            byte[] body = "{\"content\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        http.start();
        try {
            AgentGatewayClient client = new AgentGatewayClient(
                    AgentGatewayProperties.builder()
                            .baseUrl("http://127.0.0.1:" + http.getAddress().getPort())
                            .connectTimeoutMs(2000)
                            .readTimeoutMs(5000)
                            .build(),
                    new ObjectMapper(), TEST_INTERNAL_TOKEN, null,
                    AiAvailabilityGuard.disabled(new SimpleMeterRegistry()));
            client.post("/agent/decide", Map.<String, Object>of("question", "hi"), Map.class);
        } finally {
            http.stop(0);
        }

        assertEquals(TEST_INTERNAL_TOKEN, seenToken.get(),
                "X-Internal-Token 必须发出：Python 侧 /agent/* 挂在 require_internal_token 下，缺失即 401");
        assertNotNull(seenRequestId.get(), "无 trace 上下文时 requestId 也必须有值");
        assertEquals(36, seenRequestId.get().length(), "兜底应为 UUID（36 字符）：" + seenRequestId.get());
    }

    /** 打开依赖断路器后发起网关调用，检查它快速失败且未向替身服务发请求。 */
    @Test
    void guardOpen_failsFast_andSendsNoHttp() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/agent/decide", exchange -> {
            hits.incrementAndGet();
            byte[] body = "{\"content\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        http.start();
        try {
            AiAvailabilityGuard guard = new AiAvailabilityGuard(
                    true, 1, 1, 50, 60_000, 1, new SimpleMeterRegistry());
            AgentGatewayClient client = new AgentGatewayClient(
                    AgentGatewayProperties.builder()
                            .baseUrl("http://127.0.0.1:" + http.getAddress().getPort())
                            .connectTimeoutMs(2000)
                            .readTimeoutMs(5000)
                            .build(),
                    new ObjectMapper(), TEST_INTERNAL_TOKEN, null, guard);

            try (var call = guard.acquire()) { call.failure(); }
            assertEquals(AiAvailabilityGuard.State.OPEN, guard.state());

            long t0 = System.nanoTime();
            Throwable thrown = assertThrows(RuntimeException.class,
                    () -> client.post("/agent/decide", Map.<String, Object>of("question", "hi"), Map.class));
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

            assertTrue(AiUnavailableAssertions.causeChainHasUnavailable(thrown),
                    "熔断打开时抛出的异常必须能溯源到 AiServiceUnavailableException，实际：" + thrown);
            assertEquals(0, hits.get(), "熔断打开时不得发出任何 HTTP 请求");
            assertTrue(elapsedMs < 1000, "必须快速失败，实际耗时 " + elapsedMs + "ms");
        } finally {
            http.stop(0);
        }
    }
}
