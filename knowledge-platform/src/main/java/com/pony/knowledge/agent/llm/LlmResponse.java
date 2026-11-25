/* 记录聊天模型返回的正文、工具调用、结束原因和令牌用量。 */

package com.pony.knowledge.agent.llm;

import com.pony.knowledge.agent.model.ToolCall;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * LLM(OpenAI 兼容) chat.completions 的规范化响应：文本答案 或 一批工具调用。
 */
@Data
@Builder
public class LlmResponse {

    /** 文本答案（tool-calling 时可为 null/空） */
    private String content;

    /** 发起的一批工具调用（无则为空列表） */
    private List<ToolCall> toolCalls;

    /** token 用量 */
    private TokenUsage usage;

    /** 供应商的 finish_reason（stop / length / tool_calls …）；未返回时为 null。 */
    private String finishReason;

    /**
     * 判断提供商回复是否包含至少一个工具调用。
     *
     * @return 条件成立时为 true，其他状态按 false 处理
     */
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
