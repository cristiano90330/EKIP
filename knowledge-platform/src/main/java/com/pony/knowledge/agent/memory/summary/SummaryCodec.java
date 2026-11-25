/* 在会话摘要对象和 Redis 使用的 JSON 之间转换。 */

package com.pony.knowledge.agent.memory.summary;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pony.knowledge.agent.memory.MemoryItem;

/**
 * ConversationSummary 与 MemoryItem.content 的编解码。
 * 存的时候把摘要（含 summarizedMessageCount 游标）序列化成 JSON；读的时候反序列化回模型，
 * 兼容纯文本内容（无游标 → 视为 0）。Coordinator 与 ContextBuilder 共用，避免两处逻辑漂移。
 */
public final class SummaryCodec {

    /** 保留私有构造入口，以便通过静态方法使用此类型。 */
    private SummaryCodec() {
    }

    /**
     * 将摘要文本与游标、降级状态编码成通用记忆存储记录。
     * 上游由 {@code SummaryCoordinator.saveSummary} 调用。
     * 下游调用 {@code om.writeValueAsString}。
     *
     * @param summary 本次调用传入的 {@code ConversationSummary}
     * @param om 本次调用传入的 {@code ObjectMapper}
     * @return 供后续处理使用的文本或标识
     */
    public static String encode(ConversationSummary summary, ObjectMapper om) {
        try {
            return om.writeValueAsString(summary);
        } catch (Exception e) {
            throw new IllegalStateException("摘要序列化失败", e);
        }
    }

    /**
     * 从记忆记录恢复会话摘要，供历史折叠和上下文装配使用。
     * 上游由 {@code DefaultContextBuilder.loadSummary}、{@code SummaryCoordinator.loadSummary} 调用。
     * 下游调用 {@code om.readValue}。
     *
     * @param item 本次操作涉及的待存储或返回项
     * @param om 本次调用传入的 {@code ObjectMapper}
     * @return 带折叠位置的会话摘要
     */
    public static ConversationSummary decode(MemoryItem item, ObjectMapper om) {
        if (item == null || item.getContent() == null) {
            return null;
        }
        try {
            return om.readValue(item.getContent(), ConversationSummary.class);
        } catch (Exception e) {
            return ConversationSummary.builder()
                    .sessionId(item.getSessionId())
                    .summary(item.getContent())
                    .build();
        }
    }
}
