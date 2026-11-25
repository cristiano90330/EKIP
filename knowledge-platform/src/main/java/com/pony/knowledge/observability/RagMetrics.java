/* 向指标注册表记录 RAG 请求、阶段、用量、失败和降级数据。 */

package com.pony.knowledge.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * RAG 观测指标门面：Counter + Timer（P50/P95/P99）。
 * 计时由 RagStageTracer 用 Timer.Sample 打点；此处提供已配好百分位的 Timer 与各类计数器。
 */
@Component
public class RagMetrics {

    private final MeterRegistry meterRegistry;
    private final String version;

    /**
     * 保存向指标注册表记录 RAG 请求、阶段、用量、失败和降级数据所需的依赖和初始配置。
     * 上游为 Spring 组件装配。
     *
     * @param meterRegistry 注册与记录监控指标的 Micrometer 注册表
     * @param version 当前需要验证、记录或使用的版本
     */
    public RagMetrics(MeterRegistry meterRegistry,
                      @Value("${retrieval.version:baseline}") String version) {
        this.meterRegistry = meterRegistry;
        this.version = version;
    }

    /**
     * 按入口和结果累计 RAG 请求数量。
     * 上游为 RagStageTracer.observeRoot。
     *
     * @param endpoint 下游服务或 MCP 传输入口地址
     */
    public void countRequest(String endpoint) {
        meterRegistry.counter("rag.requests.total", "endpoint", endpoint, "version", version).increment();
    }

    /**
     * 按入口和原因累计 RAG 请求错误。
     * 上游为 RagStageTracer.observeRoot、RagStageTracer.observe。
     *
     * @param stage 执行阶段标识
     */
    public void countError(String stage) {
        meterRegistry.counter("rag.errors.total", "stage", stage).increment();
    }

    /**
     * 记录问答或改写消耗的输入令牌数量。
     *
     * @param tokens 本阶段需汇总的令牌数量
     */
    public void recordInputTokens(double tokens) {
        meterRegistry.counter("rag.llm.tokens.input", "version", version).increment(tokens);
    }

    /**
     * 记录模型在 RAG 请求中产生的输出令牌数量。
     *
     * @param tokens 本阶段需汇总的令牌数量
     */
    public void recordOutputTokens(double tokens) {
        meterRegistry.counter("rag.llm.tokens.output", "version", version).increment(tokens);
    }

    /**
     * 按 RAG 阶段记录该阶段的输入或输出用量。
     * 上游为 RagStageTracer.recordStageUsage。
     *
     * @param stage 执行阶段标识
     * @param tokens 本阶段需汇总的令牌数量
     */
    public void recordStageTokens(String stage, double tokens) {
        meterRegistry.counter("rag.llm.tokens.consumed", "stage", stage, "version", version).increment(tokens);
    }

    /**
     * 记录 RAG 阶段或请求估算的美元费用。
     * 上游为 RagStageTracer.recordStageUsage。
     *
     * @param stage 执行阶段标识
     * @param usd 本方法计算或判断使用的 usd 值
     */
    public void recordCost(String stage, double usd) {
        meterRegistry.counter("rag.cost.usd", "stage", stage).increment(usd);
    }

    /**
     * 按预算类别累加超限记录。
     * 上游为 RagPerformanceBudget.checkElapsed、RagPerformanceBudget.checkTokens。
     *
     * @param stage 执行阶段标识
     */
    public void countBudgetOverrun(String stage) {
        meterRegistry.counter("rag.budget.overrun", "stage", stage).increment();
    }

    /**
     * 按降级原因计数仍返回结果的 RAG 执行。
     * 上游为 RagStageTracer.recordDegradation。
     *
     * @param stage 执行阶段标识
     * @param reason 解释本次状态或拒绝的原因
     */
    public void countDegraded(String stage, String reason) {
        meterRegistry.counter("rag.stage.degraded", "stage", stage, "reason", reason, "version", version).increment();
    }

    /**
     * 按原因累计以部分候选完成的 RAG 执行。
     * 上游为 RagStageTracer.recordDegradation。
     *
     * @param stage 执行阶段标识
     * @param units 本方法计算或判断使用的 units 值
     */
    public void countPartial(String stage, double units) {
        if (units <= 0) {
            return;
        }
        meterRegistry.counter("rag.stage.partial_degraded", "stage", stage).increment(units);
    }

    /**
     * 为调用入口和 RAG 阶段提供耗时计量器。
     * 上游为 RagStageTracer.observeRoot、RagStageTracer.observe。
     *
     * @param stage 执行阶段标识
     * @return 本次计算或查询得到的 Timer 结果
     */
    public Timer timer(String stage) {
        return Timer.builder("rag." + stage + ".latency")
                .publishPercentileHistogram()
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
    }
}
