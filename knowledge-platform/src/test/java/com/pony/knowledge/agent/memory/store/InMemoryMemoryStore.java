/* 在进程内模拟会话消息和摘要存储，供隔离测试使用。 */

package com.pony.knowledge.agent.memory.store;

import com.pony.knowledge.agent.memory.MemoryItem;
import com.pony.knowledge.agent.memory.MemoryStore;
import com.pony.knowledge.agent.model.AgentMessage;
import com.pony.knowledge.agent.session.AgentSession;
import com.pony.knowledge.agent.session.AgentSessionStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Isolated test fixture for memory business rules. Production always uses RedisMemoryStore.
 */
public class InMemoryMemoryStore
        implements MemoryStore {

    private final AgentSessionStore sessions;

    /** 保留私有构造入口，以便通过静态方法使用此类型。 */
    public InMemoryMemoryStore() { this.sessions = null; }

    /**
     * 用注入依赖和构造参数建立此实例执行时使用的状态。
     *
     * @param sessions 本次调用传入的 {@code AgentSessionStore}
     */
    public InMemoryMemoryStore(AgentSessionStore sessions) { this.sessions = sessions; }

    private final Map<String, List<AgentMessage>>
            messages = new ConcurrentHashMap<>();

    private final Map<String, MemoryItem>
            summaries = new ConcurrentHashMap<>();

    /**
     * 读取持久化状态。
     * 下游调用 {@code sessions.countMessages}、{@code sessions.messages}、{@code history.subList}。
     *
     * @param sessionId 受当前用户与策略约束的服务端会话 ID
     * @param limit 最多返回或保留的条数
     * @return 按当前约定排列的模型消息列表
     */
    @Override
    public List<AgentMessage> loadRecentMessages(
            String sessionId,
            int limit) {

        if (managed(sessionId)) {
            int count = sessions.countMessages(publicId(sessionId));
            return sessions.messages(publicId(sessionId), Math.max(0, count - limit), count);
        }

        List<AgentMessage> history =
                messages.getOrDefault(
                        sessionId,
                        List.of()
                );

        int fromIndex =
                Math.max(
                        0,
                        history.size() - limit
                );

        return new ArrayList<>(
                history.subList(
                        fromIndex,
                        history.size()
                )
        );
    }

    /**
     * 通过 {@code sessions.countMessages} 记录或统计符合当前条件的数量。
     * @param sessionId 受当前用户与策略约束的服务端会话 ID
     * @return 按当前方法口径计算或读取的数值
     */
    @Override
    public int countMessages(String sessionId) {
        if (managed(sessionId)) return sessions.countMessages(publicId(sessionId));
        return messages.getOrDefault(sessionId, List.of()).size();
    }

    /**
     * 读取持久化状态。
     * 下游调用 {@code sessions.messages}、{@code history.subList}、{@code managed}。
     *
     * @param sessionId 受当前用户与策略约束的服务端会话 ID
     * @param from 包含的起始位置
     * @param to 目标区间的结束位置
     * @return 按当前约定排列的模型消息列表
     */
    @Override
    public List<AgentMessage> loadMessages(String sessionId, int from, int to) {
        if (managed(sessionId)) return sessions.messages(publicId(sessionId), from, to);
        List<AgentMessage> history = messages.getOrDefault(sessionId, List.of());
        int start = Math.max(0, Math.min(from, history.size()));
        int end = Math.max(start, Math.min(to, history.size()));
        return new ArrayList<>(history.subList(start, end));
    }

    /**
     * 通过 {@code managed} 把新消息追加到当前序列。
     * @param sessionId 受当前用户与策略约束的服务端会话 ID
     * @param message 待处理的消息或对外提示
     */
    public void appendMessage(
            String sessionId,
            AgentMessage message) {

        if (managed(sessionId)) throw new IllegalStateException("产品会话消息必须与完成回合原子保存");

        messages
                .computeIfAbsent(
                        sessionId,
                        key -> new CopyOnWriteArrayList<>()
                )
                .add(message);
    }

    /**
     * 通过 {@code sessions.summary} 读取持久化状态。
     * @param sessionId 受当前用户与策略约束的服务端会话 ID
     * @return 调用方使用的 {@code Optional<MemoryItem>}
     */
    @Override
    public Optional<MemoryItem> loadSummary(
            String sessionId) {

        if (managed(sessionId)) return sessions.summary(publicId(sessionId));

        return Optional.ofNullable(
                summaries.get(sessionId)
        );
    }

    /**
     * 通过 {@code sessions.saveSummary} 保存当前计算结果供下一次读取。
     * @param sessionId 受当前用户与策略约束的服务端会话 ID
     * @param summary 本次调用传入的 {@code MemoryItem}
     */
    @Override
    public void saveSummary(
            String sessionId,
            MemoryItem summary) {

        if (managed(sessionId)) {
            sessions.saveSummary(publicId(sessionId), summary);
            return;
        }

        summaries.put(
                sessionId,
                summary
        );
    }

    /**
     * 根据当前输入和已有状态返回是否满足条件。
     * @param sessionId 受当前用户与策略约束的服务端会话 ID
     * @return 条件成立时为 true，其他状态按 false 处理
     */
    private boolean managed(String sessionId) {
        return sessions != null && sessionId.startsWith(AgentSession.MEMORY_PREFIX);
    }

    /**
     * 协调 {@code AgentSession.MEMORY_PREFIX.length} 完成当前阶段，供后续处理使用。
     * @param sessionId 受当前用户与策略约束的服务端会话 ID
     * @return 供后续处理使用的文本或标识
     */
    private String publicId(String sessionId) { return sessionId.substring(AgentSession.MEMORY_PREFIX.length()); }
}
