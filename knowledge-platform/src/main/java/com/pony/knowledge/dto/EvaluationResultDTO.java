/* 记录单题检索、证据、生成评测指标及运行用量。 */

package com.pony.knowledge.dto;

import com.pony.knowledge.service.rag.model.RagEvidenceDecision;
import lombok.Data;

@Data
public class EvaluationResultDTO {
    /** 检索技术路线版本：baseline / hybrid / hybrid-rerank / hybrid-rerank-rewrite / compression */
    private String version;

    /** 评估用例问题（批量评估回显用） */
    private String question;

    /** 难度：normal / hard */
    private String difficulty;

    /** 失败类型：keyword / synonym / cross-chunk / ambiguity */
    private String type;

    /** Shared RAG outcome and diagnostic fields returned by Python. */
    private String outcome;

    private String reasonCode;

    private String failedStage;

    private String requestId;

    /** Evidence gate decision and the exact policy version used for this case. */
    private RagEvidenceDecision evidenceDecision;

    private String evidencePolicyVersion;

    private Double precisionAtK;

    /** Precision@N：窗口 n = 该用例的相关块数（低标注密度下 precision@k 贴天花板、不传递信息） */
    private Double precisionAtN;

    /** Redundancy（冗余度）：Top-K 检索结果 chunk 的平均两两 Jaccard（越低越不冗余）。 */
    private Double redundancy;

    private Double recallAtK;

    private Double hitAtK;

    private Double mrr;

    /** nDCG@K（binary relevance，Java RetrievalEvaluator 计算；此前管线未上报） */
    private Double ndcgAtK;

    private Double contextRelevance;

    private Double faithfulness;

    private Double answerCorrectness;

    /** 答案切题度（答案 vs 问题，LLM 裁判） */
    private Double answerRelevance;

    private String answer;

    /** RAG 总耗时（检索 + 生成），毫秒 */
    private Long latencyMs;

    /** 向量检索耗时，毫秒 */
    private Long retrievalLatencyMs;

    /** LLM 生成耗时，毫秒 */
    private Long generationLatencyMs;

    /** Rerank 阶段耗时，毫秒（baseline/hybrid 无此阶段时为 0） */
    private Long rerankLatencyMs;

    /** Query Rewrite 耗时，毫秒（非 hybrid-rerank-rewrite 档或单轮无历史时为 0） */
    private Long rewriteLatencyMs;

    /** Query Rewrite 的 LLM token 用量 */
    private Integer rewriteTokenCost;

    /** 改写后的问题（供确认 rewrite 是否生效；未改写时与原始问题相同） */
    private String rewrittenQuery;

    /** 压缩模式：extractive（未启用压缩为 null）。llm 档已于 2026-09-26 删除 */
    private String compressionMode;

    /** 压缩前 token 数 */
    private Integer tokensBefore;

    /** 压缩后 token 数 */
    private Integer tokensAfter;

    /** 压缩比 tokensAfter/tokensBefore（越小压缩越狠） */
    private Double compressionRatio;

    /** 压缩耗时，毫秒（由 Python 管线回传的 stages[compression]；未启用压缩为 0） */
    private Long compressionLatencyMs;

    /** 压缩的 LLM token 用量（extractive 为 0） */
    private Integer compressionTokenCost;

    /** 生成阶段 LLM token 用量 */
    private Integer generationTokenCost;

    /** LLM 裁判（faithfulness/correctness/relevance）token 用量 */
    private Integer judgeTokenCost;

    /** 本次请求 LLM 总 token（rewrite + rerank 未计 + compression + generation + judge） */
    private Integer llmTokensTotal;

    /** Cost 估算（USD）= llmTokensTotal / 1e6 × rag.llm-price-per-1m-tokens */
    private Double costEstimateUsd;

    /** 单次请求的 RAG 管线追踪 */
    private RagTraceDTO trace;
}
