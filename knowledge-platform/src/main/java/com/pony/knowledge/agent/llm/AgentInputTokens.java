/* 估算交给 Agent 模型的消息文本和工具声明所需的输入令牌。 */

package com.pony.knowledge.agent.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pony.knowledge.agent.model.AgentMessage;
import com.pony.knowledge.agent.tool.ToolDefinition;
import com.pony.knowledge.service.rag.generation.TokenCounter;
import java.util.List;
import java.util.Map;

/** Conservative per-call estimate, using the same payload and raw count as ModelGate. */
public final class AgentInputTokens {
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 保留私有构造入口，以便通过静态方法使用此类型。 */
    private AgentInputTokens() { }

    /**
     * 根据提供给模型的角色、正文和工具消息估算令牌量。
     * 上游由 {@code DefaultContextBuilder.build}、{@code ToolGroup.add}、{@code ToolGroup.replace} 调用。
     * 下游调用 {@code prefixed.addAll}、{@code OpenAiPayloadMapper.toOpenAiMessages}、{@code TokenCounter.count}。
     *
     * @param messages 按照交互顺序提供的消息列表
     * @return 按当前方法口径计算或读取的数值
     */
    public static int messages(List<AgentMessage> messages) {
        if (messages == null || messages.isEmpty()) return 0;
        var prefixed = new java.util.ArrayList<AgentMessage>();
        prefixed.add(AgentMessage.builder().role("system").content("").build());
        prefixed.addAll(messages);
        var mapped = OpenAiPayloadMapper.toOpenAiMessages(prefixed);
        try {
            return TokenCounter.count(JSON.writeValueAsString(mapped.subList(1, mapped.size())));
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot estimate agent messages", e);
        }
    }

    /**
     * 在对话令牌量上计入工具定义的占用，返回完整决策输入估算。
     * 上游由 {@code DefaultAgentRuntime.callBudget}、{@code DefaultAgentRuntime.think} 调用。
     * 下游调用 {@code TokenCounter.count}、{@code JSON.writeValueAsString}、{@code OpenAiPayloadMapper.toOpenAiMessages}。
     *
     * @param messages 按照交互顺序提供的消息列表
     * @param tools 本次调用传入的 {@code List<ToolDefinition>}
     * @return 按当前方法口径计算或读取的数值
     */
    public static int estimate(List<AgentMessage> messages, List<ToolDefinition> tools) {
        try {
            return TokenCounter.count(JSON.writeValueAsString(Map.of(
                    "messages", OpenAiPayloadMapper.toOpenAiMessages(messages),
                    "tools", OpenAiPayloadMapper.toOpenAiTools(tools))));
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot estimate agent input", e);
        }
    }
}
