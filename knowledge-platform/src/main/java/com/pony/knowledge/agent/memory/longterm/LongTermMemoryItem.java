/* 保存一条长期记忆的归属、类型、正文和修改时间。 */

package com.pony.knowledge.agent.memory.longterm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 一条经用户确认、可跨会话召回的长期记忆。
 *
 * <p>长期记忆以用户为作用域，会话记忆随产品会话保留，
 * 长期记忆恰恰要跨会话生效，因此按 userId 存、由 userId 召回——两者是不同的键空间，
 * 不能混用（否则 A 的偏好会出现在 B 的回答里）。
 *
 * <p>显式保存请求和自动识别的回复偏好共用审批，写入后通过词法召回。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LongTermMemoryItem {

    private String id;

    private Long userId;

    /** 类别：FACT / PREFERENCE / PROFILE（未知值按 FACT 处理）。 */
    private String kind;

    private String content;

    /** 写入来源的会话键，便于追溯「这条记忆是在哪次对话里记下的」。 */
    private String sourceSessionId;

    private Instant createdAt;

    private Instant updatedAt;

    /**
     * 将记忆分类值归一化到允许类型，对未识别类型使用事实分类。
     * 上游由 {@code LongTermMemoryService.correct}、{@code LongTermMemoryService.remember}、{@code MemorySaveTool.execute} 调用。
     *
     * @param raw 尚待解析或归一化的原始值
     * @return 供后续处理使用的文本或标识
     */
    public static String normalizeKind(String raw) {
        if (raw == null) {
            return "FACT";
        }
        String upper = raw.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (upper) {
            case "PREFERENCE", "PROFILE", "FACT" -> upper;
            default -> "FACT";
        };
    }
}
