/* 描述会话记忆的类型、内容及元数据。 */

package com.pony.knowledge.agent.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 记忆条目：可为最近消息 / 会话摘要 / 长期记忆（type 区分）。
 * summary 存 Redis 时走 JSON，需默认构造器（@Data+@Builder 下 Jackson 反序列化坑）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryItem {

    private String id;

    private String sessionId;

    private MemoryType type;

    private String content;

    private Instant createdAt;

    private Instant updatedAt;
}
