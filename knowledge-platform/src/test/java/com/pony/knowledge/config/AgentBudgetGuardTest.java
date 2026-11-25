/* 检查 Agent 启动时对上下文、工具输出与摘要阈值组合的校验。 */

package com.pony.knowledge.config;

import com.pony.knowledge.agent.context.ContextBudget;
import com.pony.knowledge.agent.memory.summary.SummaryPolicy;
import com.pony.knowledge.agent.runtime.ReactSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预算一致性守卫验收：参数组合错了就不许启动。
 *
 * <p>三条关系各自都「看起来合理」，但组合错了只在运行期表现为「证据被悄悄裁掉」或「整轮降级」，
 * 两者都不指向配置。这里把三个反例钉在启动期。
 */
class AgentBudgetGuardTest {

    /**
     * 构造 {@code ContextBudget} 供测试使用，使用 {@code ContextBudget.builder}。
     * 由 {@code consistentDefaults_pass}、{@code allowedTokens_isMaxMinusReserved} 调用。
     * @param max 本用例传入的{@code max}
     * @param out 本用例传入的{@code out}
     * @param tool 本用例传入的{@code tool}
     * 返回 {@code ContextBudget} 结果。
     */
    private static ContextBudget budget(int max, int out, int tool) {
        return ContextBudget.builder()
                .maxTokens(max).reservedOutputTokens(out).reservedToolTokens(tool).build();
    }

    /**
     * 构造 {@code SummaryPolicy} 供测试使用。
     * 由 {@code consistentDefaults_pass}、{@code toolOutputLargerThanAllowed_rejected} 调用。
     * @param maxTokens 本用例传入的{@code maxTokens}
     * 返回 {@code new SummaryPolicy(maxTokens, 6)}。
     */
    private static SummaryPolicy policy(int maxTokens) {
        return new SummaryPolicy(maxTokens, 6);
    }

    /**
     * 构造 {@code ReactSettings} 供测试使用。
     * 由 {@code consistentDefaults_pass}、{@code toolOutputLargerThanAllowed_rejected} 调用。
     * @param toolOutputMaxChars 本用例传入的{@code toolOutputMaxChars}
     * 返回 {@code new ReactSettings(true, 2, 3, toolOutputMaxChars, "")}。
     */
    private static ReactSettings react(int toolOutputMaxChars) {
        return new ReactSettings(true, 2, 3, toolOutputMaxChars, "");
    }

    /** 通过 {@code budget} 执行本例并核对输出或记录。 */
    @Test
    void consistentDefaults_pass() {
        AgentBudgetGuard guard = new AgentBudgetGuard(budget(8192, 1200, 400), policy(3000), react(4000));
        assertDoesNotThrow(guard::afterPropertiesSet);
    }

    /** 覆盖令牌的相关边界。通过 {@code org.junit.jupiter.api.Assertions.assertEquals} 执行本例并核对输出或记录。 */
    @Test
    void allowedTokens_isMaxMinusReserved() {
        org.junit.jupiter.api.Assertions.assertEquals(6592, budget(8192, 1200, 400).allowedTokens());
    }

    /** 断言抛出 {@code IllegalStateException}。调用路径经过 {@code e.getMessage}。 */
    @Test
    void toolOutputLargerThanAllowed_rejected() {
        // 单条工具结果永远装不进去 → 只能被整条裁掉，这就是「检索了但证据没进模型」的成因
        AgentBudgetGuard guard = new AgentBudgetGuard(budget(8192, 1200, 400), policy(3000), react(24000));

        IllegalStateException e = assertThrows(IllegalStateException.class, guard::afterPropertiesSet);
        assertTrue(e.getMessage().contains("tool-output-max-chars"), e.getMessage());
    }

    /** 断言抛出 {@code IllegalStateException}。调用路径经过 {@code e.getMessage}。 */
    @Test
    void summaryThresholdNotBelowAllowed_rejected() {
        // 摘要阈值 ≥ 可用提示词 → 装配期裁剪先于摘要触发，会话退化为滑动窗口
        AgentBudgetGuard guard = new AgentBudgetGuard(budget(8192, 1200, 400), policy(6592), react(4000));

        IllegalStateException e = assertThrows(IllegalStateException.class, guard::afterPropertiesSet);
        assertTrue(e.getMessage().contains("summary.max-tokens"), e.getMessage());
    }

    /** 断言抛出 {@code IllegalStateException}。调用路径经过 {@code e.getMessage}。 */
    @Test
    void reservedExceedsWindow_rejected() {
        AgentBudgetGuard guard = new AgentBudgetGuard(budget(1000, 1200, 400), policy(3000), react(4000));

        IllegalStateException e = assertThrows(IllegalStateException.class, guard::afterPropertiesSet);
        assertTrue(e.getMessage().contains("可用提示词为 0"), e.getMessage());
    }

    /** 断言抛出 {@code IllegalStateException}。调用路径经过 {@code e.getMessage}。 */
    @Test
    void allViolationsReportedTogether() {
        AgentBudgetGuard guard = new AgentBudgetGuard(budget(8192, 1200, 400), policy(9000), react(24000));

        IllegalStateException e = assertThrows(IllegalStateException.class, guard::afterPropertiesSet);
        assertTrue(e.getMessage().contains("tool-output-max-chars"), e.getMessage());
        assertTrue(e.getMessage().contains("summary.max-tokens"), e.getMessage());
    }
}
