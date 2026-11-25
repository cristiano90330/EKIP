/* 在 Redis 中读写用户的长期记忆列表，并使用原子更新保留并发修改。 */

package com.pony.knowledge.agent.memory.longterm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.time.Instant;

/**
 * 长期记忆唯一运行实现，使用 Redis 持久化。
 *
 * <p>结构与会话记忆同构：一个用户一个 List，RPUSH 追加、LRANGE 取最新 N 条、
 * LTRIM 施加容量上限（{@code keep} 个元素，负下标表示从尾部数）。
 *
 * <p>失败语义也与会话记忆一致：Redis 不可用直接抛出（由上层转 500），**不回落内存**——
 * 长期记忆回落内存的后果比会话记忆更糟：用户会看到「刚才记住的事」在下次请求里消失，
 * 而这没有任何可见的错误信号。
 *
 * <p>已知局限：Redis 没有 TTL 也不持久化到关系库，因此「长期」指的是**跨会话**而不是**跨灾难**。
 * 需要更强保证时可替换存储实现。
 */
@Component
public class RedisLongTermMemoryStore implements LongTermMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(RedisLongTermMemoryStore.class);

    private static final String KEY = "agent:memory:long:%d";

    private static final DefaultRedisScript<Long> ADD = new DefaultRedisScript<>("""
            redis.call('RPUSH', KEYS[1], ARGV[1])
            if tonumber(ARGV[2]) > 0 then redis.call('LTRIM', KEYS[1], -tonumber(ARGV[2]), -1) end
            return 1
            """, Long.class);

    // Lua 在该用户的 List 内定位并改一项；与 RPUSH/LTRIM 共用 Redis 的原子执行边界。
    private static final DefaultRedisScript<String> UPDATE = new DefaultRedisScript<>("""
            local items = redis.call('LRANGE', KEYS[1], 0, -1)
            for i, raw in ipairs(items) do
                local ok, item = pcall(cjson.decode, raw)
                if ok and type(item) == 'table' and item.id == ARGV[1] then
                    item.content = ARGV[2]
                    item.kind = ARGV[3]
                    item.updatedAt = ARGV[4]
                    local corrected = cjson.encode(item)
                    redis.call('LSET', KEYS[1], i - 1, corrected)
                    return corrected
                end
            end
            return nil
            """, String.class);

    private static final DefaultRedisScript<Long> DELETE = new DefaultRedisScript<>("""
            local items = redis.call('LRANGE', KEYS[1], 0, -1)
            for _, raw in ipairs(items) do
                local ok, item = pcall(cjson.decode, raw)
                if ok and type(item) == 'table' and item.id == ARGV[1] then
                    return redis.call('LREM', KEYS[1], 1, raw)
                end
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;

    private final ObjectMapper objectMapper;

    /**
     * 用注入依赖和构造参数建立此实例执行时使用的状态。
     *
     * @param redis 当前模块使用的 Redis 客户端
     * @param objectMapper 负责 JSON 协议编码和解码的映射器
     */
    public RedisLongTermMemoryStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /**
     * 从用户 Redis 集合读取并解码指定数量的记忆项。
     * 下游调用 {@code redis.opsForList}、{@code objectMapper.readValue}、{@code java.util.Collections.reverse}。
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

        List<String> raw = redis.opsForList().range(key(userId), -limit, -1);
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }

        List<LongTermMemoryItem> out = new ArrayList<>(raw.size());
        for (String s : raw) {
            try {
                out.add(objectMapper.readValue(s, LongTermMemoryItem.class));
            } catch (Exception e) {
                // 单条损坏跳过：一条坏数据不该让用户的所有记忆都读不出来。
                log.warn("长期记忆存在损坏条目，跳过; userId={}, reason={}", userId, e.getMessage());
            }
        }

        java.util.Collections.reverse(out);       // Redis 尾部是最新 → 返回「最新在前」
        return out;
    }

    /**
     * 用 Redis 操作添加一条记忆，并和去重、容量维护一起处理。
     * 下游调用 {@code redis.execute}、{@code key}、{@code write}。
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
        redis.execute(ADD, List.of(key(userId)), write(item, userId), String.valueOf(maxItems));
    }

    /**
     * 将指定记忆的新正文、类型和时间写回 Redis，限定为该用户和条目。
     * 下游调用 {@code Optional.empty}、{@code redis.execute}、{@code objectMapper.readValue}。
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
        if (userId == null || itemId == null) return Optional.empty();
        String raw = redis.execute(UPDATE, List.of(key(userId)), itemId, content, kind, updatedAt.toString());
        if (raw == null) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(raw, LongTermMemoryItem.class));
        } catch (Exception e) {
            throw new IllegalStateException("长期记忆纠正结果无法解析", e);
        }
    }

    /**
     * 从指定用户的记忆列表移除指定条目并返回是否删除。
     * 下游调用 {@code redis.execute}、{@code key}。
     *
     * @param userId 当前用户 ID
     * @param itemId 目标记忆条目 ID
     * @return 条件成立时为 true，其他状态按 false 处理
     */
    @Override
    public boolean delete(Long userId, String itemId) {
        if (userId == null || itemId == null) return false;
        return Long.valueOf(1).equals(redis.execute(DELETE, List.of(key(userId)), itemId));
    }

    /**
     * 把新记忆对象转换成 Redis 可保存的数据并写入对应用户范围。
     * 上游由 {@code RedisLongTermMemoryStore.add} 调用。
     * 下游调用 {@code objectMapper.writeValueAsString}。
     *
     * @param item 本次操作涉及的待存储或返回项
     * @param userId 当前用户 ID
     * @return 供后续处理使用的文本或标识
     */
    private String write(LongTermMemoryItem item, Long userId) {
        try {
            return objectMapper.writeValueAsString(item);
        } catch (Exception e) {
            throw new IllegalStateException("长期记忆序列化失败; userId=" + userId, e);
        }
    }

    /**
     * 按可信用户 ID 构造长期记忆存储键。
     * 上游由 {@code RedisLongTermMemoryStore.add}、{@code RedisLongTermMemoryStore.delete}、{@code RedisLongTermMemoryStore.list} 调用。
     * 下游调用 {@code KEY.formatted}。
     *
     * @param userId 当前用户 ID
     * @return 供后续处理使用的文本或标识
     */
    private String key(Long userId) {
        return KEY.formatted(userId);
    }
}
