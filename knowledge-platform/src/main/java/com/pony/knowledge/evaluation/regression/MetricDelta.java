/* 记录单项回归指标的变化幅度、判定和适用性。 */

package com.pony.knowledge.evaluation.regression;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单个指标的回归对比项（4.2 P2 全量记录）：baseline vs current 的绝对/相对差值 + 三态 verdict。
 * 相比旧版（仅 boolean pass），新增：dimension 归类、absoluteDelta/relativeDelta、verdict(IMPROVED/REGRESSED/UNCHANGED)。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetricDelta {

    /** 指标键，如 recallAtK / mrr / faithfulness / avgLatencyMs */
    private String metric;

    /** 维度：QUALITY / LATENCY / COST / RELIABILITY */
    private MetricDimension dimension;

    /** 人类可读标签 */
    private String label;

    private Double baselineValue;

    private Double currentValue;

    /** 差值 = current - baseline（baseline 缺失时为 null） */
    private Double absoluteDelta;

    /** 相对变化 = absoluteDelta / baseline（baseline 为 0 时为 0；baseline 缺失时为 null） */
    private Double relativeDelta;

    /** 三态判定：IMPROVED / REGRESSED / UNCHANGED */
    private String verdict;

    /** 最小有意义变化阈值 */
    private Double threshold;

    /** 基线或当前值缺失时标记为不参与判定 */
    private boolean applicable;
}
