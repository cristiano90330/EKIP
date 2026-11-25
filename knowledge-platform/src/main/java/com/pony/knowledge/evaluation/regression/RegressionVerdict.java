/* 承载一次评测回归的整体判定和各指标差异。 */

package com.pony.knowledge.evaluation.regression;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 一次回归对比的判定结果（4.2 P2 两层三态）：总体通过与否 + 整体三态 verdict + 每指标明细。
 * passed = verdict != REGRESSED（PASS=未退化，非独立第四态）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegressionVerdict {

    private boolean passed;

    /** 整体三态：IMPROVED / REGRESSED / UNCHANGED */
    private String verdict;

    private List<MetricDelta> deltas;

    /** 人类可读的一句话总结 */
    private String summary;
}
