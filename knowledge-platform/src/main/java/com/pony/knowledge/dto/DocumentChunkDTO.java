/* 承载文档切片浏览的正文、页码、标题及表格来源。 */

package com.pony.knowledge.dto;

import lombok.Data;

/** 可读文档切片的正文与来源，供文档浏览使用。 */
@Data
public class DocumentChunkDTO {
    private Long id;
    private Long documentId;
    private Long knowledgeBaseId;
    private Integer chunkIndex;
    private String content;
    private Integer charCount;
    private String sourceType;
    private Integer pageNo;
    private String headingPath;
    private String tableId;
    private Integer rowIndex;
    private Long parentChunkId;
}
