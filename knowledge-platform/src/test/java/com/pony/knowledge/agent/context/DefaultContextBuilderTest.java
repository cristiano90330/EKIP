/* 验证 Agent 消息装配的顺序、必要字段、权限与工具结果边界。 */

package com.pony.knowledge.agent.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pony.knowledge.agent.memory.DefaultMemoryManager;
import com.pony.knowledge.agent.memory.MemoryItem;
import com.pony.knowledge.agent.memory.MemoryManager;
import com.pony.knowledge.agent.memory.MemoryType;
import com.pony.knowledge.agent.memory.longterm.InMemoryLongTermMemoryStore;
import com.pony.knowledge.agent.memory.longterm.LongTermMemoryService;
import com.pony.knowledge.agent.memory.store.InMemoryMemoryStore;
import com.pony.knowledge.agent.memory.summary.ConversationSummary;
import com.pony.knowledge.agent.memory.summary.SummaryCodec;
import com.pony.knowledge.agent.model.AgentMessage;
import com.pony.knowledge.agent.model.ToolStep;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 3.8d ContextBuilder 验收。用 InMemoryMemoryStore（无 Redis）做 MemoryManager → 证明不依赖 Redis；
 * 工具结果 / 当前问题由调用方传入 → 证明不执行 Tool / RAG。
 */
class DefaultContextBuilderTest {

    private final InMemoryMemoryStore history = new InMemoryMemoryStore();
    private final MemoryManager mem = new DefaultMemoryManager(history);
    private final ObjectMapper om = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final DefaultContextBuilder builder = new DefaultContextBuilder(mem, om);

    /**
     * 提供本轮模型消息可占用的令牌预算。
     *
     * @param maxTokens 允许使用的最大令牌数
     * @param out 本次调用传入的 {@code int}
     * @param tool 注册表中的工具实例
     * @return 本轮模型消息可占用的令牌预算
     */
    private ContextBudget budget(int maxTokens, int out, int tool) {
        return ContextBudget.builder().maxTokens(maxTokens).reservedOutputTokens(out).reservedToolTokens(tool).build();
    }

    /**
     * 提供指定角色和内容的模型消息。
     * @param c 本次调用传入的 {@code String}
     * @return 指定角色和内容的模型消息
     */
    private AgentMessage user(String c) {
        return AgentMessage.builder().role("user").content(c).build();
    }

    /**
     * 在该类型内部准备后续运行所需的数据。
     *
     * @param sessionId 受当前用户与策略约束的服务端会话 ID
     * @param currentQuery 本轮用户提问
     * @param budget 本次调用传入的 {@code ContextBudget}
     * @return 调用方使用的 {@code ContextBuildRequest}
     */
    private ContextBuildRequest req(String sessionId, String currentQuery, ContextBudget budget) {
        return ContextBuildRequest.builder()
                .systemPrompt("SYS").sessionId(sessionId).currentQuery(currentQuery).budget(budget).build();
    }

    /** 通过 {@code builder.build}、{@code contents.get} 驱动用例，布尔状态符合断言。 */
    @Test
    void loadsSummary_recentAndCurrentQuery_inOrder() {
        history.appendMessage("A", user("之前1"));
        history.appendMessage("A", user("之前2"));
        mem.updateSummary("A", MemoryItem.builder().sessionId("A").type(MemoryType.SUMMARY).content("这是摘要").build());

        ContextBuildResult r = builder.build(req("A", "现在的问题", budget(5000, 0, 0)));

        List<String> roles = r.getMessages().stream().map(AgentMessage::getRole).toList();
        List<String> contents = r.getMessages().stream().map(AgentMessage::getContent).toList();
        // Summary（system）+ 最近窗口(时间序) + 当前问题
        assertTrue(contents.get(0).startsWith("SYS"), "首条为 system");
        assertTrue(r.getMessages().stream().anyMatch(m -> m.getContent() != null && m.getContent().contains("[会话摘要]")), "Summary 加载进上下文");
        int iRecent1 = contents.indexOf("之前1");
        int iRecent2 = contents.indexOf("之前2");
        int iCurrent = contents.indexOf("现在的问题");
        assertTrue(iRecent2 > iRecent1, "Recent 保持时间顺序");
        assertTrue(iCurrent > iRecent2, "当前问题在 Recent 之后");
        assertTrue(iCurrent > iRecent1);
    }

    /** 通过 {@code builder.build} 驱动用例，布尔状态符合断言。 */
    @Test
    void currentQueryAlwaysPresent_evenZeroBudget() {
        ContextBuildResult r = builder.build(req("A", "Q", budget(0, 0, 0)));
        assertTrue(r.getMessages().stream().anyMatch(m -> "Q".equals(m.getContent())), "currentQuery 恒进上下文");
        assertFalse(r.isTrimmed(), "required 不裁 → 无裁剪");
    }

    /** 通过 {@code builder.build} 驱动用例，布尔状态符合断言。 */
    @Test
    void toolResultsEnterContext() {
        List<AgentMessage> tools = List.of(
                AgentMessage.builder().role("assistant").content("").toolCallId("c1").toolName("knowledge_search").build(),
                AgentMessage.builder().role("tool").content("tool结果").toolCallId("c1").build());
        ContextBuildResult r = builder.build(ContextBuildRequest.builder().systemPrompt("SYS").sessionId("A")
                .currentQuery("Q").toolMessages(tools).budget(budget(5000, 0, 0)).build());

        assertTrue(r.getMessages().stream().anyMatch(m -> "tool结果".equals(m.getContent())), "Tool 结果进入上下文");
    }

    /** 通过 {@code builder.build} 驱动用例，布尔状态符合断言。 */
    @Test
    void overBudget_trims_keepsRequired() {
        int capacity = com.pony.knowledge.agent.llm.AgentInputTokens.messages(List.of(
                AgentMessage.builder().role("system").content("SYS").build(), user("当前问题")));
        history.appendMessage("A", user("很久之前的长消息".repeat(40)));   // 拉高 token
        ContextBuildResult r = builder.build(ContextBuildRequest.builder().systemPrompt("SYS").sessionId("A")
                .currentQuery("当前问题")
                .toolMessages(List.of(AgentMessage.builder().role("tool").content("工具长结果".repeat(20)).build()))
                .budget(budget(capacity, 0, 0)).build());

        assertTrue(r.isTrimmed(), "超预算应裁剪");
        assertTrue(r.getDroppedMessages() > 0);
        assertTrue(r.getTokensUsed() <= capacity, "裁剪后应<=含协议开销的预算");
        // Required 恒在
        assertTrue(r.getMessages().stream().anyMatch(m -> "当前问题".equals(m.getContent())), "currentQuery 未被裁");
    }

    /** 通过 {@code builder.build} 驱动用例，布尔状态符合断言。 */
    @Test
    void recentKeepsChronological_andDropsOldestFirst() {
        history.appendMessage("A", user("最早"));
        history.appendMessage("A", user("中间"));
        history.appendMessage("A", user("最新"));
        int capacity = com.pony.knowledge.agent.llm.AgentInputTokens.messages(List.of(
                AgentMessage.builder().role("system").content("SYS").build(), user("Q"), user("中间"), user("最新")));
        ContextBuildResult r = builder.build(req("A", "Q", budget(capacity, 0, 0)));

        List<String> contents = r.getMessages().stream().map(AgentMessage::getContent).toList();
        // 预算小 → 裁掉最旧 "最早"，保留 "中间"、"最新"，仍按时间序
        assertFalse(contents.contains("最早"), "最旧先裁");
        int iMid = contents.indexOf("中间");
        int iNew = contents.indexOf("最新");
        assertTrue(iNew > iMid, "保留的最近消息仍按时间序");
    }

    /** 通过 {@code builder.build} 驱动用例，布尔状态符合断言。 */
    @Test
    void summary_optional_canBeEmpty() {
        ContextBuildResult r = builder.build(req("A", "Q", budget(5000, 0, 0)));
        assertTrue(r.getMessages().stream().noneMatch(m -> m.getContent() != null && m.getContent().contains("[会话摘要]")), "无摘要时不注入");
    }

    /** 通过 {@code builder.build} 驱动用例，布尔状态符合断言。 */
    @Test
    void degradedSummary_isLabelledDistinctly() {
        mem.updateSummary("A", MemoryItem.builder().sessionId("A").type(MemoryType.SUMMARY)
                .content(SummaryCodec.encode(ConversationSummary.builder()
                        .summary("user: 问\nassistant: 答").degraded(true).build(), om)).build());

        ContextBuildResult r = builder.build(req("A", "Q", budget(5000, 0, 0)));

        assertTrue(r.getMessages().stream().anyMatch(m -> m.getContent() != null
                && m.getContent().contains("降级")), "降级摘要要标明不是整理结论");
    }

    /** 通过 {@code b.build} 驱动用例，布尔状态符合断言。 */
    @Test
    void builder_usesOnlyMemoryManagerAbstraction() {
        MemoryManager mockMem = mock(MemoryManager.class);
        when(mockMem.getRecentMessages(eq("A"), anyInt())).thenReturn(List.of(user("r1")));
        when(mockMem.getSummary(eq("A"))).thenReturn(java.util.Optional.empty());
        DefaultContextBuilder b = new DefaultContextBuilder(mockMem, om);

        ContextBuildResult r = b.build(ContextBuildRequest.builder().systemPrompt("SYS").sessionId("A")
                .currentQuery("Q")
                .toolMessages(List.of(AgentMessage.builder().role("tool").content("t").build()))
                .budget(budget(5000, 0, 0)).build());

        verify(mockMem).getRecentMessages(eq("A"), anyInt());
        verify(mockMem).getSummary(eq("A"));
        // 只走了 MemoryManager 抽象；工具结果由入参注入，未触发任何 Tool/RAG/Redis
        assertTrue(r.getMessages().stream().anyMatch(m -> "t".equals(m.getContent())));
    }

    /** 构造超预算的近期消息与工具结果，验证优先丢弃最早历史。 */
    @Test
    void overBudget_dropsOldestRecentBeforeToolResults() {
        history.appendMessage("A", user("上一轮的旧闲聊".repeat(6)));
        List<AgentMessage> tools = List.of(
                AgentMessage.builder().role("tool").content("本轮刚检索到的证据").build());

        ContextBuildResult r = builder.build(ContextBuildRequest.builder()
                .systemPrompt("SYS").sessionId("A").currentQuery("Q").toolMessages(tools)
                .budget(budget(com.pony.knowledge.agent.llm.AgentInputTokens.messages(List.of(
                        AgentMessage.builder().role("system").content("SYS").build(), user("Q"), tools.get(0))), 0, 0)).build());

        assertTrue(r.isTrimmed(), "应发生裁剪");
        assertTrue(r.getMessages().stream().anyMatch(m -> "本轮刚检索到的证据".equals(m.getContent())),
                "工具结果（本轮证据）应在最近窗口之后才被裁");
        assertTrue(r.getMessages().stream().noneMatch(m -> m.getContent() != null
                        && m.getContent().contains("上一轮的旧闲聊")),
                "最旧的窗口消息先被裁");
    }

    /** 通过 {@code builder.build} 驱动用例，布尔状态符合断言，实际结果与固定预期一致。 */
    @Test
    void assistantSteps_becomeToolTraceMessage() {
        history.appendMessage("A", user("差旅标准是多少"));
        history.appendMessage("A", AgentMessage.builder().role("assistant").content("住宿上限 500 元")
                .steps(List.of(
                        ToolStep.builder().tool("knowledge_search").ok(true).summary("5 条候选").build(),
                        ToolStep.builder().tool("knowledge_ask").ok(false).summary("超时").build()))
                .build());

        ContextBuildResult r = builder.build(req("A", "那餐补呢", budget(5000, 0, 0)));

        List<String> contents = r.getMessages().stream().map(AgentMessage::getContent).toList();
        String trace = contents.stream()
                .filter(c -> c != null && c.startsWith("[该轮工具轨迹]"))
                .findFirst().orElse(null);
        assertNotNull(trace, "带步骤的 assistant 消息要生成工具轨迹");
        assertTrue(trace.contains("knowledge_search(ok): 5 条候选"), "成功步骤带一行摘要，实际=" + trace);
        assertTrue(trace.contains("knowledge_ask(失败): 超时"), "失败步骤也要留痕，实际=" + trace);
        assertEquals(contents.indexOf("住宿上限 500 元") + 1, contents.indexOf(trace),
                "轨迹必须紧跟它所描述的那条 assistant 消息");
    }

    /** 通过 {@code svc.remember}、{@code withMemory.build} 驱动用例，布尔状态符合断言。 */
    @Test
    void longTermMemory_injectedByCurrentQuery() {
        LongTermMemoryService svc = new LongTermMemoryService(
                new InMemoryLongTermMemoryStore(), true, 50, 5, 500);
        svc.remember(1L, "汇报材料一律用中文", "PREFERENCE", "1:s1");
        DefaultContextBuilder withMemory = new DefaultContextBuilder(mem, om, svc);

        ContextBuildResult r = withMemory.build(ContextBuildRequest.builder()
                .userId(1L).systemPrompt("SYS").sessionId("A").currentQuery("汇报用什么语言")
                .budget(budget(5000, 0, 0)).build());

        String block = r.getMessages().stream().map(AgentMessage::getContent)
                .filter(c -> c != null && c.startsWith("[长期记忆]"))
                .findFirst().orElse(null);
        assertNotNull(block, "与当前问题相关的记忆应被注入");
        assertTrue(block.contains("汇报材料一律用中文"));
        assertTrue(block.contains("以知识库为准"), "冲突口径必须随块一起给出");
    }

    /** 关闭读取长期记忆后构建上下文，检查记忆存储未被调用。 */
    @Test
    void memoryReadDisabled_neverLoadsLongTermMemory() {
        LongTermMemoryService svc = mock(LongTermMemoryService.class);
        DefaultContextBuilder withMemory = new DefaultContextBuilder(mem, om, svc);
        ContextBuildResult result = withMemory.build(ContextBuildRequest.builder()
                .userId(1L).memoryReadEnabled(false).systemPrompt("SYS").sessionId("A")
                .currentQuery("Q").budget(budget(5000, 0, 0)).build());
        org.mockito.Mockito.verifyNoInteractions(svc);
        assertTrue(result.getMessages().stream().noneMatch(message -> message.getContent().contains("[长期记忆]")));
    }

    /** 以缺失用户身份的请求构建上下文，核对结果未注入长期记忆。 */
    @Test
    void longTermMemory_notInjectedWithoutUserId() {
        LongTermMemoryService svc = new LongTermMemoryService(
                new InMemoryLongTermMemoryStore(), true, 50, 5, 500);
        svc.remember(1L, "汇报材料一律用中文", "PREFERENCE", "1:s1");
        DefaultContextBuilder withMemory = new DefaultContextBuilder(mem, om, svc);

        ContextBuildResult r = withMemory.build(req("A", "汇报用什么语言", budget(5000, 0, 0)));

        assertTrue(r.getMessages().stream().noneMatch(m -> m.getContent() != null
                && m.getContent().startsWith("[长期记忆]")));
    }

    /** 通过 {@code builder.build} 驱动用例，布尔状态符合断言，实际结果与固定预期一致。 */
    @Test
    void systemPrompt_preserved_noPermissionLeak() {
        ContextBuildResult r = builder.build(ContextBuildRequest.builder()
                .systemPrompt("角色：企业知识库助手").sessionId("A").currentQuery("Q").budget(budget(5000, 0, 0)).build());

        assertEquals("角色：企业知识库助手", r.getMessages().get(0).getContent(), "systemPrompt 原样保留");
        assertTrue(r.getMessages().stream().noneMatch(m ->
                m.getContent() != null && m.getContent().contains("userId")), "上下文不含权限字段");
    }

    /** 通过 {@code builder.build} 驱动用例，布尔状态符合断言。 */
    @Test
    void rebuild_afterTool_includesToolResults() {
        ContextBuildResult before = builder.build(req("A", "Q", budget(5000, 0, 0)));
        assertTrue(before.getMessages().stream().noneMatch(m -> "tool结果".equals(m.getContent())));

        List<AgentMessage> tools = List.of(AgentMessage.builder().role("tool").content("tool结果").build());
        ContextBuildResult after = builder.build(ContextBuildRequest.builder().systemPrompt("SYS").sessionId("A")
                .currentQuery("Q").toolMessages(tools).budget(budget(5000, 0, 0)).build());

        assertTrue(after.getMessages().stream().anyMatch(m -> "tool结果".equals(m.getContent())), "Tool 执行后可重建上下文");
    }
}
