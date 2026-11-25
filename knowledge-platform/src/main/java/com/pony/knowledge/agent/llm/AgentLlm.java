/* 定义 Agent 运行时使用的同步决策与流式决策接口。 */

package com.pony.knowledge.agent.llm;

import com.pony.knowledge.agent.model.AgentDecision;
import com.pony.knowledge.agent.model.AgentMessage;
import com.pony.knowledge.agent.tool.ToolDefinition;

import java.util.List;
import java.util.function.Consumer;

/**
 * Agent 的 tool-calling LLM 契约：给定对话历史 + 工具 Schema，
 * 返回要么最终回答、要么一批工具调用。runtime 只依赖本接口。
 */
public interface AgentLlm {

    /**
     * 提交模型消息和可用工具集合，返回一次完整决策。
     * 上游由 {@code AgentLlm.respondStream}、{@code DefaultAgentRuntime.think}、{@code FinalAnswerSynthesizer.synthesize} 调用。
     *
     * @param messages 按照交互顺序提供的消息列表
     * @param tools 本次调用传入的 {@code List<ToolDefinition>}
     * @return 模型本轮的正文、工具调用和完成状态
     */
    AgentDecision respond(List<AgentMessage> messages, List<ToolDefinition> tools);

    /**
     * 接收模型正文增量回调并返回最终决策；默认实现可通过同步响应完成。
     * 上游由 {@code DefaultAgentRuntime.think}、{@code FinalAnswerSynthesizer.synthesize} 调用。
     * 下游调用 {@code onDelta.accept}、{@code respond}。
     *
     * @param messages 按照交互顺序提供的消息列表
     * @param tools 本次调用传入的 {@code List<ToolDefinition>}
     * @param onDelta 本次调用传入的 {@code Consumer<String>}
     * @return 模型本轮的正文、工具调用和完成状态
     */
    default AgentDecision respondStream(List<AgentMessage> messages, List<ToolDefinition> tools,
                                        Consumer<String> onDelta) {
        AgentDecision decision = respond(messages, tools);
        if (onDelta != null && decision != null && decision.getContent() != null
                && !decision.getContent().isEmpty()) {
            onDelta.accept(decision.getContent());
        }
        return decision;
    }
}
