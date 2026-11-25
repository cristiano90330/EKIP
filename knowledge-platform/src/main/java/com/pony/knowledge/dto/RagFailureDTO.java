/* 向调用方暴露问答失败的结果分类、原因、阶段和请求标识。 */

package com.pony.knowledge.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/** RAG 失败响应的稳定诊断字段，不包含底层异常详情。 */
@Data
@AllArgsConstructor
public class RagFailureDTO {
    private String outcome;
    private String reasonCode;
    private String failedStage;
    private String requestId;
}
