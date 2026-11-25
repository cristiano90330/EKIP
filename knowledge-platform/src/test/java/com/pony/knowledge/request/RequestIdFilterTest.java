/* 验证 HTTP 请求 ID 的接收、生成、回传和线程作用域。 */

package com.pony.knowledge.request;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pony.knowledge.mcp.auth.McpApiKeyAuthFilter;
import com.pony.knowledge.mcp.auth.McpApiKeyService;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class RequestIdFilterTest {
    private final RequestIdFilter filter = new RequestIdFilter();

    /** 断言抛出 {@code IllegalStateException}。调用路径经过 {@code RequestIdContext.open}。 */
    @Test
    void requestMetadataIsVisibleDuringChainAndRestoredAfterFailure() {
        MockHttpServletRequest request = request("browser.42");
        MockHttpServletResponse response = new MockHttpServletResponse();
        try (RequestIdContext.Scope ignored = RequestIdContext.open("outer")) {
            MDC.put("traceId", "real-trace");
            try {
                assertThrows(IllegalStateException.class, () -> filter.doFilter(request, response, (req, res) -> {
                    assertEquals("browser.42", RequestIdContext.current());
                    assertEquals("browser.42", MDC.get("requestId"));
                    assertEquals("real-trace", MDC.get("traceId"));
                    throw new IllegalStateException("failure");
                }));
                assertEquals("outer", RequestIdContext.current());
                assertEquals("outer", MDC.get("requestId"));
                assertEquals("browser.42", response.getHeader(RequestIdContext.HEADER));
            } finally {
                MDC.remove("traceId");
            }
        }
        assertNull(RequestIdContext.current());
        assertNull(MDC.get("requestId"));
    }

    /** 核对 {@code UUID.fromString(id)} 已提供。调用路径经过 {@code filter.doFilter}。 */
    @Test
    void missingOrUnsafeHeadersReceiveDistinctGeneratedIds() throws Exception {
        String previous = null;
        for (String incoming : new String[]{null, "", "bad id", "line\r\nbreak", "x".repeat(129), "中文"}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request(incoming), response, (req, res) -> {});
            String id = response.getHeader(RequestIdContext.HEADER);
            assertNotNull(UUID.fromString(id));
            assertNotEquals(previous, id);
            assertNull(RequestIdContext.current());
            previous = id;
        }
    }

    /**
     * 核对 {@code UUID.fromString(response.getHeader(RequestIdContext.HEADER))} 已提供。
     * 调用路径经过 {@code request.addHeader}。
     */
    @Test
    void duplicateHeadersReceiveGeneratedIdInsteadOfAmbiguousCallerId() throws Exception {
        MockHttpServletRequest request = request("first-id");
        request.addHeader(RequestIdContext.HEADER, "second-id");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {});
        assertNotNull(UUID.fromString(response.getHeader(RequestIdContext.HEADER)));
    }

    /**
     * 核对 {@code RequestIdContext.current()} 与预期 {@code "initial-id"} 一致。
     * 调用路径经过 {@code request.setDispatcherType}。
     */
    @Test
    void asyncAndErrorDispatchReuseOriginalRequestAttribute() throws Exception {
        MockHttpServletRequest request = request("initial-id");
        for (DispatcherType dispatch : new DispatcherType[]{DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR}) {
            request.setDispatcherType(dispatch);
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> assertEquals("initial-id", RequestIdContext.current()));
            assertEquals("initial-id", response.getHeader(RequestIdContext.HEADER));
            assertNull(RequestIdContext.current());
        }
    }

    /** 核对 {@code RequestIdContext.current()} 与预期 {@code "initial-id"} 一致。调用路径经过 {@code filter.doFilter}。 */
    @Test
    void nestedErrorDispatchReopensTheOriginalContextAndRestoresTheCaller() throws Exception {
        MockHttpServletRequest request = request("initial-id");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {
            request.setDispatcherType(DispatcherType.ERROR);
            try (RequestIdContext.Scope ignored = RequestIdContext.open("nested-caller")) {
                filter.doFilter(request, response,
                        (nestedReq, nestedRes) -> assertEquals("initial-id", RequestIdContext.current()));
                assertEquals("nested-caller", RequestIdContext.current());
            }
            assertEquals("initial-id", RequestIdContext.current());
        });
        assertEquals("initial-id", response.getHeader(RequestIdContext.HEADER));
        assertNull(RequestIdContext.current());
    }

    /**
     * 核对 {@code response.getHeader(RequestIdContext.HEADER)} 与预期 {@code "stream-id"} 一致。
     * 调用路径经过 {@code filter.doFilter}。
     */
    @Test
    void streamWritesAreForwardedImmediatelyWithoutResponseWrapping() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request("stream-id"), response, (req, res) -> {
            assertSame(response, res);
            res.setContentType("text/event-stream");
            res.getWriter().write("data: first\n\n");
            res.flushBuffer();
            assertEquals("data: first\n\n", response.getContentAsString());
            assertEquals("stream-id", response.getHeader(RequestIdContext.HEADER));
            res.getWriter().write("data: done\n\n");
        });
        assertEquals("data: first\n\ndata: done\n\n", response.getContentAsString());
        assertNull(RequestIdContext.current());
    }

    /** 核对 {@code response.getStatus()} 与预期 {@code 401} 一致。调用路径经过 {@code request.addHeader}。 */
    @Test
    void mcpEarlyRejectionAlsoKeepsRequestId() throws Exception {
        McpApiKeyService keys = mock(McpApiKeyService.class);
        McpApiKeyAuthFilter mcp = new McpApiKeyAuthFilter(keys, new ObjectMapper(), "/mcp");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader(RequestIdContext.HEADER, "mcp-denied");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> mcp.doFilter(req, res,
                (unusedReq, unusedRes) -> fail("unauthenticated request reached MCP transport")));
        assertEquals(401, response.getStatus());
        assertEquals("mcp-denied", response.getHeader(RequestIdContext.HEADER));
        assertNull(RequestIdContext.current());
        verifyNoInteractions(keys);
    }

    /** 断言抛出 {@code IllegalStateException}。调用路径经过 {@code LoggerFactory.getLogger}。 */
    @Test
    void escapingExceptionLogsFailureWithMdcInsteadOfAnUnchosenSuccessStatus() {
        Logger logger = (Logger) LoggerFactory.getLogger(RequestIdFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>() {
            /**
             * 在测试中处理 {@code append} 阶段，使用 {@code event.prepareForDeferredProcessing}、{@code super.append}。
             * @param event 本用例传入的{@code event}
             */
            @Override
            protected void append(ILoggingEvent event) {
                event.prepareForDeferredProcessing();
                super.append(event);
            }
        };
        appender.start();
        logger.addAppender(appender);
        try {
            assertThrows(IllegalStateException.class,
                    () -> filter.doFilter(request("error-log-id"), new MockHttpServletResponse(),
                            (req, res) -> { throw new IllegalStateException("failure"); }));
            assertEquals(1, appender.list.size());
            ILoggingEvent event = appender.list.get(0);
            assertTrue(event.getFormattedMessage().contains("failed"));
            assertFalse(event.getFormattedMessage().contains("status=200"));
            assertEquals("error-log-id", event.getMDCPropertyMap().get("requestId"));
            assertNull(RequestIdContext.current());
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    /** 覆盖并发的相关边界。通过 {@code Executors.newFixedThreadPool} 执行本例并核对输出或记录。 */
    @Test
    void concurrentRequestsHaveIndependentContexts() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch entered = new CountDownLatch(2);
        try {
            var first = executor.submit(() -> filterRequest("first", entered));
            var second = executor.submit(() -> filterRequest("second", entered));
            assertEquals("first", first.get(5, TimeUnit.SECONDS));
            assertEquals("second", second.get(5, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 构造 {@code String} 供测试使用，使用 {@code filter.doFilter}、{@code entered.countDown}。
     * 由 {@code concurrentRequestsHaveIndependentContexts} 调用。
     * @param id 目标记录 ID
     * @param entered 本用例传入的{@code entered}
     * 返回 {@code response.getHeader(RequestIdContext.HEADER)}。
     */
    private String filterRequest(String id, CountDownLatch entered) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request(id), response, (req, res) -> {
            entered.countDown();
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            assertEquals(id, RequestIdContext.current());
            assertEquals(id, MDC.get("requestId"));
        });
        assertNull(RequestIdContext.current());
        return response.getHeader(RequestIdContext.HEADER);
    }

    /**
     * 构造发往测试入口的请求，使用 {@code request.addHeader}。
     * 由 {@code requestMetadataIsVisibleDuringChainAndRestoredAfterFailure}、{@code missingOrUnsafeHeadersReceiveDistinctGeneratedIds} 调用。
     * @param id 目标记录 ID
     * 返回 {@code request}。
     */
    private MockHttpServletRequest request(String id) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/probe");
        if (id != null) request.addHeader(RequestIdContext.HEADER, id);
        return request;
    }
}
