/* 在 HTTP 和异步分派中建立、回显并恢复请求标识。 */

package com.pony.knowledge.request;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/** Establish correlation before Security, retaining the same ID for async and error dispatches. */
public class RequestIdFilter extends OncePerRequestFilter {
    public static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";
    private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);

    /**
     * 允许请求标识过滤器处理异步 Servlet 分派。
     * 上游为 Spring HTTP 过滤链。
     *
     * @return 固定返回 false
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    /**
     * 允许请求标识过滤器处理错误 Servlet 分派。
     * 上游为 Spring HTTP 过滤链。
     *
     * @return 固定返回 false
     */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    /**
     * 解析和回显请求标识，为后续处理建立日志上下文。
     * 上游为 Spring HTTP 过滤链。
     * 下游为 RequestIdContext.resolve、RequestIdContext.open。
     *
     * @param request 进入 HTTP 过滤链的当前请求
     * @param response 即将交给客户端的 HTTP 响应
     * @param chain 剩余的 HTTP 过滤链
     * @throws IOException 底层流或响应无法读取或写入时
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Object stored = request.getAttribute(ATTRIBUTE);
        List<String> incoming = Collections.list(request.getHeaders(RequestIdContext.HEADER));
        String requestId = stored instanceof String id ? id
                : RequestIdContext.resolve(incoming.size() == 1 ? incoming.get(0) : null);
        request.setAttribute(ATTRIBUTE, requestId);
        response.setHeader(RequestIdContext.HEADER, requestId);
        long startedAt = System.nanoTime();

        try (RequestIdContext.Scope ignored = RequestIdContext.open(requestId)) {
            boolean completed = false;
            try {
                chain.doFilter(request, response);
                completed = true;
            } finally {
                if (!response.isCommitted()) {
                    response.setHeader(RequestIdContext.HEADER, requestId);
                }
                long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
                if (completed) {
                    log.info("HTTP {} {} status={} elapsedMs={} dispatch={} requestId={}",
                            request.getMethod(), request.getRequestURI(), response.getStatus(),
                            elapsedMs, request.getDispatcherType(), requestId);
                } else {
                    // The container may only choose the error status after this filter unwinds.
                    log.warn("HTTP {} {} failed elapsedMs={} dispatch={} responseCommitted={} requestId={}",
                            request.getMethod(), request.getRequestURI(), elapsedMs,
                            request.getDispatcherType(), response.isCommitted(), requestId);
                }
            }
        }
    }

    /**
     * 在嵌套错误分派中沿用同一请求标识。
     * 上游为 Spring HTTP 过滤链。
     * 下游为 RequestIdFilter.doFilterInternal。
     *
     * @param request 进入 HTTP 过滤链的当前请求
     * @param response 即将交给客户端的 HTTP 响应
     * @param chain 剩余的 HTTP 过滤链
     * @throws IOException 底层流或响应无法读取或写入时
     */
    @Override
    protected void doFilterNestedErrorDispatch(HttpServletRequest request, HttpServletResponse response,
                                               FilterChain chain) throws ServletException, IOException {
        doFilterInternal(request, response, chain);
    }
}
