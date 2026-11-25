/* 把执行过的工具调用裁剪成可安全保存的有限历史条目。 */

package com.pony.knowledge.agent.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 把一回合的工具调用记录压成可入史的「一行一步」。
 *
 * <p>存在的理由：{@code AgentResponse.toolInvocations} 是**回合内**的观测数据，回合结束即随响应
 * 丢弃；而会话历史只存了 user/assistant 两条文本，于是下一轮的模型看不到「上一轮查过什么」，
 * 跨轮追问只能重新检索或凭结论编造。这里把最小必要信息（工具名 / 成败 / 一行摘要）
 * 挂到 assistant 消息上，代价约为几十个 token 一条。
 *
 * <p>三条硬约束，都是为了不让历史变成第二个上下文预算黑洞：
 * <ul>
 *   <li><b>条数上限</b> {@link #MAX_STEPS}：一回合最多 10 次工具调用，全存会让历史线性膨胀；</li>
 *   <li><b>摘要长度</b> {@link #SUMMARY_CHARS}：工具原始输出可达上万字符，截断后才可能长期驻留；</li>
 *   <li><b>失败也要留痕</b>：只记成功的调用会让模型以为「没查到 = 不存在」，
 *       而实际可能是工具报错（超时、权限、参数错）——这两件事对下一轮的决策完全不同。</li>
 * </ul>
 */
public final class ToolSteps {

    /** 一回合最多入史的工具步骤数（超出部分只保留前 N 条，越靠前越接近用户原始意图）。 */
    static final int MAX_STEPS = 6;

    /** 单步摘要的字符上限（按 code point 计，中文不致被切成半个字）。 */
    static final int SUMMARY_CHARS = 120;

    /** 保留私有构造入口，以便通过静态方法使用此类型。 */
    private ToolSteps() {
    }

    /**
     * 把本轮工具调用转成可保存的步骤摘要，并限制历史条数与摘要长度。
     * 上游由 {@code AgentService.executeTurn}、{@code AgentSessionService.complete} 调用。
     * 下游调用 {@code inv.isSuccess}、{@code cut}。
     *
     * @param invocations 本次调用传入的 {@code List<AgentResponse.ToolInvocation>}
     * @return 调用方使用的 {@code List<ToolStep>}
     */
    public static List<ToolStep> from(List<AgentResponse.ToolInvocation> invocations) {
        if (invocations == null || invocations.isEmpty()) {
            return List.of();
        }
        List<ToolStep> steps = new ArrayList<>(Math.min(invocations.size(), MAX_STEPS));
        for (AgentResponse.ToolInvocation inv : invocations) {
            if (steps.size() >= MAX_STEPS) {
                break;
            }
            steps.add(ToolStep.builder()
                    .tool(inv.getToolName())
                    .ok(inv.isSuccess())
                    .outcome(inv.getOutcome())
                    .reasonCode(inv.getReasonCode())
                    .summary(cut(inv.isSuccess() ? inv.getDataSummary() : inv.getErrorMessage()))
                    .build());
        }
        return steps;
    }

    /**
     * 按 Unicode 码点裁减展示文字，保持末尾字符完整。
     * 上游由 {@code ToolSteps.from} 调用。
     * 下游调用 {@code trimmed.codePointCount}、{@code trimmed.length}、{@code trimmed.offsetByCodePoints}。
     *
     * @param text 待处理正文
     * @return 供后续处理使用的文本或标识
     */
    private static String cut(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String trimmed = text.strip();
        int length = trimmed.codePointCount(0, trimmed.length());
        if (length <= SUMMARY_CHARS) {
            return trimmed;
        }
        int end = trimmed.offsetByCodePoints(0, SUMMARY_CHARS);
        return trimmed.substring(0, end) + "…";
    }
}
