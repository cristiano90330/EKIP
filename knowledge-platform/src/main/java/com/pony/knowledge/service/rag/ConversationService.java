/* 为传统问答加载并截取对话历史，追加可保留的问答。 */

package com.pony.knowledge.service.rag;

import com.pony.knowledge.service.rag.query.ChatMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 会话问答的历史业务入口，由 RagService 在完成知识库授权后调用。
 * 按完整 ConversationKey 读取最近历史，追加时将一轮问答转为 user 与 assistant 两条消息。
 * 是否追加由 RagService 根据问答结果决定，本类不自行把依赖故障转换成空历史。
 */
@Service
public class ConversationService {

    private final ConversationStore store;

    private final int historyWindow;

    /**
     * 保存为传统问答加载并截取对话历史所需的依赖和初始配置。
     * 上游为 Spring 组件装配。
     *
     * @param store ConversationStore，负责约定传统对话历史存储的读取和追加边界
     * @param historyWindow 初始化或更新同名字段的值
     */
    public ConversationService(ConversationStore store,
                               @Value("${rag.history-window:6}") int historyWindow) {
        this.store = store;
        this.historyWindow = historyWindow;
    }

    /**
     * 按用户、知识库和会话范围加载保留窗口里的历史。
     * 上游为 RagService.chat。
     * 下游为 ConversationStore.loadHistory。
     *
     * @param key 本次操作使用的 ConversationKey 组件或记录
     * @return 与过滤条件匹配的 List&lt;ChatMessage&gt; 数据集合
     */
    public List<ChatMessage> getHistory(ConversationKey key) {
        return store.loadHistory(key, historyWindow);
    }

    /**
     * 追加一轮可保存的用户问题和助手答案。
     * 上游为 RagService.chat。
     * 下游为 ConversationStore.append。
     *
     * @param key 本次操作使用的 ConversationKey 组件或记录
     * @param question 用户问题正文
     * @param answer 生成的答案
     */
    public void append(ConversationKey key, String question, String answer) {
        ChatMessage user = new ChatMessage();
        user.setRole("user");
        user.setContent(question);
        ChatMessage assistant = new ChatMessage();
        assistant.setRole("assistant");
        assistant.setContent(answer);
        store.append(key, user, assistant);
    }
}
