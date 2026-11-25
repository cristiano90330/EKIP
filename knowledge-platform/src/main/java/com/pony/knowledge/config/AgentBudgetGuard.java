/* 核对 Agent 上下文、摘要和 ReAct 预算配置的组合约束。 */

package com.pony.knowledge.config;

import com.pony.knowledge.agent.context.ContextBudget;
import com.pony.knowledge.agent.memory.summary.SummaryPolicy;
import com.pony.knowledge.agent.runtime.ReactSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 预算一致性守卫：启动时校验各级预算之间的关系，不满足直接拒绝启动。
 *
 * <p>为什么值得 fail-fast：这三个参数各自都有「合理」的取值范围，但组合错了不会报错——
 * 症状是证据被悄悄裁掉、或整轮被判超预算降级，而两种症状都不指向配置。把它们的关系写成
 * 启动期断言，比事后从「回答变差」倒查便宜得多。
 *
 * <p>校验的三条关系（数值来源见 application.yml 的 agent.context / agent.memory.summary /
 * agent.react 三段）：
 * <ol>
 *   <li>{@code max-tokens > reservedOutput + reservedTool}：否则可用提示词为 0，装配必空；</li>
 *   <li>{@code 工具结果上限 ≤ 可用提示词}：否则单条工具结果永远装不进上下文，只能被整条丢弃；</li>
 *   <li>{@code 摘要阈值 < 可用提示词}：否则装配期裁剪总是先于摘要触发，会话退化成滑动窗口。</li>
 * </ol>
 *
 * <p>工具结果上限的单位是字符，预算的单位是 token。这里按「1 字符 = 1 token」比较——
 * 中文正好如此（{@code TokenCounter} 的口径），拉丁文偏保守。保守方向是对的：
 * 它保证最坏情况下单条结果仍装得下。
 *
 * <p>还有一处本类无法校验：AI 服务的 {@code AGENT_LLM_TOKEN_BUDGET}（Python 侧硬护栏）。
 * 它应当等于 {@code max-tokens}——超过模型窗口的提示词本来也处理不了，护栏在这里触发即可。
 * 该值在 ai/.env 里，Java 读不到，只能由配置注释与 README 约定。
 */
@Component
public class AgentBudgetGuard implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(AgentBudgetGuard.class);

    private final ContextBudget contextBudget;

    private final SummaryPolicy summaryPolicy;

    private final ReactSettings reactSettings;

    /**
     * 保存核对 Agent 上下文、摘要和 ReAct 预算配置的组合约束所需的依赖和初始配置。
     * 上游为 Spring 组件装配。
     *
     * @param contextBudget 本次操作使用的 ContextBudget 组件或记录
     * @param summaryPolicy 本次操作使用的 SummaryPolicy 组件或记录
     * @param reactSettings 本次操作使用的 ReactSettings 组件或记录
     */
    public AgentBudgetGuard(ContextBudget contextBudget, SummaryPolicy summaryPolicy,
                            ReactSettings reactSettings) {
        this.contextBudget = contextBudget;
        this.summaryPolicy = summaryPolicy;
        this.reactSettings = reactSettings;
    }

    /**
     * 在组件初始化时检查摘要、上下文与 Agent 执行预算的组合是否有效。
     * 下游为 ContextBudget.allowedTokens。
     */
    @Override
    public void afterPropertiesSet() {
        int allowed = contextBudget.allowedTokens();
        int toolChars = reactSettings.getToolOutputMaxChars();
        int summaryTokens = summaryPolicy.getMaxTokens();

        List<String> violations = new ArrayList<>();

        if (allowed <= 0) {
            violations.add(("agent.context.max-tokens=%d 减去 reserved-output-tokens=%d 与 "
                    + "reserved-tool-tokens=%d 后可用提示词为 0")
                    .formatted(contextBudget.getMaxTokens(), contextBudget.getReservedOutputTokens(),
                            contextBudget.getReservedToolTokens()));
        }
        if (toolChars > allowed) {
            violations.add(("agent.react.tool-output-max-chars=%d 超过可用提示词预算 %d："
                    + "单条工具结果装不进上下文，会被整条裁掉")
                    .formatted(toolChars, allowed));
        }
        if (summaryTokens >= allowed) {
            violations.add(("agent.memory.summary.max-tokens=%d 不低于可用提示词预算 %d："
                    + "装配期裁剪会先于摘要触发，会话退化为滑动窗口")
                    .formatted(summaryTokens, allowed));
        }
        if (summaryPolicy.getRetainRecent() < 1) {
            violations.add("agent.memory.summary.retain-recent 必须 >= 1");
        }

        if (!violations.isEmpty()) {
            throw new IllegalStateException("Agent 预算配置不一致，拒绝启动：\n  - "
                    + String.join("\n  - ", violations));
        }

        log.info("Agent 预算自洽：窗口 {} = 提示词 {} + 生成 {} + 工具 {}；工具结果上限 {} 字符；摘要阈值 {}",
                contextBudget.getMaxTokens(), allowed, contextBudget.getReservedOutputTokens(),
                contextBudget.getReservedToolTokens(), toolChars, summaryTokens);
    }
}
