/* 披露单项依赖能力的状态、原因码和评估策略信息。 */

package com.pony.knowledge.dto.observability;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CapabilityStatusDTO {
    /** ready | degraded | not_ready | unknown */
    private String status;
    /** Stable short code only; implementation exception text and endpoints are never returned. */
    private String reasonCode;
    private String policyVersion;
    private String scorer;
}
