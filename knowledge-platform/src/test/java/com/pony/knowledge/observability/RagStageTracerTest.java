/* 核对阶段 span 的测量和错误传播行为。 */

package com.pony.knowledge.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.test.simple.SimpleTracer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class RagStageTracerTest {

    private SimpleMeterRegistry registry;
    private ObservationRegistry observations;
    private RagStageTracer tracer;
    private RagTraceRecorder recorder;

    /** 每个用例前准备夹具，调用 {@code ObservationRegistry.create}。 */
    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        observations = ObservationRegistry.create();
        RagMetrics metrics = new RagMetrics(registry, "hybrid");
        // token 上限 100（避免 recordStageUsage(20) 误触发预算告警）
        RagPerformanceBudget budget = new RagPerformanceBudget(metrics, 1000L, 1000L, 1000L, 1000L, 1000L, 100L, 0.01);
        recorder = new RagTraceRecorder();
        tracer = new RagStageTracer(observations, registry, metrics, budget, recorder, null, 2.0, mock(TraceStore.class));
    }

    /** 最小降级实现：避免为了观测测试把整个 rag 包拖进来。 */
    private record DegradedSignalResult(String foo) implements DegradationSignal {
        /** 构造 {@code boolean} 供测试使用。返回 {@code true}。 */
        @Override public boolean isDegraded() { return true; }

        /** 读取夹具字段。返回 {@code "server_unreachable"}。 */
        @Override public String getDegradeReason() { return "server_unreachable"; }
    }

    private record PlainResult(String foo) { }

    /** 核对 {@code trace.getSpans().size()} 与预期 {@code 1} 一致。调用路径经过 {@code RagTrace.builder}。 */
    @Test
    void degradedResultMarksCounterAndSpan() {
        RagTrace trace = RagTrace.builder().traceId("t-degraded").build();
        RagTraceContext.set(trace);
        try {
            tracer.observe("rerank", () -> new DegradedSignalResult("x"));
        } finally {
            RagTraceContext.clear();
        }
        assertEquals(1.0, registry.get("rag.stage.degraded")
                .tag("stage", "rerank").tag("reason", "server_unreachable").counter().count());
        assertEquals(1, trace.getSpans().size());
        assertTrue(trace.getSpans().get(0).isDegraded());
        assertEquals("server_unreachable", trace.getSpans().get(0).getDegradeReason());
    }

    /** 核对 {@code registry.find("rag.stage.degraded").counter()} 为空。调用路径经过 {@code RagTrace.builder}。 */
    @Test
    void resultsWithoutTheSignalIncrementNothing() {
        // 没实现 DegradationSignal 的返回值一律不计数——三个阶段共用 observe()，
        // 这条守住的是「正常响应不会污染降级比例」。
        RagTrace trace = RagTrace.builder().traceId("t-plain").build();
        RagTraceContext.set(trace);
        try {
            tracer.observe("rerank", () -> new PlainResult("x"));
            tracer.observe("compression", () -> Boolean.FALSE);
        } finally {
            RagTraceContext.clear();
        }
        assertNull(registry.find("rag.stage.degraded").counter());
        assertEquals(2, trace.getSpans().size());
        assertFalse(trace.getSpans().get(0).isDegraded());
    }

    /**
     * 核对 {@code registry.get("rag.retrieval.latency").timer().count()} 与预期 {@code 1} 一致。
     * 调用路径经过 {@code RagTrace.builder}。
     */
    @Test
    void observeRecordsTimerAndBusinessSpan() {
        RagTrace trace = RagTrace.builder().traceId("t1").build();
        RagTraceContext.set(trace);
        try {
            tracer.observe("retrieval", () -> "done");
            assertEquals(1, registry.get("rag.retrieval.latency").timer().count());
            assertEquals(1, trace.getSpans().size());
            assertEquals(TraceStage.FUSION, trace.getSpans().get(0).getStage());
            assertTrue(trace.getSpans().get(0).isSuccess());
        } finally {
            RagTraceContext.clear();
        }
    }

    /** 断言抛出 {@code IllegalStateException}。调用路径经过 {@code RagTrace.builder}。 */
    @Test
    void observeExceptionRecordsErrorCounterAndSpanFailure() {
        RagTrace trace = RagTrace.builder().traceId("t2").build();
        RagTraceContext.set(trace);
        try {
            assertThrows(IllegalStateException.class,
                    () -> tracer.observe("rerank", () -> { throw new IllegalStateException("boom"); }));
            assertEquals(1.0, registry.get("rag.errors.total").tag("stage", "rerank").counter().count());
            assertEquals(1, trace.getSpans().size());
            assertFalse(trace.getSpans().get(0).isSuccess());
            assertEquals("boom", trace.getSpans().get(0).getErrorMessage());
        } finally {
            RagTraceContext.clear();
        }
    }

    /**
     * 核对 {@code registry.get("rag.request.latency").timer().count()} 与预期 {@code 1} 一致。
     * 调用路径经过 {@code tracer.observeRoot}。
     */
    @Test
    void observeRootCountsRequestAndRecordsLatency() {
        tracer.observeRoot("ask", () -> "ok");
        assertEquals(1.0, registry.get("rag.requests.total").tag("endpoint", "ask").counter().count());
        assertEquals(1, registry.get("rag.request.latency").timer().count());
    }

    /** 覆盖令牌的相关边界。通过 {@code tracer.recordStageUsage} 执行本例并核对输出或记录。 */
    @Test
    void recordStageUsageRecordsTokensCostAndBudget() {
        tracer.recordStageUsage("generation", 20); // 单价默认 2.0 USD/1M → cost=0.00004，不超预算
        assertEquals(20.0, registry.get("rag.llm.tokens.consumed").tag("stage", "generation").counter().count());
        assertEquals(0.00004, registry.get("rag.cost.usd").tag("stage", "generation").counter().count(), 1e-9);
    }

    /** 通过 {@code ObservationRegistry.create} 执行本例并核对输出或记录。 */
    @Test
    void observeWorksWithHandlerThatRequiresTimerSample() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ObservationRegistry observations = ObservationRegistry.create();
        observations.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            /** 构造 {@code boolean} 供测试使用。接收{@code context}。返回 {@code true}。 */
            @Override public boolean supportsContext(Observation.Context context) { return true; }

            /**
             * 在测试中处理 {@code onStart} 阶段，使用 {@code context.put}、{@code io.micrometer.core.instrument.Timer.start}。
             * @param context 本用例传入的{@code context}
             */
            @Override public void onStart(Observation.Context context) {
                context.put(io.micrometer.core.instrument.Timer.Sample.class,
                        io.micrometer.core.instrument.Timer.start(meterRegistry));
            }

            /** 在测试中处理 {@code onStop} 阶段，使用 {@code context.get}。接收{@code context}。 */
            @Override public void onStop(Observation.Context context) {
                Object sample = context.get(io.micrometer.core.instrument.Timer.Sample.class);
                if (sample == null) {
                    throw new IllegalStateException("no Timer.Sample in context");
                }
            }
        });
        RagMetrics metrics = new RagMetrics(meterRegistry, "hybrid");
        RagPerformanceBudget budget = new RagPerformanceBudget(metrics, 1000L, 1000L, 1000L, 1000L, 1000L, 100L, 0.01);
        RagStageTracer t = new RagStageTracer(observations, meterRegistry, metrics, budget,
                new RagTraceRecorder(), null, 2.0, mock(TraceStore.class));
        RagTraceContext.set(RagTrace.builder().traceId("t").build());
        try {
            assertDoesNotThrow(() -> t.observe("retrieval", () -> "ok"));
            assertDoesNotThrow(() -> t.observeRoot("ask", () -> "ok"));
        } finally {
            RagTraceContext.clear();
        }
    }

    /**
     * 用真实 tracer + 观测桥接 handler 校验 observe() 在 lambda 内留有活跃 span。
     * 若回归成 createNotStarted(...).openScope()（缺 .start()），onStart 不会创建 span，
     * currentSpan() 为 null → 此断言失败。现有 guarded stop() 测试吞掉 onStop 异常、无法捕获该回归。
     *
     * <p>2026-10-23：production 桥由 Brave 换成 OTel（为了 OTLP 导出），本测试改用
     * {@link SimpleTracer}（micrometer-tracing-test）—— 它同样是真实 Tracer，但与被测的
     * production 桥解耦，下次换桥不必再改写这条断言。
     */
    /** 覆盖追踪的相关边界。通过 {@code ObservationRegistry.create} 执行本例并核对输出或记录。 */
    @Test
    void observeKeepsActiveSpanWithRealTracer() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ObservationRegistry observations = ObservationRegistry.create();
        RagMetrics metrics = new RagMetrics(meterRegistry, "hybrid");
        RagPerformanceBudget budget = new RagPerformanceBudget(metrics, 1000L, 1000L, 1000L, 1000L, 1000L, 100L, 0.01);
        SimpleTracer realTracer = new SimpleTracer();
        observations.observationConfig().observationHandler(new DefaultTracingObservationHandler(realTracer));
        RagStageTracer t = new RagStageTracer(observations, meterRegistry, metrics, budget,
                new RagTraceRecorder(), realTracer, 2.0, mock(TraceStore.class));
        RagTraceContext.set(RagTrace.builder().traceId("t").build());
        try {
            t.observe("retrieval", () -> {
                assertNotNull(t.currentSpan(), "observe 必须留有活跃 span（缺少 .start() 时此断言失败）");
                return "ok";
            });
        } finally {
            RagTraceContext.clear();
        }
    }
}
