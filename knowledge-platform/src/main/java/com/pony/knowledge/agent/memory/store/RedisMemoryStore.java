/* 把 Agent 记忆查询和摘要写入转发到服务端会话存储，并校验会话标识。 */

package com.pony.knowledge.agent.memory.store;

import com.pony.knowledge.agent.memory.MemoryItem;
import com.pony.knowledge.agent.memory.MemoryStore;
import com.pony.knowledge.agent.model.AgentMessage;
import com.pony.knowledge.agent.session.AgentSession;
import com.pony.knowledge.agent.session.AgentSessionStore;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** Short-term memory uses the same Redis-backed store as the product session. */
@Component
public class RedisMemoryStore implements MemoryStore {
    private final AgentSessionStore sessions;

    /**
     * 用注入依赖和构造参数建立此实例执行时使用的状态。
     *
     * @param sessions 本次调用传入的 {@code AgentSessionStore}
     */
    public RedisMemoryStore(AgentSessionStore sessions) {
        this.sessions = sessions;
    }

    /**
     * 校验服务端记忆键后，按会话消息数量定位最近窗口并读取消息。
     * 下游调用 {@code sessions.countMessages}、{@code sessions.messages}、{@code publicId}。
     *
     * @param sessionId 受当前用户与策略约束的服务端会话 ID
     * @param limit 最多返回或保留的条数
     * @return 按当前约定排列的模型消息列表
     */
    @Override
    public List<AgentMessage> loadRecentMessages(String sessionId, int limit) {
        String id = publicId(sessionId);
        int count = sessions.countMessages(id);
        return sessions.messages(id, Math.max(0, count - limit), count);
    }

    /**
     * 将服务端记忆键还原为产品会话标识后读取消息计数。
     * 下游调用 {@code sessions.countMessages}、{@code publicId}。
     *
     * @param sessionId 受当前用户与策略约束的服务端会话 ID
     * @return 按当前方法口径计算或读取的数值
     */
    @Override
    public int countMessages(String sessionId) {
        return sessions.countMessages(publicId(sessionId));
    }

    /**
     * 在产品会话存储中按区间读取本会话的历史消息。
     * 下游调用 {@code sessions.messages}、{@code publicId}。
     *
     * @param sessionId 受当前用户与策略约束的服务端会话 ID
     * @param from 包含的起始位置
     * @param to 目标区间的结束位置
     * @return 按当前约定排列的模型消息列表
     */
    @Override
    public List<AgentMessage> loadMessages(String sessionId, int from, int to) {
        return sessions.messages(publicId(sessionId), from, to);
    }

    /**
     * 从产品会话存储读取该会话最新的摘要记忆项。
     * 下游调用 {@code sessions.summary}、{@code publicId}。
     *
     * @param sessionId 受当前用户与策略约束的服务端会话 ID
     * @return 调用方使用的 {@code Optional<MemoryItem>}
     */
    @Override
    public Optional<MemoryItem> loadSummary(String sessionId) {
        return sessions.summary(publicId(sessionId));
    }

    /**
     * 将新摘要写入产品会话存储，沿用该会话生命周期约束。
     * 下游调用 {@code sessions.saveSummary}、{@code publicId}。
     *
     * @param sessionId 受当前用户与策略约束的服务端会话 ID
     * @param summary 本次调用传入的 {@code MemoryItem}
     */
    @Override
    public void saveSummary(String sessionId, MemoryItem summary) {
        sessions.saveSummary(publicId(sessionId), summary);
    }

    /**
     * 校验传入记忆键采用服务端产品会话格式，并提取存储所用的会话标识。
     * 上游由 {@code RedisMemoryStore.countMessages}、{@code RedisMemoryStore.loadMessages}、{@code RedisMemoryStore.loadRecentMessages} 调用。
     * 下游调用 {@code AgentSession.MEMORY_PREFIX.length}。
     *
     * @param memoryId 本次调用传入的 {@code String}
     * @return 供后续处理使用的文本或标识
     */
    private String publicId(String memoryId) {
        if (memoryId == null || !memoryId.startsWith(AgentSession.MEMORY_PREFIX)) {
            throw new IllegalArgumentException("短期记忆要求产品会话键");
        }
        return memoryId.substring(AgentSession.MEMORY_PREFIX.length());
    }
}
