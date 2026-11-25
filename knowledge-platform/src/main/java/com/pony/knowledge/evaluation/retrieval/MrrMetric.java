/* 按第一个相关切片的名次计算倒数排名。 */

package com.pony.knowledge.evaluation.retrieval;

import java.util.List;
import java.util.Set;

/**
 * MRR（Mean Reciprocal Rank）：第一个相关 chunk 在检索列表（完整、1-based）中的位次倒数。
 * 不做 k 截断——检索列表本身长度已 ≤ K，语义同 Python ai/app/evaluation/evaluator.py#reciprocal_rank。
 */
public final class MrrMetric {

    /** 初始化 MrrMetric，供本文件定义的构建入口使用。 */
    private MrrMetric() {
    }

    /**
     * 查找首个标注相关结果并取其排名倒数。
     * 上游为 RetrievalEvaluator.evaluate。
     *
     * @param retrieved 需要本方法按序读取或筛选的 List&lt;Long&gt; 数据
     * @param relevant 需要本方法按序读取或筛选的 Set&lt;Long&gt; 数据
     * @return 首命中名次的倒数，无命中时为零
     */
    public static double reciprocalRank(List<Long> retrieved, Set<Long> relevant) {
        if (retrieved == null || retrieved.isEmpty() || relevant == null || relevant.isEmpty()) {
            return 0.0;
        }
        for (int i = 0; i < retrieved.size(); i++) {
            Long id = retrieved.get(i);
            if (id != null && relevant.contains(id)) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }
}
