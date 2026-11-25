/* 验证工具排序、注册冲突、权限过滤和目录元数据。 */

package com.pony.knowledge.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pony.knowledge.agent.controller.AgentToolController;
import com.pony.knowledge.agent.model.AgentExecutionContext;
import com.pony.knowledge.agent.tool.input.ToolInput;
import com.pony.knowledge.agent.tool.output.ToolResult;
import com.pony.knowledge.utils.CurrentUserUtil;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 3.1 P0-2 验收：ToolRegistry 的顺序必须是确定的。
 *
 * <p>原实现用 {@code Collectors.toMap} → HashMap，{@code definitions()} 发出去的是哈希序：
 * 同一个进程内虽然稳定，但跨进程/跨版本可能变，等于给「上一版为什么选了 A、这一版选了 B」
 * 多塞了一个不可控变量（还有 position bias 的疑虑）。这里用「按 name 升序」把顺序钉死。
 */
class ToolRegistryTest {

    /** 只暴露 name 的极简工具，用来测顺序与重名。 */
    private record NamedTool(String name, boolean requiresApproval) implements AgentTool {

        /**
         * 将当前参数交给共有构造入口，并补齐这组重载省略的设置。
         *
         * @param name 本次调用传入的 {@code String}
         */
        private NamedTool(String name) {
            this(name, false);
        }

        /** 声明此工具对模型公开的属性，包括用途与输入类型。 */
        @Override
        public ToolDefinition definition() {
            return ToolDefinition.builder().name(name).description("description " + name)
                    .inputClass(ToolInput.class).parameters(Map.of("type", "object"))
                    .requiresApproval(requiresApproval).build();
        }

        /**
         * 执行此工具所定义的业务操作，使用上下文中的身份与访问范围。
         * @param input 本次操作的结构化输入
         * @param context 可信执行上下文，包含身份、范围和本轮执行状态
         * @return 带载荷或可识别错误状态的工具结果
         */
        @Override
        public ToolResult execute(ToolInput input, AgentExecutionContext context) {
            throw new UnsupportedOperationException("仅用于顺序测试");
        }
    }

    /** 通过 {@code registry.get}、{@code executor.execute} 驱动用例，布尔状态符合断言，实际结果与固定预期一致。 */
    @Test
    void memorySchemasFollowTheSamePolicyAsExecution_andSaveIsABarrierWithoutApproval() {
        ToolRegistry registry = new ToolRegistry(List.of(new NamedTool("memory_save"),
                new NamedTool("memory_search"), new NamedTool("knowledge_search")));
        AgentExecutionContext context = AgentExecutionContext.builder()
                .memoryReadEnabled(false).memoryWriteEnabled(false).currentQuery("记住：新的事实").build();
        assertEquals(List.of("knowledge_search"), registry.definitionsFor(context).stream().map(ToolDefinition::getName).toList());
        context.setMemoryWriteEnabled(true);
        assertEquals(List.of("knowledge_search", "memory_save"), registry.definitionsFor(context).stream().map(ToolDefinition::getName).toList());
        assertFalse(registry.get("memory_save").definition().isRequiresApproval());
        context.setCurrentQuery("请查询我以前让你记住的联调代号");
        assertEquals(List.of("knowledge_search"), registry.definitionsFor(context).stream()
                .map(ToolDefinition::getName).toList());
        context.setCurrentQuery("记住：新的事实");
        com.pony.knowledge.agent.runtime.ToolConcurrencyPolicy concurrency =
                new com.pony.knowledge.agent.runtime.ToolConcurrencyPolicy(registry, Set.of());
        ToolExecutor executor = mock(ToolExecutor.class);
        java.util.concurrent.atomic.AtomicReference<String> content = new java.util.concurrent.atomic.AtomicReference<>("旧内容");
        when(executor.execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            com.pony.knowledge.agent.model.ToolCall call = invocation.getArgument(0);
            if ("memory_save".equals(call.getToolName())) {
                Thread.sleep(30); // 若错误地与后面的 search 并行，search 将读到旧内容。
                content.set("新内容");
            }
            return ToolResult.builder().toolName(call.getToolName()).success(true).data(content.get()).build();
        });
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            com.pony.knowledge.agent.runtime.ToolActExecutor act = new com.pony.knowledge.agent.runtime.ToolActExecutor(
                    executor, new ObjectMapper(), pool, concurrency, true, 2);
            List<ToolResult> results = act.execute(context, List.of(
                    com.pony.knowledge.agent.model.ToolCall.builder().toolName("memory_search").callId("before").arguments("{}").build(),
                    com.pony.knowledge.agent.model.ToolCall.builder().toolName("memory_save").callId("save").arguments("{}").build(),
                    com.pony.knowledge.agent.model.ToolCall.builder().toolName("memory_search").callId("after").arguments("{}").build()));
            assertEquals("旧内容", results.get(0).getData());
            assertEquals("新内容", results.get(2).getData(), "同轮写入后的读取必须看到写入结果");
        } finally { pool.shutdownNow(); }
    }

    /** 通过 {@code registry.definitions} 驱动该场景，实际结果与固定预期一致。 */
    @Test
    void definitions_areSortedByName_regardlessOfRegistrationOrder() {
        // 故意用「注册顺序 ≠ 字母序」的输入：HashMap 下这个断言会不稳定
        ToolRegistry registry = new ToolRegistry(
                List.of(new NamedTool("knowledge_search"), new NamedTool("knowledge_ask"),
                        new NamedTool("zzz_last"), new NamedTool("aaa_first")));

        assertEquals(List.of("aaa_first", "knowledge_ask", "knowledge_search", "zzz_last"),
                registry.definitions().stream().map(ToolDefinition::getName).toList(),
                "definitions() 必须按 name 升序");
    }

    /** 构造输入与依赖返回值，实际结果与固定预期一致。 */
    @Test
    void definitions_order_isStableAcrossInstances() {
        List<AgentTool> tools = List.of(new NamedTool("b"), new NamedTool("a"), new NamedTool("c"));

        List<String> first = new ToolRegistry(tools).definitions().stream()
                .map(ToolDefinition::getName).toList();
        List<String> second = new ToolRegistry(tools).definitions().stream()
                .map(ToolDefinition::getName).toList();

        assertEquals(first, second, "两次构建的 tools 数组顺序必须一致（发出去的 prompt 才可复现）");
    }

    /** 通过 {@code t.definition} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void getAll_keepsRegistrationOrder() {
        ToolRegistry registry = new ToolRegistry(
                List.of(new NamedTool("zzz"), new NamedTool("aaa"), new NamedTool("mmm")));

        assertEquals(List.of("zzz", "aaa", "mmm"),
                registry.getAll().stream().map(t -> t.definition().getName()).toList());
    }

    /** 构造输入与依赖返回值，抛出 {@code IllegalStateException}，布尔状态符合断言。 */
    @Test
    void duplicateToolName_failsFast() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new ToolRegistry(List.of(new NamedTool("dup"), new NamedTool("dup"))));

        assertTrue(e.getMessage().contains("dup"), "错误信息应带上重名工具，便于定位");
    }

    /** 通过 {@code registry.get} 驱动用例，抛出 {@code IllegalArgumentException}。 */
    @Test
    void unknownTool_throws() {
        ToolRegistry registry = new ToolRegistry(List.of(new NamedTool("only")));

        assertThrows(IllegalArgumentException.class, () -> registry.get("missing"));
    }

    /** 通过 {@code registry.definitions} 驱动该场景，布尔状态符合断言，实际结果与固定预期一致。 */
    @Test
    void definitions_filtersByAllowedNames_nullMeansAll() {
        ToolRegistry registry = new ToolRegistry(
                List.of(new NamedTool("knowledge_search"), new NamedTool("knowledge_ask"),
                        new NamedTool("memory_search")));

        assertEquals(List.of("knowledge_ask"),
                registry.definitions(Set.of("knowledge_ask", "missing"))
                        .stream().map(ToolDefinition::getName).toList());
        assertEquals(3, registry.definitions(null).size(), "null 表示默认 Agent 的全部工具");
        assertEquals(0, registry.definitions(Set.of()).size(), "空集表示一个都不暴露");
        assertTrue(registry.contains("knowledge_search"));
        assertFalse(registry.contains("missing"));
    }

    /** 调用工具目录接口，确认它返回登记元数据并且没有执行工具。 */
    @Test
    void catalogEndpoint_returnsOnlyRegisteredPublicMetadata_withoutExecutingTools() throws Exception {
        ToolRegistry registry = new ToolRegistry(List.of(
                new NamedTool("read_document"), new NamedTool("approval_tool", true)));
        CurrentUserUtil currentUser = mock(CurrentUserUtil.class);
        when(currentUser.getUserId()).thenReturn(7L);

        var result = new AgentToolController(registry, currentUser).list();

        assertEquals(200, result.getCode());
        assertEquals(List.of("approval_tool", "read_document"),
                result.getData().stream().map(AgentToolResponse::name).toList());
        assertTrue(result.getData().get(0).requiresApproval());
        assertFalse(result.getData().get(1).requiresApproval());
        assertEquals("description read_document", result.getData().get(1).description());
        var json = new ObjectMapper().valueToTree(result.getData().get(0));
        assertEquals(3, json.size(), "公开目录不应混入 inputClass 或模型参数 Schema");
        assertFalse(json.has("inputClass"));
        assertFalse(json.has("parameters"));
        verify(currentUser).getUserId();
    }

    /** 构造输入与依赖返回值，抛出 {@code IllegalStateException}，跳过受保护的调用。 */
    @Test
    void catalogEndpoint_requiresCurrentUserBeforeReadingRegistry() {
        ToolRegistry registry = mock(ToolRegistry.class);
        CurrentUserUtil currentUser = mock(CurrentUserUtil.class);
        when(currentUser.getUserId()).thenThrow(new IllegalStateException("未认证"));

        assertThrows(IllegalStateException.class,
                () -> new AgentToolController(registry, currentUser).list());

        verifyNoInteractions(registry);
    }
}
