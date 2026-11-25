/* 初始化和结束 RAG 轨迹，并录入外部服务汇报的阶段。 */

package com.pony.knowledge.observability;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;

/**
 * 业务 Trace 记录器：记录单次 RAG 请求每个阶段的耗时/成败，回答「这一次为什么慢」。
 * start() 记开始，finish() 补 endTime/durationMs 并追加到当前 RagTrace（ThreadLocal）。
 * 并行检索的 worker 线程用 finish(RagTrace, ...) 显式写入调用线程的 trace。
 */
@Component
public class RagTraceRecorder {

    /**
     * 建立轨迹标识、入口、执行策略与当前账号信息。
     * 上游为 RagStageTracer.observe。
     * 下游为 RagSpan.builder。
     *
     * @param stage 执行阶段标识
     * @return RAG 单阶段执行时间、用量、错误和降级信息，以 RagSpan 返回
     */
    public RagSpan start(TraceStage stage) {
        return RagSpan.builder()
                .stage(stage)
                .startTime(System.nanoTime())
                .success(true)
                .build();
    }

    /**
     * 结束当前轨迹并填入结束时间。
     * 上游为 RagStageTracer.observe。
     *
     * @param span 当前要记录或转换的阶段
     * @param success 操作是否成功结束
     */
    public void finish(RagSpan span, boolean success) {
        finish(span, success, null);
    }

    /**
     * 结束当前轨迹并填入结束时间。
     * 上游为 RagStageTracer.observe。
     * 下游为 RagTraceContext.get。
     *
     * @param span 当前要记录或转换的阶段
     * @param success 操作是否成功结束
     * @param errorMessage 可记录或返回的错误详情
     */
    public void finish(RagSpan span, boolean success, String errorMessage) {
        finish(RagTraceContext.get(), span, success, errorMessage);
    }

    /**
     * 结束当前轨迹并填入结束时间。
     * 上游为 RagStageTracer.observe。
     * 下游为 RagSpan.setEndTime、RagSpan.setDurationMs。
     *
     * @param trace 用于读取或更新的 RAG 请求轨迹
     * @param span 当前要记录或转换的阶段
     * @param success 操作是否成功结束
     * @param errorMessage 可记录或返回的错误详情
     */
    public void finish(RagTrace trace, RagSpan span, boolean success, String errorMessage) {
        if (span == null) {
            return;
        }
        long endTime = System.nanoTime();
        span.setEndTime(endTime);
        span.setDurationMs((endTime - span.getStartTime()) / 1_000_000);
        span.setSuccess(success);
        span.setErrorMessage(errorMessage);
        if (trace == null) {
            return;
        }
        if (trace.getSpans() == null) {
            // 并行检索的多个 worker 线程会并发向同一 RagTrace.spans 追加，必须用同步列表防竞态。
            trace.setSpans(Collections.synchronizedList(new ArrayList<>()));
        }
        trace.getSpans().add(span);
    }

    /**
     * 将上游服务传来的阶段耗时及用量附加到 Java 轨迹。
     * 上游为 PythonRagPipeline.recordStages。
     * 下游为 RagSpan.builder。
     *
     * @param trace 用于读取或更新的 RAG 请求轨迹
     * @param stage 执行阶段标识
     * @param durationMs 以毫秒计的时间限制
     * @param success 操作是否成功结束
     * @param errorMessage 可记录或返回的错误详情
     * @param degraded 本方法计算或判断使用的 degraded 值
     * @param totalTokens 本阶段输入与输出合计的令牌数
     */
    public void recordExternal(RagTrace trace, TraceStage stage, long durationMs,
            boolean success, String errorMessage, boolean degraded, Integer totalTokens) {
        if (trace == null || stage == null) {
            return;
        }
        long endTime = System.nanoTime();
        RagSpan span = RagSpan.builder()
                .stage(stage)
                .startTime(endTime - durationMs * 1_000_000L)
                .endTime(endTime)
                .durationMs(durationMs)
                .success(success)
                .errorMessage(errorMessage)
                .degraded(degraded)
                .totalTokens(totalTokens)
                .build();
        if (trace.getSpans() == null) {
            trace.setSpans(Collections.synchronizedList(new ArrayList<>()));
        }
        trace.getSpans().add(span);
    }
}
