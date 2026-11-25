/* 隔离保存各用户的长期记忆，供管理、修改和检索测试使用。 */

package com.pony.knowledge.agent.memory.longterm;


import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test fixture for long-term memory rules; no production or offline runtime mode.
 */
public class InMemoryLongTermMemoryStore implements LongTermMemoryStore {

    private final Map<Long, List<LongTermMemoryItem>> byUser = new ConcurrentHashMap<>();

    /**
     * 按范围读取匹配项。
     * 下游调用 {@code java.util.Collections.reverse}、{@code newestFirst.subList}。
     *
     * @param userId 当前用户 ID
     * @param limit 最多返回或保留的条数
     * @return 调用方使用的 {@code List<LongTermMemoryItem>}
     */
    @Override
    public List<LongTermMemoryItem> list(Long userId, int limit) {
        if (userId == null || limit <= 0) {
            return List.of();
        }
        List<LongTermMemoryItem> all = byUser.getOrDefault(userId, List.of());
        synchronized (all) {
            List<LongTermMemoryItem> newestFirst = new ArrayList<>(all);
            java.util.Collections.reverse(newestFirst);
            return newestFirst.size() <= limit ? newestFirst : newestFirst.subList(0, limit);
        }
    }

    /**
     * 在该类型内部准备后续运行所需的数据。
     *
     * @param userId 当前用户 ID
     * @param item 本次操作涉及的待存储或返回项
     * @param maxItems 本次调用传入的 {@code int}
     */
    @Override
    public void add(Long userId, LongTermMemoryItem item, int maxItems) {
        if (userId == null || item == null) {
            return;
        }
        List<LongTermMemoryItem> list = byUser.computeIfAbsent(userId, k -> new ArrayList<>());
        synchronized (list) {
            list.add(item);
            while (maxItems > 0 && list.size() > maxItems) {
                list.remove(0);              // 淘汰最旧的
            }
        }
    }

    /**
     * 将变更写回目标记录。
     * 下游调用 {@code Optional.empty}。
     *
     * @param userId 当前用户 ID
     * @param itemId 目标记忆条目 ID
     * @param content 待处理的正文内容
     * @param kind 长期记忆业务分类
     * @param updatedAt 本次调用传入的 {@code Instant}
     * @return 调用方使用的 {@code Optional<LongTermMemoryItem>}
     */
    @Override
    public Optional<LongTermMemoryItem> update(Long userId, String itemId, String content, String kind, Instant updatedAt) {
        List<LongTermMemoryItem> list = userId == null ? null : byUser.get(userId);
        if (list == null) return Optional.empty();
        synchronized (list) {
            for (int i = 0; i < list.size(); i++) {
                LongTermMemoryItem existing = list.get(i);
                if (!java.util.Objects.equals(itemId, existing.getId())) continue;
                LongTermMemoryItem corrected = LongTermMemoryItem.builder().id(existing.getId()).userId(userId)
                        .content(content).kind(kind).sourceSessionId(existing.getSourceSessionId())
                        .createdAt(existing.getCreatedAt()).updatedAt(updatedAt).build();
                list.set(i, corrected);
                return Optional.of(corrected);
            }
            return Optional.empty();
        }
    }

    /**
     * 移除指定范围内的目标记录。
     * 下游调用 {@code list.removeIf}。
     *
     * @param userId 当前用户 ID
     * @param itemId 目标记忆条目 ID
     * @return 条件成立时为 true，其他状态按 false 处理
     */
    @Override
    public boolean delete(Long userId, String itemId) {
        List<LongTermMemoryItem> list = userId == null ? null : byUser.get(userId);
        if (list == null) return false;
        synchronized (list) {
            return list.removeIf(item -> java.util.Objects.equals(itemId, item.getId()));
        }
    }
}
