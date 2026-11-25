/* 定义 Agent 工具的声明和受可信上下文约束的执行接口。 */

package com.pony.knowledge.agent.tool;

import com.pony.knowledge.agent.model.AgentExecutionContext;
import com.pony.knowledge.agent.tool.input.ToolInput;
import com.pony.knowledge.agent.tool.output.ToolResult;

/**
 * 工具契约：每个 AgentTool 提供统一 Definition（名字 + 描述 + LLM schema + 输入类型）
 * 与纯执行逻辑（输入 + 系统身份上下文 → 结果）。Trace/Metrics 由 ToolExecutor 统一接入。
 */
public interface AgentTool {

    /**
     * 返回工具对模型公开的名称、说明和输入约束。
     * 上游由 {@code ToolExecutor.execute}、{@code ToolRegistry.ToolRegistry}、{@code ToolRegistry.definitions} 调用。
     *
     * @return 公开给模型或页面目录的工具定义
     */
    ToolDefinition definition();

    /**
     * 接收校验后的输入与可信执行上下文，返回可交回运行时的工具结果。
     * 上游由 {@code ToolExecutor.runTool} 调用。
     *
     * @param input 本次操作的结构化输入
     * @param context 可信执行上下文，包含身份、范围和本轮执行状态
     * @return 带载荷或可识别错误状态的工具结果
     */
    ToolResult execute(ToolInput input, AgentExecutionContext context);
}
