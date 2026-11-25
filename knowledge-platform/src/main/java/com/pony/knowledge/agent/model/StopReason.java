/* 列出 Agent 停止循环时对外可见的业务原因。 */

package com.pony.knowledge.agent.model;

public enum StopReason {

    /** LLM 给出最终答案，无更多工具调用 */
    FINAL_ANSWER,

    /**
     * 模型因输出上限被截断，但已产出正文：按「交付已有正文」结束本轮，不重答。
     * 与 {@link #FINAL_ANSWER} 分开记，是为了让「答案不完整」这件事在响应里可见。
     */
    FINAL_ANSWER_TRUNCATED,

    /** 模型内容安全过滤（finish_reason=content_filter）：终态，不再累积同一份被过滤的上下文。 */
    CONTENT_FILTERED,

    /** 模型自然停止但正文为空，nudge 重试用尽后以兜底文案收场。 */
    EMPTY_RESPONSE,

    /**
     * 无进展检测命中：连续多轮无工具且内容完全相同，或连续多轮都在输出上限处被截断。
     * 两种情况都意味着「再转一圈也是同一结果」，区别只在日志里。
     */
    STUCK_REPEATED,

    /** 达到 maxIterations 仍持续调用工具，强制停止（防无限循环） */
    MAX_ITERATIONS,

    /** 达到 maxToolCalls，超限立即停止 */
    MAX_TOOL_CALLS,

    /** 请求被取消（Cancellation） */
    CANCELLED,

    /** 一次记忆申请待用户确认，当前模型回合已经结束。 */
    MEMORY_APPROVAL_REQUIRED,

    /** Agent 级时间预算超限 */
    BUDGET_EXCEEDED,

    /**
     * LLM 决策被治理层降级（预算超限 / 熔断打开 / 降级链走完仍失败），本轮没有模型回答。
     *
     * <p>此档返回 HTTP 200，answer 为可读的兜底文案，degraded=true 供前端与监控识别，
     * 使「模型说没有」与「服务没起」可区分。
     */
    LLM_DEGRADED
}
