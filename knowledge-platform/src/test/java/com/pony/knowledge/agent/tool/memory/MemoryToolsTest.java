/* 验证长期记忆工具使用本轮明确意图和可信用户范围。 */

package com.pony.knowledge.agent.tool.memory;

import com.pony.knowledge.agent.memory.longterm.InMemoryLongTermMemoryStore;
import com.pony.knowledge.agent.memory.longterm.LongTermMemoryService;
import com.pony.knowledge.agent.model.AgentExecutionContext;
import com.pony.knowledge.agent.tool.input.MemorySaveInput;
import com.pony.knowledge.agent.tool.input.MemorySearchInput;
import com.pony.knowledge.agent.tool.output.MemorySearchOutput;
import com.pony.knowledge.agent.tool.output.ToolResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 长期记忆的两个工具（P3-1）。
 *
 * <p>要守住的是归属：写入与检索的用户只来自 {@link AgentExecutionContext}，
 * 工具入参里没有、也不会有 userId——这是「记忆串号」唯一可能的入口。
 */
class MemoryToolsTest {

    private final LongTermMemoryService service = new LongTermMemoryService(
            new InMemoryLongTermMemoryStore(), true, 50, 5, 500);

    private final MemorySaveTool saveTool = new MemorySaveTool(service);

    private final MemorySearchTool searchTool = new MemorySearchTool(service);

    /**
     * 在该类型内部准备后续运行所需的数据。
     * @param userId 当前用户 ID
     * @return 调用方使用的 {@code AgentExecutionContext}
     */
    private AgentExecutionContext ctx(Long userId) {
        return AgentExecutionContext.builder().userId(userId).sessionId("1:s1")
                .currentQuery("请记住：汇报材料一律用中文。我在华东区。用户一的偏好。")
                .build();
    }

    /** 将对早期记忆的查询作为输入，检查查询意图未取得再次写入的权限。 */
    @Test
    void queryingAnEarlierSaveDoesNotAuthorizeANewWrite() {
        AgentExecutionContext context = ctx(1L);
        context.setCurrentQuery("请查询我以前让你记住的联调代号，告诉我代号是什么。");
        assertFalse(context.allowsTool("memory_save"));
        ToolResult result = saveTool.execute(
                MemorySaveInput.builder().content("联调代号").kind("FACT").build(), context);
        assertFalse(result.isSuccess());
        assertTrue(service.list(1L).isEmpty());
    }

    /** 让模型提供未在当前用户指令中出现的内容，确认工具拒绝提出写入申请。 */
    @Test
    void saveRejectsContentThatWasInventedByTheModel() {
        AgentExecutionContext context = ctx(1L);
        context.setCurrentQuery("请记住：我的联调代号是青柚-b1f6d30c");
        assertTrue(context.allowsTool("memory_save"));
        ToolResult result = saveTool.execute(
                MemorySaveInput.builder().content("我的联调代号是蓝莓").kind("FACT").build(), context);
        assertFalse(result.isSuccess());
        assertTrue(service.list(1L).isEmpty());
    }

    /** 给出明确的记忆保存命令，检查工具返回与用户原文一致的确认申请。 */
    @Test
    void explicitCommandProposesVerbatimContentWithoutSaving() {
        AgentExecutionContext context = ctx(1L);
        context.setCurrentQuery("请记住：我的联调代号是青柚-b1f6d30c");
        ToolResult result = saveTool.execute(MemorySaveInput.builder()
                .content("我的联调代号是青柚-b1f6d30c").kind("FACT").build(), context);
        assertEquals("memory_approval_required", result.getReasonCode());
        assertEquals("我的联调代号是青柚-b1f6d30c", result.getMemorySaveProposal().content());
        assertTrue(service.list(1L).isEmpty());
    }

    /** 通过 {@code service.remember}、{@code service.list} 驱动用例，布尔状态符合断言，实际结果与固定预期一致。 */
    @Test
    void duplicateWithDifferentCaseDoesNotClaimThatStoredTextWasChanged() {
        assertTrue(service.remember(1L, "我的代号是 Demo-ID", "FACT", "test-session"));
        assertFalse(service.remember(1L, "我的代号是 demo-id", "FACT", "test-session"));
        assertEquals("我的代号是 Demo-ID", service.list(1L).get(0).getContent());
    }

    /** 通过 {@code service.remember}、{@code searchTool.execute} 驱动用例，布尔状态符合断言，实际结果与固定预期一致。 */
    @Test
    void searchReturnsPreviouslySavedMemory() {
        assertTrue(service.remember(1L, "汇报材料一律用中文", "PREFERENCE", "test-session"));
        ToolResult found = searchTool.execute(
                MemorySearchInput.builder().query("汇报用什么语言").limit(5).build(), ctx(1L));

        MemorySearchOutput out = (MemorySearchOutput) found.getData();
        assertEquals(1, out.getItems().size());
        assertEquals("汇报材料一律用中文", out.getItems().get(0).getContent());
        assertEquals("查询到的用户自述（可能已过期）：\n- 汇报材料一律用中文", found.getDirectAnswer());
    }

    /** 通过 {@code saveTool.execute} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void unknownKind_normalisesToFact() {
        ToolResult saved = saveTool.execute(
                MemorySaveInput.builder().content("我在华东区").kind("某个不存在的类别").build(), ctx(1L));

        assertEquals("FACT", saved.getMemorySaveProposal().kind());
    }

    /** 通过 {@code saveTool.execute} 驱动用例，布尔状态符合断言。 */
    @Test
    void save_withoutUser_fails() {
        ToolResult result = saveTool.execute(
                MemorySaveInput.builder().content("我在华东区").build(), ctx(null));

        assertFalse(result.isSuccess(), "没有身份就不能写：作用域缺失时必须失败而不是猜一个用户");
    }

    /** 通过 {@code service.remember}、{@code searchTool.execute} 驱动用例，布尔状态符合断言，返回的数据、边界或顺序符合断言。 */
    @Test
    void search_neverCrossesUsers() {
        service.remember(1L, "用户一的偏好", "PREFERENCE", "test-session");

        ToolResult found = searchTool.execute(
                MemorySearchInput.builder().query("偏好").build(), ctx(2L));

        assertTrue(((MemorySearchOutput) found.getData()).getItems().isEmpty(),
                "别人的记忆不能被检索到");
    }

    /** 通过 {@code searchTool.execute} 驱动用例，布尔状态符合断言，返回的数据、边界或顺序符合断言。 */
    @Test
    void search_withoutUser_returnsEmpty() {
        ToolResult found = searchTool.execute(
                MemorySearchInput.builder().query("偏好").build(), ctx(null));

        assertTrue(found.isSuccess());
        assertTrue(((MemorySearchOutput) found.getData()).getItems().isEmpty());
    }

    /** 通过 {@code saveTool.definition}、{@code searchTool.definition} 驱动用例，布尔状态符合断言，实际结果与固定预期一致。 */
    @Test
    void definitions_areStableAndDescribeScope() {
        assertEquals("memory_save", saveTool.definition().getName());
        assertTrue(saveTool.definition().isRequiresApproval());
        assertEquals("memory_search", searchTool.definition().getName());
        assertTrue(saveTool.definition().getDescription().contains("ONLY when the user explicitly asks"),
                "「只在用户明确要求时写」必须写进描述：否则模型会自行决定什么值得记");
        assertTrue(searchTool.definition().getDescription().contains("knowledge_search"),
                "要写清与知识库检索的分工");
    }
}
