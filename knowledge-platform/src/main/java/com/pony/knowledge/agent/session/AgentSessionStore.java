/* 定义 Agent 会话元数据、回合槽、消息和摘要的存储契约。 */

package com.pony.knowledge.agent.session;

import com.pony.knowledge.agent.memory.MemoryItem;
import com.pony.knowledge.agent.model.AgentMessage;
import java.util.List;
import java.util.Optional;

/** 产品会话、模型消息与摘要具有同一个生命周期；存储失败直接上抛。 */
public interface AgentSessionStore {
    /**
     * 持久化新会话的元数据和目录索引，供后续按用户和会话 ID 查找。
     * 上游由 {@code AgentSessionService.create} 调用。
     * 下游调用 {@code RedisAgentSessionStore.create}。
     *
     * @param session 会话元数据及归属信息
     */
    void create(AgentSession session);

    /**
     * 按服务端会话 ID 获取元数据，失效的会话返回空结果。
     * 上游由 {@code AgentSessionService.owned} 调用。
     *
     * @param id 服务端签发的产品会话 ID
     * @return 调用方使用的 {@code Optional<AgentSession>}
     */
    Optional<AgentSession> get(String id);

    /**
     * 从所属用户的目录读取一页会话元数据。
     * 上游由 {@code AgentSessionService.list} 调用。
     *
     * @param userId 当前用户 ID
     * @param offset 从结果边界起算的分页偏移
     * @param limit 最多返回或保留的条数
     * @return 调用方使用的 {@code List<AgentSession>}
     */
    List<AgentSession> list(Long userId, int offset, int limit);

    /**
     * 为仍有效且空闲的会话占用回合槽，保存调用方给出的独立令牌。
     * 上游由 {@code AgentSessionService.begin} 调用。
     * 下游调用 {@code RedisAgentSessionStore.beginTurn}。
     *
     * @param id 服务端签发的产品会话 ID
     * @param token 当前操作的令牌
     * @return 条件成立时为 true，其他状态按 false 处理
     */
    boolean beginTurn(String id, String token);

    /**
     * 用持有者令牌释放本次回合槽。
     * 上游由 {@code AgentSessionService.end} 调用。
     *
     * @param id 服务端签发的产品会话 ID
     * @param token 当前操作的令牌
     */
    void endTurn(String id, String token);

    /**
     * 按本轮占用令牌保存终态、两条交互消息和更新后的会话元数据。
     * 上游由 {@code AgentSessionService.complete} 调用。
     * 下游调用 {@code RedisAgentSessionStore.completeTurn}。
     *
     * @param session 会话元数据及归属信息
     * @param token 当前操作的令牌
     * @param turn 已完成回合及其交付摘要
     * @param messages 按照交互顺序提供的消息列表
     */
    void completeTurn(AgentSession session, String token, AgentSessionTurn turn, List<AgentMessage> messages);

    /**
     * 按会话 ID 和用户归属删除元数据、回合、消息及摘要；活跃回合阻止删除。
     * 上游由 {@code AgentSessionService.delete} 调用。
     *
     * @param id 服务端签发的产品会话 ID
     * @param userId 当前用户 ID
     * @return 条件成立时为 true，其他状态按 false 处理
     */
    boolean delete(String id, Long userId);

    /**
     * 按记录顺序读取指定会话的历史回合区间。
     * 上游由 {@code AgentSessionService.history} 调用。
     *
     * @param id 服务端签发的产品会话 ID
     * @param offset 从结果边界起算的分页偏移
     * @param limit 最多返回或保留的条数
     * @return 调用方使用的 {@code List<AgentSessionTurn>}
     */
    List<AgentSessionTurn> history(String id, int offset, int limit);

    /**
     * 返回当前会话保留的完整回合数量。
     * 上游由 {@code AgentSessionService.history} 调用。
     *
     * @param id 服务端签发的产品会话 ID
     * @return 按当前方法口径计算或读取的数值
     */
    int countTurns(String id);

    /**
     * 读取上下文或摘要需要的历史消息区间，采用左闭右开下标。
     * 上游由 {@code RedisMemoryStore.loadMessages}、{@code RedisMemoryStore.loadRecentMessages} 调用。
     *
     * @param id 服务端签发的产品会话 ID
     * @param from 包含的起始位置
     * @param to 目标区间的结束位置
     * @return 按当前约定排列的模型消息列表
     */
    List<AgentMessage> messages(String id, int from, int to);

    /**
     * 返回当前会话可用于上下文构建的消息条数。
     * 上游由 {@code RedisMemoryStore.countMessages}、{@code RedisMemoryStore.loadRecentMessages} 调用。
     *
     * @param id 服务端签发的产品会话 ID
     * @return 按当前方法口径计算或读取的数值
     */
    int countMessages(String id);

    /**
     * 读取当前会话的摘要及其折叠位置。
     * 上游由 {@code RedisMemoryStore.loadSummary} 调用。
     *
     * @param id 服务端签发的产品会话 ID
     * @return 调用方使用的 {@code Optional<MemoryItem>}
     */
    Optional<MemoryItem> summary(String id);

    /**
     * 把归属于当前会话的摘要写回存储，由实现保护已删除或到期会话。
     * 上游由 {@code RedisMemoryStore.saveSummary} 调用。
     *
     * @param id 服务端签发的产品会话 ID
     * @param summary 本次调用传入的 {@code MemoryItem}
     */
    void saveSummary(String id, MemoryItem summary);
}
