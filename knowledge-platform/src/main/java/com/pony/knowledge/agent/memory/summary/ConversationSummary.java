/* 保存增量摘要、折叠位置和降级标志。 */

package com.pony.knowledge.agent.memory.summary;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 一段会话的摘要结果。由 SummaryService 产出，持久化为 MemoryItem(type=SUMMARY)，
 * ContextBuilder 读取后与最近窗口一起喂给 LLM。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationSummary {

    private String sessionId;

    /** 摘要正文 */
    private String summary;

    /** 本次已折入摘要的消息条数 */
    private int summarizedMessageCount;

    /**
     * 是否为降级归档（摘要模型不可用时的原始对话摘录）。
     *
     * <p>降级摘要与正常摘要共用同一份存储与游标，区别只在于内容质量与被注入时的标注：
     * 它仍是「这批消息已经折过了」的证据，所以游标照常前进——否则同一批消息会每回合重折一次，
     * 每一次都失败、每一次都付费。标记出来是为了让 ContextBuilder 在注入时告诉模型
     * 「这是原始摘录，不是整理过的要点」，而不是让它当成结论使用。
     */
    private boolean degraded;

    private Instant updatedAt;
}
