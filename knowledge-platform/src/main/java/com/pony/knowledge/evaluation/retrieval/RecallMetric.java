/* 计算标注相关切片在前 K 个结果中的覆盖率。 */

package com.pony.knowledge.evaluation.retrieval;

import java.util.List;
import java.util.Set;

/**
 * Recall@K：|retrieved[:k] ∩ relevant| / |relevant|。
 * 与 Python ai/app/evaluation/evaluator.py#recall_at_k 逐字对齐，避免 Java/Python 指标漂移。
 */
public final class RecallMetric {

    /** 初始化 RecallMetric，供本文件定义的构建入口使用。 */
    private RecallMetric() {
    }

    /**
     * 把前 K 中的去重相关结果数除以标注相关总数。
     * 上游为 RetrievalEvaluator.evaluate。
     * 下游为 RecallMetric.hitsWithinTopK。
     *
     * @param retrieved 需要本方法按序读取或筛选的 List&lt;Long&gt; 数据
     * @param relevant 需要本方法按序读取或筛选的 Set&lt;Long&gt; 数据
     * @param k 评估或截断使用的 K 值
     * @return 前 K 个候选覆盖的相关样本比例
     */
    public static double recallAtK(List<Long> retrieved, Set<Long> relevant, int k) {
        if (relevant == null || relevant.isEmpty()) {
            return 0.0;
        }
        return (double) hitsWithinTopK(retrieved, relevant, k) / relevant.size();
    }

    /**
     * 统计预测列表前 K 中被相关集合命中的不同切片 ID。
     * 上游为 HitAtKMetric.hitAtK、PrecisionMetric.precisionAtK。
     *
     * @param retrieved 需要本方法按序读取或筛选的 List&lt;Long&gt; 数据
     * @param relevant 需要本方法按序读取或筛选的 Set&lt;Long&gt; 数据
     * @param k 评估或截断使用的 K 值
     * @return 参与召回率分子计算的不同相关 ID 数
     */
    static int hitsWithinTopK(List<Long> retrieved, Set<Long> relevant, int k) {
        if (retrieved == null || retrieved.isEmpty() || k <= 0) {
            return 0;
        }
        int limit = Math.min(k, retrieved.size());
        int count = 0;

        for (int i = 0; i < limit; i++) {
            Long id = retrieved.get(i);
            if (id != null && relevant.contains(id)) {
                count++;
            }
        }
        return count;
    }
}
