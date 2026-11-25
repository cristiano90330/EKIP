/* 从本轮用户消息中识别可提交审批的语言、格式和详略偏好。 */

package com.pony.knowledge.agent.memory.longterm;

import com.pony.knowledge.agent.tool.approval.MemorySaveApproval;

import java.util.regex.Pattern;

/** Extracts an exact, self-stated answer preference from the user's current message. */
public final class MemoryPreferenceExtractor {
    private static final Pattern CHINESE = Pattern.compile(
            "(?:(?:以后|今后|今后的回答)请?(?:用|使用)(?:中文|简体中文|繁体中文|英文|英语|表格|项目符号|编号列表)(?:来)?(?:回答|回复|呈现)|"
            + "(?:我更喜欢|我偏好)(?:简洁|简明|详细|分点|结构化|中文|英文)的?(?:回答|回复))");
    private static final Pattern ENGLISH = Pattern.compile(
            "(?:I prefer (?:brief|concise|detailed|structured) (?:answers|responses)|"
            + "(?:From now on,? please|In future,? please) (?:answer|respond) in "
            + "(?:English|Chinese|bullet points|a numbered list|a table))",
            Pattern.CASE_INSENSITIVE);

    /** 保留私有构造入口，以便通过静态方法使用此类型。 */
    private MemoryPreferenceExtractor() { }

    /**
     * 从明确的语言、格式或详略要求生成待确认的原文建议，没有命中时返回空值。
     * 上游由 {@code AgentService.executeTurn} 调用。
     * 下游调用 {@code message.split}、{@code CHINESE.matcher}、{@code ENGLISH.matcher}。
     *
     * @param message 待处理的消息或对外提示
     * @return 调用方使用的 {@code MemorySaveApproval.Proposal}
     */
    public static MemorySaveApproval.Proposal suggest(String message) {
        for (String sentence : message.split("[。！!？?\\r\\n]|\\.(?:\\s+|$)")) {
            String content = sentence.strip();
            if (CHINESE.matcher(content).matches() || ENGLISH.matcher(content).matches()) {
                return new MemorySaveApproval.Proposal(content, "PREFERENCE");
            }
        }
        return null;
    }
}
