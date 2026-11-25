/* 把 Agent 回合的诊断状态、终态和异常写入请求级追踪。 */

package com.pony.knowledge.agent.observability;

import com.pony.knowledge.agent.model.AgentResponse;
import com.pony.knowledge.agent.model.StopReason;
import com.pony.knowledge.observability.RagTrace;
import com.pony.knowledge.observability.RagTraceContext;
import com.pony.knowledge.observability.TraceStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Agent 请求观测：包住一次 /agent/chat 端到端。
 *
 * 全链路一个 traceId 的实现方式：
 *   用一个 Micrometer Observation（agent.request）包住整个 Agent 回合，继承 HTTP 入口 span 的 traceId；
 *   工具层（ToolExecutor → RagStageTracer.observeRoot）与检索/RAG 在 Agent 观测打开期间嵌套创建子 observation，
 *   继续沿用同一 traceId。
 *
 * 记忆层失败的可追溯性由异常上抛 + GlobalExceptionHandler 的 ERROR 全栈保证，
 * logback pattern 自带 Micrometer 的 [traceId-spanId]，仍串在同一条链路上。
 * currentTraceId() 供 AgentService 回填到 AgentExecutionContext.traceId。
 */
@Component
public class AgentTracer {

    private static final Logger log = LoggerFactory.getLogger(AgentTracer.class);

    private final ObservationRegistry observationRegistry;

    private final MeterRegistry meterRegistry;

    private final AgentMetrics agentMetrics;

    private final Tracer tracer;

    private final TraceStore traceStore;

    /**
     * 用注入依赖和构造参数建立此实例执行时使用的状态。
     *
     * @param observationRegistry 本次调用传入的 {@code ObservationRegistry}
     * @param meterRegistry 本次调用传入的 {@code MeterRegistry}
     * @param agentMetrics 本次调用传入的 {@code AgentMetrics}
     * @param tracer 本次调用传入的 {@code Tracer}
     * @param traceStore 本次调用传入的 {@code TraceStore}
     */
    public AgentTracer(ObservationRegistry observationRegistry, MeterRegistry meterRegistry,
                       AgentMetrics agentMetrics, Tracer tracer, TraceStore traceStore) {
        this.observationRegistry = observationRegistry;
        this.meterRegistry = meterRegistry;
        this.agentMetrics = agentMetrics;
        this.tracer = tracer;
        this.traceStore = traceStore;
    }

    /**
     * 为本次 Agent 执行建立请求追踪，结束时记录终态或异常，并恢复外层追踪上下文。
     * 下游调用 {@code observeRoot}。
     *
     * @param endpoint 本次调用传入的 {@code String}
     * @param work 在当前观测范围内执行的业务回调
     * @return 调用方使用的 {@code T}
     */
    public <T> T observeRoot(String endpoint, Supplier<T> work) {
        return observeRoot(endpoint, null, work);
    }

    /**
     * 为本次 Agent 执行建立请求追踪，结束时记录终态或异常，并恢复外层追踪上下文。
     * 下游调用 {@code Observation.createNotStarted}、{@code Timer.start}、{@code observation.openScope}。
     *
     * @param endpoint 本次调用传入的 {@code String}
     * @param originalQuery 本次调用传入的 {@code String}
     * @param work 在当前观测范围内执行的业务回调
     * @return 当前记录中的 {@code result}
     */
    public <T> T observeRoot(String endpoint, String originalQuery, Supplier<T> work) {
        Observation observation = Observation.createNotStarted("agent.request", observationRegistry)
                .lowCardinalityKeyValue("endpoint", endpoint)
                .start();
        Timer.Sample sample = Timer.start(meterRegistry);
        RagTrace previous = RagTraceContext.get();
        try (Observation.Scope scope = observation.openScope()) {
            RagTraceContext.set(RagTrace.builder().traceId(currentTraceId()).originalQuery(originalQuery)
                    .startTime(System.nanoTime()).startTimeEpochMs(System.currentTimeMillis()).build());
            T result = work.get();
            recordResponse(RagTraceContext.get(), result);
            return result;
        } catch (RuntimeException | Error e) {
            recordFailure(RagTraceContext.get(), e);
            observation.error(e);
            agentMetrics.countError("request");
            throw e;
        } finally {
            try {
                observation.stop();
            } catch (Exception stopEx) {
                log.warn("Observation stop failed for agent.request", stopEx);
            }
            long elapsedMs = sample.stop(agentMetrics.requestTimer()) / 1_000_000L;
            agentMetrics.countRequest(endpoint);
            RagTrace trace = RagTraceContext.get();
            if (trace != null) {
                trace.setEndTime(System.nanoTime());
                logCompactTrace(trace, elapsedMs);
                try {
                    traceStore.save(trace, endpoint);
                } catch (Exception saveEx) {
                    log.warn("Agent trace 落库失败 traceId={}", trace.getTraceId(), saveEx);
                }
            }
            if (previous != null) {
                RagTraceContext.set(previous);
            } else {
                RagTraceContext.clear();
            }
        }
    }

    /**
     * 把回合响应的终态、调用次数及令牌写入当前业务追踪。
     * 上游由 {@code AgentTracer.observeRoot} 调用。
     * 下游调用 {@code response.isDegraded}、{@code stopReason.name}、{@code firstNonBlank}。
     *
     * @param trace 本次 Agent 调用的业务追踪对象
     * @param result 此次工具或管线的结果
     */
    private static void recordResponse(RagTrace trace, Object result) {
        if (trace == null || !(result instanceof AgentResponse response)) return;
        StopReason stopReason = response.getStopReason();
        if (stopReason == StopReason.CANCELLED) {
            trace.setOutcome("cancelled");
            trace.setReasonCode("agent_cancelled");
            return;
        }
        if (stopReason == StopReason.BUDGET_EXCEEDED) {
            trace.setOutcome("timeout");
            trace.setReasonCode(firstNonBlank(response.getReasonCode(), "agent_budget_exceeded"));
            return;
        }
        if (response.isDegraded() || stopReason == StopReason.LLM_DEGRADED) {
            trace.setOutcome("dependency_unavailable");
            trace.setReasonCode(firstNonBlank(response.getReasonCode(), "agent_llm_degraded"));
            return;
        }
        if (stopReason == StopReason.CONTENT_FILTERED) {
            trace.setOutcome("filtered");
            trace.setReasonCode("agent_content_filtered");
            return;
        }
        if (stopReason == StopReason.EMPTY_RESPONSE || stopReason == StopReason.STUCK_REPEATED) {
            trace.setOutcome("incomplete");
            trace.setReasonCode("agent_stop_" + stopReason.name().toLowerCase(java.util.Locale.ROOT));
            return;
        }
        if (stopReason == StopReason.FINAL_ANSWER_TRUNCATED) {
            trace.setOutcome("partial");
            trace.setReasonCode("agent_final_answer_truncated");
            return;
        }

        trace.setOutcome(firstNonBlank(response.getOutcome(), "success"));
        String reasonCode = response.getReasonCode();
        if (reasonCode == null && stopReason != null && stopReason != StopReason.FINAL_ANSWER) {
            reasonCode = "agent_stop_" + stopReason.name().toLowerCase(java.util.Locale.ROOT);
        }

        trace.setReasonCode(reasonCode);
    }

    /**
     * 根据传播出的异常给当前追踪标记故障阶段及业务终态。
     * 上游由 {@code AgentTracer.observeRoot} 调用。
     * 下游调用 {@code hasCause}。
     *
     * @param trace 本次 Agent 调用的业务追踪对象
     * @param error 用于分类与诊断的异常
     */
    private static void recordFailure(RagTrace trace, Throwable error) {
        if (trace == null) return;
        if (hasCause(error, InterruptedException.class, java.util.concurrent.CancellationException.class)) {
            trace.setOutcome("cancelled");
            trace.setReasonCode("agent_cancelled");
        } else if (hasCause(error, java.util.concurrent.TimeoutException.class)) {
            trace.setOutcome("timeout");
            trace.setReasonCode("agent_timeout");
        } else {
            trace.setOutcome("dependency_unavailable");
            trace.setReasonCode("agent_request_failed");
        }
    }

    /**
     * 沿异常传播链查找给定类别的故障，供诊断分类使用。
     * 上游由 {@code AgentTracer.recordFailure} 调用。
     *
     * @param error 用于分类与诊断的异常
     * @param types 本次调用传入的 {@code Class<? extends Throwable>[]}
     * @return 条件成立时为 true，其他状态按 false 处理
     */
    @SafeVarargs
    private static boolean hasCause(Throwable error, Class<? extends Throwable>... types) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            for (Class<? extends Throwable> type : types) {
                if (type.isInstance(cause)) return true;
            }
        }
        return false;
    }

    /**
     * 从首选值与备用值中取得可用于日志关联的标识。
     * 上游由 {@code AgentTracer.recordResponse} 调用。
     *
     * @param value 本次调用传入的 {@code String}
     * @param fallback 本次调用传入的 {@code String}
     * @return 供后续处理使用的文本或标识
     */
    private static String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * 读取当前业务追踪标识，缺失时使用请求链路的备用值。
     * 上游由 {@code AgentService.executeTurn}、{@code AgentTracer.observeRoot} 调用。
     * 下游调用 {@code tracer.currentSpan}、{@code current.context}。
     *
     * @return 供后续处理使用的文本或标识
     */
    public String currentTraceId() {
        Span current = tracer == null ? null : tracer.currentSpan();
        return current == null ? "unknown" : current.context().traceId();
    }

    /**
     * 用追踪标识、结果与耗时写一条汇总诊断信息。
     * 上游由 {@code AgentTracer.observeRoot} 调用。
     * 下游调用 {@code Collectors.joining}。
     *
     * @param trace 本次 Agent 调用的业务追踪对象
     * @param totalMs 本次调用传入的 {@code long}
     */
    private void logCompactTrace(RagTrace trace, long totalMs) {
        if (trace.getSpans() == null || trace.getSpans().isEmpty()) {
            log.info("Agent trace: traceId={} totalMs={}", trace.getTraceId(), totalMs);
            return;
        }
        String spans = trace.getSpans().stream()
                .map(s -> s.getStage() + "=" + s.getDurationMs() + "ms")
                .collect(Collectors.joining(","));
        log.info("Agent trace: traceId={} totalMs={} spans=[{}]", trace.getTraceId(), totalMs, spans);
    }
}
