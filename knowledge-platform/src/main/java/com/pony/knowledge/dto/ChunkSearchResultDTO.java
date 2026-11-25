/* 承载纯检索返回的切片、知识库、位置与距离信息。 */

package com.pony.knowledge.dto;

import lombok.Data;

/** 检索结果的公开传输对象，包含 chunk 内容、相似度及可用于来源展示的文档位置元数据。 */
@Data
public class ChunkSearchResultDTO {
    private Long id;

    private Long documentId;

    /** 来源知识库（全局检索为 null），来源链 Chunk → Document → KB */
    private Long knowledgeBaseId;

    private Integer chunkIndex;

    private String content;

    private Double distance;

    /** 章节路径，例如 "1.1" / "3.2"；可为空。 */
    private String headingPath;

    /** 来源页码，可为空。 */
    private Integer pageNo;

    /** 来源类型（paragraph / table / heading 等；可为 null） */
    private String sourceType;
}
