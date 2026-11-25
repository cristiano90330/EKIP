/* 验证内部 AI 请求传递 Java 请求关联标识并处理缺省上下文。 */

package com.pony.knowledge.service.ai;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.pony.knowledge.observability.RagTrace;
import com.pony.knowledge.observability.RagTraceContext;
import com.pony.knowledge.request.RequestIdContext;
import com.pony.knowledge.request.RequestIdFilter;
import com.pony.knowledge.resilience.AiAvailabilityGuard;
import com.pony.knowledge.resilience.AiUnavailableAssertions;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本章 3.1 P1-10 / P1-11：Java → Python 的每次调用都要带上 `X-Request-ID`（否则 Python 侧
 * request_id 恒为 "-"，两进程日志永远对不上），并且**每个方法**都要留下一条耗时日志
 * （拦载器按 RestClient 实例注册，一处配置覆盖全部方法，不需要每个方法各自补日志）。
 *
 * <p>用一个 JDK 内置的 {@link HttpServer} 顶替 Python 服务（只连 127.0.0.1 的随机端口，
 * 不发外网请求），断言**实际发出去的请求**带了什么头。之所以不能像常规那样换一个
 * mock 的 request factory——`AiServiceClient` 构造器自己会 `requestFactory(...)` 覆盖掉它。
 *
 * <p>探针方法用保留的 {@code embed}（旧端点方法已随分段路径删除）。
 *
 * <p>2026-09-29 起这个「统一出口」还多押一条：`X-Internal-Token` 也必须发出去
 * （Python 侧 `ai/app/security.py` 缺失即 401，见 {@link #internalToken_isSentOnEveryCall}）。
 */
class AiServiceClientRequestIdTest {

    /** 与 pom.xml 的 surefire 注入值、`ai/tests/conftest.py` 的 `TEST_INTERNAL_TOKEN` 同一字面量。 */
    private static final String TEST_INTERNAL_TOKEN = "test-internal-token";

    /** 在用例结束后释放该用例创建的资源。 */
    @AfterEach
    void clearTrace() {
        RagTraceContext.clear();
    }

    /** 通过 {@code seen.get} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void requestId_carriesBusinessTraceId() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        try (Server server = start(seen, 200)) {
            RagTraceContext.set(RagTrace.builder().traceId("trace-abc").build());
            AiServiceClient client = newClient(server.port());
            assertNotNull(client.embed(List.of("客户合同"), java.util.Map.of("schemaVersion",1,"budgetMs",120000,"bindings",java.util.Map.of(),"indexScopes",List.of())));
        }
        assertEquals("trace-abc", seen.get());
    }

    /** 通过 {@code seen.get}、{@code RagTraceContext.get} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void browserRequestIdReachesAiWithoutChangingBusinessTrace() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/knowledge/ask");
        request.addHeader("X-Request-ID", "browser-request-42");
        MockHttpServletResponse response = new MockHttpServletResponse();
        RagTraceContext.set(RagTrace.builder().traceId("independent-trace").build());
        try (Server server = start(seen, 200)) {
            new RequestIdFilter().doFilter(request, response,
                    (req, res) -> newClient(server.port()).embed(List.of("客户合同"), java.util.Map.of("schemaVersion",1,"budgetMs",120000,"bindings",java.util.Map.of(),"indexScopes",List.of())));
        }

        assertEquals("browser-request-42", seen.get());
        assertEquals("browser-request-42", response.getHeader("X-Request-ID"));
        assertEquals("independent-trace", RagTraceContext.get().getTraceId());
        assertNull(RequestIdContext.current());
    }

    /** 通过 {@code seen.get} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void requestId_fallsBackToGeneratedId_whenNoTraceContext() throws Exception {
        // @Async 解析线程里没有 HTTP 入口 span：不能发空值，否则 Python 侧写 "-"，日志永远对不上
        AtomicReference<String> seen = new AtomicReference<>();
        try (Server server = start(seen, 200)) {
            AiServiceClient client = newClient(server.port());
            client.embed(List.of("客户合同"), java.util.Map.of("schemaVersion",1,"budgetMs",120000,"bindings",java.util.Map.of(),"indexScopes",List.of()));
        }
        String rid = seen.get();
        assertNotNull(rid, "无 trace 上下文时也必须带 requestId");
        assertEquals(36, rid.length(), "兜底应为 UUID（36 字符）：" + rid);
    }

    /** 通过 {@code server.port}、{@code attachAppender} 驱动该场景，布尔状态符合断言。 */
    @Test
    void everyOutboundCallIsLogged_withElapsedTimeAndStatus() throws Exception {
        // P1-11：每个方法自己可能一行日志都没有，但拦截器必须给它记一条（一处配置覆盖全部方法）
        ListAppender<ILoggingEvent> appender = attachAppender();
        try (Server server = start(new AtomicReference<>(), 200)) {
            newClient(server.port()).embed(List.of("客户合同"), java.util.Map.of("schemaVersion",1,"budgetMs",120000,"bindings",java.util.Map.of(),"indexScopes",List.of()));
        } finally {
            detachAppender(appender);
        }
        assertTrue(messages(appender).stream().anyMatch(m -> m.contains("← Python")
                        && m.contains("/embedding") && m.contains("耗时=") && m.contains("status=200")),
                "应记一条带 uri / 耗时 / 状态码的出站日志，实际：" + messages(appender));
    }

    /** 通过 {@code server.port}、{@code attachAppender} 驱动该场景，抛出 {@code HttpServerErrorException}，布尔状态符合断言。 */
    @Test
    void failingCallIsLoggedAsWarn_beforeRestClientThrows() throws Exception {
        // Python 5xx 时 RestClient 会抛 HttpServerErrorException，但「被拒」这件事 Java 侧也要有痕迹
        ListAppender<ILoggingEvent> appender = attachAppender();
        try (Server server = start(new AtomicReference<>(), 500)) {
            assertThrows(HttpServerErrorException.class,
                    () -> newClient(server.port()).embed(List.of("客户合同"), java.util.Map.of("schemaVersion",1,"budgetMs",120000,"bindings",java.util.Map.of(),"indexScopes",List.of())));
        } finally {
            detachAppender(appender);
        }
        assertTrue(messages(appender).stream().anyMatch(m -> m.contains("status=500")),
                "5xx 应记一条 WARN，实际：" + messages(appender));
    }

    /** 分别触发网关调用并检查每次请求都带当前内部认证令牌。 */
    @Test
    void internalToken_isSentOnEveryCall() throws Exception {
        // 2026-09-29：Python 侧所有业务路由都要求 X-Internal-Token，且 /rag/query 能按调用方自带的
        // kbIds 直连 PG 读任意知识库 —— 这个头一旦发不出去，线上表现是「所有业务调用 401」。
        // 与 requestId 同样靠「拦截器一处注册」覆盖全部调用点，这里钉住它真的到了服务端。
        AtomicReference<String> seenToken = new AtomicReference<>();
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/embedding", exchange -> {
            seenToken.set(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
            byte[] body = "{\"embeddings\":[[0.1]]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        http.start();
        try {
            newClient(http.getAddress().getPort()).embed(List.of("客户合同"), java.util.Map.of("schemaVersion",1,"budgetMs",120000,"bindings",java.util.Map.of(),"indexScopes",List.of()));
        } finally {
            http.stop(0);
        }
        assertEquals(TEST_INTERNAL_TOKEN, seenToken.get());
    }

    /** 打开依赖断路器后发起网关调用，检查它快速失败且未向替身服务发请求。 */
    @Test
    void guardOpen_failsFast_andSendsNoHttp() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/embedding", exchange -> {
            hits.incrementAndGet();
            byte[] body = "{\"embeddings\":[[0.1]]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        http.start();
        try {
            // 阈值 1 + 冷却 60s：一次失败即打开，且整条用例期间不会进入半开
            AiAvailabilityGuard guard = new AiAvailabilityGuard(
                    true, 1, 1, 50, 60_000, 1, new SimpleMeterRegistry());
            AiServiceClient client = new AiServiceClient(RestClient.builder(),
                    "http://127.0.0.1:" + http.getAddress().getPort(), 3000, 60000, 60000,
                    TEST_INTERNAL_TOKEN, null, guard);

            try (var call = guard.acquire()) { call.failure(); }                 // 模拟"上一次调用连接被拒"
            assertEquals(AiAvailabilityGuard.State.OPEN, guard.state());

            long t0 = System.nanoTime();
            Throwable thrown = assertThrows(RuntimeException.class, () -> client.embed(List.of("客户合同"), java.util.Map.of("schemaVersion",1,"budgetMs",120000,"bindings",java.util.Map.of(),"indexScopes",List.of())));
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

            assertTrue(AiUnavailableAssertions.causeChainHasUnavailable(thrown),
                    "熔断打开时抛出的异常必须能溯源到 AiServiceUnavailableException，实际：" + thrown);
            assertEquals(0, hits.get(), "熔断打开时不得发出任何 HTTP 请求");
            assertTrue(elapsedMs < 1000, "必须快速失败，实际耗时 " + elapsedMs + "ms");
        } finally {
            http.stop(0);
        }
    }

    /**
     * 在该类型内部准备后续运行所需的数据。
     * @param appender 本次调用传入的 {@code ListAppender<ILoggingEvent>}
     * @return 调用方使用的 {@code List<String>}
     */
    private List<String> messages(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /**
     * 协调 {@code appender.start} 完成当前阶段，供后续处理使用。
     * 下游调用 {@code appender.start}。
     *
     * @return 当前记录中的 {@code appender}
     */
    private ListAppender<ILoggingEvent> attachAppender() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(AiServiceClient.class)).addAppender(appender);
        return appender;
    }

    /**
     * 在该类型内部准备后续运行所需的数据。
     * @param appender 本次调用传入的 {@code ListAppender<ILoggingEvent>}
     */
    private void detachAppender(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(AiServiceClient.class)).detachAppender(appender);
    }

    /**
     * 协调 {@code AiAvailabilityGuard.disabled} 完成当前阶段，供后续处理使用。
     * @param port 本次调用传入的 {@code int}
     * @return 调用方使用的 {@code AiServiceClient}
     */
    private AiServiceClient newClient(int port) {
        return new AiServiceClient(RestClient.builder(), "http://127.0.0.1:" + port, 3000, 60000, 60000,
                TEST_INTERNAL_TOKEN, null, AiAvailabilityGuard.disabled(new SimpleMeterRegistry()));
    }

    /**
     * 协调 {@code HttpServer.create} 完成当前阶段，供后续处理使用。
     * 下游调用 {@code HttpServer.create}、{@code http.createContext}、{@code exchange.sendResponseHeaders}。
     *
     * @param requestId 本次调用传入的 {@code AtomicReference<String>}
     * @param status 本次调用传入的 {@code int}
     * @return 调用方使用的 {@code Server}
     */
    private Server start(AtomicReference<String> requestId, int status) throws Exception {
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/embedding", exchange -> {
            requestId.set(exchange.getRequestHeaders().getFirst("X-Request-ID"));
            byte[] body = "{\"embeddings\":[[0.1]]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        http.start();
        return new Server(http);
    }

    /** 用 try-with-resources 保证端口释放。 */
    private static final class Server implements AutoCloseable {
        private final HttpServer http;

        /**
         * 用注入依赖和构造参数建立此实例执行时使用的状态。
         *
         * @param http 本次调用传入的 {@code HttpServer}
         */
        Server(HttpServer http) {
            this.http = http;
        }

        /** 在该类型内部准备后续运行所需的数据。 */
        int port() {
            return http.getAddress().getPort();
        }

        /** 通过 {@code http.stop} 释放本次操作所持资源。 */
        @Override
        public void close() {
            http.stop(0);
        }
    }
}
