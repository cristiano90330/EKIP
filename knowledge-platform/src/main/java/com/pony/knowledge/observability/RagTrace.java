/* 保存一次 RAG 请求的所有者、执行策略、结果和阶段轨迹。 */

package com.pony.knowledge.observability;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** 保存一次 RAG 请求的执行摘要，以及各处理阶段的 trace span。 */
@Data
@Builder
public class RagTrace {

    private String traceId;

    /**
     * 记录归属用户，由服务端在落库时按认证上下文补充，不采信客户端传入值。
     * 归属未知（无认证上下文）时为空，普通用户的列表与详情都不回显此类记录。
     */
    private Long userId;

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

    private long startTimeEpochMs; // 墙钟，对外排序/展示；startTime 是 nanoTime 仅内部算耗时

    private long endTime;

    private List<RagSpan> spans;
}
