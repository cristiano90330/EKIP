/* 验证上下文加载按摘要游标读取历史并处理边界和缺省情况。 */

package com.pony.knowledge.agent.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pony.knowledge.agent.memory.DefaultMemoryManager;
import com.pony.knowledge.agent.memory.MemoryItem;
import com.pony.knowledge.agent.memory.MemoryType;
import com.pony.knowledge.agent.memory.store.InMemoryMemoryStore;
import com.pony.knowledge.agent.memory.summary.ConversationSummary;
import com.pony.knowledge.agent.memory.summary.SummaryCodec;
import com.pony.knowledge.agent.model.AgentMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 压缩点作为跨轮加载边界（P3-2）。
 *
 * <p>验收的不是「能不能加载」，而是**不重复计量**：摘要已覆盖的消息不得再以原文出现。
 * 改造前窗口固定取「最后 N 条」，与摘要的游标无关，同一段内容会同时以两种形式进上下文。
 */
class CompactionPointLoadTest {

    private final InMemoryMemoryStore history = new InMemoryMemoryStore();
    private final DefaultMemoryManager mem = new DefaultMemoryManager(history);

    private final ObjectMapper om = new ObjectMapper();

    private final DefaultContextBuilder builder = new DefaultContextBuilder(mem, om);

    /**
     * 通过 {@code history.appendMessage} 把新消息追加到当前序列。
     * @param session 会话元数据及归属信息
     * @param contents 本次调用传入的 {@code String[]}
     */
    private void append(String session, String... contents) {
        for (String c : contents) {
            history.appendMessage(session, AgentMessage.builder().role("user").content(c).build());
        }
    }

    /**
     * 通过 {@code mem.updateSummary} 保存当前计算结果供下一次读取。
     * 下游调用 {@code mem.updateSummary}、{@code SummaryCodec.encode}。
     *
     * @param session 会话元数据及归属信息
     * @param text 待处理正文
     * @param cursor 本次调用传入的 {@code int}
     * @param degraded 本次调用传入的 {@code boolean}
     */
    private void saveSummary(String session, String text, int cursor, boolean degraded) {
        mem.updateSummary(session, MemoryItem.builder()
                .sessionId(session).type(MemoryType.SUMMARY)
                .content(SummaryCodec.encode(ConversationSummary.builder()
                        .summary(text).summarizedMessageCount(cursor).degraded(degraded).build(), om))
                .build());
    }

    /**
     * 装配后续操作需要的输入或结果。
     * @param session 会话元数据及归属信息
     * @param window 允许读取的窗口范围和字符预算
     * @return 模型消息及其用量和裁减诊断
     */
    private ContextBuildResult build(String session, int window) {
        return builder.build(ContextBuildRequest.builder()
                .systemPrompt("SYS").sessionId(session).currentQuery("Q")
                .recentWindow(window)
                .budget(ContextBudget.builder().maxTokens(100_000).build())
                .build());
    }

    /**
     * 在该类型内部准备后续运行所需的数据。
     * @param r 本次调用传入的 {@code ContextBuildResult}
     * @return 调用方使用的 {@code List<String>}
     */
    private List<String> contentsOf(ContextBuildResult r) {
        return r.getMessages().stream().map(AgentMessage::getContent).toList();
    }

    /** 保存带折叠位置的摘要，检查上下文从该位置之后读取消息。 */
    @Test
    void loadsOnlyMessagesAfterCompactionPoint() {
        append("A", "第一条", "第二条", "第三条", "第四条", "第五条");
        saveSummary("A", "前两条的要点", 2, false);

        List<String> contents = contentsOf(build("A", 10));

        assertFalse(contents.contains("第一条"), "已被摘要覆盖的消息不得再以原文出现");
        assertFalse(contents.contains("第二条"));
        assertTrue(contents.contains("第三条") && contents.contains("第五条"));
        assertTrue(contents.stream().anyMatch(c -> c != null && c.contains("前两条的要点")), "摘要仍要注入");
    }

    /** 通过 {@code append}、{@code saveSummary} 驱动该场景，布尔状态符合断言。 */
    @Test
    void windowSmallerThanRemainder_staysAfterCursor() {
        append("A", "第一条", "第二条", "第三条", "第四条", "第五条");
        saveSummary("A", "要点", 2, false);

        List<String> contents = contentsOf(build("A", 2));

        assertFalse(contents.contains("第二条"), "游标之前一律不加载");
        assertTrue(contents.contains("第四条") && contents.contains("第五条"));
    }

    /** 通过 {@code append}、{@code saveSummary} 驱动该场景，布尔状态符合断言，实际结果与固定预期一致。 */
    @Test
    void cursorAtEnd_loadsNoHistory() {
        append("A", "第一条", "第二条");
        saveSummary("A", "全部要点", 2, false);

        ContextBuildResult r = build("A", 10);

        assertEquals(1, r.getMessages().stream()
                .filter(m -> "system".equals(m.getRole()) && m.getContent().contains("全部要点")).count());
        assertFalse(contentsOf(r).contains("第一条"));
        assertFalse(contentsOf(r).contains("第二条"));
    }

    /** 让摘要游标越过消息总量，验证窗口恢复到存储中可读取的最近消息。 */
    @Test
    void cursorBeyondCount_fallsBackToRecentWindow() {
        append("A", "第一条", "第二条");
        saveSummary("A", "要点", 99, false);

        List<String> contents = contentsOf(build("A", 10));

        assertTrue(contents.contains("第一条") && contents.contains("第二条"), "越界时必须退回最近窗口");
    }

    /** 通过 {@code append}、{@code contentsOf} 驱动该场景，布尔状态符合断言。 */
    @Test
    void noSummary_keepsOriginalPath() {
        append("A", "第一条", "第二条");

        List<String> contents = contentsOf(build("A", 10));

        assertTrue(contents.contains("第一条") && contents.contains("第二条"));
    }
}
