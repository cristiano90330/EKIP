/* 核对 MCP 工具适配层对权限、输入、响应和超时的处理。 */

package com.pony.knowledge.mcp.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pony.knowledge.agent.model.AgentExecutionContext;
import com.pony.knowledge.agent.model.ToolCall;
import com.pony.knowledge.agent.tool.AgentTool;
import com.pony.knowledge.agent.tool.ToolDefinition;
import com.pony.knowledge.agent.tool.ToolExecutor;
import com.pony.knowledge.agent.tool.ToolRegistry;
import com.pony.knowledge.agent.tool.input.ToolInput;
import com.pony.knowledge.agent.tool.output.ToolResult;
import com.pony.knowledge.mcp.config.McpProperties;
import com.pony.knowledge.modelmanagement.ModelExecutionContext;
import com.pony.knowledge.service.KnowledgeBaseAccessService;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpToolBridgeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ToolExecutor toolExecutor = mock(ToolExecutor.class);

    private final KnowledgeBaseAccessService accessService = mock(KnowledgeBaseAccessService.class);

    /** 记录 bridge 递交给 ToolExecutor 的调用，用于断言身份与参数往返。 */
    private static final class FakeTool implements AgentTool {

        private final String name;

        private final Map<String, Object> parameters;

        /** 构造 {@code FakeTool} 夹具，绑定本次场景的输入。接收属性或资源名称、{@code parameters}。 */
        FakeTool(String name, Map<String, Object> parameters) {
            this.name = name;
            this.parameters = parameters;
        }

        /** 构造 {@code ToolDefinition} 供测试使用，使用 {@code ToolDefinition.builder}。返回 {@code ToolDefinition} 结果。 */
        @Override
        public ToolDefinition definition() {
            return ToolDefinition.builder()
                    .name(name)
                    .description("desc of " + name)
                    .inputClass(ToolInput.class)
                    .parameters(parameters)
                    .build();
        }

        /** 构造 {@code ToolResult} 供测试使用。接收{@code input}、{@code context}。返回 {@code ToolResult} 结果。 */
        @Override
        public ToolResult execute(ToolInput input, AgentExecutionContext context) {
            throw new UnsupportedOperationException("由 ToolExecutor 负责执行");
        }
    }

    /**
     * 构造 {@code Map<String, Object>} 供测试使用，使用 {@code schema.put}。
     * 由 {@code whitelistControlsWhatGetsExposed}、{@code emptyWhitelist_exposesNothing_withoutThrowing} 调用。
     * 返回 {@code schema}。
     */
    private Map<String, Object> schemaWithRequired() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("query", Map.of("type", "string")));
        schema.put("required", List.of("query"));
        return schema;
    }

    /**
     * 构造 {@code McpToolBridge} 供测试使用，使用 {@code properties.setExposedTools}。
     * 由 {@code whitelistControlsWhatGetsExposed}、{@code emptyWhitelist_exposesNothing_withoutThrowing} 调用。
     * @param exposedTools 本用例传入的{@code exposedTools}
     * @param tools 本用例传入的{@code tools}
     * 返回 {@code McpToolBridge} 结果。
     */
    private McpToolBridge bridge(List<String> exposedTools, AgentTool... tools) {
        McpProperties properties = new McpProperties();
        properties.setExposedTools(exposedTools);
        return new McpToolBridge(new ToolRegistry(List.of(tools)), toolExecutor, accessService,
                properties, objectMapper);
    }

    /**
     * 构造 {@code McpSyncServerExchange} 供测试使用，使用 {@code exchange.sessionId}、{@code exchange.transportContext}。
     * 由 {@code call_resolvesIdentityFromTransportContext_andPassesThroughToolExecutor}、{@code successFalse_mapsToIsErrorResult_notAProtocolException} 调用。
     * @param userId 账号 ID
     * 返回 {@code exchange}。
     */
    private McpSyncServerExchange exchangeWithUser(Long userId) {
        McpSyncServerExchange exchange = mock(McpSyncServerExchange.class);
        when(exchange.sessionId()).thenReturn("session-1");
        when(exchange.transportContext()).thenReturn(userId == null
                ? McpTransportContext.EMPTY
                : McpTransportContext.create(Map.of(McpTransportContextKeys.USER_ID, userId)));
        return exchange;
    }

    /**
     * 构造 {@code McpSchema.CallToolResult} 供测试使用，使用 {@code spec.callHandler}、{@code spec.tool}。
     * 由 {@code call_resolvesIdentityFromTransportContext_andPassesThroughToolExecutor}、{@code successFalse_mapsToIsErrorResult_notAProtocolException} 调用。
     * @param spec 本用例传入的{@code spec}
     * @param exchange 本用例传入的{@code exchange}
     * @param arguments 本用例传入的{@code arguments}
     * 返回 {@code McpSchema.CallToolResult} 结果。
     */
    private McpSchema.CallToolResult call(McpServerFeatures.SyncToolSpecification spec,
            McpSyncServerExchange exchange, Map<String, Object> arguments) {
        return spec.callHandler().apply(exchange, new McpSchema.CallToolRequest(spec.tool().name(), arguments));
    }

    /** 核对 {@code specs.size()} 与预期 {@code 1} 一致。调用路径经过 {@code bridge.buildSpecifications}。 */
    @Test
    void whitelistControlsWhatGetsExposed() {
        McpToolBridge bridge = bridge(List.of("knowledge_search"),
                new FakeTool("knowledge_search", schemaWithRequired()),
                new FakeTool("internal_only", schemaWithRequired()));

        List<McpServerFeatures.SyncToolSpecification> specs = bridge.buildSpecifications();

        assertEquals(1, specs.size());
        assertEquals("knowledge_search", specs.get(0).tool().name());
        assertEquals("desc of knowledge_search", specs.get(0).tool().description());
    }

    /** 核对 {@code bridge.buildSpecifications().isEmpty()} 为真。 */
    @Test
    void emptyWhitelist_exposesNothing_withoutThrowing() {
        McpToolBridge bridge = bridge(List.of(), new FakeTool("knowledge_search", schemaWithRequired()));

        assertTrue(bridge.buildSpecifications().isEmpty());
    }

    /** 断言抛出 {@code IllegalStateException}。调用路径经过 {@code error.getMessage}。 */
    @Test
    void unknownWhitelistName_failsFastAndNamesTheOffender() {
        McpToolBridge bridge = bridge(List.of("knowledge_search", "typo_tool"),
                new FakeTool("knowledge_search", schemaWithRequired()));

        IllegalStateException error = assertThrows(IllegalStateException.class, bridge::buildSpecifications);

        assertTrue(error.getMessage().contains("typo_tool"), error.getMessage());
    }

    /** 核对 {@code tool.inputSchema().type()} 与预期 {@code "object"} 一致。调用路径经过 {@code noRequired.put}。 */
    @Test
    void schemaWithoutRequiredKey_convertsToEmptyList_notNpe() {
        Map<String, Object> noRequired = new LinkedHashMap<>();
        noRequired.put("type", "object");
        noRequired.put("properties", Map.of("topK", Map.of("type", "integer")));

        McpToolBridge bridge = bridge(List.of("knowledge_search"), new FakeTool("knowledge_search", noRequired));
        McpSchema.Tool tool = bridge.buildSpecifications().get(0).tool();

        assertEquals("object", tool.inputSchema().type());
        assertTrue(tool.inputSchema().properties().containsKey("topK"));
        assertTrue(tool.inputSchema().required().isEmpty());
    }

    /**
     * 核对 {@code callCaptor.getValue().getToolName()} 与预期 {@code "knowledge_search"} 一致。
     * 调用路径经过 {@code accessService.resolveReadableIds}。
     */
    @Test
    void call_resolvesIdentityFromTransportContext_andPassesThroughToolExecutor() {
        when(accessService.resolveReadableIds(42L)).thenReturn(Set.of(1L, 2L));
        when(toolExecutor.execute(any(), any())).thenAnswer(invocation -> {
            assertEquals(42L, ModelExecutionContext.currentOwnerId());
            assertNull(ModelExecutionContext.current(), "不得沿用其他请求的模型快照");
            return ToolResult.builder().toolName("knowledge_search").success(true)
                    .data(Map.of("hits", 3)).build();
        });
        McpToolBridge bridge = bridge(List.of("knowledge_search"),
                new FakeTool("knowledge_search", schemaWithRequired()));
        McpServerFeatures.SyncToolSpecification spec = bridge.buildSpecifications().get(0);

        McpSchema.CallToolResult result;
        try (ModelExecutionContext ignored = ModelExecutionContext.open(Map.of("budgetMs", 2000), 90L)) {
            result = call(spec, exchangeWithUser(42L), Map.of("query", "连接池"));
            assertEquals(90L, ModelExecutionContext.currentOwnerId());
            assertEquals(Map.of("budgetMs", 2000), ModelExecutionContext.current());
        }
        assertNull(ModelExecutionContext.currentOwnerId());

        ArgumentCaptor<ToolCall> callCaptor = ArgumentCaptor.forClass(ToolCall.class);
        ArgumentCaptor<AgentExecutionContext> contextCaptor =
                ArgumentCaptor.forClass(AgentExecutionContext.class);
        verify(toolExecutor).execute(callCaptor.capture(), contextCaptor.capture());

        assertEquals("knowledge_search", callCaptor.getValue().getToolName());
        assertEquals("{\"query\":\"连接池\"}", callCaptor.getValue().getArguments());
        assertFalse(callCaptor.getValue().getCallId().isBlank());

        AgentExecutionContext context = contextCaptor.getValue();
        assertEquals(42L, context.getUserId());
        assertEquals(Set.of(1L, 2L), context.getAccessibleKnowledgeBaseIds());
        assertEquals("session-1", context.getSessionId());

        assertFalse(result.isError());
        assertEquals("{\"hits\":3}", text(result));
    }

    /** 核对 {@code result.isError()} 为真。调用路径经过 {@code accessService.resolveReadableIds}。 */
    @Test
    void successFalse_mapsToIsErrorResult_notAProtocolException() {
        when(accessService.resolveReadableIds(any())).thenReturn(Set.of());
        when(toolExecutor.execute(any(), any())).thenReturn(
                ToolResult.builder().toolName("knowledge_search").success(false)
                        .errorMessage("工具执行超时（> 60000ms）").build());
        McpToolBridge bridge = bridge(List.of("knowledge_search"),
                new FakeTool("knowledge_search", schemaWithRequired()));

        McpSchema.CallToolResult result = call(bridge.buildSpecifications().get(0),
                exchangeWithUser(42L), Map.of("query", "x"));

        assertTrue(result.isError());
        assertEquals("工具执行超时（> 60000ms）", text(result));
    }

    /** 核对 {@code result.isError()} 为真。调用路径经过 {@code bridge.buildSpecifications}。 */
    @Test
    void missingIdentity_isRejectedBeforeExecutingAnyTool() {
        McpToolBridge bridge = bridge(List.of("knowledge_search"),
                new FakeTool("knowledge_search", schemaWithRequired()));

        McpSchema.CallToolResult result = call(bridge.buildSpecifications().get(0),
                exchangeWithUser(null), Map.of("query", "x"));

        assertTrue(result.isError());
        assertTrue(text(result).contains("缺少认证身份"), text(result));
        verify(toolExecutor, never()).execute(any(), any());
        verify(accessService, never()).resolveReadableIds(any());
    }

    /** 核对 {@code contexts.getAllValues} 为真。调用路径经过 {@code accessService.resolveReadableIds}。 */
    @Test
    void eachCallInSameSessionRecomputesCurrentLibraryScope() {
        when(accessService.resolveReadableIds(42L))
                .thenReturn(Set.of(1L, 2L), Set.of(2L), Set.of());
        when(toolExecutor.execute(any(), any())).thenReturn(
                ToolResult.builder().toolName("knowledge_search").success(true)
                        .data(Map.of("hits", 0)).build());
        McpToolBridge bridge = bridge(List.of("knowledge_search"),
                new FakeTool("knowledge_search", schemaWithRequired()));
        McpServerFeatures.SyncToolSpecification spec = bridge.buildSpecifications().get(0);
        McpSyncServerExchange exchange = exchangeWithUser(42L);
        Map<String, Object> arguments = Map.of("query", "x", "userId", 999L,
                "accessibleKnowledgeBaseIds", List.of(999L));

        call(spec, exchange, arguments);
        call(spec, exchange, arguments);
        call(spec, exchange, arguments);

        ArgumentCaptor<AgentExecutionContext> contexts =
                ArgumentCaptor.forClass(AgentExecutionContext.class);
        verify(toolExecutor, times(3)).execute(any(), contexts.capture());
        verify(accessService, times(3)).resolveReadableIds(42L);
        assertEquals(List.of(Set.of(1L, 2L), Set.of(2L), Set.of()),
                contexts.getAllValues().stream().map(AgentExecutionContext::getAccessibleKnowledgeBaseIds).toList());
        assertTrue(contexts.getAllValues().stream().allMatch(context -> context.getUserId().equals(42L)));
        assertTrue(contexts.getAllValues().stream()
                .allMatch(context -> "session-1".equals(context.getSessionId())));
        verify(accessService, never()).resolveReadableIds(999L);
    }

    /**
     * 构造 {@code String} 供测试使用。
     * 由 {@code successFalse_mapsToIsErrorResult_notAProtocolException}、{@code missingIdentity_isRejectedBeforeExecutingAnyTool} 调用。
     * @param result 本用例传入的{@code result}
     * 返回 {@code ((McpSchema.TextContent)first).text()}。
     */
    private String text(McpSchema.CallToolResult result) {
        McpSchema.Content first = result.content().get(0);
        return ((McpSchema.TextContent) first).text();
    }
}
