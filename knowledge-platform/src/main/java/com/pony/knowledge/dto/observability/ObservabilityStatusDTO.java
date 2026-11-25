/* 承载应用版本、检索配置、向量存储和轨迹保留状态。 */

package com.pony.knowledge.dto.observability;

import lombok.Data;

@Data
public class ObservabilityStatusDTO {
    private String appVersion;
    private String branch;
    private String commitSha;
    private String retrievalVersion;
    private String pipelineEngine;
    private String compressionMode;
    private String vectorStore;
    private int traceRetention;
}
