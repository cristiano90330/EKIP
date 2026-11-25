/* 承载轨迹列表的执行信息和耗时摘要。 */

package com.pony.knowledge.dto.observability;

import lombok.Data;

@Data
public class TraceSummaryDTO {
    private String traceId;
    private String entrypoint;
    private String requestedPipelineEngine;
    private String requestedRetrievalVersion;
    private String pipelineEngine;
    private String retrievalVersion;
    private String providerUsed;
    private String evidenceScorer;
    private String outcome;
    private String reasonCode;
    private String originalQuery;
    private long totalMs;
    private int spanCount;
    private boolean success;
    private long startTime;
}
