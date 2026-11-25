/* 验证评测回归对核心指标变化、门槛、历史基线和辅助指标的判定。 */

package com.pony.knowledge.evaluation.regression;

import com.pony.knowledge.dto.MetricAggregateDTO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 两层三态回归引擎（P2-1）：每指标 IMPROVED/REGRESSED/UNCHANGED + 整体 verdict，
 * passed = verdict != REGRESSED；整体只权衡 QUALITY 维度关键指标集。
 */
class RegressionEvaluatorTest {

    private final RegressionEvaluator ev = new RegressionEvaluator();

    /** 核对 {@code v.getVerdict()} 与预期 {@code "REGRESSED"} 一致。调用路径经过 {@code base.setRecallAtK}。 */
    @Test
    void keyMetricRegressed_overallRegressedAndFail() {
        MetricAggregateDTO base = new MetricAggregateDTO();
        base.setRecallAtK(0.9);
        base.setFaithfulness(0.9);
        base.setAnswerCorrectness(0.9);
        MetricAggregateDTO cur = new MetricAggregateDTO();
        cur.setRecallAtK(0.3);
        cur.setFaithfulness(0.9);
        cur.setAnswerCorrectness(0.9);
        RegressionVerdict v = ev.compare(base, cur, null);
        assertEquals("REGRESSED", v.getVerdict());
        assertFalse(v.isPassed());
    }

    /** 核对 {@code v.getVerdict()} 与预期 {@code "IMPROVED"} 一致。调用路径经过 {@code base.setRecallAtK}。 */
    @Test
    void keyMetricImproved_overallImprovedAndPass() {
        MetricAggregateDTO base = new MetricAggregateDTO();
        base.setRecallAtK(0.4);
        base.setFaithfulness(0.9);
        base.setAnswerCorrectness(0.9);
        MetricAggregateDTO cur = new MetricAggregateDTO();
        cur.setRecallAtK(0.9);
        cur.setFaithfulness(0.9);
        cur.setAnswerCorrectness(0.9);
        RegressionVerdict v = ev.compare(base, cur, null);
        assertEquals("IMPROVED", v.getVerdict());
        assertTrue(v.isPassed());
    }

    /** 核对 {@code v.getVerdict()} 与预期 {@code "UNCHANGED"} 一致。调用路径经过 {@code base.setRecallAtK}。 */
    @Test
    void noMeaningfulChange_unchangedAndPass() {
        MetricAggregateDTO base = new MetricAggregateDTO();
        base.setRecallAtK(0.8);
        base.setFaithfulness(0.9);
        base.setAnswerCorrectness(0.9);
        MetricAggregateDTO cur = new MetricAggregateDTO();
        cur.setRecallAtK(0.82);
        cur.setFaithfulness(0.9);
        cur.setAnswerCorrectness(0.9);
        RegressionVerdict v = ev.compare(base, cur, null);
        assertEquals("UNCHANGED", v.getVerdict());
        assertTrue(v.isPassed());
    }

    /** 核对 {@code v.isPassed()} 为假。调用路径经过 {@code cur.setRecallAtK}。 */
    @Test
    void noBaseline_notApplicableFail() {
        MetricAggregateDTO cur = new MetricAggregateDTO();
        cur.setRecallAtK(0.9);
        RegressionVerdict v = ev.compare(null, cur, null);
        assertFalse(v.isPassed());
        assertEquals("UNCHANGED", v.getVerdict());
    }

    /** 核对 {@code v.getVerdict()} 与预期 {@code "UNCHANGED"} 一致。调用路径经过 {@code base.setRecallAtK}。 */
    @Test
    void latencyDelta_recordedButNotInOverall() {
        MetricAggregateDTO base = new MetricAggregateDTO();
        base.setRecallAtK(0.8);
        base.setFaithfulness(0.9);
        base.setAnswerCorrectness(0.9);
        base.setAvgLatencyMs(100.0);
        MetricAggregateDTO cur = new MetricAggregateDTO();
        cur.setRecallAtK(0.8);
        cur.setFaithfulness(0.9);
        cur.setAnswerCorrectness(0.9);
        cur.setAvgLatencyMs(500.0);
        RegressionVerdict v = ev.compare(base, cur, null);
        assertEquals("UNCHANGED", v.getVerdict());
        assertTrue(v.isPassed());
        MetricDelta lat = find(v, "avgLatencyMs");
        assertEquals("REGRESSED", lat.getVerdict());
        assertEquals("LATENCY", lat.getDimension().name());
    }

    /**
     * 判分阈值低于噪声底(0.10)时自动抬到噪声底：-0.05 的 faithfulness 变化（若用 0.03 阈值
     * 会误判 REGRESSED）判 UNCHANGED，避免把单采样 judge 噪声当成退化。
     */
    /** 核对 {@code f.getVerdict()} 与预期 {@code "UNCHANGED"} 一致。调用路径经过 {@code th.setMaxFaithfulnessDrop}。 */
    @Test
    void judgeThresholdBelowNoiseFloor_isClampedToFloor() {
        RegressionThreshold th = new RegressionThreshold();
        th.setMaxFaithfulnessDrop(0.03);
        MetricAggregateDTO base = new MetricAggregateDTO();
        base.setRecallAtK(0.8);
        base.setFaithfulness(0.9);
        base.setAnswerCorrectness(0.9);
        MetricAggregateDTO cur = new MetricAggregateDTO();
        cur.setRecallAtK(0.8);
        cur.setFaithfulness(0.85); // -0.05，在噪声底内，不应判退化
        cur.setAnswerCorrectness(0.9);
        RegressionVerdict v = ev.compare(base, cur, th);
        MetricDelta f = find(v, "faithfulness");
        assertEquals(RegressionThreshold.JUDGE_METRIC_NOISE_FLOOR, f.getThreshold(), 1e-9);
        assertEquals("UNCHANGED", f.getVerdict());
        assertEquals("UNCHANGED", v.getVerdict());
        assertTrue(v.isPassed());
    }

    /** 核对 {@code recall.getVerdict()} 与预期 {@code "IMPROVED"} 一致。调用路径经过 {@code base.setRecallAtK}。 */
    @Test
    void relativeDeltaPositiveOnImprove() {
        MetricAggregateDTO base = new MetricAggregateDTO();
        base.setRecallAtK(0.4);
        MetricAggregateDTO cur = new MetricAggregateDTO();
        cur.setRecallAtK(0.9);
        RegressionVerdict v = ev.compare(base, cur, null);
        MetricDelta recall = find(v, "recallAtK");
        assertEquals(0.5, recall.getAbsoluteDelta(), 1e-9);
        assertEquals(1.25, recall.getRelativeDelta(), 1e-9); // 0.5/0.4
        assertEquals("IMPROVED", recall.getVerdict());
    }

    /**
     * 构造 {@code MetricDelta} 供测试使用，使用 {@code v.getDeltas}、{@code d.getMetric}。
     * 由 {@code latencyDelta_recordedButNotInOverall}、{@code judgeThresholdBelowNoiseFloor_isClampedToFloor} 调用。
     * @param v 本用例传入的{@code v}
     * @param metric 本用例传入的{@code metric}
     * 返回 {@code MetricDelta} 结果。
     */
    private MetricDelta find(RegressionVerdict v, String metric) {
        return v.getDeltas().stream().filter(d -> d.getMetric().equals(metric)).findFirst().orElseThrow();
    }

    // ---- precision@n：新增指标必须容忍「老基线没有该字段」 ----

    /**
     * 老基线文件里没有 precisionAtN（反序列化后为 null）→ 该对比项 applicable=false、UNCHANGED，
     * **绝不能当 0 判成 REGRESSED**。current 有值 0.9 也不该触发退化。
     */
    /** 核对 {@code pn.isApplicable()} 为假。调用路径经过 {@code base.setRecallAtK}。 */
    @Test
    void precisionAtN_missingInBaseline_isNotApplicable_notRegression() {
        MetricAggregateDTO base = new MetricAggregateDTO(); // 老基线：无 precisionAtN
        base.setRecallAtK(0.8);
        base.setFaithfulness(0.9);
        base.setAnswerCorrectness(0.9);
        MetricAggregateDTO cur = new MetricAggregateDTO();
        cur.setRecallAtK(0.8);
        cur.setFaithfulness(0.9);
        cur.setAnswerCorrectness(0.9);
        cur.setPrecisionAtN(0.9);
        RegressionVerdict v = ev.compare(base, cur, null);
        MetricDelta pn = find(v, "precisionAtN");
        assertFalse(pn.isApplicable());
        assertEquals("UNCHANGED", pn.getVerdict());
        assertTrue(v.isPassed());
        assertEquals("UNCHANGED", v.getVerdict());
    }

    /** 核对 {@code pn.isApplicable()} 为真。调用路径经过 {@code base.setRecallAtK}。 */
    @Test
    void precisionAtN_drop_recordedButNotKeyMetric() {
        MetricAggregateDTO base = new MetricAggregateDTO();
        base.setRecallAtK(0.8);
        base.setFaithfulness(0.9);
        base.setAnswerCorrectness(0.9);
        base.setPrecisionAtN(0.9);
        MetricAggregateDTO cur = new MetricAggregateDTO();
        cur.setRecallAtK(0.8);
        cur.setFaithfulness(0.9);
        cur.setAnswerCorrectness(0.9);
        cur.setPrecisionAtN(0.3);
        RegressionVerdict v = ev.compare(base, cur, null);
        MetricDelta pn = find(v, "precisionAtN");
        assertTrue(pn.isApplicable());
        assertEquals("REGRESSED", pn.getVerdict());
        assertEquals(0.3, pn.getThreshold(), 1e-9);
        assertTrue(v.isPassed());
        assertEquals("UNCHANGED", v.getVerdict());
    }

    /** 核对 {@code v.getVerdict()} 与预期 {@code "UNCHANGED"} 一致。调用路径经过 {@code base.setRecallAtK}。 */
    @Test
    void precisionAtN_improve_recordedButNotKeyMetric() {
        MetricAggregateDTO base = new MetricAggregateDTO();
        base.setRecallAtK(0.8);
        base.setPrecisionAtN(0.3);
        MetricAggregateDTO cur = new MetricAggregateDTO();
        cur.setRecallAtK(0.8);
        cur.setPrecisionAtN(0.9);
        RegressionVerdict v = ev.compare(base, cur, null);
        assertEquals("IMPROVED", find(v, "precisionAtN").getVerdict());
        assertEquals("UNCHANGED", v.getVerdict());
        assertTrue(v.isPassed());
    }
}
