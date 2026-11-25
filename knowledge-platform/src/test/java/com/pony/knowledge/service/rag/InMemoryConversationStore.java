/* 提供与问答存储契约一致的进程内替身，供会话业务测试使用。 */

package com.pony.knowledge.service.rag;

import com.pony.knowledge.service.rag.query.ChatMessage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Minimal business-test fixture; persistence, TTL and eviction belong to Redis tests. */
public class InMemoryConversationStore implements ConversationStore {
    private final Map<ConversationKey, List<ChatMessage>> sessions = new HashMap<>();

    /**
     * 读取持久化状态。
     * 下游调用 {@code messages.subList}。
     *
     * @param key 用于访问当前数据的存储键
     * @param limit 最多返回或保留的条数
     * @return 调用方使用的 {@code List<ChatMessage>}
     */
    @Override public synchronized List<ChatMessage> loadHistory(ConversationKey key, int limit) {
        if (key == null || limit <= 0) return List.of();
        List<ChatMessage> messages = sessions.getOrDefault(key, List.of());
        return List.copyOf(messages.subList(Math.max(0, messages.size() - limit), messages.size()));
    }

    /**
     * 把新消息追加到当前序列。
     *
     * @param key 用于访问当前数据的存储键
     * @param user 本次调用传入的 {@code ChatMessage}
     * @param assistant 本次调用传入的 {@code ChatMessage}
     */
    @Override public synchronized void append(ConversationKey key, ChatMessage user, ChatMessage assistant) {
        if (key == null) return;
        List<ChatMessage> messages = sessions.computeIfAbsent(key, ignored -> new ArrayList<>());
        if (user != null) messages.add(user);
        if (assistant != null) messages.add(assistant);
    }
}
