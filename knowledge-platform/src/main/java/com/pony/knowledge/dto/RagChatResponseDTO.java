/* 承载指定知识库问答的答案、证据、降级与执行信息。 */

package com.pony.knowledge.dto;

import lombok.Data;

import java.util.List;

/** 会话问答响应，返回答案、改写结果、获准来源以及本次实际执行状态。 */
@Data
public class RagChatResponseDTO {
    private String pipelineEngine;

    private String retrievalVersion;

    private String evidenceMode;

    private String answer;

    /** 用户原始问题 */
    private String originalQuery;

    /** 改写后的问题（Query Rewrite 输出；未改写时与 originalQuery 相同） */
    private String rewrittenQuery;

    private List<SourceDTO> sources;

    /** 本次回答是否降级，具体原因由 degradeReason 表达。 */
    private boolean degraded;

    /** 降级原因（如「prompt 超预算直接降级」）；未降级时为空 */
    private String degradeReason;

    /** 实际服务的 provider（主 / 降级供应商名） */
    private String providerUsed;

    private String outcome;

    private String reasonCode;

    private String failedStage;

    private String requestId;

    private String retrievalStatus;

    private com.pony.knowledge.service.rag.model.RagEvidenceDecision evidenceDecision;
}
