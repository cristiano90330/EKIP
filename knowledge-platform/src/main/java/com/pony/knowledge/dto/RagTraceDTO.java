/* 保留单题评测的检索、改写、压缩、生成与裁判轨迹数据。 */

package com.pony.knowledge.dto;

import lombok.Data;

import java.util.List;

/**
 * 单次请求的 RAG 管线追踪：记录 查询 → 改写 → 检索 → 压缩 → 生成 的关键中间态，
 * 便于人工定位检索 / 生成哪里出了问题。挂在 EvaluationResultDTO.trace 上。
 */
@Data
public class RagTraceDTO {

    private String question;

    private String rewrittenQuery;

    private boolean rewritten;

    private String rewriteReason;

    private String compressionMode;

    private Integer tokensBefore;

    private Integer tokensAfter;

    private Double compressionRatio;

    private Long retrievalLatencyMs;

    private Long rerankLatencyMs;

    private Long rewriteLatencyMs;

    private Long compressionLatencyMs;

    private Long generationLatencyMs;

    private Integer rewriteTokenCost;

    private Integer generationTokenCost;

    private Integer judgeTokenCost;

    private String answer;

    private Double faithfulness;

    private Double answerCorrectness;

    private Double answerRelevance;

    private List<TraceChunk> retrievedChunks;

    @Data
    public static class TraceChunk {
        private Long chunkId;

        private Long documentId;

        private Long knowledgeBaseId;

        private Integer chunkIndex;

        private Double distance;
    }
}
