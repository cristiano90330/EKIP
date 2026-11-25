/* 承载单个 RAG 阶段的耗时、用量和执行结果。 */

package com.pony.knowledge.dto.observability;

import lombok.Data;

@Data
public class TraceSpanDTO {
    private String stage;
    private long durationMs;
    private Integer totalTokens;
    private Double costUsd;
    private Integer inputTokens;
    private Integer outputTokens;
    private boolean success;
    private String errorMessage;
}