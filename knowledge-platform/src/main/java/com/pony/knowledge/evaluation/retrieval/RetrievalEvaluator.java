/* 对检索结果计算指标，并聚合批量用例结果。 */

package com.pony.knowledge.evaluation.retrieval;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 检索质量评估器：以「检索命中的 chunk id 列表 + 标注的相关 chunk id 列表」计算
 * Recall@K / Precision@K / Precision@N / MRR / nDCG@K / Hit@K。纯本地算术，无 Spring、无 LLM。
 * Precision@N 的窗口 n 取该用例的相关块数，用于绕开 precision@k 在低标注密度下的天花板。
 *
 * 召回侧四指标公式与 Python ai/app/evaluation/evaluator.py 逐字对齐（retrieval.version 矩阵共用同一口径），
 * nDCG@K 为新增指标。当前 EvaluationServiceImpl 已改为调用本类，取代原先发 Python 的
 * AiServiceClient.evaluateRetrieval。
 */
public class RetrievalEvaluator {

    /**
     * 对本次命中排名和标注相关集合计算检索质量指标。
     * 上游为 EvaluationServiceImpl.evaluateRetrieval、EvaluationServiceImpl.evaluateBatch。
     * 下游为 RecallMetric.recallAtK、PrecisionMetric.precisionAtK。
     *
     * @param retrievedIds 相关记录的 ID 列表
     * @param relevantIds 标注为相关的切片 ID
     * @param k 评估或截断使用的 K 值
     * @return 本次得到的 RetrievalEvaluationResult 实例
     */
    public RetrievalEvaluationResult evaluate(List<Long> retrievedIds, List<Long> relevantIds, int k) {
        List<Long> retrieved = retrievedIds == null ? Collections.emptyList() : retrievedIds;
        Set<Long> relevant = relevantIds == null ? Collections.emptySet() : new HashSet<>(relevantIds);

        RetrievalEvaluationResult r = new RetrievalEvaluationResult();
        r.setRecallAtK(RecallMetric.recallAtK(retrieved, relevant, k));
        r.setPrecisionAtK(PrecisionMetric.precisionAtK(retrieved, relevant, k));
        // precision@n：窗口 n = 该用例的相关块数（低标注密度下 precision@k 贴天花板不传递信息）
        r.setPrecisionAtN(PrecisionMetric.precisionAtN(retrieved, relevant, relevant.size()));
        r.setMrr(MrrMetric.reciprocalRank(retrieved, relevant));
        r.setNdcgAtK(NdcgMetric.ndcgAtK(retrieved, relevant, k));
        r.setHitAtK(HitAtKMetric.hitAtK(retrieved, relevant, k));
        return r;
    }

    /**
     * 对多个用例的检索指标生成总体结果。
     *
     * @param results 需要本方法按序读取或筛选的 List&lt;RetrievalEvaluationResult&gt; 数据
     * @return 本次得到的 RetrievalEvaluationResult 实例
     */
    public static RetrievalEvaluationResult aggregate(List<RetrievalEvaluationResult> results) {
        RetrievalEvaluationResult agg = new RetrievalEvaluationResult();
        if (results == null || results.isEmpty()) {
            agg.setRecallAtK(0.0);
            agg.setPrecisionAtK(0.0);
            agg.setPrecisionAtN(0.0);
            agg.setMrr(0.0);
            agg.setNdcgAtK(0.0);
            agg.setHitAtK(0.0);
            return agg;
        }
        double recall = 0, precision = 0, precisionN = 0, mrr = 0, ndcg = 0, hit = 0;

        for (RetrievalEvaluationResult r : results) {
            recall += r.getRecallAtK() == null ? 0 : r.getRecallAtK();
            precision += r.getPrecisionAtK() == null ? 0 : r.getPrecisionAtK();
            precisionN += r.getPrecisionAtN() == null ? 0 : r.getPrecisionAtN();
            mrr += r.getMrr() == null ? 0 : r.getMrr();
            ndcg += r.getNdcgAtK() == null ? 0 : r.getNdcgAtK();
            hit += r.getHitAtK() == null ? 0 : r.getHitAtK();
        }
        int n = results.size();
        agg.setRecallAtK(recall / n);
        agg.setPrecisionAtK(precision / n);
        agg.setPrecisionAtN(precisionN / n);
        agg.setMrr(mrr / n);
        agg.setNdcgAtK(ndcg / n);
        agg.setHitAtK(hit / n);
        return agg;
    }
}
