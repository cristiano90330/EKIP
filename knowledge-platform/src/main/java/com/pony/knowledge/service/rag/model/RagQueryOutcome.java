/* 承载服务内部问答结果、候选与证据、执行信息和降级状态。 */

package com.pony.knowledge.service.rag.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Python 端到端查询（/rag/query）的返回（决策文档 §4 第 5 项「重切边界」）。
 * 同时承载「检索结果（证据）+ 生成结果（答案）」，是 Java 侧唯一的查询结果类型。
 */
@Data
@Builder
public class RagQueryOutcome {

    private String pipelineEngine;

    private String retrievalVersion;

    private String evidenceMode;

    private String answer;

    /** Shared public outcome: success, no_evidence, dependency_unavailable, or timeout. */
    private String outcome;

    private String reasonCode;

    private String failedStage;

    private String requestId;

    private String retrievalStatus;

    private String originalQuery;

    private String rewrittenQuery;

    /** Raw ranked retrieval candidates, used by search and retrieval metrics. */
    private List<RagEvidence> sources;

    /** Evidence-gate accepted candidates, used by generation and user-facing answer sources. */
    private List<RagEvidence> evidences;

    private RagEvidenceDecision evidenceDecision;

    private RagDocumentPage documentPage;

    /** 生成阶段是否降级（预算超限/熔断/重试耗尽）；true 时 answer 为空，兜底文案由 Java 给 */
    private boolean degraded;

    private String degradeReason;

    private String providerUsed;

    /** 本次生成的 LLM token 用量 */
    private Integer tokenCost;

    /**
     * 查询响应上报的阶段耗时与 token 用量，供调用方解释本次执行过程。
     */
    private List<StageTiming> stages;

    /** 一个阶段的观测（字段对齐 Python `RagQueryStage` 与 Java `RagSpan`）。 */
    @Data
    @Builder
    public static class StageTiming {
        /** 阶段短名：rewrite / vector_retrieval / keyword_retrieval / fusion / rerank / generate */
        private String stage;

        private long durationMs;

        private boolean success;

        private boolean degraded;

        /** LLM 阶段的 token 用量；非 LLM 阶段为 null */
        private Integer totalTokens;
    }

    /**
     * 压缩统计（决策文档 §5.12.10 第 3 步：压缩搬到 Python 后必须回传）。
     *
     * <p>评测报告有 `compressionMode/tokensBefore/tokensAfter/compressionRatio` 四列，
     * 它们来自 Python 的压缩统计。压缩在 Python 内完成后若不回传，这四列会恒为空
     * —— 等于把评测的一个维度悄悄废掉。</p>
     */
    private CompressionInfo compression;

    @Data
    @Builder
    public static class CompressionInfo {
        private String mode;

        private int tokensBefore;

        private int tokensAfter;

        private double ratio;
    }
}
