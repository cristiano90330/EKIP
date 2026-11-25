/* 限制模型 HTTP 请求执行时间并取消超时任务。 */

package com.pony.knowledge.modelmanagement;

import java.io.Closeable;
import java.net.http.HttpTimeoutException;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import com.pony.knowledge.request.RequestIdContext;
import com.pony.knowledge.observability.RagTraceContext;
import com.pony.knowledge.observability.RagTrace;

/** Bounded transport workers enforce a wall-clock budget, including streaming body reads. */
public final class ModelHttpDeadline {
    private static final ExecutorService WORKERS = new ThreadPoolExecutor(0, 8, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), task -> {
                Thread thread = new Thread(task, "model-http");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    /** 初始化 ModelHttpDeadline，供本文件定义的构建入口使用。 */
    private ModelHttpDeadline() { }

    /**
     * 计算包围底层模型请求的外部截止时间。
     * 上游为 AgentGatewayClient.post、AgentGatewayClient.postSse。
     * 下游为 ModelManagementException.badRequest。
     *
     * @param execution 含当前操作所需属性的映射
     * @param fallback 原始值缺失或无效时使用的备选值
     * @return 用于约束底层 HTTP 操作的外部截止毫秒数
     */
    public static long outerTimeout(Map<String, Object> execution, long fallback) {
        if (execution == null) return fallback;
        Object value = execution.get("budgetMs");
        if (!(value instanceof Number n) || n.longValue() < 100 || n.longValue() > 600000)
            throw ModelManagementException.badRequest("模型执行总预算无效");
        return n.longValue() + 5000L;
    }

    /**
     * 在模型工作线程内运行任务，超过调用期限时取消并报告异常。
     * 上游为 AgentGatewayClient.post、AgentGatewayClient.postSse。
     * 下游为 RequestIdContext.current、ModelExecutionContext.wrap。
     *
     * @param timeoutMs 本次调用的最长等待时间（毫秒）
     * @param operation 由此处在对应上下文或作用域内执行的 Callable&lt;T&gt; 回调
     * @param body 调用方传入的请求体
     * @return 工作线程实际完成返回的结果，等待超时则抛出异常
     */
    public static <T> T run(long timeoutMs, Callable<T> operation, AtomicReference<? extends Closeable> body) throws Exception {
        String requestId = RequestIdContext.current();
        RagTrace trace = RagTraceContext.get();
        Future<T> work;

        try {
            work = WORKERS.submit(ModelExecutionContext.wrap(() -> {
                RagTrace previous = RagTraceContext.get();
                try (RequestIdContext.Scope ignored = RequestIdContext.open(requestId)) {
                    if (trace == null) RagTraceContext.clear(); else RagTraceContext.set(trace);
                    return operation.call();
                } finally {
                    if (previous == null) RagTraceContext.clear(); else RagTraceContext.set(previous);
                }
            }));
        } catch (RejectedExecutionException capacity) {
            throw ModelManagementException.unavailable("模型传输并发已满，请稍后重试");
        }
        try {
            return work.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            work.cancel(true);
            close(body);
            throw new HttpTimeoutException("模型服务请求超过总预算");
        } catch (InterruptedException cancelled) {
            work.cancel(true);
            close(body);
            throw cancelled;
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception exception) throw exception;
            throw new IllegalStateException("模型传输失败");
        } finally {
            if (work.isCancelled()) close(body);
        }
    }

    /**
     * 关闭仍在读取的响应体。
     * 上游为 ModelHttpDeadline.run。
     *
     * @param body 调用方传入的请求体
     */
    private static void close(AtomicReference<? extends Closeable> body) {
        if (body == null) return;
        Closeable stream = body.get();
        if (stream != null) {
            try { stream.close(); } catch (Exception ignored) { }
        }
    }
}
