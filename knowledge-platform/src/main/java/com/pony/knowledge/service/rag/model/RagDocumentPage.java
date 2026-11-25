/* 承载文档阅读的当前范围、下次偏移和分页状态。 */

package com.pony.knowledge.service.rag.model;

import lombok.Builder;
import lombok.Data;

/** 扫描进度独立于准入结果；依赖失败不返回可推进的游标。 */
@Data
@Builder
public class RagDocumentPage {
    private int offset;
    private int contentOffset;
    private Integer endOffset;
    private Integer nextOffset;
    private Integer nextContentOffset;
    private boolean hasMore;
    private int scannedChars;
}
