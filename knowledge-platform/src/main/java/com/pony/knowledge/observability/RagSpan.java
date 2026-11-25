/* 记录 RAG 单阶段执行时间、用量、错误和降级信息。 */

package com.pony.knowledge.observability;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RagSpan {

    private TraceStage stage;

    private long startTime;

    private long endTime;

    private long durationMs;

    private boolean success;

    private String errorMessage;

    private Integer totalTokens;
    private Double costUsd;
    private Integer inputTokens;
    private Integer outputTokens;

    /** 标记本阶段是否使用兜底结果。 */
    private boolean degraded;

    /** 固定短码，与 {@code rag.stage.degraded} 的 reason tag 同值。 */
    private String degradeReason;
}
