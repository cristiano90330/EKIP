/* 约定接收已授权执行上下文、模型消息和事件接收方的回合入口。 */

package com.pony.knowledge.agent.runtime;

import com.pony.knowledge.agent.model.AgentExecutionContext;
import com.pony.knowledge.agent.model.AgentMessage;
import com.pony.knowledge.agent.model.AgentResponse;

import java.util.List;

/**
 * Agent Runtime 契约：在系统身份上下文（Authentication + Permission 已解析）
 * 之上跑 Tool Calling Loop，返回最终回答 + 停止原因 + 迭代/工具统计。
 */
public interface AgentRuntime {

    /**
     * 接收可信执行范围和上下文消息，完成一次 Agent 回合并返回含终态的响应。
     * 上游由 {@code AgentService.runLoop} 调用。
     *
     * @param context 可信执行上下文，包含身份、范围和本轮执行状态
     * @param messages 按照交互顺序提供的消息列表
     * @return 带答案、工具轨迹、来源和停止原因的回合结果
     */
    AgentResponse run(AgentExecutionContext context, List<AgentMessage> messages);
}
