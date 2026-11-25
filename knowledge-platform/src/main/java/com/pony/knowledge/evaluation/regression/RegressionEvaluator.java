/* 比较基线与当前指标，按阈值和质量噪声下限输出回归判定。 */

package com.pony.knowledge.evaluation.regression;

import com.pony.knowledge.dto.MetricAggregateDTO;

import java.util.ArrayList;
import java.util.List;

/**
 * 回归判定器（4.2 P2 两层三态）：把「Baseline 聚合指标」与「Current 聚合指标」对比，输出
 * 每指标 IMPROVED/REGRESSED/UNCHANGED 与整体 verdict。纯本地算术，无 Spring、无 LLM。
 *
 * 整体规则（spec §5）：
 *   REGRESSED : 任一【关键质量指标】verdict==REGRESSED
 *   IMPROVED  : 无关键质量指标退化 且 ≥1 关键指标 IMPROVED
 *   UNCHANGED : 两者都不是
 *  passed = (verdict != REGRESSED)。
 * Overall 只权衡 QUALITY 维度关键指标集；LATENCY/COST/RELIABILITY 记录进 MetricDelta 但不纳入整体。
 */
public class RegressionEvaluator {

    /**
     * 按门槛比较基准和当前质量、成本、延迟等指标，返回整体判定。
     * 上游为 EvaluationServiceImpl.regressionCompare。
     * 下游为 RegressionVerdict.builder。
     *
     * @param baseline 用于比较的基线指标
     * @param current 这次运行的当前结果或状态
     * @param threshold 用于判定的门槛
     * @return 一次评测回归的整体判定和各指标差异，以 RegressionVerdict 返回
     */
    public RegressionVerdict compare(MetricAggregateDTO baseline, MetricAggregateDTO current,
            RegressionThreshold threshold) {
        if (threshold == null) {
            threshold = new RegressionThreshold();
        }
        List<MetricDelta> deltas = new ArrayList<>();
        if (current == null) {
            return RegressionVerdict.builder().passed(false).verdict("UNCHANGED").deltas(deltas)
                    .summary("current 聚合指标为空，无法执行回归判定").build();
        }

        Double b, c;
        b = baseline == null ? null : baseline.getRecallAtK();          c = current.getRecallAtK();
        addQuality(deltas, "recallAtK", "Recall@K", b, c, threshold.getMinRecallAtK());
        b = baseline == null ? null : baseline.getPrecisionAtK();       c = current.getPrecisionAtK();
        addQuality(deltas, "precisionAtK", "Precision@K", b, c, threshold.getMinPrecisionAtK());
        // Precision@N 与 Precision@K 同族（「命中精度」），只是窗口换成该用例的相关块数。
        // null 语义对齐既有 addQuality：baseline 或 current 缺该字段（老基线文件里没有 precisionAtN）
        // → applicable=false、verdict=UNCHANGED，**不判退化**。这正是本指标可以安全新增的原因：
        // 历史 baseline_*.json 反序列化后 precisionAtN=null，回归比对按「不可比对」跳过而非当 0。
        b = baseline == null ? null : baseline.getPrecisionAtN();       c = current.getPrecisionAtN();
        addQuality(deltas, "precisionAtN", "Precision@N", b, c, threshold.getMinPrecisionAtN());
        b = baseline == null ? null : baseline.getMrr();                c = current.getMrr();
        addQuality(deltas, "mrr", "MRR@K", b, c, threshold.getMinMrr());
        b = baseline == null ? null : baseline.getHitAtK();             c = current.getHitAtK();
        addQuality(deltas, "hitAtK", "Hit@K", b, c, threshold.getMinHitAtK());
        b = baseline == null ? null : baseline.getNdcgAtK();            c = current.getNdcgAtK();
        addQuality(deltas, "ndcgAtK", "NDCG@K", b, c, threshold.getMinNdcgAtK());
        b = baseline == null ? null : baseline.getFaithfulness();       c = current.getFaithfulness();
        addQuality(deltas, "faithfulness", "Faithfulness", b, c,
                judgeFloor(threshold.getMaxFaithfulnessDrop()));
        b = baseline == null ? null : baseline.getAnswerCorrectness();  c = current.getAnswerCorrectness();
        addQuality(deltas, "answerCorrectness", "Answer Correctness", b, c,
                judgeFloor(threshold.getMaxCorrectnessDrop()));
        b = baseline == null ? null : baseline.getAnswerRelevance();    c = current.getAnswerRelevance();
        addQuality(deltas, "answerRelevance", "Answer Relevance", b, c,
                judgeFloor(threshold.getMaxRelevanceDrop()));

        b = baseline == null ? null : baseline.getAvgLatencyMs();       c = current.getAvgLatencyMs();
        addTiming(deltas, MetricDimension.LATENCY, "avgLatencyMs", "Avg Latency(ms)", b, c, threshold.getMinLatencyChangeMs());
        b = baseline == null ? null : baseline.getCostEstimateUsd();    c = current.getCostEstimateUsd();
        addTiming(deltas, MetricDimension.COST, "costEstimateUsd", "Cost(USD)", b, c, threshold.getMinCostChangeUsd());

        List<String> keys = threshold.getKeyMetrics() == null ? List.of() : threshold.getKeyMetrics();
        boolean anyKeyApplicable = false, keyRegressed = false, keyImproved = false;

        for (MetricDelta d : deltas) {
            if (!d.isApplicable() || !keys.contains(d.getMetric())) {
                continue;
            }
            anyKeyApplicable = true;
            if ("REGRESSED".equals(d.getVerdict())) {
                keyRegressed = true;
            } else if ("IMPROVED".equals(d.getVerdict())) {
                keyImproved = true;
            }
        }

        String verdict;

        if (keyRegressed) {
            verdict = "REGRESSED";
        } else if (keyImproved) {
            verdict = "IMPROVED";
        } else {
            verdict = "UNCHANGED";
        }
        boolean passed = anyKeyApplicable && !keyRegressed;
        String summary;

        if (!anyKeyApplicable) {
            summary = "无适用关键质量指标（缺基线或指标缺数），无法判定";
        } else if ("REGRESSED".equals(verdict)) {
            summary = "关键质量指标退化，需回退检查";
        } else if ("IMPROVED".equals(verdict)) {
            summary = "关键质量指标提升";
        } else {
            summary = "关键质量指标无有意义变化";
        }
        return RegressionVerdict.builder().passed(passed).verdict(verdict).deltas(deltas).summary(summary).build();
    }

    /**
     * 为裁判质量指标应用预设噪声下限。
     * 上游为 RegressionEvaluator.compare。
     *
     * @param configured 本方法计算或判断使用的 configured 值
     * @return 套用裁判质量噪声容忍下限后的门槛
     */
    private double judgeFloor(double configured) {
        return Math.max(configured, RegressionThreshold.JUDGE_METRIC_NOISE_FLOOR);
    }

    /**
     * 计算质量指标差异并判断可能的退化。
     * 上游为 RegressionEvaluator.compare。
     * 下游为 MetricDelta.builder。
     *
     * @param deltas 需要本方法按序读取或筛选的 List&lt;MetricDelta&gt; 数据
     * @param metric 本方法计算或判断使用的 metric 值
     * @param label 本方法计算或判断使用的 label 值
     * @param baseline 用于比较的基线指标
     * @param current 这次运行的当前结果或状态
     * @param minChange 本方法计算或判断使用的 minChange 值
     */
    private void addQuality(List<MetricDelta> deltas, String metric, String label,
            Double baseline, Double current, Double minChange) {
        if (current == null || baseline == null) {
            deltas.add(MetricDelta.builder().metric(metric).dimension(MetricDimension.QUALITY)
                    .label(label).baselineValue(baseline).currentValue(current)
                    .threshold(minChange).verdict("UNCHANGED").applicable(false).build());
            return;
        }
        double absDelta = current - baseline;
        double rel = baseline == 0.0 ? 0.0 : absDelta / baseline;
        String verdict = Math.abs(absDelta) < minChange ? "UNCHANGED"
                : (absDelta > 0 ? "IMPROVED" : "REGRESSED");
        deltas.add(MetricDelta.builder().metric(metric).dimension(MetricDimension.QUALITY).label(label)
                .baselineValue(baseline).currentValue(current).absoluteDelta(absDelta).relativeDelta(rel)
                .threshold(minChange).verdict(verdict).applicable(true).build());
    }

    /**
     * 登记耗时或成本变化以及受阈值影响的结果。
     * 上游为 RegressionEvaluator.compare。
     * 下游为 MetricDelta.builder。
     *
     * @param deltas 需要本方法按序读取或筛选的 List&lt;MetricDelta&gt; 数据
     * @param dim 本次操作使用的 MetricDimension 组件或记录
     * @param metric 本方法计算或判断使用的 metric 值
     * @param label 本方法计算或判断使用的 label 值
     * @param baseline 用于比较的基线指标
     * @param current 这次运行的当前结果或状态
     * @param minChange 本方法计算或判断使用的 minChange 值
     */
    private void addTiming(List<MetricDelta> deltas, MetricDimension dim, String metric, String label,
            Double baseline, Double current, Double minChange) {
        if (current == null || baseline == null) {
            deltas.add(MetricDelta.builder().metric(metric).dimension(dim).label(label)
                    .baselineValue(baseline).currentValue(current)
                    .threshold(minChange).verdict("UNCHANGED").applicable(false).build());
            return;
        }
        double absDelta = current - baseline;
        double rel = baseline == 0.0 ? 0.0 : absDelta / baseline;
        String verdict = Math.abs(absDelta) < minChange ? "UNCHANGED"
                : (absDelta < 0 ? "IMPROVED" : "REGRESSED");
        deltas.add(MetricDelta.builder().metric(metric).dimension(dim).label(label)
                .baselineValue(baseline).currentValue(current).absoluteDelta(absDelta).relativeDelta(rel)
                .threshold(minChange).verdict(verdict).applicable(true).build());
    }
}
