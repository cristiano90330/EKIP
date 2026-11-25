/* 接收批量评测用例、截断数量和生成评测开关。 */

package com.pony.knowledge.dto;

import lombok.Data;

import java.util.List;

/**
 * 批量检索质量评估请求：一次提交整个评估数据集。
 */
@Data
public class EvaluationBatchRequestDTO {
    private int k = 5;

    private List<EvaluationRequestDTO> cases;

    /** true 时逐条跑生成 + LLM 裁判（faithfulness/correctness/生成耗时），用于 Answer Quality 对比；默认 false 只算检索指标 */
    private boolean evaluateGeneration = false;
}
