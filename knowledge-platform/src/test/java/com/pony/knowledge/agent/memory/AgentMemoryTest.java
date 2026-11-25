/* 验证滑动消息窗口与不同会话间的消息和摘要隔离。 */

package com.pony.knowledge.agent.memory;

import com.pony.knowledge.agent.memory.store.InMemoryMemoryStore;
import com.pony.knowledge.agent.model.AgentMessage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 3.8a Memory 抽象验收：Session 隔离 / 最近 N 条(Sliding Window) / Summary 隔离。
 * 只用 InMemoryMemoryStore（3.8b 可无痛替换 RedisMemoryStore），测 MemoryManager→MemoryStore 契约。
 *
 * 架构确认（本测试隐含）：
 *   Session ≠ Memory ≠ AgentState —— memory 只依赖 sessionId(String) 键，不触碰 AgentExecutionContext/Auth。
 *   MemoryManager → MemoryStore 分级：manager 只做转发/装配，store 存具体会话数据。
 *   A / B 两个 session 绝对隔离：读 A 永不得到 B 的内容或 summary。
 */
class AgentMemoryTest {

    private final InMemoryMemoryStore history = new InMemoryMemoryStore();

    /** 在该类型内部准备后续运行所需的数据。 */
    private MemoryManager newManager() {
        return new DefaultMemoryManager(history);
    }

    /**
     * 协调 {@code Instant.now} 完成当前阶段，供后续处理使用。
     * @param content 待处理的正文内容
     * @return 指定角色和内容的模型消息
     */
    private AgentMessage userMsg(String content) {
        return AgentMessage.builder()
                .role("user")
                .content(content)
                .timestamp(Instant.now())
                .build();
    }

    /** 通过 {@code recent.get} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void test1_saveThenGetRecentMessage() {
        MemoryManager m = newManager();
        history.appendMessage("A", userMsg("hello"));

        List<AgentMessage> recent = m.getRecentMessages("A", 10);

        assertEquals(1, recent.size(), "存一条应能读回一条");
        assertEquals("hello", recent.get(0).getContent());
        assertNotNull(recent.get(0).getTimestamp(), "AgentMessage 应带 timestamp");
    }

    /** 通过 {@code history.appendMessage}、{@code newManager} 驱动该场景，实际结果与固定预期一致。 */
    @Test
    void test2_differentSessionsAreIsolated() {
        MemoryManager m = newManager();
        history.appendMessage("A", userMsg("hello"));
        history.appendMessage("B", userMsg("world"));

        List<AgentMessage> a = m.getRecentMessages("A", 10);

        assertEquals(List.of("hello"), a.stream().map(AgentMessage::getContent).toList(),
                "A 只能读到自己的 hello，不能读到 B 的 world");
    }

    /** 通过 {@code history.appendMessage}、{@code newManager} 驱动该场景，实际结果与固定预期一致。 */
    @Test
    void test3_recentLimitIsSlidingWindow() {
        MemoryManager m = newManager();
        for (int i = 1; i <= 5; i++) {
            history.appendMessage("A", userMsg(String.valueOf(i)));
        }

        List<AgentMessage> recent = m.getRecentMessages("A", 3);

        assertEquals(List.of("3", "4", "5"), recent.stream().map(AgentMessage::getContent).toList(),
                "limit=3 应取最近 3 条（为 Sliding Window 打基础），而非最早 3 条 1,2,3");
    }

    /** 通过 {@code m.updateSummary}、{@code m2.updateSummary} 驱动该场景，实际结果与固定预期一致。 */
    @Test
    void test4_summaryIsolatedPerSession() {
        MemoryManager m = newManager();
        m.updateSummary("A", MemoryItem.builder()
                .sessionId("A").type(MemoryType.SUMMARY).content("Summary A").build());
        m.updateSummary("B", MemoryItem.builder()
                .sessionId("B").type(MemoryType.SUMMARY).content("Summary B").build());

        MemoryItem a = m.getSummary("A").orElseThrow();

        assertEquals("Summary A", a.getContent(), "A 的 summary 不能被 B 串");
        assertEquals("A", a.getSessionId());
        // 同一 session 多次写 summary 覆盖（最新为准）
        MemoryManager m2 = newManager();
        m2.updateSummary("A", MemoryItem.builder().sessionId("A").content("v1").build());
        m2.updateSummary("A", MemoryItem.builder().sessionId("A").content("v2").build());
        assertSame("v2", m2.getSummary("A").orElseThrow().getContent());
    }
}
