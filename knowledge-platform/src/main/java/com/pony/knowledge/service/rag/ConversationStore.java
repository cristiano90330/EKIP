/* 约定传统对话历史存储的读取和追加边界。 */

package com.pony.knowledge.service.rag;

import com.pony.knowledge.service.rag.query.ChatMessage;

import java.util.List;

/** 会话存储契约：按服务端派生的用户/知识库/会话键隔离历史。 */
public interface ConversationStore {

    /**
     * 读取指定会话键在存储中保留的已有消息。
     * 上游为 ConversationService.getHistory。
     *
     * @param key 本次操作使用的 ConversationKey 组件或记录
     * @param limit 本次最多处理或返回的项数
     * @return 与过滤条件匹配的 List&lt;ChatMessage&gt; 数据集合
     */
    List<ChatMessage> loadHistory(ConversationKey key, int limit);

    /**
     * 将一轮问题和答案追加到会话历史并使其满足窗口约束。
     * 上游为 ConversationService.append。
     *
     * @param key 本次操作使用的 ConversationKey 组件或记录
     * @param user 账号数据
     * @param assistant 本轮需要持久化的助手消息
     */
    void append(ConversationKey key, ChatMessage user, ChatMessage assistant);
}
