/* 计算前 K 个结果中是否出现至少一个相关切片。 */

package com.pony.knowledge.evaluation.retrieval;

import java.util.List;
import java.util.Set;

/**
 * Hit@K：前 k 条内是否至少命中一个相关 chunk（0 或 1）。
 * 与 Python ai/app/evaluation/evaluator.py#hit_at_k 逐字对齐。
 */
public final class HitAtKMetric {

    /** 初始化 HitAtKMetric，供本文件定义的构建入口使用。 */
    private HitAtKMetric() {
    }

    /**
     * 判断标注相关切片是否进入预测列表的前 K 项。
     * 上游为 RetrievalEvaluator.evaluate。
     * 下游为 RecallMetric.hitsWithinTopK。
     *
     * @param retrieved 需要本方法按序读取或筛选的 List&lt;Long&gt; 数据
     * @param relevant 需要本方法按序读取或筛选的 Set&lt;Long&gt; 数据
     * @param k 评估或截断使用的 K 值
     * @return 命中时为 1.0，否则为 0.0
     */
    public static double hitAtK(List<Long> retrieved, Set<Long> relevant, int k) {
        if (relevant == null || relevant.isEmpty()) {
            return 0.0;
        }
        return RecallMetric.hitsWithinTopK(retrieved, relevant, k) > 0 ? 1.0 : 0.0;
    }
}
