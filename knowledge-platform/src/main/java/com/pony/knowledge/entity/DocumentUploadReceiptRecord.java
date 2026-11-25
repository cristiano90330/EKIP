/* 映射按账号、知识库和请求键保存的上传幂等回执。 */

package com.pony.knowledge.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一次上传意图与已创建文档、任务的关联记录。
 * 用户、知识库和请求键限定作用域，内容摘要用于检测同键换内容；响应状态从关联记录读取。
 */
@Data
public class DocumentUploadReceiptRecord {
    private Long id;
    private Long userId;
    private Long knowledgeBaseId;
    private String requestKey;
    private String contentSha256;
    private Long documentId;
    private Long taskId;
    private LocalDateTime createTime;
}
