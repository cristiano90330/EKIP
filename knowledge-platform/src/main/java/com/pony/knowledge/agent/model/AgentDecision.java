/* 表示模型的一轮选择，包括文本、工具调用、结束原因和异常状态。 */

package com.pony.knowledge.agent.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 一次 LLM 决策：要么给出文本回答（无 toolCalls → 最终答案），
 * 要么发起一批工具调用（由 runtime 执行后继续下一轮）。
 *
 * promptTokens/completionTokens：本次决策的 token 用量。
 * 供应商未返回 usage 时按 0 计——LlmClient 保留「没返回」的 null，归零发生在 OpenAiAgentLlm。
 *
 * degraded/degradeReason：只有走治理层的 GatewayAgentLlm 才可能为 true，
 * 表示预算超限 / 熔断打开 / 降级链走完仍失败，这一轮没有模型回答。
 * 直连档只有异常，没有降级态，因此恒为 false。
 */
@Data
@Builder
public class AgentDecision {

    private String content;

    private List<ToolCall> toolCalls;

    private int promptTokens;

    private int completionTokens;

    private boolean degraded;

    /** 治理层给出的原因（如 circuit open、prompt 超预算直接降级）。 */
    private String degradeReason;

    /** Stable failure state from model execution; partial stream content is never success. */
    private String outcome;

    private String reasonCode;

    /** 实际服务的 provider 名（主 / 降级供应商）；直连档为空串。 */
    private String providerUsed;

    /**
     * 供应商的 finish_reason 原文（stop / length / content_filter / tool_calls …）。
     *
     * <p>为 null 表示「拿不到」（直连档、旧版 Python 或供应商未返回）：此时 Analyze 退化为
     * 「无 toolCalls + 正文非空 ⇒ 最终答案」，即改造前的判定，不产生行为回退。
     */
    private String finishReason;

    /**
     * 检查当前模型决策是否包含可执行工具调用。
     * 上游由 {@code ResponseAnalyzer.analyze} 调用。
     *
     * @return 条件成立时为 true，其他状态按 false 处理
     */
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
