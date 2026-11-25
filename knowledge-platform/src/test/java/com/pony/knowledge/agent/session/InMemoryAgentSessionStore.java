/* 为 Agent 会话测试提供带过期与互斥控制的内存存储替身。 */

package com.pony.knowledge.agent.session;

import com.pony.knowledge.agent.memory.MemoryItem;
import com.pony.knowledge.agent.model.AgentMessage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Test fixture for session business logic; never registered as a runtime Spring bean. */
public class InMemoryAgentSessionStore implements AgentSessionStore {
    private static final class State {
        AgentSession session;
        String active;
        final List<AgentSessionTurn> turns = new ArrayList<>();
        final List<AgentMessage> messages = new ArrayList<>();
        MemoryItem summary;

        /**
         * 用注入依赖和构造参数建立此实例执行时使用的状态。
         *
         * @param session 会话元数据及归属信息
         */
        State(AgentSession session) { this.session = session; }
    }
    private final Map<String, State> states = new HashMap<>();
    private final Map<Long, Map<String, Long>> userIndex = new HashMap<>();

    /**
     * 创建当前入口需要的对象或持久化记录。
     * 下游调用 {@code states.containsKey}、{@code session.id}、{@code index}。
     *
     * @param session 会话元数据及归属信息
     */
    @Override public synchronized void create(AgentSession session) {
        if (states.containsKey(session.id())) throw new IllegalStateException("会话 ID 冲突");
        states.put(session.id(), new State(session));
        index(session);
    }

    /**
     * 委托 {@code Optional.ofNullable} 读取调用方使用的记录或状态。
     * @param id 服务端签发的产品会话 ID
     * @return 调用方使用的 {@code Optional<AgentSession>}
     */
    @Override public synchronized Optional<AgentSession> get(String id) {
        State state = live(id);
        return Optional.ofNullable(state == null ? null : state.session);
    }

    /**
     * 按范围读取匹配项。
     * 下游调用 {@code Comparator.comparingLong}、{@code live}、{@code AgentSession::updatedAt}。
     *
     * @param userId 当前用户 ID
     * @param offset 从结果边界起算的分页偏移
     * @param limit 最多返回或保留的条数
     * @return 调用方使用的 {@code List<AgentSession>}
     */
    @Override public synchronized List<AgentSession> list(Long userId, int offset, int limit) {
        // 仅遍历当前用户索引，不扫描其他用户会话。
        List<String> ids = new ArrayList<>(userIndex.getOrDefault(userId, Map.of()).keySet());
        List<AgentSession> sessions = new ArrayList<>();
        for (String id : ids) { State state = live(id); if (state != null) sessions.add(state.session); }
        return sessions.stream().sorted(Comparator.comparingLong(AgentSession::updatedAt).reversed())
                .skip(offset).limit(limit).toList();
    }

    /**
     * 协调 {@code live} 完成当前阶段，供后续处理使用。
     * 下游调用 {@code live}。
     *
     * @param id 服务端签发的产品会话 ID
     * @param token 当前操作的令牌
     * @return 条件成立时为 true，其他状态按 false 处理
     */
    @Override public synchronized boolean beginTurn(String id, String token) {
        State state = live(id);
        if (state == null || state.active != null) return false;
        state.active = token;
        return true;
    }

    /**
     * 协调 {@code live} 完成当前阶段，供后续处理使用。
     * @param id 服务端签发的产品会话 ID
     * @param token 当前操作的令牌
     */
    @Override public synchronized void endTurn(String id, String token) {
        State state = live(id);
        if (state != null && token.equals(state.active)) state.active = null;
    }

    /**
     * 协调 {@code session.id} 完成当前阶段，供后续处理使用。
     * 下游调用 {@code session.id}、{@code state.messages.addAll}、{@code live}。
     *
     * @param session 会话元数据及归属信息
     * @param token 当前操作的令牌
     * @param turn 已完成回合及其交付摘要
     * @param messages 按照交互顺序提供的消息列表
     */
    @Override public synchronized void completeTurn(AgentSession session, String token,
                                                   AgentSessionTurn turn, List<AgentMessage> messages) {
        State state = live(session.id());
        if (state == null || !token.equals(state.active)) throw new IllegalStateException("会话已失效");
        state.turns.add(turn);
        state.messages.addAll(messages);
        state.session = session;
        index(session);
    }

    /**
     * 移除指定范围内的目标记录。
     * 下游调用 {@code state.session.userId}、{@code live}。
     *
     * @param id 服务端签发的产品会话 ID
     * @param userId 当前用户 ID
     * @return 条件成立时为 true，其他状态按 false 处理
     */
    @Override public synchronized boolean delete(String id, Long userId) {
        State state = live(id);
        if (state == null || !userId.equals(state.session.userId())) return true;
        if (state.active != null) return false;
        states.remove(id);
        userIndex.getOrDefault(userId, new HashMap<>()).remove(id);
        return true;
    }

    /**
     * 协调 {@code live} 完成当前阶段，供后续处理使用。
     * 下游调用 {@code live}、{@code slice}。
     *
     * @param id 服务端签发的产品会话 ID
     * @param offset 从结果边界起算的分页偏移
     * @param limit 最多返回或保留的条数
     * @return 调用方使用的 {@code List<AgentSessionTurn>}
     */
    @Override public synchronized List<AgentSessionTurn> history(String id, int offset, int limit) {
        State state = live(id);
        return state == null ? List.of() : slice(state.turns, offset, offset + limit);
    }

    /**
     * 通过 {@code live} 记录或统计符合当前条件的数量。
     * @param id 服务端签发的产品会话 ID
     * @return 按当前方法口径计算或读取的数值
     */
    @Override public synchronized int countTurns(String id) {
        State state = live(id); return state == null ? 0 : state.turns.size();
    }

    /**
     * 协调 {@code live} 完成当前阶段，供后续处理使用。
     * 下游调用 {@code live}、{@code slice}。
     *
     * @param id 服务端签发的产品会话 ID
     * @param from 包含的起始位置
     * @param to 目标区间的结束位置
     * @return 按当前约定排列的模型消息列表
     */
    @Override public synchronized List<AgentMessage> messages(String id, int from, int to) {
        State state = live(id);
        return state == null ? List.of() : slice(state.messages, from, to);
    }

    /**
     * 通过 {@code live} 记录或统计符合当前条件的数量。
     * @param id 服务端签发的产品会话 ID
     * @return 按当前方法口径计算或读取的数值
     */
    @Override public synchronized int countMessages(String id) {
        State state = live(id); return state == null ? 0 : state.messages.size();
    }

    /**
     * 协调 {@code Optional.ofNullable} 完成当前阶段，供后续处理使用。
     * @param id 服务端签发的产品会话 ID
     * @return 调用方使用的 {@code Optional<MemoryItem>}
     */
    @Override public synchronized Optional<MemoryItem> summary(String id) {
        State state = live(id); return Optional.ofNullable(state == null ? null : state.summary);
    }

    /**
     * 通过 {@code live} 保存当前计算结果供下一次读取。
     * @param id 服务端签发的产品会话 ID
     * @param summary 本次调用传入的 {@code MemoryItem}
     */
    @Override public synchronized void saveSummary(String id, MemoryItem summary) {
        State state = live(id);
        if (state != null) state.summary = summary;
    }

    /**
     * 协调 {@code state.session.expiresAt} 完成当前阶段，供后续处理使用。
     * 下游调用 {@code state.session.expiresAt}、{@code state.session.userId}。
     *
     * @param id 服务端签发的产品会话 ID
     * @return 调用方使用的 {@code State}
     */
    private State live(String id) {
        State state = states.get(id);
        if (state != null && state.session.expiresAt() <= System.currentTimeMillis()) {
            states.remove(id);
            userIndex.getOrDefault(state.session.userId(), new HashMap<>()).remove(id);
            return null;
        }
        return state;
    }

    /**
     * 协调 {@code session.userId} 完成当前阶段，供后续处理使用。
     * @param session 会话元数据及归属信息
     */
    private void index(AgentSession session) {
        userIndex.computeIfAbsent(session.userId(), ignored -> new HashMap<>()).put(session.id(), session.updatedAt());
    }

    /**
     * 协调 {@code data.subList} 完成当前阶段，供后续处理使用。
     * 下游调用 {@code data.subList}。
     *
     * @param data 本次调用传入的 {@code List<T>}
     * @param from 包含的起始位置
     * @param to 目标区间的结束位置
     * @return 调用方使用的 {@code List<T>}
     */
    private static <T> List<T> slice(List<T> data, int from, int to) {
        int start = Math.max(0, Math.min(from, data.size()));
        return List.copyOf(data.subList(start, Math.max(start, Math.min(to, data.size()))));
    }
}
