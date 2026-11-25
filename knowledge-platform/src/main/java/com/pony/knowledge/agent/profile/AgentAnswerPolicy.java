/* 解析自建 Agent 的通用回答和证据约束策略。 */

package com.pony.knowledge.agent.profile;

import com.pony.knowledge.exception.BusinessException;

/** 回答交付策略；grounded 的证据约束由运行时执行，不能被系统提示词覆盖。 */
public enum AgentAnswerPolicy {
    GROUNDED("grounded"), GENERAL("general");

    private final String value;

    /**
     * 用注入依赖和构造参数建立此实例执行时使用的状态。
     *
     * @param value 本次调用传入的 {@code String}
     */
    AgentAnswerPolicy(String value) {
        this.value = value;
    }

    /**
     * 返回可保存和对外交付的策略代码。
     *
     * @return 当前记录中的 {@code value}
     */
    public String value() {
        return value;
    }

    /**
     * 解析来自配置的策略值，得到枚举用于后续回答控制。
     * 上游由 {@code AgentPolicyFingerprint.resolve}、{@code AgentProfileService.resolveForExecution}、{@code AgentProfileService.toResponse} 调用。
     * 下游调用 {@code values}。
     *
     * @param value 本次调用传入的 {@code String}
     * @return 调用方使用的 {@code AgentAnswerPolicy}
     */
    public static AgentAnswerPolicy from(String value) {
        if (value == null) return GROUNDED;
        for (AgentAnswerPolicy policy : values()) {
            if (policy.value.equals(value)) return policy;
        }
        throw new BusinessException("answerPolicy 只允许 grounded / general");
    }
}
