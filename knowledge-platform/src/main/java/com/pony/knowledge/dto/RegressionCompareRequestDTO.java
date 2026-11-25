/* 接收需要对比的基线、当前指标和可选门槛。 */

package com.pony.knowledge.dto;

import com.pony.knowledge.evaluation.regression.RegressionThreshold;
import lombok.Data;

/**
 * POST /evaluation/regression 入参：内联 baseline + current + threshold，跑两层三态回归（4.2 P2）。
 * baseline 可为 null（无法判定关键指标 → passed=false + UNCHANGED）；端点在 Java 侧复用 RegressionEvaluator。
 */
@Data
public class RegressionCompareRequestDTO {

    /** 基线聚合指标（可从 BaselineStore / run_eval 读入） */
    private MetricAggregateDTO baseline;

    /** 当前聚合指标（必填） */
    private MetricAggregateDTO current;

    /** 最小有意义变化阈值（缺省用 RegressionThreshold 默认） */
    private RegressionThreshold threshold;
}
