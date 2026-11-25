/* 提供 Agent 消息与摘要读取接口，隔离运行时和具体存储实现。 */

package com.pony.knowledge.agent.memory;

import com.pony.knowledge.agent.model.AgentMessage;

import java.util.List;
import java.util.Optional;

/**
 * ContextBuilder 与 SummaryCoordinator 读取会话消息并更新摘要的入口。
 * 上层依赖本接口；运行时使用 Redis MemoryStore，单元测试可注入内存替身。
 */
public interface MemoryManager {

    /**
     * 为上下文或摘要构建读取有限的最近会话消息。
     * 上游由 {@code DefaultContextBuilder.loadWindow} 调用。
     *
     * @param sessionId 受当前用户与策略约束的服务端会话 ID
     * @param limit 最多返回或保留的条数
     * @return 按当前约定排列的模型消息列表
     */
    List<AgentMessage> getRecentMessages(
            String sessionId,
            int limit
    );

    /**
     * 返回能够恢复的会话消息总量。
     * 上游由 {@code DefaultContextBuilder.loadWindow}、{@code SummaryCoordinator.snapshot} 调用。
     *
     * @param sessionId 受当前用户与策略约束的服务端会话 ID
     * @return 按当前方法口径计算或读取的数值
     */
    int countMessages(
            String sessionId
    );

    /**
     * 读取指定会话及边界内的历史消息。
     * 上游由 {@code DefaultContextBuilder.loadWindow}、{@code SummaryCoordinator.estimateTokensWithEarlyExit}、{@code SummaryCoordinator.snapshot} 调用。
     *
     * @param sessionId 受当前用户与策略约束的服务端会话 ID
     * @param from 包含的起始位置
     * @param to 目标区间的结束位置
     * @return 按当前约定排列的模型消息列表
     */
    List<AgentMessage> getMessages(
            String sessionId,
            int from,
            int to
    );

    /**
     * 读取会话可用于上下文的当前摘要。
     * 上游由 {@code DefaultContextBuilder.loadSummary}、{@code SummaryCoordinator.loadSummary} 调用。
     *
     * @param sessionId 受当前用户与策略约束的服务端会话 ID
     * @return 调用方使用的 {@code Optional<MemoryItem>}
     */
    Optional<MemoryItem> getSummary(
            String sessionId
    );

    /**
     * 将新的增量摘要写回指定会话。
     * 上游由 {@code SummaryCoordinator.saveSummary} 调用。
     *
     * @param sessionId 受当前用户与策略约束的服务端会话 ID
     * @param summary 本次调用传入的 {@code MemoryItem}
     */
    void updateSummary(
            String sessionId,
            MemoryItem summary
    );
}
