/* 记录证据策略、数量统计和准入判断。 */

package com.pony.knowledge.service.rag.model;

import lombok.Builder;
import lombok.Data;

/** Explainable decision made by the Python evidence gate. */
@Data
@Builder
public class RagEvidenceDecision {
    private String state;
    private String policyVersion;
    private String scorer;
    private Double threshold;
    private Integer retrievedCount;
    private Integer acceptedCount;
    private Integer rejectedCount;
    private String reasonCode;
}
