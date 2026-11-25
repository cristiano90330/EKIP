/* 承载服务能力总体状态、探测时间和分项状态。 */

package com.pony.knowledge.dto.observability;

import lombok.Data;

import java.time.Instant;
import java.util.Map;

@Data
public class ObservabilityCapabilitiesDTO {
    /** ready | degraded | not_ready */
    private String status;
    private Instant checkedAt;
    private Map<String, CapabilityStatusDTO> capabilities;
}
