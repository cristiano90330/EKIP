/* 将传统问答历史序列化到 Redis，按窗口及过期时间维护历史。 */

package com.pony.knowledge.service.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pony.knowledge.service.rag.query.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话历史的持久读写边界，通过完整会话键定位消息列表。
 * 读取最近消息，追加一轮问答；依赖读写失败向业务层报告，单条损坏消息记录后跳过。
 */
@Component
public class RedisConversationStore implements ConversationStore {

    private static final Logger log = LoggerFactory.getLogger(RedisConversationStore.class);

    private static final String MSG_KEY = "rag:conversation:%s:messages";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    /**
     * 保存将传统问答历史序列化到 Redis所需的依赖和初始配置。
     * 上游为 Spring 组件装配。
     *
     * @param redis 访问 Redis 保存和读取状态的客户端
     * @param objectMapper 序列化和解析 JSON 使用的组件
     */
    public RedisConversationStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /**
     * 从 Redis 读出指定会话的最近消息并恢复为有序历史。
     * 上游为 ConversationService.getHistory。
     * 下游为 ConversationKey.storageId。
     *
     * @param key 本次操作使用的 ConversationKey 组件或记录
     * @param limit 本次最多处理或返回的项数
     * @return 与过滤条件匹配的 List&lt;ChatMessage&gt; 数据集合
     */
    @Override
    public List<ChatMessage> loadHistory(ConversationKey key, int limit) {
        if (key == null || limit <= 0) {
            return List.of();
        }
        String redisKey = MSG_KEY.formatted(key.storageId());
        List<String> raw;

        try {
            raw = redis.opsForList().range(redisKey, -limit, -1);
        } catch (DataAccessException e) {
            throw new com.pony.knowledge.exception.ConversationStoreUnavailableException(
                    "Redis 会话历史读取失败", e);
        }
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<ChatMessage> out = new ArrayList<>(raw.size());

        for (String s : raw) {
            try {
                out.add(objectMapper.readValue(s, ChatMessage.class));
            } catch (Exception e) {
                // 单条数据损坏不终止整次读取，保留日志供定位。
                log.warn("Redis 会话历史存在损坏条目，跳过; key={}, value={}, reason={}",
                        redisKey, s, e.getMessage());
            }
        }
        return out;
    }

    /**
     * 追加问答消息并依照窗口和过期配置维护 Redis 历史。
     * 上游为 ConversationService.append。
     * 下游为 ConversationKey.storageId。
     *
     * @param key 本次操作使用的 ConversationKey 组件或记录
     * @param user 账号数据
     * @param assistant 本轮需要持久化的助手消息
     */
    @Override
    public void append(ConversationKey key, ChatMessage user, ChatMessage assistant) {
        if (key == null) {
            return;
        }
        String redisKey = MSG_KEY.formatted(key.storageId());
        try {
            redis.opsForList().rightPushAll(redisKey,
                    objectMapper.writeValueAsString(user),
                    objectMapper.writeValueAsString(assistant));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "会话消息序列化失败，无法写入 Redis; key=" + redisKey, e);
        } catch (DataAccessException e) {
            throw new com.pony.knowledge.exception.ConversationStoreUnavailableException(
                    "Redis 会话历史写入失败", e);
        }
    }
}
