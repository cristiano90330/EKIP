/* 返回批量评测的总体、分组和逐条结果。 */

package com.pony.knowledge.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 批量评估结果：总指标 + 按难度 / 按失败类型切片 + 每条用例明细。
 * version 记录了本次评估所处的检索技术路线，用于迭代对比。
 */
@Data
public class EvaluationBatchResultDTO {
    private String version;

    private int k;

    private int totalCases;

    private MetricAggregateDTO total;

    private Map<String, MetricAggregateDTO> byDifficulty;

    private Map<String, MetricAggregateDTO> byType;

    private List<EvaluationResultDTO> perCase;
}
