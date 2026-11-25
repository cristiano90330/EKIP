/* 记录 Agent 回合、模型、上下文裁减和摘要的 Micrometer 指标。 */

package com.pony.knowledge.agent.observability;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Agent 观测指标门面：Summary、Context、LLM、Request 四块统一埋点。
 * 与 RagMetrics 同一套路：Counter + Timer（P50/P95/P99 + 直方图）。
 *
 *  - summary   : 摘要耗时 / 摘要 token / 摘要失败 / 摘要超时
 *  - context   : 上下文装配耗时 / 估算与实际 token / 是否裁剪 / 裁掉条数 / 裁剪后仍超预算
 *  - llm       : 决策 token 用量 / 治理层降级次数
 *  - request   : 请求计数 / 错误计数 / 端到端耗时
 *
 * <p>记忆层的 Redis 失败不走自定义计数器：fail-fast 后由 http.server.requests 的
 * status=500 与 exception 标签观测（actuator + micrometer-registry-prometheus 已在 classpath）。
 */
@Component
public class AgentMetrics {

    private final MeterRegistry meterRegistry;

    /**
     * 用注入依赖和构造参数建立此实例执行时使用的状态。
     *
     * @param meterRegistry 本次调用传入的 {@code MeterRegistry}
     */
    public AgentMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    // ---------- Summary ----------

    /**
     * 取得摘要任务计时器，供摘要协调器记录整次调用经过时间。
     * 上游由 {@code SummaryCoordinator.summarizeGuarded} 调用。
     *
     * @return 调用方使用的 {@code Timer}
     */
    public Timer summaryTimer() {
        return Timer.builder("agent.summary.latency")
                .publishPercentileHistogram()
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
    }

    /**
     * 将本次摘要结果的令牌数记录到分布指标。
     * 上游由 {@code SummaryCoordinator.doIncrementalSummarize} 调用。
     * 下游调用 {@code meterRegistry.counter}。
     *
     * @param tokens 本次调用传入的 {@code double}
     */
    public void recordSummaryTokens(double tokens) {
        meterRegistry.counter("agent.summary.tokens").increment(tokens);
    }

    /**
     * 按正常摘要或降级归档维度记录历史折叠前后令牌量。
     * 上游由 {@code SummaryCoordinator.doIncrementalSummarize} 调用。
     * 下游调用 {@code meterRegistry.counter}。
     *
     * @param foldedTokens 本次调用传入的 {@code int}
     * @param summaryTokens 本次调用传入的 {@code int}
     * @param degraded 本次调用传入的 {@code boolean}
     */
    public void recordSummaryCompaction(int foldedTokens, int summaryTokens, boolean degraded) {
        String mode = degraded ? "degraded" : "normal";
        meterRegistry.counter("agent.summary.folded_tokens", "mode", mode).increment(foldedTokens);
        meterRegistry.counter("agent.summary.compressed_tokens", "mode", mode).increment(summaryTokens);
        double ratio = foldedTokens <= 0 ? 0 : (double) summaryTokens / foldedTokens;
        DistributionSummary.builder("agent.summary.compression_ratio")
                .description("压缩后 ÷ 折入（<1 净收益；≥1 表示摘要没省下空间）")
                .tag("mode", mode)
                .register(meterRegistry)
                .record(ratio);
    }

    /**
     * 递增摘要计算的失败计数。
     * 上游由 {@code SummaryCoordinator.doIncrementalSummarize}、{@code SummaryCoordinator.maybeSummarize}、{@code SummaryCoordinator.summarizeGuarded} 调用。
     * 下游调用 {@code meterRegistry.counter}。
     */
    public void countSummaryFailure() {
        meterRegistry.counter("agent.summary.failure").increment();
    }

    /**
     * 统计一次采用原文归档的降级摘要。
     * 上游由 {@code SummaryCoordinator.doIncrementalSummarize} 调用。
     * 下游调用 {@code meterRegistry.counter}。
     */
    public void countSummaryDegraded() {
        meterRegistry.counter("agent.summary.degraded").increment();
    }

    /**
     * 递增摘要超时计数，便于区分总失败量中的超时。
     * 上游由 {@code SummaryCoordinator.maybeSummarize} 调用。
     * 下游调用 {@code meterRegistry.counter}。
     */
    public void countSummaryTimeout() {
        meterRegistry.counter("agent.summary.timeout").increment();
    }

    // ---------- Context ----------

    /**
     * 提供装配上下文消息的计时器。
     * 上游由 {@code DefaultContextBudgetManager.build} 调用。
     *
     * @return 调用方使用的 {@code Timer}
     */
    public Timer contextBuildTimer() {
        return Timer.builder("agent.context.build.latency")
                .publishPercentileHistogram()
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
    }

    /**
     * 记录裁减前上下文的估算令牌量。
     * 上游由 {@code DefaultContextBudgetManager.build} 调用。
     * 下游调用 {@code meterRegistry.counter}。
     *
     * @param tokens 本次调用传入的 {@code double}
     */
    public void recordContextEstimated(double tokens) {
        meterRegistry.counter("agent.context.tokens.estimated").increment(tokens);
    }

    /**
     * 记录裁减后实际准备发送的上下文令牌量。
     * 上游由 {@code DefaultContextBudgetManager.build} 调用。
     * 下游调用 {@code meterRegistry.counter}。
     *
     * @param tokens 本次调用传入的 {@code double}
     */
    public void recordContextFinal(double tokens) {
        meterRegistry.counter("agent.context.tokens.final").increment(tokens);
    }

    /**
     * 记录本次上下文构建被裁减的消息数量。
     * 上游由 {@code DefaultContextBudgetManager.build} 调用。
     * 下游调用 {@code meterRegistry.counter}。
     *
     * @param truncated 本次调用传入的 {@code double}
     */
    public void recordContextTruncated(double truncated) {
        meterRegistry.counter("agent.context.truncated").increment(truncated);
    }

    /**
     * 记录本次构建舍弃的上下文项数量。
     * 上游由 {@code DefaultContextBudgetManager.build} 调用。
     * 下游调用 {@code meterRegistry.counter}。
     *
     * @param dropped 本次调用传入的 {@code double}
     */
    public void recordContextDropped(double dropped) {
        meterRegistry.counter("agent.context.dropped").increment(dropped);
    }

    /**
     * 记录正文被裁减的消息数量。
     * 上游由 {@code DefaultContextBudgetManager.build} 调用。
     * 下游调用 {@code meterRegistry.counter}。
     *
     * @param truncated 本次调用传入的 {@code double}
     */
    public void recordContextContentTruncated(double truncated) {
        meterRegistry.counter("agent.context.content_truncated").increment(truncated);
    }

    /**
     * 为无法在允许输入预算内完成的消息构建记一次超限。
     * 上游由 {@code DefaultContextBudgetManager.build} 调用。
     * 下游调用 {@code meterRegistry.counter}。
     */
    public void countContextBudgetExceeded() {
        meterRegistry.counter("agent.context.budget_exceeded").increment();
    }

    /**
     * 记录本次上下文超过输入限制的令牌数。
     * 上游由 {@code DefaultContextBudgetManager.build} 调用。
     * 下游调用 {@code meterRegistry.counter}。
     *
     * @param tokens 本次调用传入的 {@code double}
     */
    public void recordContextOverBudgetTokens(double tokens) {
        meterRegistry.counter("agent.context.budget_exceeded.tokens").increment(tokens);
    }

    /**
     * 递增因模型上下文溢出启动的重试次数。
     * 上游由 {@code DefaultAgentRuntime.reactIteration} 调用。
     * 下游调用 {@code meterRegistry.counter}。
     */
    public void countContextOverflowRetry() {
        meterRegistry.counter("agent.context.overflow_retry").increment();
    }

    /**
     * 记录一次通过上下文缩减恢复的模型决策。
     * 上游由 {@code DefaultAgentRuntime.reactIteration} 调用。
     * 下游调用 {@code meterRegistry.counter}。
     */
    public void countContextOverflowRecovered() {
        meterRegistry.counter("agent.context.overflow_recovered").increment();
    }

    // ---------- LLM 决策 ----------

    /**
     * 分别将本轮输入和输出令牌记入计量。
     * 上游由 {@code DefaultAgentRuntime.recordUsage} 调用。
     * 下游调用 {@code meterRegistry.counter}。
     *
     * @param promptTokens 本次调用传入的 {@code int}
     * @param completionTokens 本次调用传入的 {@code int}
     */
    public void recordLlmTokens(int promptTokens, int completionTokens) {
        if (promptTokens > 0) {
            meterRegistry.counter("agent.llm.tokens.prompt").increment(promptTokens);
        }
        if (completionTokens > 0) {
            meterRegistry.counter("agent.llm.tokens.completion").increment(completionTokens);
        }
    }

    /**
     * 记录本轮发生依赖降级的模型决策。
     * 上游由 {@code DefaultAgentRuntime.degradedTurn} 调用。
     * 下游调用 {@code meterRegistry.counter}。
     */
    public void countLlmDegraded() {
        meterRegistry.counter("agent.llm.degraded").increment();
    }



    // ---------- Request ----------

    /**
     * 按 Agent 入口名称登记一次请求。
     * 上游由 {@code AgentTracer.observeRoot} 调用。
     * 下游调用 {@code meterRegistry.counter}。
     *
     * @param endpoint 本次调用传入的 {@code String}
     */
    public void countRequest(String endpoint) {
        meterRegistry.counter("agent.requests.total", "endpoint", endpoint).increment();
    }

    /**
     * 按故障所在阶段登记一个 Agent 错误。
     * 上游由 {@code AgentTracer.observeRoot} 调用。
     * 下游调用 {@code meterRegistry.counter}。
     *
     * @param stage 本次调用传入的 {@code String}
     */
    public void countError(String stage) {
        meterRegistry.counter("agent.errors.total", "stage", stage).increment();
    }

    /**
     * 取得 Agent 请求整体运行时长的计时器。
     * 上游由 {@code AgentTracer.observeRoot} 调用。
     *
     * @return 调用方使用的 {@code Timer}
     */
    public Timer requestTimer() {
        return Timer.builder("agent.request.latency")
                .publishPercentileHistogram()
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
    }
}
