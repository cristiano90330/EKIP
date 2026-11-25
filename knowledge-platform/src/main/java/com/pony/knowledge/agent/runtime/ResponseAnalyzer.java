/* 根据模型结束原因、正文和工具调用选择循环的后续动作。 */

package com.pony.knowledge.agent.runtime;

import com.pony.knowledge.agent.model.AgentDecision;

/**
 * Analyze 阶段：判断一次 LLM 决策意味着「终局」还是「继续调工具」。
 *
 * <p>所有终止判定集中在这里，runtime 只按 {@link Action} 分支。这样做的收益很直接：
 * 新增一种终止原因时改一个地方，而不是在循环体里再插一个 if。
 *
 * <p><b>finish_reason 缺失时的口径</b>：供应商没返回（直连档、旧版 Python）时退化为
 * 「无工具 + 正文非空 ⇒ 最终答案」，与改造前完全一致，不产生行为回退。这条退化路径是
 * 有意的：宁可少识别「截断/过滤」，也不能因为字段缺失就把正常答案判成空。
 */
public final class ResponseAnalyzer {

    /** 内容被安全策略拦截且模型没给出任何正文时，返回给用户的固定说明。 */
    public static final String CONTENT_FILTERED_FALLBACK =
            "该请求被内容安全策略拦截，请换一种问法。";

    /** 空回复重试用尽后的兜底文案（不是模型生成的，必须说清「没答成」而不是「没有」）。 */
    public static final String EMPTY_RESPONSE_FALLBACK =
            "抱歉，本轮没能生成回答，请重试或换一种问法。";

    /** 一次决策的语义结论。 */
    public enum Action {
        /** 正常自然停止：{@code content} 即最终答案。 */
        FINAL_ANSWER,
        /** 输出上限截断但已有正文：交付已生成部分，不重答。 */
        FINAL_ANSWER_TRUNCATED,
        /** 内容过滤：终态。 */
        CONTENT_FILTERED,
        /** 自然停止但正文为空：可 nudge 重试。 */
        EMPTY_CONTENT,
        /** 有工具调用：继续 Act/Act+Observe。 */
        TOOL_CALLS
    }

    /** 结论 + 该结论对应的答案文本（非答案类结论为 null）。 */
    public static final class Verdict {

        private final Action action;

        private final String answer;

        /**
         * 用注入依赖和构造参数建立此实例执行时使用的状态。
         *
         * @param action 本次调用传入的 {@code Action}
         * @param answer 本次调用传入的 {@code String}
         */
        private Verdict(Action action, String answer) {
            this.action = action;
            this.answer = answer;
        }

        /**
         * 返回当前记录保存的 {@code action}。
         * 上游由 {@code DefaultAgentRuntime.reactIteration} 调用。
         */
        public Action getAction() {
            return action;
        }

        /**
         * 返回当前记录保存的 {@code answer}。
         * 上游由 {@code DefaultAgentRuntime.reactIteration} 调用。
         */
        public String getAnswer() {
            return answer;
        }
    }

    /** 保留私有构造入口，以便通过静态方法使用此类型。 */
    private ResponseAnalyzer() {
    }

    /**
     * 按模型完成原因、工具列表和正文，决定交付答案、调用工具或终止。
     * 上游由 {@code DefaultAgentRuntime.finalizeTurn}、{@code DefaultAgentRuntime.reactIteration} 调用。
     * 下游调用 {@code decision.hasToolCalls}、{@code normalize}、{@code isContentFilter}。
     *
     * @param decision 待处理的决策及其终态信息
     * @return 调用方使用的 {@code Verdict}
     */
    public static Verdict analyze(AgentDecision decision) {
        if (decision.hasToolCalls()) {
            // 有工具调用就是非终局：此时正文多半是「我来查一下」这类引子，不能当答案。
            return new Verdict(Action.TOOL_CALLS, null);
        }

        String finish = normalize(decision.getFinishReason());
        String content = decision.getContent() == null ? "" : decision.getContent();

        if (isContentFilter(finish)) {
            return new Verdict(Action.CONTENT_FILTERED,
                    content.isBlank() ? CONTENT_FILTERED_FALLBACK : content);
        }
        if (isLength(finish)) {
            // 截断到连正文都没有（全花在推理上）→ 交给空回复 nudge，比直接给空答案好。
            return content.isBlank()
                    ? new Verdict(Action.EMPTY_CONTENT, null)
                    : new Verdict(Action.FINAL_ANSWER_TRUNCATED, content);
        }
        if (content.isBlank()) {
            return new Verdict(Action.EMPTY_CONTENT, null);
        }

        return new Verdict(Action.FINAL_ANSWER, content);
    }

    /**
     * 归一化结束码后识别模型输出长度上限。
     * 上游由 {@code DefaultAgentRuntime.guardsTripped}、{@code ResponseAnalyzer.analyze} 调用。
     * 下游调用 {@code normalize}。
     *
     * @param finishReason 模型提供的响应结束原因
     * @return 条件成立时为 true，其他状态按 false 处理
     */
    public static boolean isLength(String finishReason) {
        String value = normalize(finishReason);
        return "length".equals(value) || "max_tokens".equals(value) || "max_output_tokens".equals(value);
    }

    /**
     * 识别模型因内容过滤提前结束的原因码。
     * 上游由 {@code ResponseAnalyzer.analyze} 调用。
     * 下游调用 {@code normalize}。
     *
     * @param finishReason 模型提供的响应结束原因
     * @return 条件成立时为 true，其他状态按 false 处理
     */
    public static boolean isContentFilter(String finishReason) {
        return "content_filter".equals(normalize(finishReason));
    }

    /**
     * 把提供商结束原因归一化为便于业务判断的标记值。
     * 上游由 {@code ResponseAnalyzer.analyze}、{@code ResponseAnalyzer.isContentFilter}、{@code ResponseAnalyzer.isLength} 调用。
     *
     * @param finishReason 模型提供的响应结束原因
     * @return 供后续处理使用的文本或标识
     */
    private static String normalize(String finishReason) {
        return finishReason == null ? "" : finishReason.trim().toLowerCase();
    }
}
