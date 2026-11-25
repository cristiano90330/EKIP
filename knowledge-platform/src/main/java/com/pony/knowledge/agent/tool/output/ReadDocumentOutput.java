/* 描述文档阅读结果的正文片段、来源与续读游标。 */

package com.pony.knowledge.agent.tool.output;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * read_document 输出：只包含文档限定检索并经证据准入的片段，不包含原始排序候选。
 */
@Data
@Builder
@JsonPropertyOrder({"mode", "offset", "contentOffset", "endOffset", "nextOffset", "nextContentOffset",
        "hasMore", "scannedChars", "documentId", "fileName", "query", "outcome", "truncated", "snippets"})
public class ReadDocumentOutput {

    private String mode;
    private Integer offset;
    private Integer contentOffset;
    private Integer endOffset;
    private Integer nextOffset;
    private Integer nextContentOffset;
    private boolean hasMore;
    private Integer scannedChars;

    private Long documentId;

    private String fileName;

    private String query;

    /** success / no_evidence；依赖不可用或超时以失败结果返回，不伪装成无证据。 */
    private String outcome;

    /** 片段条数或内容是否触达输出上限。 */
    private boolean truncated;

    private List<Snippet> snippets;

    @Data
    @Builder
    public static class Snippet {

        private Long documentId;

        /** 稳定来源 ID：同一文档同一块在重复读取时保持一致。 */
        private Long chunkId;

        private Long knowledgeBaseId;

        private Integer chunkIndex;

        /** 仅 page 窗口有值；引用以该值区分同一长块的不同窗口。 */
        private Integer contentOffset;

        private String content;

        private String headingPath;

        private Integer pageNo;

        private String sourceType;
    }
}
