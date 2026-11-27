/* 把可公开的 Agent 工具注册到 MCP，并在账号权限范围内执行。 */

package com.pony.knowledge.mcp.bridge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pony.knowledge.agent.model.AgentExecutionContext;
import com.pony.knowledge.agent.model.ToolCall;
import com.pony.knowledge.agent.tool.AgentTool;
import com.pony.knowledge.agent.tool.ToolDefinition;
import com.pony.knowledge.agent.tool.ToolExecutor;
import com.pony.knowledge.agent.tool.ToolRegistry;
import com.pony.knowledge.agent.tool.output.ToolResult;
import com.pony.knowledge.mcp.config.McpProperties;
import com.pony.knowledge.modelmanagement.ModelExecutionContext;
import com.pony.knowledge.service.KnowledgeBaseAccessService;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * AgentTool → MCP Tool 的通用桥：一处工具定义，Agent Runtime 与 MCP Server 两处暴露。
 *
 * 主要负责：
 *   1. 按配置白名单导出工具，未注册的名称会阻止启动（default-deny）
 *   2. parameters Map → McpSchema.JsonSchema
 *   3. tools/call → 解析身份 → 复用 ToolExecutor（Bean Validation / 超时 / 重试 / 永不抛）→ CallToolResult
 *
 * 身份走 McpTransportContext 而非 SecurityContextHolder：handler 可能跑在非请求线程上。
 */
@Component
@RequiredArgsConstructor
public class McpToolBridge {

    private final ToolRegistry toolRegistry;

    private final ToolExecutor toolExecutor;

    private final KnowledgeBaseAccessService knowledgeBaseAccessService;

    private final McpProperties mcpProperties;

    private final ObjectMapper objectMapper;

    /**
     * 只为配置允许的 Agent 工具建立 MCP 工具规格。
     * 上游为 McpServerConfig.mcpSyncServer。
     * 下游为 McpProperties.getExposedTools、ToolRegistry.getAll。
     *
     * @return 与过滤条件匹配的 List&lt;McpServerFeatures.SyncToolSpecification&gt; 数据集合
     */
    public List<McpServerFeatures.SyncToolSpecification> buildSpecifications() {
        List<String> exposed = mcpProperties.getExposedTools();
        if (exposed == null || exposed.isEmpty()) {
            return List.of();
        }

        Map<String, AgentTool> registered = new LinkedHashMap<>();

        for (AgentTool tool : toolRegistry.getAll()) {
            registered.put(tool.definition().getName(), tool);
        }

        List<String> unknown = exposed.stream()
                .filter(name -> !registered.containsKey(name))
                .toList();
        if (!unknown.isEmpty()) {
            throw new IllegalStateException("mcp.exposed-tools 含未注册的工具名 " + unknown
                    + "，当前已注册 " + registered.keySet() + "（宁可不启动也不要静默少暴露一个工具）");
        }

        return exposed.stream()
                .map(registered::get)
                .map(this::toSpecification)
                .toList();
    }

    /**
     * 把工具描述、JSON 参数和执行函数转为 MCP 同步规范。
     * 下游为 AgentTool.definition。
     *
     * @param agentTool 本次操作使用的 AgentTool 组件或记录
     * @return 本次计算或查询得到的 McpServerFeatures.SyncToolSpecification 结果
     */
    private McpServerFeatures.SyncToolSpecification toSpecification(AgentTool agentTool) {
        ToolDefinition definition = agentTool.definition();
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name(definition.getName())
                .description(definition.getDescription())
                .inputSchema(toInputSchema(definition.getParameters()))
                .build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> call(agentTool, exchange, request))
                .build();
    }

    /**
     * 从 MCP 调用上下文解析账号及可读知识库，再执行对应的 Agent 工具。
     * 上游为 McpToolBridge.toSpecification。
     * 下游为 McpSyncServerExchange.transportContext、KnowledgeBaseAccessService.resolveReadableIds。
     *
     * @param agentTool 本次操作使用的 AgentTool 组件或记录
     * @param exchange 本次操作所用的 McpSyncServerExchange 数据
     * @param request 当前调用的请求参数
     * @return 包含工具输出或错误信息的 MCP 响应
     */
    private McpSchema.CallToolResult call(
            AgentTool agentTool,
            McpSyncServerExchange exchange,
            McpSchema.CallToolRequest request) {

        Object principal = exchange.transportContext() == null
                ? null
                : exchange.transportContext().get(McpTransportContextKeys.USER_ID);

        if (!(principal instanceof Long userId)) {
            return errorResult("缺少认证身份，无法确定可访问的知识库范围");
        }

        // 规则一：resolveReadableIds 绝不返回 null；无权限 → 空集合 → 检索层返回空结果
        Set<Long> accessibleKnowledgeBaseIds =
                knowledgeBaseAccessService.resolveReadableIds(userId);

        // 工具执行上下文来自认证账号和服务端查询到的可读范围，客户端提交的参数只描述工具请求。
        AgentExecutionContext context = AgentExecutionContext.builder()
                .userId(userId)
                .accessibleKnowledgeBaseIds(accessibleKnowledgeBaseIds)
                .sessionId(exchange.sessionId())
                .build();

        String argumentsJson;

        try {
            argumentsJson = objectMapper.writeValueAsString(
                    request.arguments() == null ? Map.of() : request.arguments());
        } catch (JsonProcessingException e) {
            return errorResult("参数序列化失败: " + e.getOriginalMessage());
        }

        ToolCall call = ToolCall.builder()
                .callId(UUID.randomUUID().toString())
                .toolName(agentTool.definition().getName())
                .arguments(argumentsJson)
                .build();

        // 工具线程复用已认证 Key 的账号解析模型，执行快照仍由实际调用需求决定。
        try (ModelExecutionContext ignored = ModelExecutionContext.open(null, userId)) {
            return toCallResult(toolExecutor.execute(call, context));
        }
    }

    /**
     * 将内部工具结果转换为 MCP 可返回的结构化内容和状态。
     * 上游为 McpToolBridge.call。
     * 下游为 ToolResult.isSuccess。
     *
     * @param result 供当前流程映射、写入或判断的结果
     * @return 携带工具执行结果的 MCP 内容和错误标记
     */
    private McpSchema.CallToolResult toCallResult(ToolResult result) {
        if (!result.isSuccess()) {
            return errorResult(result.getErrorMessage());
        }
        try {
            return McpSchema.CallToolResult.builder()
                    .addTextContent(objectMapper.writeValueAsString(result.getData()))
                    .isError(false)
                    .build();
        } catch (JsonProcessingException e) {
            return errorResult("结果序列化失败: " + e.getOriginalMessage());
        }
    }

    /**
     * 构建 MCP 工具级错误提示。
     * 上游为 McpToolBridge.call、McpToolBridge.toCallResult。
     *
     * @param message 用于构建结果或异常的说明文字
     * @return 标记为错误并附安全提示的 MCP 工具响应
     */
    private McpSchema.CallToolResult errorResult(String message) {
        return McpSchema.CallToolResult.builder()
                .addTextContent(message == null ? "工具执行失败" : message)
                .isError(true)
                .build();
    }

    /**
     * 把 Agent 工具的参数定义转换为 MCP 输入结构。
     * 上游为 McpToolBridge.toSpecification。
     *
     * @param parameters 含当前操作所需属性的映射
     * @return 本次计算或查询得到的 McpSchema.JsonSchema 结果
     */
    @SuppressWarnings("unchecked")
    private McpSchema.JsonSchema toInputSchema(Map<String, Object> parameters) {
        Object properties = parameters.get("properties");
        Object required = parameters.get("required");
        return new McpSchema.JsonSchema(
                (String) parameters.getOrDefault("type", "object"),
                properties instanceof Map ? (Map<String, Object>) properties : Map.of(),
                // ToolSchemaGenerator 在无必填字段时不写 required key，此处必须容忍缺失
                required instanceof List ? new ArrayList<>((List<String>) required) : List.of(),
                null,
                null,
                null);
    }
}
