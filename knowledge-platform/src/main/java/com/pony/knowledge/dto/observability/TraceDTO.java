/* 承载单条 RAG 轨迹及各阶段详细记录。 */

package com.pony.knowledge.dto.observability;

import lombok.Data;
import java.util.List;

@Data
public class TraceDTO {
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
    private String rewrittenQuery;
    private long startTime;
    private long totalMs;
    private List<TraceSpanDTO> spans;
}
