/* 分别按截断 K 或返回数量计算相关切片占比。 */

package com.pony.knowledge.evaluation.retrieval;

import java.util.List;
import java.util.Set;

/**
 * Precision@K：hits / len(retrieved[:k])。
 * 注意分母是「实际检索到的前 k 条条数」min(k, retrieved.size())，而非固定 k——
 * 与 Python ai/app/evaluation/evaluator.py#precision_at_k 逐字对齐。
 *
 * <p>另含 {@link #precisionAtN(List, Set, int)}：把窗口从固定 k 换成**该用例的相关块数 n**。
 * 为什么需要它：本数据集平均每用例仅约 1.17 条相关块，k=5 时 precision@5 的理论上限只有
 * ≈1.17/5=0.23，实测值贴着上限 → 该指标在此标注密度下不传递信息。n=相关块数时上限恒为 1.0。</p>
 */
public final class PrecisionMetric {

    /** 初始化 PrecisionMetric，供本文件定义的构建入口使用。 */
    private PrecisionMetric() {
    }

    /**
     * 按用户指定 K 值计算预测列表前 K 的正确比例。
     * 上游为 RetrievalEvaluator.evaluate。
     * 下游为 RecallMetric.hitsWithinTopK。
     *
     * @param retrieved 需要本方法按序读取或筛选的 List&lt;Long&gt; 数据
     * @param relevant 需要本方法按序读取或筛选的 Set&lt;Long&gt; 数据
     * @param k 评估或截断使用的 K 值
     * @return 前 K 个候选中相关项的精确率
     */
    public static double precisionAtK(List<Long> retrieved, Set<Long> relevant, int k) {
        int topK = topKSize(retrieved, k);
        if (topK == 0) {
            return 0.0;
        }
        return (double) RecallMetric.hitsWithinTopK(retrieved, relevant, k) / topK;
    }

    /**
     * 按实际返回的候选数量计算检索准确比例。
     * 上游为 RetrievalEvaluator.evaluate。
     * 下游为 RecallMetric.hitsWithinTopK。
     *
     * @param retrieved 需要本方法按序读取或筛选的 List&lt;Long&gt; 数据
     * @param relevant 需要本方法按序读取或筛选的 Set&lt;Long&gt; 数据
     * @param n 本方法计算或判断使用的 n 值
     * @return 按实际返回数量计算的精确率
     */
    public static double precisionAtN(List<Long> retrieved, Set<Long> relevant, int n) {
        if (relevant == null || relevant.isEmpty() || n <= 0) {
            return 0.0;
        }
        int denom = topKSize(retrieved, n);

        if (denom == 0) {
            return 0.0;
        }
        return (double) RecallMetric.hitsWithinTopK(retrieved, relevant, n) / denom;
    }

    /**
     * 求实际结果数量与 K 限制共同决定的候选窗口。
     * 上游为 PrecisionMetric.precisionAtK、PrecisionMetric.precisionAtN。
     *
     * @param retrieved 需要本方法按序读取或筛选的 List&lt;Long&gt; 数据
     * @param k 评估或截断使用的 K 值
     * @return 考虑 K 上限后真正可参与评测的结果数
     */
    private static int topKSize(List<Long> retrieved, int k) {
        if (retrieved == null || retrieved.isEmpty() || k <= 0) {
            return 0;
        }
        return Math.min(k, retrieved.size());
    }
}
