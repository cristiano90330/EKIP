/* 验证评测样本的召回、排名及汇总统计口径。 */

package com.pony.knowledge.service.impl;

import com.pony.knowledge.dto.EvaluationResultDTO;
import com.pony.knowledge.dto.MetricAggregateDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 验证 EvaluationServiceImpl 的聚合（static aggregate）把 NDCG、precision@n 与总延迟平均进来（P1-1）。
 * 这直接决定 /evaluation/batch 的 total（run_eval 读取 ndcgAtK / precisionAtN）是否上报。
 */
class EvaluationAggregateTest {

    /** 通过 {@code EvaluationServiceImpl.aggregate} 驱动该场景，实际结果与固定预期一致。 */
    @Test
    void aggregate_ndcgAndAvgLatency() {
        EvaluationResultDTO a = new EvaluationResultDTO();
        a.setNdcgAtK(0.8);
        a.setPrecisionAtN(1.0);
        a.setLatencyMs(100L);
        EvaluationResultDTO b = new EvaluationResultDTO();
        b.setNdcgAtK(0.6);
        b.setPrecisionAtN(0.0);
        b.setLatencyMs(200L);
        MetricAggregateDTO agg = EvaluationServiceImpl.aggregate(List.of(a, b));
        assertEquals(0.7, agg.getNdcgAtK(), 1e-9);          // (0.8+0.6)/2
        assertEquals(150.0, agg.getAvgLatencyMs(), 1e-9);  // (100+200)/2
        assertEquals(0.5, agg.getPrecisionAtN(), 1e-9);    // (1.0+0.0)/2
        assertEquals(2, agg.getCaseCount());
    }

    /** 通过 {@code EvaluationServiceImpl.aggregate} 驱动该场景，实际结果与固定预期一致。 */
    @Test
    void aggregate_precisionAtNNullCountsAsZero() {
        EvaluationResultDTO a = new EvaluationResultDTO();
        a.setPrecisionAtN(1.0);
        EvaluationResultDTO b = new EvaluationResultDTO(); // precisionAtN = null
        MetricAggregateDTO agg = EvaluationServiceImpl.aggregate(List.of(a, b));
        // 分母是「用例数 = 2（而非 1）」，即 a 单独得 1.0 不会把均值抬成 1.0
        assertEquals(0.5, agg.getPrecisionAtN(), 1e-9);
    }

    /** 通过 {@code EvaluationServiceImpl.aggregate} 驱动该场景，实际结果与固定预期一致。 */
    @Test
    void aggregate_emptyNulls() {
        MetricAggregateDTO agg = EvaluationServiceImpl.aggregate(List.of());
        assertEquals(0.0, agg.getNdcgAtK(), 1e-9);
        assertEquals(0.0, agg.getAvgLatencyMs(), 1e-9);
        assertEquals(0.0, agg.getPrecisionAtN(), 1e-9);
    }

    /** 通过 {@code EvaluationServiceImpl.aggregate} 驱动该场景，实际结果与固定预期一致。 */
    @Test
    void aggregate_judgeMetricsUseOwnNonBlankCount() {
        EvaluationResultDTO a = new EvaluationResultDTO();
        a.setFaithfulness(0.9);
        a.setAnswerCorrectness(0.8);
        EvaluationResultDTO b = new EvaluationResultDTO();
        b.setAnswerCorrectness(1.0); // faithfulness 缺失

        MetricAggregateDTO agg = EvaluationServiceImpl.aggregate(List.of(a, b));

        assertEquals(0.9, agg.getFaithfulness(), 1e-9);      // 仅 a 有分 → 分母 1
        assertEquals(0.9, agg.getAnswerCorrectness(), 1e-9); // (0.8+1.0)/2 → 分母 2
    }

    /** 通过 {@code EvaluationServiceImpl.aggregate} 驱动该场景，核对输出和依赖调用。 */
    @Test
    void aggregate_allJudgeMetricsMissing_areNull() {
        MetricAggregateDTO agg = EvaluationServiceImpl.aggregate(List.of(new EvaluationResultDTO()));
        assertNull(agg.getFaithfulness());
        assertNull(agg.getAnswerCorrectness());
        assertNull(agg.getAnswerRelevance());
    }
}
