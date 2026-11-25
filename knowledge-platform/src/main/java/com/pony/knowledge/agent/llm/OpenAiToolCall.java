/* 将提供商的函数调用描述映射为运行时工具调用对象。 */

package com.pony.knowledge.agent.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.pony.knowledge.agent.model.ToolCall;

import java.util.List;

/**
 * OpenAI 响应里 {@code message.tool_calls[]} 的线格式。
 *
 * <p>直连档（{@link LlmClient} 解析 chat.completions）与治理层档
 * （{@link GatewayAgentLlm} 解析 /agent/decide 的透传结果）读的是同一个形状，
 * 各写一份 DTO 会导致新增字段时只修好一边。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
class OpenAiToolCall {

    public String id;

    public String type;

    public Function function;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Function {

        public String name;

        /** 一段 JSON 文本（不是对象）——OpenAI 的约定，交给 ToolExecutor 再解析。 */
        public String arguments;
    }

    /**
     * 将提供商工具调用列表映射到运行时工具调用及原始参数。
     * 上游由 {@code GatewayAgentLlm.toDecision} 调用。
     *
     * @param outers 本次调用传入的 {@code List<OpenAiToolCall>}
     * @return 转换后的工具调用列表
     */
    static List<ToolCall> toToolCalls(List<OpenAiToolCall> outers) {
        if (outers == null || outers.isEmpty()) {
            return List.of();
        }
        return outers.stream()
                .filter(outer -> outer != null && outer.function != null)
                .map(outer -> ToolCall.builder()
                        .callId(outer.id)
                        .toolName(outer.function.name)
                        .arguments(outer.function.arguments)
                        .build())
                .toList();
    }
}
