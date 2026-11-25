/* 定义回归指标的质量、延迟、成本与可靠性维度。 */

package com.pony.knowledge.evaluation.regression;

/**
 * 回归指标维度（3.9g 扩展：每指标不只比差值，还归类到质量/延迟/成本/可靠性）。
 * 4.2 P2 Overall 只权衡 QUALITY；LATENCY/COST/RELIABILITY 记录进 MetricDelta 但不参与整体判定。
 */
public enum MetricDimension {
    QUALITY,
    LATENCY,
    COST,
    RELIABILITY
}
