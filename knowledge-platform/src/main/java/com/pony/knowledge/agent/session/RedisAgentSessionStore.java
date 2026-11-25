/* 将会话、回合、历史和摘要持久化到 Redis，原子协调互斥和过期。 */

package com.pony.knowledge.agent.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pony.knowledge.agent.memory.MemoryItem;
import com.pony.knowledge.agent.model.AgentMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** 元数据、完成回合、模型消息和摘要共用过期时间；用户索引使用 ZRANGE 分页。 */
@Component
public class RedisAgentSessionStore implements AgentSessionStore {
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final long leaseMs;

    /**
     * 用注入依赖和构造参数建立此实例执行时使用的状态。
     *
     * @param redis 当前模块使用的 Redis 客户端
     * @param mapper 负责 JSON 协议编码和解码的映射器
     * @param budgetMs 本次调用传入的 {@code long}
     * @param streamMs 本次调用传入的 {@code long}
     */
    public RedisAgentSessionStore(StringRedisTemplate redis, ObjectMapper mapper,
                                  @Value("${agent.budget-time-ms:60000}") long budgetMs,
                                  @Value("${agent.stream.timeout-ms:180000}") long streamMs) {
        this.redis = redis;
        this.mapper = mapper;
        this.leaseMs = Math.max(600000, Math.max(budgetMs, streamMs) + 60000);
    }

    /**
     * 以 Lua 将新会话元数据和用户目录更新作为一个操作，二者共享到期约束。
     * 下游调用 {@code session.id}、{@code session.userId}、{@code session.expiresAt}。
     *
     * @param session 会话元数据及归属信息
     */
    @Override public void create(AgentSession session) {
        Long result = execute("""
                if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end
                redis.call('HSET', KEYS[1], 'data', ARGV[1], 'expiresAt', ARGV[2], 'owner', ARGV[3])
                redis.call('PEXPIREAT', KEYS[1], ARGV[2])
                redis.call('ZADD', KEYS[2], ARGV[4], ARGV[5])
                local latest = redis.call('ZRANGE', KEYS[2], 0, 0, 'REV', 'WITHSCORES')
                redis.call('PEXPIREAT', KEYS[2], latest[2])
                return 1
                """, List.of(key(session.id(), "meta"), index(session.userId())),
                json(session), String.valueOf(session.expiresAt()), String.valueOf(session.userId()),
                String.valueOf(session.expiresAt()), session.id());
        if (!Long.valueOf(1).equals(result)) throw new IllegalStateException("会话创建失败");
    }

    /**
     * 按会话元数据键加载已序列化会话，缺失时返回空值容器。
     * 上游由 {@code RedisAgentSessionStore.list} 调用。
     * 下游调用 {@code redis.opsForHash}、{@code Optional.empty}、{@code key}。
     *
     * @param id 服务端签发的产品会话 ID
     * @return 调用方使用的 {@code Optional<AgentSession>}
     */
    @Override public Optional<AgentSession> get(String id) {
        Object data = redis.opsForHash().get(key(id, "meta"), "data");
        return data == null ? Optional.empty() : Optional.of(read(data.toString(), AgentSession.class));
    }

    /**
     * 先清理已到期的目录项目，再按用户索引倒序取页及复核会话归属。
     * 下游调用 {@code redis.opsForZSet}、{@code index}。
     *
     * @param userId 当前用户 ID
     * @param offset 从结果边界起算的分页偏移
     * @param limit 最多返回或保留的条数
     * @return 调用方使用的 {@code List<AgentSession>}
     */
    @Override public List<AgentSession> list(Long userId, int offset, int limit) {
        redis.opsForZSet().removeRangeByScore(index(userId), Double.NEGATIVE_INFINITY, System.currentTimeMillis());
        Set<String> ids = redis.opsForZSet().reverseRange(index(userId), offset, offset + limit - 1L);
        if (ids == null) return List.of();
        List<AgentSession> result = new ArrayList<>();
        for (String id : ids) {
            Optional<AgentSession> found = get(id);
            if (found.isPresent() && userId.equals(found.get().userId())) result.add(found.get());
            else redis.opsForZSet().remove(index(userId), id);
        }
        return result;
    }

    /**
     * 用 Lua 先检查会话仍有效，再通过带有效期的独占键写入本轮令牌。
     * 下游调用 {@code execute}、{@code key}。
     *
     * @param id 服务端签发的产品会话 ID
     * @param token 当前操作的令牌
     * @return 条件成立时为 true，其他状态按 false 处理
     */
    @Override public boolean beginTurn(String id, String token) {
        return Long.valueOf(1).equals(execute("""
                if redis.call('EXISTS', KEYS[1]) == 0 then return 0 end
                if redis.call('SET', KEYS[2], ARGV[1], 'NX', 'PX', ARGV[2]) then return 1 end
                return 0
                """, List.of(key(id, "meta"), key(id, "active")), token, String.valueOf(leaseMs)));
    }

    /**
     * 仅当占用键的值等于给定令牌时才删除该键。
     * 下游调用 {@code execute}、{@code key}。
     *
     * @param id 服务端签发的产品会话 ID
     * @param token 当前操作的令牌
     */
    @Override public void endTurn(String id, String token) {
        execute("""
                if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end
                return 0
                """, List.of(key(id, "active")), token);
    }

    /**
     * 以 Lua 校验元数据和令牌，再一次保存终态、会话消息、目录和到期时间。
     * 下游调用 {@code session.id}、{@code session.userId}、{@code session.expiresAt}。
     *
     * @param session 会话元数据及归属信息
     * @param token 当前操作的令牌
     * @param turn 已完成回合及其交付摘要
     * @param messages 按照交互顺序提供的消息列表
     */
    @Override public void completeTurn(AgentSession session, String token, AgentSessionTurn turn,
                                       List<AgentMessage> messages) {
        Long result = execute("""
                if redis.call('EXISTS', KEYS[1]) == 0 or redis.call('GET', KEYS[5]) ~= ARGV[1] then return 0 end
                redis.call('HSET', KEYS[1], 'data', ARGV[2], 'expiresAt', ARGV[3])
                redis.call('RPUSH', KEYS[2], ARGV[4])
                redis.call('RPUSH', KEYS[3], ARGV[5], ARGV[6])
                for i=1,4 do redis.call('PEXPIREAT', KEYS[i], ARGV[3]) end
                redis.call('ZADD', KEYS[6], ARGV[7], ARGV[8])
                local latest = redis.call('ZRANGE', KEYS[6], 0, 0, 'REV', 'WITHSCORES')
                redis.call('PEXPIREAT', KEYS[6], latest[2])
                return 1
                """, List.of(key(session.id(), "meta"), key(session.id(), "turns"),
                        key(session.id(), "messages"), key(session.id(), "summary"),
                        key(session.id(), "active"), index(session.userId())),
                token, json(session), String.valueOf(session.expiresAt()), json(turn),
                json(messages.get(0)), json(messages.get(1)), String.valueOf(session.expiresAt()), session.id());
        if (!Long.valueOf(1).equals(result)) throw new IllegalStateException("会话已失效或回合租约已结束");
    }

    /**
     * 在 Lua 中复核归属与回合槽，将会话的关联数据和用户目录项一并清理。
     * 下游调用 {@code execute}、{@code key}、{@code index}。
     *
     * @param id 服务端签发的产品会话 ID
     * @param userId 当前用户 ID
     * @return 条件成立时为 true，其他状态按 false 处理
     */
    @Override public boolean delete(String id, Long userId) {
        Long result = execute("""
                if redis.call('HGET', KEYS[1], 'owner') ~= ARGV[1] then return 1 end
                if redis.call('EXISTS', KEYS[5]) == 1 then return 0 end
                redis.call('DEL', KEYS[1], KEYS[2], KEYS[3], KEYS[4])
                redis.call('ZREM', KEYS[6], ARGV[2])
                return 1
                """, List.of(key(id, "meta"), key(id, "turns"), key(id, "messages"),
                        key(id, "summary"), key(id, "active"), index(userId)), String.valueOf(userId), id);
        return Long.valueOf(1).equals(result);
    }

    /**
     * 从会话回合列表读取限定的页，内部转换成 Redis 闭区间索引。
     * 下游调用 {@code range}、{@code key}。
     *
     * @param id 服务端签发的产品会话 ID
     * @param offset 从结果边界起算的分页偏移
     * @param limit 最多返回或保留的条数
     * @return 调用方使用的 {@code List<AgentSessionTurn>}
     */
    @Override public List<AgentSessionTurn> history(String id, int offset, int limit) {
        if (limit <= 0) return List.of();
        return range(key(id, "turns"), offset, offset + limit - 1L, AgentSessionTurn.class);
    }

    /**
     * 读取该会话的已保存回合列表长度。
     * 下游调用 {@code count}、{@code key}。
     *
     * @param id 服务端签发的产品会话 ID
     * @return 按当前方法口径计算或读取的数值
     */
    @Override public int countTurns(String id) { return count(key(id, "turns")); }

    /**
     * 将左闭右开的消息切片边界转成 Redis 的闭区间读取。
     * 下游调用 {@code range}、{@code key}。
     *
     * @param id 服务端签发的产品会话 ID
     * @param from 包含的起始位置
     * @param to 目标区间的结束位置
     * @return 按当前约定排列的模型消息列表
     */
    @Override public List<AgentMessage> messages(String id, int from, int to) {
        return to <= from ? List.of() : range(key(id, "messages"), from, to - 1L, AgentMessage.class);
    }

    /**
     * 读取该会话可恢复的模型消息长度。
     * 下游调用 {@code count}、{@code key}。
     *
     * @param id 服务端签发的产品会话 ID
     * @return 按当前方法口径计算或读取的数值
     */
    @Override public int countMessages(String id) { return count(key(id, "messages")); }

    /**
     * 读取归属该产品会话的当前摘要 JSON 并转换成记忆项。
     * 下游调用 {@code redis.opsForValue}、{@code Optional.empty}、{@code key}。
     *
     * @param id 服务端签发的产品会话 ID
     * @return 调用方使用的 {@code Optional<MemoryItem>}
     */
    @Override public Optional<MemoryItem> summary(String id) {
        String data = redis.opsForValue().get(key(id, "summary"));
        return data == null ? Optional.empty() : Optional.of(read(data, MemoryItem.class));
    }

    /**
     * 在会话元数据仍有效时保存摘要，并沿用会话到期时刻。
     * 下游调用 {@code execute}、{@code key}、{@code json}。
     *
     * @param id 服务端签发的产品会话 ID
     * @param summary 本次调用传入的 {@code MemoryItem}
     */
    @Override public void saveSummary(String id, MemoryItem summary) {
        // 摘要可能晚于删除或过期，只有元数据还存在时才允许写入，沿用同一个绝对过期点。
        execute("""
                local expiry = redis.call('HGET', KEYS[1], 'expiresAt')
                if not expiry then return 0 end
                redis.call('SET', KEYS[2], ARGV[1])
                redis.call('PEXPIREAT', KEYS[2], expiry)
                return 1
                """, List.of(key(id, "meta"), key(id, "summary")), json(summary));
    }

    /**
     * 从指定 Redis 列表键读取已保存项数量。
     * 上游由 {@code RedisAgentSessionStore.countMessages}、{@code RedisAgentSessionStore.countTurns} 调用。
     * 下游调用 {@code redis.opsForList}、{@code Math.toIntExact}。
     *
     * @param key 用于访问当前数据的存储键
     * @return 按当前方法口径计算或读取的数值
     */
    private int count(String key) {
        Long count = redis.opsForList().size(key); return count == null ? 0 : Math.toIntExact(count);
    }

    /**
     * 读取指定 Redis 列表区间，并解码成调用方要求的类型。
     * 上游由 {@code RedisAgentSessionStore.history}、{@code RedisAgentSessionStore.messages} 调用。
     * 下游调用 {@code redis.opsForList}、{@code read}。
     *
     * @param key 用于访问当前数据的存储键
     * @param from 包含的起始位置
     * @param to 目标区间的结束位置
     * @param type 本次调用传入的 {@code Class<T>}
     * @return 调用方使用的 {@code List<T>}
     */
    private <T> List<T> range(String key, long from, long to, Class<T> type) {
        List<String> data = redis.opsForList().range(key, from, to);
        return data == null ? List.of() : data.stream().map(item -> read(item, type)).toList();
    }

    /**
     * 用指定键和参数调用 Redis Lua 脚本，返回状态或受影响条数。
     * 上游由 {@code RedisAgentSessionStore.beginTurn}、{@code RedisAgentSessionStore.completeTurn}、{@code RedisAgentSessionStore.create} 调用。
     * 下游调用 {@code redis.execute}。
     *
     * @param script 本次调用传入的 {@code String}
     * @param keys 本次调用传入的 {@code List<String>}
     * @param args 本次调用传入的 {@code String[]}
     * @return 按当前方法口径计算或读取的数值
     */
    private Long execute(String script, List<String> keys, String... args) {
        return redis.execute(new DefaultRedisScript<>(script, Long.class), keys, (Object[]) args);
    }

    /**
     * 将会话、回合、摘要或消息编码成存储所需的 JSON。
     * 上游由 {@code RedisAgentSessionStore.completeTurn}、{@code RedisAgentSessionStore.create}、{@code RedisAgentSessionStore.saveSummary} 调用。
     * 下游调用 {@code mapper.writeValueAsString}。
     *
     * @param value 本次调用传入的 {@code Object}
     * @return 供后续处理使用的文本或标识
     */
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("会话序列化失败", e); }
    }

    /**
     * 将保存的 JSON 恢复成目标会话数据类型。
     * 上游由 {@code RedisAgentSessionStore.get}、{@code RedisAgentSessionStore.range}、{@code RedisAgentSessionStore.summary} 调用。
     * 下游调用 {@code mapper.readValue}。
     *
     * @param value 本次调用传入的 {@code String}
     * @param type 本次调用传入的 {@code Class<T>}
     * @return 调用方使用的 {@code T}
     */
    private <T> T read(String value, Class<T> type) {
        try { return mapper.readValue(value, type); }
        catch (JsonProcessingException e) { throw new IllegalStateException("会话数据损坏", e); }
    }

    /**
     * 按产品会话标识与数据类型生成元数据、消息、摘要或占用键。
     * 上游由 {@code RedisAgentSessionStore.beginTurn}、{@code RedisAgentSessionStore.completeTurn}、{@code RedisAgentSessionStore.countMessages} 调用。
     *
     * @param id 服务端签发的产品会话 ID
     * @param suffix 本次调用传入的 {@code String}
     * @return 供后续处理使用的文本或标识
     */
    private String key(String id, String suffix) { return "agent:session:" + id + ":" + suffix; }

    /**
     * 生成当前用户的会话目录排序索引键。
     * 上游由 {@code RedisAgentSessionStore.completeTurn}、{@code RedisAgentSessionStore.create}、{@code RedisAgentSessionStore.delete} 调用。
     *
     * @param userId 当前用户 ID
     * @return 供后续处理使用的文本或标识
     */
    private String index(Long userId) { return "agent:session:user:" + userId; }
}
