/* 承载同步知识问答的答案、获准来源及执行状态。 */

package com.pony.knowledge.dto;

import lombok.Data;

import java.util.List;

/** 非会话 RAG 响应，包含答案、获准来源和实际管线状态。 */
@Data
public class RAGResponseDTO {
    private String pipelineEngine;

    private String retrievalVersion;

    private String evidenceMode;

    private String answer;

    private List<SourceDTO> sources;

    private String outcome;

    private String reasonCode;

    private String failedStage;

    private String requestId;

    private String retrievalStatus;

    private com.pony.knowledge.service.rag.model.RagEvidenceDecision evidenceDecision;

    private boolean degraded;

    private String degradeReason;

    private String providerUsed;
}

