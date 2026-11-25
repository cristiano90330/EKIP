/* 委托会话记忆存储读取消息、读取摘要和更新摘要。 */

package com.pony.knowledge.agent.memory;

import com.pony.knowledge.agent.model.AgentMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class DefaultMemoryManager
        implements MemoryManager {

    private final MemoryStore memoryStore;

    /**
     * 将会话键与窗口大小交给记忆存储，返回最近的一段消息。
     * 下游调用 {@code memoryStore.loadRecentMessages}。
     *
     * @param sessionId 受当前用户与策略约束的服务端会话 ID
     * @param limit 最多返回或保留的条数
     * @return 按当前约定排列的模型消息列表
     */
    @Override
    public List<AgentMessage> getRecentMessages(
            String sessionId,
            int limit) {

        return memoryStore.loadRecentMessages(
                sessionId,
                limit
        );
    }

    /**
     * 委托记忆存储读取当前会话中的消息总量。
     * 下游调用 {@code memoryStore.countMessages}。
     *
     * @param sessionId 受当前用户与策略约束的服务端会话 ID
     * @return 按当前方法口径计算或读取的数值
     */
    @Override
    public int countMessages(
            String sessionId) {

        return memoryStore.countMessages(
                sessionId
        );
    }

    /**
     * 委托记忆存储读取当前会话中指定的消息范围。
     * 下游调用 {@code memoryStore.loadMessages}。
     *
     * @param sessionId 受当前用户与策略约束的服务端会话 ID
     * @param from 包含的起始位置
     * @param to 目标区间的结束位置
     * @return 按当前约定排列的模型消息列表
     */
    @Override
    public List<AgentMessage> getMessages(
            String sessionId,
            int from,
            int to) {

        return memoryStore.loadMessages(
                sessionId,
                from,
                to
        );
    }

    /**
     * 委托存储获取该会话最新摘要，允许空值。
     * 下游调用 {@code memoryStore.loadSummary}。
     *
     * @param sessionId 受当前用户与策略约束的服务端会话 ID
     * @return 调用方使用的 {@code Optional<MemoryItem>}
     */
    @Override
    public Optional<MemoryItem> getSummary(
            String sessionId) {

        return memoryStore.loadSummary(
                sessionId
        );
    }

    /**
     * 把已编码的摘要更新交给该会话的底层存储。
     * 下游调用 {@code memoryStore.saveSummary}。
     *
     * @param sessionId 受当前用户与策略约束的服务端会话 ID
     * @param summary 本次调用传入的 {@code MemoryItem}
     */
    @Override
    public void updateSummary(
            String sessionId,
            MemoryItem summary) {

        memoryStore.saveSummary(
                sessionId,
                summary
        );
    }
}
