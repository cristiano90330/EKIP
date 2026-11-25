/* 将直接聊天模型的响应转换成 Agent 运行时使用的决策。 */

package com.pony.knowledge.agent.llm;

import com.pony.knowledge.agent.model.AgentDecision;
import com.pony.knowledge.agent.model.AgentMessage;
import com.pony.knowledge.agent.model.ToolCall;
import com.pony.knowledge.agent.tool.ToolDefinition;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容的 Tool-Calling LLM（AgentLlm 的直连供应商档）：
 *   respond()：Java 对话消息 + Tool Schema → OpenAI chat.completions → AgentDecision。
 *   供应商差异（OpenAI / DeepSeek / Qwen(DashScope) / 智谱）由 AgentLlmProperties 的
 *   apiKey/baseUrl/model 注入，本类零供应商耦合。
 *
 * <p>报文映射在 {@link OpenAiPayloadMapper}：走 Python 治理层的那一档发的是同一份
 * messages/tools，只是收件人不同。
 *
 * <p>使用场景（{@code agent.llm.mode=direct}）：AI 服务没起的本地开发，或直连一个
 * 没有治理层包裹的供应商。默认档是 {@link GatewayAgentLlm}，让 Agent 决策与 RAG 一样
 * 受 ModelGate 的预算、熔断与降级治理。
 */
@RequiredArgsConstructor
public class OpenAiAgentLlm implements AgentLlm {

    private final AgentLlmProperties properties;

    private final LlmClient llmClient;

    /**
     * 补齐系统提示词并调用直接模型适配器，返回映射后的 Agent 决策。
     * 下游调用 {@code OpenAiPayloadMapper.toOpenAiMessages}、{@code OpenAiPayloadMapper.toOpenAiTools}、{@code llmClient.chatCompletions}。
     *
     * @param messages 按照交互顺序提供的消息列表
     * @param tools 本次调用传入的 {@code List<ToolDefinition>}
     * @return 模型本轮的正文、工具调用和完成状态
     */
    @Override
    public AgentDecision respond(List<AgentMessage> messages, List<ToolDefinition> tools) {
        List<Map<String, Object>> openAiMessages = OpenAiPayloadMapper.toOpenAiMessages(messages);
        List<Map<String, Object>> openAiTools = OpenAiPayloadMapper.toOpenAiTools(tools);

        LlmResponse llmResponse = llmClient.chatCompletions(
                properties.getModel(), openAiMessages, openAiTools);

        // usage 可能为 null（部分供应商不返回）：按 0 计，调用方据此区分「没花」与「不知道花多少」
        TokenUsage usage = llmResponse.getUsage();
        return AgentDecision.builder()
                .content(llmResponse.getContent())
                .toolCalls(llmResponse.getToolCalls() == null ? List.of() : llmResponse.getToolCalls())
                .promptTokens(usage == null ? 0 : usage.getPromptTokens())
                .completionTokens(usage == null ? 0 : usage.getCompletionTokens())
                .finishReason(llmResponse.getFinishReason())
                .build();
    }
}
