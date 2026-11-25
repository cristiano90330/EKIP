/* 定义按用户保存、列出、修正和删除长期记忆的持久化契约。 */

package com.pony.knowledge.agent.memory.longterm;

import java.util.List;
import java.util.Optional;
import java.time.Instant;

/**
 * 长期记忆存储契约：所有操作按 userId 定界，纠正和删除与并发追加原子互斥。
 *
 * <p>为什么要单独一个接口而不是复用 {@code MemoryStore}：后者的键是 sessionId、值是对话消息，
 * 生命周期与用途都不同。合成一个接口会让「会话键」和「用户键」在同一处流转，
 * 而那正是最容易被写错成「串号」的地方。
 */
public interface LongTermMemoryStore {

    /**
     * 按用户读取指定上限的长期记忆记录。
     * 上游由 {@code LongTermMemoryService.list}、{@code LongTermMemoryService.managementList}、{@code LongTermMemoryService.recall} 调用。
     *
     * @param userId 当前用户 ID
     * @param limit 最多返回或保留的条数
     * @return 调用方使用的 {@code List<LongTermMemoryItem>}
     */
    List<LongTermMemoryItem> list(Long userId, int limit);

    /**
     * 在用户的记忆集合中插入条目，同时按容量清理旧条目。
     * 上游由 {@code LongTermMemoryService.remember} 调用。
     *
     * @param userId 当前用户 ID
     * @param item 本次操作涉及的待存储或返回项
     * @param maxItems 本次调用传入的 {@code int}
     */
    void add(Long userId, LongTermMemoryItem item, int maxItems);

    /**
     * 按用户及条目标识更新内容、分类和修改时间，返回保存后的记录。
     * 上游由 {@code LongTermMemoryService.correct} 调用。
     *
     * @param userId 当前用户 ID
     * @param itemId 目标记忆条目 ID
     * @param content 待处理的正文内容
     * @param kind 长期记忆业务分类
     * @param updatedAt 本次调用传入的 {@code Instant}
     * @return 调用方使用的 {@code Optional<LongTermMemoryItem>}
     */
    Optional<LongTermMemoryItem> update(Long userId, String itemId, String content, String kind, Instant updatedAt);

    /**
     * 按用户隔离范围和条目标识删除记忆，返回移除结果。
     * 上游由 {@code LongTermMemoryService.delete} 调用。
     *
     * @param userId 当前用户 ID
     * @param itemId 目标记忆条目 ID
     * @return 条件成立时为 true，其他状态按 false 处理
     */
    boolean delete(Long userId, String itemId);
}
