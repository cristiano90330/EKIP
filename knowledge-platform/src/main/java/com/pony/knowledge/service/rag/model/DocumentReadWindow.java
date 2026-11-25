/* 描述 Agent 文档工具指定的分页、字符偏移或邻域窗口。 */

package com.pony.knowledge.service.rag.model;

/** 服务端有界阅读选项；offset 是非 parent 块序号，contentOffset 是块内 Unicode 字符偏移。 */
public record DocumentReadWindow(int offset, int contentOffset, Integer endOffset,
                                 Long aroundChunkId, int contextRadius, int windowChars) {
}
