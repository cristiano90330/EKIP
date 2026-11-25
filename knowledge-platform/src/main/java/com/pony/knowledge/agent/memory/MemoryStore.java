/* 定义会话消息窗口和摘要的底层存取方式。 */

package com.pony.knowledge.agent.memory;

import com.pony.knowledge.agent.model.AgentMessage;

import java.util.List;
import java.util.Optional;

/**
 * 按产品会话读取消息和更新摘要。回合消息由 AgentSessionStore 原子提交。
 *
 * countMessages / loadMessages 是免全量加载的读取：摘要只需要条数与 [cursor, upTo) 那一段，
 * Redis 侧对应 LLEN 与 LRANGE。
 */
public interface MemoryStore {

    /**
     * 从指定会话的存储结构读取最近消息窗口。
     * 上游由 {@code DefaultMemoryManager.getRecentMessages} 调用。
     *
     * @param sessionId 受当前用户与策略约束的服务端会话 ID
     * @param limit 最多返回或保留的条数
     * @return 按当前约定排列的模型消息列表
     */
    List<AgentMessage> loadRecentMessages(
            String sessionId,
            int limit
    );

    /**
     * 按会话键统计已保存的历史消息条数。
     * 上游由 {@code DefaultMemoryManager.countMessages} 调用。
     *
     * @param sessionId 受当前用户与策略约束的服务端会话 ID
     * @return 按当前方法口径计算或读取的数值
     */
    int countMessages(
            String sessionId
    );

    /**
     * 从指定会话的历史中按给定起讫边界读取消息。
     * 上游由 {@code DefaultMemoryManager.getMessages} 调用。
     *
     * @param sessionId 受当前用户与策略约束的服务端会话 ID
     * @param from 包含的起始位置
     * @param to 目标区间的结束位置
     * @return 按当前约定排列的模型消息列表
     */
    List<AgentMessage> loadMessages(
            String sessionId,
            int from,
            int to
    );

    /**
     * 读取该会话已保存的摘要记忆项，缺失时返回空值。
     * 上游由 {@code DefaultMemoryManager.getSummary} 调用。
     *
     * @param sessionId 受当前用户与策略约束的服务端会话 ID
     * @return 调用方使用的 {@code Optional<MemoryItem>}
     */
    Optional<MemoryItem> loadSummary(
            String sessionId
    );

    /**
     * 持久化与指定会话关联的最新摘要记忆项。
     * 上游由 {@code DefaultMemoryManager.updateSummary} 调用。
     *
     * @param sessionId 受当前用户与策略约束的服务端会话 ID
     * @param summary 本次调用传入的 {@code MemoryItem}
     */
    void saveSummary(
            String sessionId,
            MemoryItem summary
    );
}
