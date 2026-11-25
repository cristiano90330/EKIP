/* 将检索证据的位置和正文提供给引用展示。 */

package com.pony.knowledge.dto;

import lombok.Data;

/** 问答响应中的获准引用来源，保留 chunk 正文及其文档位置元数据。 */
@Data
public class SourceDTO {
    /** Agent 本轮模型输入内的稳定引用 ID；普通 RAG 响应可为空。 */
    private String citationId;

    private Long documentId;

    private Long chunkId;

    /** 来源知识库（全局检索为 null），来源链 Chunk → Document → KB */
    private Long knowledgeBaseId;

    private Integer chunkIndex;

    /** 连续阅读窗口起点（Unicode 字符）；普通查询来源可为空。 */
    private Integer contentOffset;

    private String content;

    private Double distance;

    /** 章节路径，例如 "1.1" / "3.2"；可为空。 */
    private String headingPath;

    /** 来源页码，可为空。 */
    private Integer pageNo;

    /** 来源类型（paragraph / table / heading 等；可为 null） */
    private String sourceType;
}
