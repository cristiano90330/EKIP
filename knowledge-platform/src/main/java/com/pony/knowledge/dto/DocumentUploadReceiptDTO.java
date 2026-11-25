/* 向上传调用方返回请求键、受理结果和关联的文档及任务。 */

package com.pony.knowledge.dto;

import lombok.Data;

/** 上传受理回执，关联幂等请求键、文档和任务，并标明是否复用了已有受理结果。 */
@Data
public class DocumentUploadReceiptDTO {
    private String requestKey;
    private Long documentId;
    private Long taskId;
    private String documentStatus;
    private String taskStatus;
    private boolean reused;
}
