/* 承载评测的成功与失败分布、质量、耗时和用量汇总。 */

package com.pony.knowledge.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 一组评估用例的聚合指标（可用于 total / 按难度 / 按类型切片）。
 *
 * <p>`ignoreUnknown = true` 是**跨语言交换格式的向前兼容**所必需，不是顺手加的：
 * 这份 JSON 由 `run_eval.py` 直接写文件（eval/reports/current_*.json、baseline_*.json），
 * 再由 `RegressionCompareRequestDTO` 当 HTTP 入参接收。生产方每加一个字段，
 * 消费方就会在**反序列化**这一步炸掉。例：run_eval 加的 `vectorStore` 标识字段
 * 曾把基线读挂（测试见 {@code MetricAggregateDTOJsonToleranceTest}）。</p>
 *
 * <p>服务端已不再读写基线文件（原 3.9 汇总报告端点移除，`BaselineStore` 仅由上述测试
 * 用作「裸 `new ObjectMapper()` 读旧基线文件」的回归保护）；基线持久化现由
 * run_eval.py 负责。保留该兼容性是因为旧基线文件仍在 eval/reports 下被回归比对使用。</p>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MetricAggregateDTO {
    private int caseCount;

    private Double recallAtK;

    private Double precisionAtK;

    /**
     * Precision@N 平均值（窗口 n = 各用例的相关块数）。
     * 新基线文件才有此字段；老基线反序列化后为 null → 回归比对按「不可比对」跳过（不判退化）。
     */
    private Double precisionAtN;

    /** Redundancy（冗余度）平均值：Top-K 平均两两 Jaccard。新基线才有此字段，老基线 null → 不判退化。 */
    private Double redundancy;

    private Double mrr;

    private Double hitAtK;

    /** nDCG@K 平均值（binary relevance） */
    private Double ndcgAtK;

    /** 总延迟平均值（毫秒，检索+生成），LATENCY 维度回归用 */
    private Double avgLatencyMs;

    /** Query Rewrite 平均耗时，毫秒 */
    private Double rewriteLatencyMs;

    /** Query Rewrite 累计 token 用量 */
    private Integer rewriteTokenCost;

    /** 压缩比平均值（仅压缩用例参与） */
    private Double compressionRatio;

    /** 压缩平均耗时，毫秒 */
    private Double compressionLatencyMs;

    /** LLM 压缩累计 token 用量 */
    private Integer compressionTokenCost;

    /** 生成质量：faithfulness 平均值（evaluateGeneration=true 时才有；分母=该指标自身的非空用例数） */
    private Double faithfulness;

    /** 生成质量：answerCorrectness 平均值（分母=该指标自身的非空用例数，与 faithfulness 各自独立） */
    private Double answerCorrectness;

    /** 答案切题度平均值 */
    private Double answerRelevance;

    /** 生成平均耗时，毫秒 */
    private Double generationLatencyMs;

    /** LLM 总 token 平均值 */
    private Double llmTokensTotal;

    /** Cost 估算平均值（USD） */
    private Double costEstimateUsd;

    /** Per-case shared RAG outcomes, so failed retrieval is distinguishable from no evidence. */
    private Map<String, Integer> outcomeCounts;

    /** Per-case evidence-gate states and policy versions represented in this aggregate. */
    private Map<String, Integer> evidenceDecisionCounts;

    private List<String> evidencePolicyVersions;
}
