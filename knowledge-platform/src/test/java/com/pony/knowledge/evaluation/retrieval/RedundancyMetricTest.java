/* 检查检索结果去重指标如何按两两相似度计算汇总。 */

package com.pony.knowledge.evaluation.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RedundancyMetricTest {

    /** 覆盖空值的相关边界。通过 {@code RedundancyMetric.meanPairwiseJaccard} 执行本例并核对输出或记录。 */
    @Test
    void emptyOrSingle_returnsZero() {
        assertThat(RedundancyMetric.meanPairwiseJaccard(List.of())).isEqualTo(0.0);
        assertThat(RedundancyMetric.meanPairwiseJaccard(null)).isEqualTo(0.0);
        assertThat(RedundancyMetric.meanPairwiseJaccard(List.of("只有一个"))).isEqualTo(0.0);
    }

    /** 覆盖重复的相关边界。通过 {@code RedundancyMetric.meanPairwiseJaccard} 执行本例并核对输出或记录。 */
    @Test
    void nearDuplicate_pair_highJaccard() {
        double d = RedundancyMetric.meanPairwiseJaccard(List.of(
                "报销必须提供合法有效的发票抬头应为公司全称",
                "报销必须提供合法有效的发票抬头应为公司全称不得使用个人抬头"));
        assertThat(d).isGreaterThan(0.5);
    }

    /** 通过 {@code RedundancyMetric.meanPairwiseJaccard} 执行本例并核对输出或记录。 */
    @Test
    void unrelated_pair_lowJaccard() {
        double d = RedundancyMetric.meanPairwiseJaccard(List.of(
                "报销必须提供合法有效的发票抬头应为公司全称",
                "员工离职后人事部门应在两个工作日内回收系统权限"));
        assertThat(d).isLessThan(0.1);
    }

    /** 通过 {@code RedundancyMetric.meanPairwiseJaccard} 执行本例并核对输出或记录。 */
    @Test
    void threeWay_meanIsAverageOfPairs() {
        // A 与 B 近重复、C 无关：平均 = (sim(A,B) + sim(A,C) + sim(B,C)) / 3，应明显低于「全近重复」
        double near = RedundancyMetric.meanPairwiseJaccard(List.of(
                "报销必须提供合法有效的发票抬头应为公司全称",
                "报销必须提供合法有效的发票抬头应为公司全称不得使用个人抬头",
                "员工离职后人事部门应在两个工作日内回收系统权限"));
        assertThat(near).isBetween(0.1, 0.5);
    }

    /** 覆盖空值的相关边界。通过 {@code RedundancyMetric.meanPairwiseJaccard} 执行本例并核对输出或记录。 */
    @Test
    void nullContent_treatedAsEmpty() {
        double d = RedundancyMetric.meanPairwiseJaccard(java.util.Arrays.asList("有内容的一段", null));
        assertThat(d).isEqualTo(0.0);   // 一方空 → Jaccard 0
    }
}
