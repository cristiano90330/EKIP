/* 验证问答上下文的历史加载、回合写入和异常处理。 */

package com.pony.knowledge.service.rag;

import com.pony.knowledge.service.rag.query.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationServiceTest {

    /**
     * 在该类型内部准备后续运行所需的数据。
     * @param window 允许读取的窗口范围和字符预算
     * @return 调用方使用的 {@code ConversationService}
     */
    private ConversationService svc(int window) {
        return new ConversationService(new InMemoryConversationStore(), window);
    }

    /**
     * 在该类型内部准备后续运行所需的数据。
     * @param conversationId 本次调用传入的 {@code String}
     * @return 调用方使用的 {@code ConversationKey}
     */
    private ConversationKey key(String conversationId) {
        return new ConversationKey(42L, 7L, conversationId);
    }

    /** 通过 {@code h.get} 驱动用例，核对 {@code h} 长度为 {@code 2}。 */
    @Test
    void 追加后按窗口截断取最近历史() {
        ConversationService svc = svc(2);
        svc.append(key("c1"), "q1", "a1");
        svc.append(key("c1"), "q2", "a2");
        List<ChatMessage> h = svc.getHistory(key("c1"));
        assertThat(h).hasSize(2);
        assertThat(h.get(0).getRole()).isEqualTo("user");
        assertThat(h.get(0).getContent()).isEqualTo("q2");
        assertThat(h.get(1).getContent()).isEqualTo("a2");
    }

    /** 通过 {@code svc.append}、{@code svc} 驱动该场景，返回的数据、边界或顺序符合断言。 */
    @Test
    void 相同句柄按用户和知识库分别存储() {
        ConversationService svc = svc(6);
        svc.append(new ConversationKey(42L, 7L, "same"), "q", "a");

        assertThat(svc.getHistory(new ConversationKey(43L, 7L, "same"))).isEmpty();
        assertThat(svc.getHistory(new ConversationKey(42L, 8L, "same"))).isEmpty();
    }
}
