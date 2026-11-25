/* 汇总单次检索的召回、精确率和排序质量。 */

package com.pony.knowledge.evaluation.retrieval;

import lombok.Data;

/**
 * 单次检索评估结果（原生 Java 计算）。字段与 Python /evaluation/retrieval 的 MetricResult 对齐，另含 nDCG@K。
 */
@Data
public class RetrievalEvaluationResult {

    private Double recallAtK;

    private Double precisionAtK;

    /**
     * Precision@N：窗口 n = 该用例的相关块数。相关集为空或检索为空时按 0.0 计
     * （见 {@link PrecisionMetric#precisionAtN}）。precision@k 在低标注密度下贴天花板、
     * 不传递信息，故并行上报本指标。
     */
    private Double precisionAtN;

    private Double mrr;

    private Double ndcgAtK;

    private Double hitAtK;
}
