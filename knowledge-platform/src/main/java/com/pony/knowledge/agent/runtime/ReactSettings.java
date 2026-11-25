/* 解析 Agent 运行时的结束回退、空答复重试和并发屏障设置。 */

package com.pony.knowledge.agent.runtime;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * ReAct 护栏参数（全部来自 {@code agent.react.*}，改档位只改 yml）。
 *
 * <p>单独成类而不是往 {@code DefaultAgentRuntime} 构造器里再加 5 个 {@code @Value}：
 * 既有测试用的是 7/11/12 参构造器，参数一多就会退化成「改一个默认值要改一堆测试」。
 * 这里给 {@link #defaults()} 一份与 yml 默认值一致的快照，测试与直连调用直接用它。
 */
@Component
public class ReactSettings {

    /** 是否在轮次/预算用尽时用已有工具结果合成答案。 */
    private final boolean finalizeOnExhaust;

    /** 空回复允许 nudge 的次数。 */
    private final int maxEmptyRetries;

    /** 连续在输出上限处被截断的轮次上限。 */
    private final int maxConsecutiveTruncatedRounds;

    /**
     * 单个工具结果喂回模型时的字符上限（code point 计）。
     *
     * <p>必须 ≤ 上下文可用提示词预算，否则单条结果永远装不进去（{@code AgentBudgetGuard} 会在启动时拦住）。
     */
    private final int toolOutputMaxChars;

    /** 显式登记为屏障的工具名（可写工具另有 requiresApproval 判据，不靠这里）。 */
    private final Set<String> barrierTools;

    /**
     * 用注入依赖和构造参数建立此实例执行时使用的状态。
     * 下游调用 {@code parse}。
     *
     * @param finalizeOnExhaust 本次调用传入的 {@code boolean}
     * @param maxEmptyRetries 本次调用传入的 {@code int}
     * @param maxConsecutiveTruncatedRounds 本次调用传入的 {@code int}
     * @param toolOutputMaxChars 本次调用传入的 {@code int}
     * @param barrierTools 本次调用传入的 {@code String}
     */
    @Autowired
    public ReactSettings(
            @Value("${agent.react.finalize-on-exhaust:true}") boolean finalizeOnExhaust,
            @Value("${agent.react.max-empty-retries:2}") int maxEmptyRetries,
            @Value("${agent.react.max-consecutive-truncated-rounds:3}") int maxConsecutiveTruncatedRounds,
            @Value("${agent.react.tool-output-max-chars:4000}") int toolOutputMaxChars,
            @Value("${agent.react.barrier-tools:}") String barrierTools) {
        this.finalizeOnExhaust = finalizeOnExhaust;
        this.maxEmptyRetries = Math.max(0, maxEmptyRetries);
        this.maxConsecutiveTruncatedRounds = Math.max(1, maxConsecutiveTruncatedRounds);
        this.toolOutputMaxChars = toolOutputMaxChars;
        this.barrierTools = parse(barrierTools);
    }

    /**
     * 用注入依赖和构造参数建立此实例执行时使用的状态。
     *
     * @param finalizeOnExhaust 本次调用传入的 {@code boolean}
     * @param maxEmptyRetries 本次调用传入的 {@code int}
     * @param maxConsecutiveTruncatedRounds 本次调用传入的 {@code int}
     * @param toolOutputMaxChars 本次调用传入的 {@code int}
     * @param barrierTools 本次调用传入的 {@code Set<String>}
     */
    private ReactSettings(boolean finalizeOnExhaust, int maxEmptyRetries,
                          int maxConsecutiveTruncatedRounds, int toolOutputMaxChars,
                          Set<String> barrierTools) {
        this.finalizeOnExhaust = finalizeOnExhaust;
        this.maxEmptyRetries = maxEmptyRetries;
        this.maxConsecutiveTruncatedRounds = maxConsecutiveTruncatedRounds;
        this.toolOutputMaxChars = toolOutputMaxChars;
        this.barrierTools = barrierTools;
    }

    /**
     * 构造 Agent 默认循环配置，供直接使用和替身环境调用。
     * 上游由 {@code DefaultAgentRuntime.DefaultAgentRuntime} 调用。
     *
     * @return 调用方使用的 {@code ReactSettings}
     */
    public static ReactSettings defaults() {
        return new ReactSettings(true, 2, 3, 4000, Set.of());
    }

    /**
     * 将配置中分隔的工具名解析为用于约束并发批次的集合。
     * 上游由 {@code ReactSettings.ReactSettings} 调用。
     * 下游调用 {@code raw.split}。
     *
     * @param raw 尚待解析或归一化的原始值
     * @return 调用方使用的 {@code Set<String>}
     */
    private static Set<String> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        Set<String> names = new LinkedHashSet<>();
        Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .forEach(names::add);
        return Set.copyOf(names);
    }

    /**
     * 检查当前输入是否满足该条件。
     * 上游由 {@code DefaultAgentRuntime.finalizeTurn} 调用。
     * @return 当前记录中的 {@code finalizeOnExhaust}
     */
    public boolean isFinalizeOnExhaust() {
        return finalizeOnExhaust;
    }

    /**
     * 返回当前记录保存的 {@code maxEmptyRetries}。
     * 上游由 {@code DefaultAgentRuntime.handleEmptyContent} 调用。
     */
    public int getMaxEmptyRetries() {
        return maxEmptyRetries;
    }

    /**
     * 返回当前记录保存的 {@code maxConsecutiveTruncatedRounds}。
     * 上游由 {@code DefaultAgentRuntime.guardsTripped} 调用。
     */
    public int getMaxConsecutiveTruncatedRounds() {
        return maxConsecutiveTruncatedRounds;
    }

    /**
     * 返回当前记录保存的 {@code toolOutputMaxChars}。
     * 上游由 {@code DefaultAgentRuntime.appendToolResults}、{@code DefaultAgentRuntime.toToolResultText} 调用。
     */
    public int getToolOutputMaxChars() {
        return toolOutputMaxChars;
    }

    /**
     * 返回当前记录保存的 {@code barrierTools}。
     * 上游由 {@code DefaultAgentRuntime.DefaultAgentRuntime} 调用。
     */
    public Set<String> getBarrierTools() {
        return barrierTools;
    }
}
