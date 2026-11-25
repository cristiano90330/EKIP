/* 比较实际排名和理想排名的折损增益。 */

package com.pony.knowledge.evaluation.retrieval;

import java.util.List;
import java.util.Set;

/**
 * nDCG@K（binary relevance）：DCG@K / IDCG@K。相关 chunk 记 1、无关记 0；
 * discount = 1/log2(rank+1)，rank 从 1 开始。
 *
 * <pre>
 * DCG@K  = Σ_{i=1..min(K, len(retrieved))} rel_i / log2(i+1)      rel_i ∈ {0,1}
 * IDCG@K = Σ_{i=1..min(K, |relevant|)}   1     / log2(i+1)
 * </pre>
 *
 * <p>🔴 IDCG 的项数必须是 {@code min(K, |relevant|)}，**不是实际命中数 hits**。
 * 用 hits 当 IDCG 会让分母随检索质量下降而同步变小：三条相关块只召回一条、且正好排首位时
 * （hits=1 → IDCG=1.0 → nDCG=1.0），而正确值是 {@code 1/(1+0.6309+0.5) ≈ 0.469}。
 * 即「召回越差 nDCG 越高」，该指标对召回完全不敏感 —— 2026-10-23 修正。
 * 原测试只覆盖了「单条相关」的情形（此时 hits 恰等于 min(K,|R|)=1），把该缺陷掩盖掉了。</p>
 *
 * <p>当 |relevant| &gt; K 时，IDCG@K 只累加到 K 项：前 K 位全部命中即为 1.0，
 * 不因第 K 位之后的相关块未召回而扣分 —— 这是 nDCG@K 的既定语义，不是本类的取舍。</p>
 */
public final class NdcgMetric {

    /** 初始化 NdcgMetric，供本文件定义的构建入口使用。 */
    private NdcgMetric() {
    }

    /**
     * 用理想前 K 排名的折损增益归一实际结果的折损增益。
     * 上游为 RetrievalEvaluator.evaluate。
     * 下游为 NdcgMetric.log2、NdcgMetric.idealDcg。
     *
     * @param retrieved 需要本方法按序读取或筛选的 List&lt;Long&gt; 数据
     * @param relevant 需要本方法按序读取或筛选的 Set&lt;Long&gt; 数据
     * @param k 评估或截断使用的 K 值
     * @return 归一到理想排名增益的实际结果
     */
    public static double ndcgAtK(List<Long> retrieved, Set<Long> relevant, int k) {
        if (retrieved == null || retrieved.isEmpty() || relevant == null || relevant.isEmpty() || k <= 0) {
            return 0.0;
        }
        int limit = Math.min(k, retrieved.size());
        double dcg = 0.0;

        for (int i = 0; i < limit; i++) {
            Long id = retrieved.get(i);
            if (id != null && relevant.contains(id)) {
                dcg += 1.0 / log2(i + 2); // rank = i+1 → log2(rank+1) = log2(i+2)
            }
        }
        double idcg = idealDcg(Math.min(k, relevant.size()));
        return idcg <= 0 ? 0.0 : dcg / idcg;
    }

    /**
     * 计算相关候选排在最前方时可获得的折损增益。
     * 上游为 NdcgMetric.ndcgAtK。
     * 下游为 NdcgMetric.log2。
     *
     * @param n 本方法计算或判断使用的 n 值
     * @return 全部可用相关项优先排列并按名次折损后的理想增益
     */
    private static double idealDcg(int n) {
        double idcg = 0.0;
        for (int i = 0; i < n; i++) {
            idcg += 1.0 / log2(i + 2);
        }
        return idcg;
    }

    /**
     * 计算用于名次折损权重的以 2 为底的对数。
     * 上游为 NdcgMetric.ndcgAtK、NdcgMetric.idealDcg。
     *
     * @param x 本方法计算或判断使用的 x 值
     * @return 给定正数的以 2 为底对数
     */
    private static double log2(double x) {
        return Math.log(x) / Math.log(2.0);
    }
}
