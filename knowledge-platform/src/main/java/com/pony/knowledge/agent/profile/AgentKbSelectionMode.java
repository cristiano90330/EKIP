/* 解析 Agent 使用全部可读库、选定库或无知识库的范围模式。 */

package com.pony.knowledge.agent.profile;

import com.pony.knowledge.exception.BusinessException;

/** Agent 配置的知识库选择方式。ALL 取当前可读集，SELECTED 取配置交集，NONE 固定为空。 */
public enum AgentKbSelectionMode {

    ALL("all"),
    SELECTED("selected"),
    NONE("none");

    private final String value;

    /**
     * 用注入依赖和构造参数建立此实例执行时使用的状态。
     *
     * @param value 本次调用传入的 {@code String}
     */
    AgentKbSelectionMode(String value) {
        this.value = value;
    }

    /**
     * 返回 API 与数据库使用的选库模式代码。
     *
     * @return 当前记录中的 {@code value}
     */
    public String value() {
        return value;
    }

    /**
     * 将输入的模式值转换成实际参与权限解析的选库策略。
     * 上游由 {@code AgentProfileService.resolveForExecution}、{@code AgentProfileService.validate} 调用。
     * 下游调用 {@code values}。
     *
     * @param value 本次调用传入的 {@code String}
     * @return 当前记录中的 {@code mode}
     */
    public static AgentKbSelectionMode from(String value) {
        for (AgentKbSelectionMode mode : values()) {
            if (mode.value.equals(value)) {
                return mode;
            }
        }
        throw new BusinessException("kbSelectionMode 只允许 all / selected / none");
    }
}
