/* 建立 RAG 根与阶段观测，并把分段用量写入指标和轨迹。 */

package com.pony.knowledge.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import com.pony.knowledge.exception.RagQueryFailureException;
import com.pony.knowledge.exception.ConversationStoreUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 统一记录 RAG 阶段的 Micrometer 指标和业务 Trace span：前者汇总整体表现，后者保留单次请求的阶段明细。
 */
@Component
public class RagStageTracer {

    private static final Logger log = LoggerFactory.getLogger(RagStageTracer.class);

    private final ObservationRegistry observationRegistry;
    private final MeterRegistry meterRegistry;
    private final RagMetrics ragMetrics;
    private final RagPerformanceBudget budget;
    private final RagTraceRecorder traceRecorder;
    private final Tracer tracer;
    private final double llmPricePer1mTokens;
    private final TraceStore traceStore;

    private static final Map<String, TraceStage> STAGE_MAP = Map.of(
            "rewrite", TraceStage.QUERY_REWRITE,
            "retrieval", TraceStage.FUSION,
            "rerank", TraceStage.RERANK,
            "compression", TraceStage.COMPRESSION,
            "generation", TraceStage.LLM_GENERATION);

    /**
     * 保存建立 RAG 根与阶段观测所需的依赖和初始配置。
     * 上游为 Spring 组件装配。
     *
     * @param observationRegistry 本次操作所用的 ObservationRegistry 数据
     * @param meterRegistry 注册与记录监控指标的 Micrometer 注册表
     * @param ragMetrics 本次操作使用的 RagMetrics 组件或记录
     * @param budget 本次操作使用的 RagPerformanceBudget 组件或记录
     * @param traceRecorder 本次操作使用的 RagTraceRecorder 组件或记录
     * @param tracer 本次操作所用的 Tracer 数据
     * @param llmPricePer1mTokens 初始化或更新同名字段的值
     * @param traceStore TraceStore，负责约定轨迹保存和按所有者读取的存储接口
     */
    public RagStageTracer(ObservationRegistry observationRegistry, MeterRegistry meterRegistry,
                          RagMetrics ragMetrics, RagPerformanceBudget budget,
                          RagTraceRecorder traceRecorder, Tracer tracer,
                          @Value("${rag.llm-price-per-1m-tokens:2.0}") double llmPricePer1mTokens,
                          TraceStore traceStore) {
        this.observationRegistry = observationRegistry;
        this.meterRegistry = meterRegistry;
        this.ragMetrics = ragMetrics;
        this.budget = budget;
        this.traceRecorder = traceRecorder;
        this.tracer = tracer;
        this.llmPricePer1mTokens = llmPricePer1mTokens;
        this.traceStore = traceStore;
    }

    /**
     * 建立或复用请求根观测，在处理完成后发布阶段和结果。
     * 上游为 ToolExecutor.execute、KnowledgeSearchController.search。
     *
     * @param endpoint 下游服务或 MCP 传输入口地址
     * @param work 由此处在对应上下文或作用域内执行的 Supplier&lt;T&gt; 回调
     * @return 业务回调实际返回的结果
     */
    public <T> T observeRoot(String endpoint, Supplier<T> work) {
        return observeRoot(endpoint, null, work);
    }

    /**
     * 建立或复用请求根观测，在处理完成后发布阶段和结果。
     * 上游为 ToolExecutor.execute、KnowledgeSearchController.search。
     * 下游为 RagTrace.builder、RagMetrics.countError。
     *
     * @param endpoint 下游服务或 MCP 传输入口地址
     * @param ownerUserId 要定位的关联标识
     * @param work 由此处在对应上下文或作用域内执行的 Supplier&lt;T&gt; 回调
     * @return 业务回调实际返回的结果
     */
    public <T> T observeRoot(String endpoint, Long ownerUserId, Supplier<T> work) {
        Observation observation = Observation.createNotStarted("rag.request", observationRegistry)
                .lowCardinalityKeyValue("endpoint", endpoint)
                .start();
        Timer.Sample sample = Timer.start(meterRegistry);
        // 支持嵌套调用：保存外层根 Trace，并在结束时恢复。
        RagTrace previous = RagTraceContext.get();

        try (Observation.Scope scope = observation.openScope()) {
            RagTraceContext.set(RagTrace.builder().traceId(traceId()).userId(ownerUserId)
                    .startTime(System.nanoTime()).startTimeEpochMs(System.currentTimeMillis()).build());
            return work.get();
        } catch (Exception e) {
            RagTrace trace = RagTraceContext.get();
            if (trace != null && e instanceof RagQueryFailureException failure) {
                trace.setOutcome(failure.getOutcome());
                trace.setReasonCode(failure.getReasonCode());
            } else if (trace != null && e instanceof ConversationStoreUnavailableException) {
                trace.setOutcome("dependency_unavailable");
                trace.setReasonCode("redis_unavailable");
            }
            observation.error(e);
            ragMetrics.countError("request");
            throw e;
        } finally {
            try {
                observation.stop();
            } catch (Exception stopEx) {
                log.warn("Observation stop failed for stage={}", "request", stopEx);
            }
            long elapsedMs = sample.stop(ragMetrics.timer("request")) / 1_000_000L;
            ragMetrics.countRequest(endpoint);
            RagTrace trace = RagTraceContext.get();
            if (trace != null) {
                trace.setEndTime(System.nanoTime());
                logCompactTrace(trace, elapsedMs);
                try {
                    traceStore.save(trace, endpoint);
                } catch (Exception saveEx) {
                    log.warn("RAG trace 落库失败 traceId={}", trace.getTraceId(), saveEx);
                }
            }
            // 恢复外层根 Trace（无外层时等价于原来的 clear）
            if (previous != null) {
                RagTraceContext.set(previous);
            } else {
                RagTraceContext.clear();
            }
        }
    }

    /**
     * 围绕指定逻辑记录阶段耗时、成功状态与调试信息。
     * 下游为 RagTraceRecorder.start、RagMetrics.countError。
     *
     * @param stage 执行阶段标识
     * @param work 由此处在对应上下文或作用域内执行的 Supplier&lt;T&gt; 回调
     * @return 业务回调实际返回的结果
     */
    public <T> T observe(String stage, Supplier<T> work) {
        Observation observation = Observation.createNotStarted("rag.stage." + stage, observationRegistry)
                .lowCardinalityKeyValue("stage", stage)
                .start();
        Timer.Sample sample = Timer.start(meterRegistry);
        RagSpan span = traceRecorder.start(STAGE_MAP.getOrDefault(stage, TraceStage.FUSION));
        Throwable failure = null;
        T result = null;

        try (Observation.Scope scope = observation.openScope()) {
            result = work.get();
            return result;
        } catch (Exception e) {
            failure = e;
            observation.error(e);
            ragMetrics.countError(stage);
            throw e;
        } finally {
            try {
                observation.stop();
            } catch (Exception stopEx) {
                log.warn("Observation stop failed for stage={}", stage, stopEx);
            }
            long elapsedMs = sample.stop(ragMetrics.timer(stage)) / 1_000_000L;
            budget.checkElapsed(stage, elapsedMs);
            recordDegradation(stage, span, result);   // 必须在 finish 之前：finish 把同一个 span 塞进 trace
            traceRecorder.finish(span, failure == null, failure == null ? null : failure.getMessage());
        }
    }

    /**
     * 将降级原因与解析失败量写入阶段观测。
     * 上游为 RagStageTracer.observe。
     * 下游为 RagMetrics.countPartial、RagMetrics.countDegraded。
     *
     * @param stage 执行阶段标识
     * @param span 当前要记录或转换的阶段
     * @param result 供当前流程映射、写入或判断的结果
     */
    private void recordDegradation(String stage, RagSpan span, Object result) {
        if (!(result instanceof DegradationSignal signal)) {
            return;
        }
        if (signal.getParseFailures() > 0) {
            ragMetrics.countPartial(stage, signal.getParseFailures());   // 判据③：只计数，不算降级
        }
        if (!signal.isDegraded()) {
            return;
        }
        String reason = signal.getDegradeReason();
        if (reason == null || reason.isBlank()) {
            reason = "unspecified";
        }
        ragMetrics.countDegraded(stage, reason);
        span.setDegraded(true);
        span.setDegradeReason(reason);
    }

    /**
     * 在提供的父 Span 作用域内执行回调，退出时恢复原作用域。
     *
     * @param parent 本次操作所用的 Span 数据
     * @param work 由此处在对应上下文或作用域内执行的 Supplier&lt;T&gt; 回调
     * @return 业务回调在对应轨迹作用域内的执行结果
     */
    public <T> T inTrace(Span parent, Supplier<T> work) {
        if (parent == null) {
            return work.get();
        }
        try (Tracer.SpanInScope scope = tracer.withSpan(parent)) {
            return work.get();
        }
    }

    /**
     * 从观测组件取得当前线程的 Span，组件未配置时返回空。
     * 上游为 RagStageTracer.traceId。
     *
     * @return 当前 Micrometer Span，未配置观测时为空
     */
    public Span currentSpan() {
        return tracer == null ? null : tracer.currentSpan();
    }

    /**
     * 将模型实际用量写入当前 RAG 阶段。
     *
     * @param stage 执行阶段标识
     * @param totalTokens 本阶段输入与输出合计的令牌数
     */
    public void recordStageUsage(String stage, int totalTokens) {
        recordStageUsage(stage, null, null, totalTokens);
    }

    /**
     * 将模型实际用量写入当前 RAG 阶段。
     * 下游为 RagMetrics.recordStageTokens、RagMetrics.recordCost。
     *
     * @param stage 执行阶段标识
     * @param inputTokens 消耗的输入令牌数
     * @param outputTokens 生成的输出令牌数
     * @param totalTokens 本阶段输入与输出合计的令牌数
     */
    public void recordStageUsage(String stage, Integer inputTokens, Integer outputTokens, int totalTokens) {
        if (totalTokens <= 0) {
            return;
        }
        ragMetrics.recordStageTokens(stage, totalTokens);
        double usd = totalTokens / 1_000_000.0 * llmPricePer1mTokens;
        ragMetrics.recordCost(stage, usd);
        budget.checkTokens(stage, totalTokens);
        budget.checkCost(stage, usd);
        attachUsageToSpan(stage, inputTokens, outputTokens, totalTokens, usd);
    }

    /**
     * 将输入、输出令牌及估算成本填入阶段轨迹。
     * 上游为 RagStageTracer.recordStageUsage。
     * 下游为 RagTraceContext.get。
     *
     * @param stage 执行阶段标识
     * @param inputTokens 消耗的输入令牌数
     * @param outputTokens 生成的输出令牌数
     * @param totalTokens 本阶段输入与输出合计的令牌数
     * @param usd 本方法计算或判断使用的 usd 值
     */
    private void attachUsageToSpan(String stage, Integer inputTokens, Integer outputTokens, int totalTokens, double usd) {
        RagTrace trace = RagTraceContext.get();
        if (trace == null || trace.getSpans() == null) {
            return;
        }
        TraceStage target = STAGE_MAP.get(stage);
        RagSpan span = trace.getSpans().stream()
                .filter(s -> s.getStage() == target)
                .reduce((first, second) -> second)
                .orElse(null);
        if (span != null) {
            span.setTotalTokens(totalTokens);
            span.setCostUsd(usd);
            span.setInputTokens(inputTokens);
            span.setOutputTokens(outputTokens);
        }
    }

    /**
     * 读取所在请求当前可用的轨迹 ID。
     * 上游为 RagStageTracer.observeRoot。
     * 下游为 RagStageTracer.currentSpan。
     *
     * @return 当前 Span 的轨迹标识，无 Span 时为 unknown
     */
    private String traceId() {
        Span current = currentSpan();
        return current == null ? "unknown" : current.context().traceId();
    }

    /**
     * 将本次轨迹的关键阶段和结果汇总为紧凑日志。
     * 上游为 RagStageTracer.observeRoot。
     * 下游为 RagTrace.getSpans、RagTrace.getTraceId。
     *
     * @param trace 用于读取或更新的 RAG 请求轨迹
     * @param totalMs 以毫秒计的时间限制
     */
    private void logCompactTrace(RagTrace trace, long totalMs) {
        if (trace.getSpans() == null || trace.getSpans().isEmpty()) {
            log.info("RAG trace: traceId={} totalMs={}", trace.getTraceId(), totalMs);
            return;
        }
        String spans = trace.getSpans().stream()
                .map(s -> s.getStage() + "=" + s.getDurationMs() + "ms")
                .collect(Collectors.joining(","));
        log.info("RAG trace: traceId={} totalMs={} spans=[{}]", trace.getTraceId(), totalMs, spans);
    }
}
