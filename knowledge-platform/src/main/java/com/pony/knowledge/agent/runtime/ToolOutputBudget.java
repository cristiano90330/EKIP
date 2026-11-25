/* 限制传回模型的工具输出长度，同时保留可识别的截断提示。 */

package com.pony.knowledge.agent.runtime;

/**
 * 工具结果文本预算：超过上限时保留头部 70% + 尾部 30%，中间用标记替代。
 *
 * <p>为什么不是简单截断：工具结果的结构是「开头是摘要/命中列表，结尾是结论/最后几条」，
 * 掐掉尾巴会丢掉最关键的收尾部分。按字符（code point）而不是字节计算，
 * 中文与 emoji 才不会被切一半。
 *
 * <p>为什么放在 Observe 而不是工具内部：工具输出本身要完整（响应与断言可能用到），
 * 只有「喂回模型的那份文本」需要受预算约束。
 */
public final class ToolOutputBudget {

    /** 截断标记自身预留的字符数。 */
    private static final int MARKER_RESERVE = 160;

    /** 头部占比。 */
    private static final double HEAD_RATIO = 0.7;

    /** 保留私有构造入口，以便通过静态方法使用此类型。 */
    private ToolOutputBudget() {
    }

    /**
     * 按输出限制对工具文本做有界裁减，并附带截断提示。
     * 上游由 {@code DefaultAgentRuntime.appendToolResults}、{@code DefaultAgentRuntime.toToolResultText}、{@code DefaultAgentRuntime.toolResultPayload} 调用。
     * 下游调用 {@code text.codePointCount}、{@code text.length}、{@code cut}。
     *
     * @param text 待处理正文
     * @param maxChars 允许使用的最大正文字符数
     * @return 供后续处理使用的文本或标识
     */
    public static String truncate(String text, int maxChars) {
        if (text == null || maxChars <= 0) {
            return text;
        }

        int length = text.codePointCount(0, text.length());
        if (length <= maxChars) {
            return text;
        }

        int usable = maxChars - MARKER_RESERVE;
        if (usable <= 0) {
            return cut(text, 0, maxChars) + "\n\n…[输出超限，已截断]…";
        }

        int head = (int) (usable * HEAD_RATIO);
        int tail = usable - head;
        String marker = "\n\n…[output truncated: " + length + " → " + maxChars
                + " chars, showing first " + head + " + last " + tail + "]…\n\n";
        return cut(text, 0, head) + marker + cut(text, length - tail, tail);
    }

    /**
     * 从指定码点位置截取若干码点长度的文本，避免拆分代理对。
     * 上游由 {@code ToolOutputBudget.truncate} 调用。
     * 下游调用 {@code text.codePointCount}、{@code text.length}、{@code text.offsetByCodePoints}。
     *
     * @param text 待处理正文
     * @param startCodePoints 本次调用传入的 {@code int}
     * @param count 当前批次涉及的条目数量
     * @return 供后续处理使用的文本或标识
     */
    private static String cut(String text, int startCodePoints, int count) {
        int length = text.codePointCount(0, text.length());
        int from = text.offsetByCodePoints(0, Math.max(0, startCodePoints));
        int to = text.offsetByCodePoints(from, Math.max(0, Math.min(count, length - startCodePoints)));
        return text.substring(from, to);
    }
}
