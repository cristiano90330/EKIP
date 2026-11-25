/* 描述文档列表结果、可见元数据和分页游标。 */

package com.pony.knowledge.agent.tool.output;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** 不暴露原始文件路径、上传者或存储对象 key。 */
@Data
@Builder
public class ListDocumentsOutput {
    private int offset;
    private Integer nextOffset;
    private boolean hasMore;
    private List<Item> documents;

    @Data
    @Builder
    public static class Item {
        private Long documentId;
        private Long knowledgeBaseId;
        private String fileName;
        private String fileType;
        private Long fileSize;
    }
}
