/* 验证检索排序、命中范围、空输入及批次聚合下的评测指标。 */

package com.pony.knowledge.evaluation.retrieval;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 与 Python ai/app/evaluation/evaluator.py 的口径逐字对齐：
 *  recall@k = |retrieved[:k] ∩ relevant| / |relevant|（relevant 空→0）
 *  precision@k = hits / len(retrieved[:k])（分母=实际检索条数，非 k）
 *  mrr = 第一个相关中完整检索列表中的 1-based 位次倒数
 *  hit@k = 0/1
 *  precision@n = hits / min(n, retrieved.size())，n = |relevant|（低标注密度下替代 precision@k）
 *  nDCG@k = DCG@k / IDCG@k，IDCG 项数 = min(k, |relevant|)（Java 独有，Python 侧无对应实现）
 * 聚焦「precision 分母不是 k」「mrr 用完整列表」「relevant 空」「k 超界」「nDCG 的 IDCG 项数」
 * 这几个易漂移点。
 */
class RetrievalEvaluatorTest {

    private final RetrievalEvaluator evaluator = new RetrievalEvaluator();

    /**
     * 更新捕获的夹具字段，使用 {@code Arrays.asList}。
     * 由 {@code precisionAtN_directContract} 调用。
     * 接收{@code ids}。
     * 返回 {@code new HashSet<>(Arrays.asList(ids))}。
     */
    private static Set<Long> set(Long... ids) {
        return new HashSet<>(Arrays.asList(ids));
    }

    /** 核对 {@code r.getRecallAtK()} 与预期 {@code 0.0} 一致。调用路径经过 {@code evaluator.evaluate}。 */
    @Test
    void recallRelevantEmpty_isZero() {
        RetrievalEvaluationResult r = evaluator.evaluate(
                List.of(10L, 11L, 12L), List.of(), 5);
        assertEquals(0.0, r.getRecallAtK());
        assertEquals(0.0, r.getHitAtK());
    }

    /** 核对 {@code r.getRecallAtK()} 与预期 {@code 1.0} 一致。调用路径经过 {@code evaluator.evaluate}。 */
    @Test
    void basicHitAtFirstRank() {
        RetrievalEvaluationResult r = evaluator.evaluate(
                List.of(10L, 11L, 12L, 13L), List.of(10L), 5);
        // recall: 1/1
        assertEquals(1.0, r.getRecallAtK());
        // precision: len(retrieved[:5])=4, hits=1
        assertEquals(0.25, r.getPrecisionAtK());
        // mrr: rank 1
        assertEquals(1.0, r.getMrr());
        assertEquals(1.0, r.getHitAtK());
    }

    /** 核对 {@code r.getRecallAtK()} 与预期 {@code 1.0} 一致。调用路径经过 {@code evaluator.evaluate}。 */
    @Test
    void relevantAtThirdRank() {
        RetrievalEvaluationResult r = evaluator.evaluate(
                List.of(10L, 11L, 12L, 13L), List.of(12L), 5);
        assertEquals(1.0, r.getRecallAtK());
        assertEquals(0.25, r.getPrecisionAtK());
        assertEquals(1.0 / 3, r.getMrr(), 1e-9);
        assertEquals(1.0, r.getHitAtK());
        // nDCG@k：retrieved=[10,11,12,...], relevant={12} → dcg=1/log2(4)=0.5, idcg=1.0
        assertEquals(0.5, r.getNdcgAtK(), 1e-9);
    }

    /** 核对 {@code r.getRecallAtK()} 与预期 {@code 0.0} 一致。调用路径经过 {@code evaluator.evaluate}。 */
    @Test
    void noHit_allZero() {
        RetrievalEvaluationResult r = evaluator.evaluate(
                List.of(10L, 11L), List.of(99L), 5);
        assertEquals(0.0, r.getRecallAtK());
        assertEquals(0.0, r.getPrecisionAtK());
        assertEquals(0.0, r.getMrr());
        assertEquals(0.0, r.getHitAtK());
    }

    /** 给定超出 K 的命中项，确认截断影响 Recall 和 Precision，MRR 仍按其排名计算。 */
    @Test
    void kTruncation_appliesToRecallAndPrecisionNotMrr() {
        // retrieved 5 条，relevant 在第 3 位，k=3
        RetrievalEvaluationResult r = evaluator.evaluate(
                List.of(10L, 11L, 12L, 13L, 14L), List.of(12L), 3);
        assertEquals(1.0, r.getRecallAtK());       // top3 命中 1/1
        assertEquals(1.0 / 3, r.getPrecisionAtK(), 1e-9); // top3 size=3
        assertEquals(1.0 / 3, r.getMrr(), 1e-9);   // mrr 用完整列表，rank 3
        assertEquals(1.0, r.getHitAtK());
    }

    /** 核对 {@code r.getPrecisionAtK()} 与预期 {@code 0.5} 一致。调用路径经过 {@code evaluator.evaluate}。 */
    @Test
    void fewerThanKRetrieved_precisionDenominatorIsActualCount() {
        // 关键：precision@K 分母 = 实际检索条数 min(k,size)，不是 k
        RetrievalEvaluationResult r = evaluator.evaluate(
                List.of(10L, 11L), List.of(11L), 5);
        assertEquals(0.5, r.getPrecisionAtK());    // hits=1, len(retrieved[:5])=2
        assertEquals(0.5, r.getMrr());             // rank 2
        assertEquals(1.0, r.getRecallAtK());
    }

    /** 通过 {@code evaluator.evaluate} 执行本例并核对输出或记录。 */
    @Test
    void aggregate_averagesPerCase() {
        RetrievalEvaluationResult a = evaluator.evaluate(List.of(10L, 11L), List.of(10L), 5);
        RetrievalEvaluationResult b = evaluator.evaluate(List.of(20L), List.of(99L), 5);
        RetrievalEvaluationResult agg = RetrievalEvaluator.aggregate(List.of(a, b));
        assertEquals(0.5, agg.getRecallAtK(), 1e-9);        // (1.0 + 0.0)/2
        assertEquals(0.5, agg.getMrr(), 1e-9);              // (1.0 + 0.0)/2
        assertEquals(0.5, agg.getHitAtK(), 1e-9);           // (1.0 + 0.0)/2
        assertEquals(0.25, agg.getPrecisionAtK(), 1e-9);    // (0.5 + 0.0)/2
        assertEquals(0.5, agg.getPrecisionAtN(), 1e-9);     // (1.0 + 0.0)/2，n=1 各自窗口
    }

    // ---- precision@n：窗口 n = 该用例的相关块数（绕开 precision@k 的天花板） ----

    /** 通过 {@code evaluator.evaluate} 执行本例并核对输出或记录。 */
    @Test
    void precisionAtN_hitAtFirstRank_isOne() {
        RetrievalEvaluationResult r = evaluator.evaluate(
                List.of(10L, 11L, 12L, 13L), List.of(10L), 5);
        assertEquals(1.0, r.getPrecisionAtN(), 1e-9);
        assertEquals(0.25, r.getPrecisionAtK(), 1e-9);
    }

    /** 通过 {@code evaluator.evaluate} 执行本例并核对输出或记录。 */
    @Test
    void precisionAtN_relevantNotInWindow_isZero() {
        RetrievalEvaluationResult r = evaluator.evaluate(
                List.of(10L, 11L, 12L), List.of(12L), 5);
        assertEquals(0.0, r.getPrecisionAtN(), 1e-9);
    }

    /** 通过 {@code evaluator.evaluate} 执行本例并核对输出或记录。 */
    @Test
    void precisionAtN_twoRelevantOneHit_isHalf() {
        RetrievalEvaluationResult r = evaluator.evaluate(
                List.of(10L, 11L, 12L), List.of(10L, 99L), 5);
        assertEquals(0.5, r.getPrecisionAtN(), 1e-9);
        assertEquals(0.5, r.getRecallAtK(), 1e-9);   // 1/2
    }

    /** 通过 {@code evaluator.evaluate} 执行本例并核对输出或记录。 */
    @Test
    void precisionAtN_nGreaterThanRetrieved_denominatorIsActualCount() {
        RetrievalEvaluationResult r = evaluator.evaluate(
                List.of(10L), List.of(10L, 11L, 12L), 5);
        assertEquals(1.0, r.getPrecisionAtN(), 1e-9);
    }

    /** 核对 {@code r.getPrecisionAtN()} 与预期 {@code 0.0} 一致。调用路径经过 {@code evaluator.evaluate}。 */
    @Test
    void precisionAtN_relevantEmpty_isZero() {
        RetrievalEvaluationResult r = evaluator.evaluate(List.of(10L, 11L), List.of(), 5);
        assertEquals(0.0, r.getPrecisionAtN());
    }

    /** 核对 {@code r.getPrecisionAtN()} 与预期 {@code 0.0} 一致。调用路径经过 {@code evaluator.evaluate}。 */
    @Test
    void precisionAtN_retrievedEmpty_isZero() {
        RetrievalEvaluationResult r = evaluator.evaluate(List.of(), List.of(10L), 5);
        assertEquals(0.0, r.getPrecisionAtN());
    }

    /** 核对 {@code r.getPrecisionAtN()} 与预期 {@code 0.0} 一致。调用路径经过 {@code evaluator.evaluate}。 */
    @Test
    void precisionAtN_nullInputs_isZero() {
        RetrievalEvaluationResult r = evaluator.evaluate(null, null, 5);
        assertEquals(0.0, r.getPrecisionAtN());
    }

    /** 通过 {@code PrecisionMetric.precisionAtN} 执行本例并核对输出或记录。 */
    @Test
    void precisionAtN_directContract() {
        assertEquals(0.0, PrecisionMetric.precisionAtN(List.of(1L, 2L), set(), 0));
        assertEquals(0.0, PrecisionMetric.precisionAtN(List.of(1L, 2L), set(1L), 0));
        assertEquals(0.5, PrecisionMetric.precisionAtN(List.of(1L, 9L), set(1L, 2L), 2), 1e-9);
        assertEquals(0.0, PrecisionMetric.precisionAtN(null, set(1L), 1));
    }

    /** 核对 {@code agg.getRecallAtK()} 与预期 {@code 0.0} 一致。调用路径经过 {@code RetrievalEvaluator.aggregate}。 */
    @Test
    void aggregate_emptyReturnsZero() {
        RetrievalEvaluationResult agg = RetrievalEvaluator.aggregate(List.of());
        assertEquals(0.0, agg.getRecallAtK());
        assertEquals(0.0, agg.getMrr());
        assertEquals(0.0, agg.getPrecisionAtN());
    }

    /** 核对 {@code r.getRecallAtK()} 与预期 {@code 0.0} 一致。调用路径经过 {@code evaluator.evaluate}。 */
    @Test
    void nullIdsBehaveAsEmpty() {
        RetrievalEvaluationResult r = evaluator.evaluate(null, null, 5);
        assertEquals(0.0, r.getRecallAtK());
        assertEquals(0.0, r.getMrr());
    }

    // ---- nDCG@K：IDCG 项数 = min(k, |relevant|)，不是实际命中数 ----

    /** 通过 {@code evaluator.evaluate} 执行本例并核对输出或记录。 */
    @Test
    void ndcg_partialRecallIsNotPerfect() {
        RetrievalEvaluationResult r = evaluator.evaluate(
                List.of(10L, 99L, 98L), List.of(10L, 11L, 12L), 5);
        // DCG = 1（rank1 命中）; IDCG = 1 + 1/log2(3) + 1/log2(4)
        double idcg = 1.0 + 1.0 / (Math.log(3) / Math.log(2)) + 0.5;
        assertEquals(1.0 / idcg, r.getNdcgAtK(), 1e-9);
        // 同一份检索下 recall 只有 1/3 —— nDCG 必须反映这个缺口
        assertEquals(1.0 / 3, r.getRecallAtK(), 1e-9);
    }

    /** 通过 {@code evaluator.evaluate} 执行本例并核对输出或记录。 */
    @Test
    void ndcg_fullRecallInTopK_isOne() {
        RetrievalEvaluationResult r = evaluator.evaluate(
                List.of(10L, 11L, 12L, 13L), List.of(10L, 11L, 12L), 5);
        assertEquals(1.0, r.getNdcgAtK(), 1e-9);
    }

    /** 设置第三名命中且有三项相关文档，按折损收益核对 nDCG。 */
    @Test
    void ndcg_singleHitAtThirdRankWithThreeRelevant() {
        RetrievalEvaluationResult r = evaluator.evaluate(
                List.of(10L, 11L, 12L), List.of(12L, 13L, 14L), 5);
        double idcg = 1.0 + 1.0 / (Math.log(3) / Math.log(2)) + 0.5;
        assertEquals(0.5 / idcg, r.getNdcgAtK(), 1e-9);
    }

    /** 通过 {@code evaluator.evaluate} 执行本例并核对输出或记录。 */
    @Test
    void ndcg_relevantExceedsK_capsIdcgAtK() {
        RetrievalEvaluationResult r = evaluator.evaluate(
                List.of(10L, 11L, 12L), List.of(10L, 11L, 12L, 13L, 14L), 3);
        assertEquals(1.0, r.getNdcgAtK(), 1e-9);
        assertEquals(3.0 / 5, r.getRecallAtK(), 1e-9); // recall 仍按全部相关块算
    }
}
