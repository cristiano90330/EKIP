/* 承载问答候选或获准证据的正文、来源和排序信息。 */

package com.pony.knowledge.service.rag.model;

import lombok.Builder;
import lombok.Data;

/** 经证据门禁准入、可供 Java 映射为公开引用来源的检索片段。 */
@Data
@Builder
public class RagEvidence {

    private Long documentId;

    private Long chunkId;

    /** 来源知识库（全局检索为 null），来源链 Chunk → Document → KB */
    private Long knowledgeBaseId;

    private Integer chunkIndex;

    /** 连续阅读窗口起点；普通查询为 null。 */
    private Integer contentOffset;

    private String content;

    /** 融合/重排分（rerank 或 RRF 档有值） */
    private Double score;

    /** 向量余弦距离（关键词命中为 null；baseline 档 score 为空时退回此值） */
    private Double distance;

    private Integer rank;

    /** 章节路径，例如 "1.1" / "3.2"；可为空。 */
    private String headingPath;

    /** 来源页码，可为空。 */
    private Integer pageNo;

    /** 来源类型（paragraph / table / heading 等；可为 null） */
    private String sourceType;
}
