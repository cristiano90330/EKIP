/* 定义根据会话快照生成新摘要的接口。 */

package com.pony.knowledge.agent.memory.summary;

/**
 * 会话摘要服务：对一个 MemorySnapshot（待摘要消息窗口 + 已有摘要）产出一段浓缩摘要。
 */
public interface SummaryService {

    /**
     * 从已有摘要和待折叠消息生成新摘要，并交付给会话整理器。
     * 上游由 {@code SummaryCoordinator.doIncrementalSummarize} 调用。
     *
     * @param snapshot 用于此次处理的会话或模型快照
     * @return 带折叠位置的会话摘要
     */
    ConversationSummary summarize(MemorySnapshot snapshot);
}
