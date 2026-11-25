/* 管理 Agent 工具登记、查找、有序 Schema 和页面目录。 */

package com.pony.knowledge.agent.tool;

import org.springframework.stereotype.Component;
import com.pony.knowledge.agent.model.AgentExecutionContext;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tool 注册表：按 definition().name() 管理全部 AgentTool，
 * 按调用方给出的允许集合向 LLM 暴露 Tool Schema（null = 默认 Agent 的全部工具）。
 *
 * <p>顺序确定性（影响 LLM 选工具时的 position bias 与可复现性）：
 * <ul>
 *   <li>内部用 {@link LinkedHashMap} 保存，迭代顺序 = 注册顺序（getAll() 也稳定）；</li>
 *   <li>definitions() 再按 name 升序排序，连「注册顺序受 Spring 注入顺序影响」也不依赖。</li>
 * </ul>
 */
@Component
public class ToolRegistry {

    private final Map<String, AgentTool> tools;

    /**
     * 用注入依赖和构造参数建立此实例执行时使用的状态。
     * 下游调用 {@code tool.definition}、{@code ordered.putIfAbsent}、{@code Collections.unmodifiableMap}。
     *
     * @param toolList 本次调用传入的 {@code List<AgentTool>}
     */
    public ToolRegistry(
            List<AgentTool> toolList) {

        // 手写而非 Collectors.toMap：既要 LinkedHashMap 的插入序，又要保留「重名直接报错」的原语义
        Map<String, AgentTool> ordered = new LinkedHashMap<>();
        for (AgentTool tool : toolList) {
            String name = tool.definition().getName();
            if (ordered.putIfAbsent(name, tool) != null) {
                throw new IllegalStateException("重复注册的工具名: " + name);
            }
        }
        this.tools = Collections.unmodifiableMap(ordered);
    }

    /**
     * 按注册名称查询工具实例，缺失时抛出业务入口可处理的异常。
     * 上游由 {@code ToolExecutor.execute} 调用。
     *
     * @param name 本次调用传入的 {@code String}
     * @return 当前记录中的 {@code tool}
     */
    public AgentTool get(String name) {

        AgentTool tool = tools.get(name);

        if (tool == null) {
            throw new IllegalArgumentException(
                    "Unknown tool: " + name
            );
        }

        return tool;
    }

    /**
     * 按当前登记顺序返回可用工具实例。
     * 上游由 {@code ToolConcurrencyPolicy.requiresApproval} 调用。
     * 下游调用 {@code tools.values}。
     *
     * @return 调用方使用的 {@code Collection<AgentTool>}
     */
    public Collection<AgentTool> getAll() {
        return tools.values();
    }

    /**
     * 检查指定名称是否存在于当前工具注册表。
     * 上游由 {@code AgentProfileService.resolveForExecution}、{@code AgentProfileService.validate} 调用。
     * 下游调用 {@code tools.containsKey}。
     *
     * @param name 本次调用传入的 {@code String}
     * @return 条件成立时为 true，其他状态按 false 处理
     */
    public boolean contains(String name) {
        return tools.containsKey(name);
    }

    /**
     * 将已登记工具映射成页面可以阅读的元数据目录。
     * 上游由 {@code AgentToolController.list} 调用。
     * 下游调用 {@code definition.isRequiresApproval}、{@code definitions}。
     *
     * @return 调用方使用的 {@code List<AgentToolResponse>}
     */
    public List<AgentToolResponse> catalog() {
        return definitions().stream()
                .map(definition -> new AgentToolResponse(
                        definition.getName(), definition.getDescription(), definition.isRequiresApproval()))
                .toList();
    }

    /**
     * 生成按名称排序的工具模型声明，可按调用方允许的工具名筛选。
     * 下游调用 {@code definitions}。
     *
     * @return 调用方使用的 {@code List<ToolDefinition>}
     */
    public List<ToolDefinition> definitions() {
        return definitions(null);
    }

    /**
     * 按允许的工具名称筛选并排序模型声明，构造本轮可调用工具集合。
     * 上游由 {@code DefaultAgentRuntime.callBudget}、{@code DefaultAgentRuntime.offeredToolNames}、{@code DefaultAgentRuntime.think} 调用。
     * 下游调用 {@code context.allowsTool}、{@code definitions}。
     *
     * @param context 可信执行上下文，包含身份、范围和本轮执行状态
     * @return 调用方使用的 {@code List<ToolDefinition>}
     */
    public List<ToolDefinition> definitionsFor(AgentExecutionContext context) {
        return definitions(context.getAllowedToolNames()).stream()
                .filter(definition -> context.allowsTool(definition.getName())).toList();
    }

    /**
     * 生成按名称排序的工具模型声明，可按调用方允许的工具名筛选。
     * 下游调用 {@code tools.values}、{@code tool.definition}、{@code Comparator.comparing}。
     *
     * @param allowedNames 本次调用传入的 {@code Set<String>}
     * @return 调用方使用的 {@code List<ToolDefinition>}
     */
    public List<ToolDefinition> definitions(Set<String> allowedNames) {
        return tools.values().stream()
                .filter(tool -> allowedNames == null || allowedNames.contains(tool.definition().getName()))
                .map(AgentTool::definition)
                .sorted(Comparator.comparing(ToolDefinition::getName))
                .toList();
    }
}
