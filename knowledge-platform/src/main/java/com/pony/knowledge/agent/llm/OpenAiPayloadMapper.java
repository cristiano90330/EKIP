/* 统一把 Agent 提示词、消息、工具定义及参数映射成 OpenAI 兼容结构。 */

package com.pony.knowledge.agent.llm;

import com.pony.knowledge.agent.model.AgentMessage;
import com.pony.knowledge.agent.model.AgentPrompts;
import com.pony.knowledge.agent.model.ToolCall;
import com.pony.knowledge.agent.tool.ToolDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Java 侧 Agent 消息与工具 → OpenAI chat.completions 报文的唯一映射处。
 *
 * <p>直连档与治理层档必须发完全相同的报文，区别只是收件人（LLM 供应商 / AI 服务）。
 * 两处各写一份不会报错，只会让两条路径的 Agent 行为静默分叉。
 *
 * <p>口径（有测试钉住，见 OpenAiAgentLlmTest）：
 * <ul>
 *   <li>调用方没给 system 消息时补 {@link AgentPrompts#KNOWLEDGE_ASSISTANT_SYSTEM} 兜底；</li>
 *   <li>content 为 null 一律归一成空字符串（OpenAI 对缺键与空串的处理不一致）；</li>
 *   <li>一次决策的多个工具调用挂同一条 assistant 消息的 tool_calls 上；</li>
 *   <li>role=tool 才带 tool_call_id。</li>
 * </ul>
 */
final class OpenAiPayloadMapper {

    /** 兜底 system 提示：仅在调用方没有装配 system 消息时使用（直连 Runtime 的路径）。 */
    private static final String SYSTEM_MESSAGE = AgentPrompts.KNOWLEDGE_ASSISTANT_SYSTEM;

    /** 保留私有构造入口，以便通过静态方法使用此类型。 */
    private OpenAiPayloadMapper() {
    }

    /**
     * 把运行时消息映射成接口所需的角色、正文和工具调用回执。
     * 上游由 {@code AgentInputTokens.estimate}、{@code AgentInputTokens.messages}、{@code GatewayAgentLlm.payload} 调用。
     * 下游调用 {@code systemMessage}、{@code contentOf}、{@code toOpenAiToolCalls}。
     *
     * @param messages 按照交互顺序提供的消息列表
     * @return 当前记录中的 {@code result}
     */
    static List<Map<String, Object>> toOpenAiMessages(List<AgentMessage> messages) {
        List<Map<String, Object>> result = new ArrayList<>();
        // 调用方已装配 system 消息（ContextBuilder 输出）时不再重复注入
        boolean hasSystem = messages.stream().anyMatch(m -> "system".equals(m.getRole()));
        if (!hasSystem) {
            result.add(systemMessage(SYSTEM_MESSAGE));
        }

        for (AgentMessage m : messages) {
            String role = m.getRole();
            if ("system".equals(role)) {
                // system 必须显式转发：漏掉此分支会让 ContextBuilder 装配好的系统提示静默丢失，
                // 而上方的 hasSystem 守卫又因此不再补兜底
                result.add(systemMessage(contentOf(m)));
            } else if ("user".equals(role)) {
                Map<String, Object> msg = new LinkedHashMap<>();
                msg.put("role", "user");
                msg.put("content", contentOf(m));
                result.add(msg);
            } else if ("assistant".equals(role)) {
                Map<String, Object> msg = new LinkedHashMap<>();
                msg.put("role", "assistant");
                msg.put("content", contentOf(m));
                if (m.getToolCalls() != null && !m.getToolCalls().isEmpty()) {
                    msg.put("tool_calls", toOpenAiToolCalls(m.getToolCalls()));
                }
                result.add(msg);
            } else if ("tool".equals(role)) {
                Map<String, Object> msg = new LinkedHashMap<>();
                msg.put("role", "tool");
                msg.put("tool_call_id", m.getToolCallId());
                msg.put("content", contentOf(m));
                result.add(msg);
            }
        }
        return result;
    }

    /**
     * 把注册工具的声明和输入 Schema 转成 OpenAI 兼容函数定义。
     * 上游由 {@code AgentInputTokens.estimate}、{@code GatewayAgentLlm.payload}、{@code OpenAiAgentLlm.respond} 调用。
     *
     * @param tools 本次调用传入的 {@code List<ToolDefinition>}
     * @return 调用方使用的 {@code List<Map<String, Object>>}
     */
    static List<Map<String, Object>> toOpenAiTools(List<ToolDefinition> tools) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (tools == null) {
            return out;
        }
        for (ToolDefinition def : tools) {
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", def.getName());
            function.put("description", def.getDescription() == null ? "" : def.getDescription());
            Object parameters = def.getParameters();
            function.put("parameters", parameters != null
                    ? parameters
                    : Map.of("type", "object", "properties", Map.of()));

            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("type", "function");
            tool.put("function", function);
            out.add(tool);
        }
        return out;
    }

    /**
     * 从 Agent 消息取得适用于请求载荷的正文字段。
     * 上游由 {@code OpenAiPayloadMapper.toOpenAiMessages} 调用。
     *
     * @param m 本次调用传入的 {@code AgentMessage}
     * @return 供后续处理使用的文本或标识
     */
    private static String contentOf(AgentMessage m) {
        return m.getContent() == null ? "" : m.getContent();
    }

    /**
     * 把输入的系统提示词包装成 OpenAI 消息对象。
     * 上游由 {@code OpenAiPayloadMapper.toOpenAiMessages} 调用。
     *
     * @param content 待处理的正文内容
     * @return 当前记录中的 {@code msg}
     */
    private static Map<String, Object> systemMessage(String content) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "system");
        msg.put("content", content);
        return msg;
    }

    /**
     * 将运行时工具调用转换为提供商消息中的函数名、参数和调用标识。
     * 上游由 {@code OpenAiPayloadMapper.toOpenAiMessages} 调用。
     *
     * @param calls 模型本轮提出的工具调用列表
     * @return 当前记录中的 {@code out}
     */
    private static List<Map<String, Object>> toOpenAiToolCalls(List<ToolCall> calls) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ToolCall c : calls) {
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", c.getToolName());
            function.put("arguments", c.getArguments() == null ? "" : c.getArguments());

            Map<String, Object> toolCall = new LinkedHashMap<>();
            toolCall.put("id", c.getCallId());
            toolCall.put("type", "function");
            toolCall.put("function", function);
            out.add(toolCall);
        }
        return out;
    }
}
