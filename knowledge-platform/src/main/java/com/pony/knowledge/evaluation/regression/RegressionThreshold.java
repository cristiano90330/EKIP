/* 描述关键质量指标门槛及延迟、成本可记录差值。 */

package com.pony.knowledge.evaluation.regression;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 回归判定阈值（4.2 P2）：与 Baseline 对比时的「最小有意义变化」标准。
 * 语义 = 最小有意义变化阈值（可配置，非统计显著性断言）：指标变化幅度超过阈值才判 IMPROVED/REGRESSED，
 * 未超过视为 UNCHANGED。数值沿用旧版默认（检索 0.4/0.3/0.4/0.5，生成 0.15）作保守起点，
 * 新增 NDCG/Latency/Cost 的最小有意义变化。
 */
@Data
@NoArgsConstructor
public class RegressionThreshold {

    /**
     * 判分（judge）单采样噪声底（4.2 收口，纯 B：不采样、只抬阈值）。
     * 实测 2026-08-29（qwen3.6-flash @ temperature=0，边界输入）：faithfulness/correctness
     * 多次打分 std≈0.05~0.07。判分类阈值若低于此值，单采样抖动会把噪声当退化、误判 REGRESSED。
     * 故 RegressionEvaluator 对 judge 指标阈值强制 max(配置值, 此值)。
     */
    public static final double JUDGE_METRIC_NOISE_FLOOR = 0.10;

    /** 关键质量指标集（Overall 只据此判定；默认 {Recall, Faithfulness, Correctness}） */
    private List<String> keyMetrics = new ArrayList<>(List.of("recallAtK", "faithfulness", "answerCorrectness"));

    // ---- QUALITY 维度：最小有意义变化 ----
    private Double minRecallAtK = 0.4;

    private Double minPrecisionAtK = 0.3;

    /**
     * Precision@N（窗口 n = 各用例的相关块数）的最小有意义变化。
     * 与 Precision@K 同为「命中精度」族，沿用同一保守阈值 0.3（30 个百分点）。
     * 注意：本指标**不在** {@link #keyMetrics} 默认集里 —— 与 precisionAtK 一样只作为
     * 记录项参与 MetricDelta，不单独驱动整体 verdict；这样既避免新旧基线批次（老基线无此字段）
     * 在口径切换期误判整体回归，也不改变现有 Overall 规则。
     */
    private Double minPrecisionAtN = 0.3;

    private Double minMrr = 0.4;

    private Double minHitAtK = 0.5;

    private Double minNdcgAtK = 0.2;

    private Double maxFaithfulnessDrop = 0.15;

    private Double maxCorrectnessDrop = 0.15;

    private Double maxRelevanceDrop = 0.15;

    // ---- LATENCY / COST 维度：最小有意义变化 ----
    private Double minLatencyChangeMs = 50.0;

    private Double minCostChangeUsd = 0.0005;
}
