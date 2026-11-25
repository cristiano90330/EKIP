/* 验证请求结束时记录的 Trace、来源和各阶段数据。 */

package com.pony.knowledge.observability;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RagTraceRecorderTest {

    /** 核对 {@code trace.getSpans().size()} 与预期 {@code 1} 一致。调用路径经过 {@code RagTrace.builder}。 */
    @Test
    void startAndFinishAppendsSpanToCurrentTrace() {
        RagTraceRecorder recorder = new RagTraceRecorder();
        RagTrace trace = RagTrace.builder().traceId("t1").startTime(0L).build();
        RagTraceContext.set(trace);
        try {
            RagSpan span = recorder.start(TraceStage.RERANK);
            recorder.finish(span, true);

            assertEquals(1, trace.getSpans().size());
            assertEquals(TraceStage.RERANK, trace.getSpans().get(0).getStage());
            assertTrue(trace.getSpans().get(0).isSuccess());
            assertNotNull(trace.getSpans().get(0).getDurationMs());
        } finally {
            RagTraceContext.clear();
        }
    }

    /** 核对 {@code trace.getSpans().get(0).isSuccess()} 为假。调用路径经过 {@code RagTrace.builder}。 */
    @Test
    void finishWithErrorMarksFailure() {
        RagTraceRecorder recorder = new RagTraceRecorder();
        RagTrace trace = RagTrace.builder().traceId("t2").build();
        RagTraceContext.set(trace);
        try {
            RagSpan span = recorder.start(TraceStage.LLM_GENERATION);
            recorder.finish(span, false, "boom");
            assertFalse(trace.getSpans().get(0).isSuccess());
            assertEquals("boom", trace.getSpans().get(0).getErrorMessage());
        } finally {
            RagTraceContext.clear();
        }
    }

    /** 覆盖追踪的相关边界。通过 {@code recorder.start} 执行本例并核对输出或记录。 */
    @Test
    void finishWithNoCurrentTraceIsNoop() {
        RagTraceRecorder recorder = new RagTraceRecorder();
        RagSpan span = recorder.start(TraceStage.COMPRESSION);
        assertDoesNotThrow(() -> recorder.finish(span, true));
    }

    /** 核对 {@code trace.getSpans().size()} 与预期 {@code 1} 一致。调用路径经过 {@code RagTrace.builder}。 */
    @Test
    void finishIntoExplicitTraceIgnoresThreadLocal() {
        RagTraceRecorder recorder = new RagTraceRecorder();
        RagTrace trace = RagTrace.builder().traceId("t3").build();
        RagSpan span = recorder.start(TraceStage.KEYWORD_RETRIEVAL);
        recorder.finish(trace, span, true, null);
        assertEquals(1, trace.getSpans().size());
    }
}
