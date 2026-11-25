/* 验证知识搜索工具可由 Spring 装配并登记到工具注册表。 */

package com.pony.knowledge.agent.tool.knowledge;

import com.pony.knowledge.agent.tool.ToolDefinition;
import com.pony.knowledge.agent.tool.ToolRegistry;
import com.pony.knowledge.service.DocumentReadAccessService;
import com.pony.knowledge.service.rag.PythonRagPipeline;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 3.7b/c 验收：只扫描知识工具包并注入 mock 边界，验证 Spring 注册和 Schema；不连接业务数据库。
 */
class KnowledgeSearchToolSpringTest {

    /** 通过 {@code registry.get} 驱动用例，抛出 {@code IllegalArgumentException}，布尔状态符合断言。 */
    @Test
    void toolIsSpringManagedAndRegistryDiscoversIt() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(PythonRagPipeline.class, () -> mock(PythonRagPipeline.class));
            context.registerBean(DocumentReadAccessService.class, () -> mock(DocumentReadAccessService.class));
            context.register(ToolRegistry.class);
            context.scan("com.pony.knowledge.agent.tool.knowledge");
            context.refresh();

            KnowledgeSearchTool tool = context.getBean(KnowledgeSearchTool.class);
            ReadDocumentTool readDocumentTool = context.getBean(ReadDocumentTool.class);
            ToolRegistry registry = context.getBean(ToolRegistry.class);
        assertNotNull(tool, "Tool 应由 Spring 创建");
        assertSame(tool, registry.get("knowledge_search"), "ToolRegistry 应能发现 knowledge_search");
        assertTrue(registry.getAll().contains(tool));
        assertSame(readDocumentTool, registry.get("read_document"),
                "ToolRegistry 应能发现 read_document");
        assertTrue(registry.contains("read_document"), "Agent 配置白名单应可引用 read_document");
        assertThrows(IllegalArgumentException.class, () -> registry.get("unknown_tool"));

        // 默认 Agent 可见；配置白名单可只暴露 read_document。
        List<ToolDefinition> defs = registry.definitions();
        assertTrue(defs.stream().map(ToolDefinition::getName).toList()
                .containsAll(List.of("knowledge_search", "knowledge_ask", "read_document")));
        assertEquals(List.of("read_document"), registry.definitions(Set.of("read_document"))
                .stream().map(ToolDefinition::getName).toList());
        ToolDefinition def = defs.stream()
                .filter(item -> "knowledge_search".equals(item.getName()))
                .findFirst().orElseThrow();
        assertEquals("object", def.getParameters().get("type"));
        assertTrue(def.getParameters().containsKey("properties"));
        }
    }
}
