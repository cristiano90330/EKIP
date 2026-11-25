/* 在 Java 中估算文本的令牌数并按令牌预算截断。 */

package com.pony.knowledge.service.rag.generation;

/**
 * 近似 token 计数：中文 1 字≈1 token（与项目切块约定一致），拉丁/数字按 4 字符≈1 token。
 * 与 Python 侧 {@code ai/app/llm/tokens.py} 的 count_tokens 对齐，保证前后端统计的 ratio 一致。
 */
public final class TokenCounter {

    /** 初始化 TokenCounter，供本文件定义的构建入口使用。 */
    private TokenCounter() {
    }

    /**
     * 估算文本由汉字、字母数字和标点构成的令牌用量。
     * 上游为 DefaultContextBuilder.shrink、AgentInputTokens.messages。
     *
     * @param text 待处理的文本
     * @return 估算得到的文本令牌数
     */
    public static int count(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int tokens = 0;
        int alphaRun = 0;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) {
                tokens += 1;
            } else if (Character.isLetterOrDigit(c)) {
                alphaRun++;
            } else {
                if (alphaRun > 0) {
                    tokens += (alphaRun + 3) / 4;
                    alphaRun = 0;
                }
            }
        }

        if (alphaRun > 0) {
            tokens += (alphaRun + 3) / 4;
        }
        return Math.max(tokens, 1);
    }

    /**
     * 按估算令牌成本裁截文本到允许预算。
     * 上游为 DefaultContextBuilder.shrink、SummaryCoordinator.degradedArchive。
     * 下游为 TokenCounter.runTokens。
     *
     * @param text 待处理的文本
     * @param maxTokens 允许使用的最大令牌数
     * @return 按估算令牌限制裁剪后的源文本片段
     */
    public static String truncateToTokens(String text, int maxTokens) {
        if (text == null || text.isEmpty() || maxTokens <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int closed = 0;      // 已闭合部分的 token（汉字 + 已结束的字母数字 run）
        int run = 0;         // 当前未闭合的字母数字 run 长度
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int nextClosed = closed;
            int nextRun = run;
            if (c >= 0x4E00 && c <= 0x9FFF) {
                nextClosed = closed + runTokens(run) + 1;   // 先闭合 run，再算这个汉字
                nextRun = 0;
            } else if (Character.isLetterOrDigit(c)) {
                nextRun = run + 1;
            } else {
                nextClosed = closed + runTokens(run);       // 闭合 run；标点本身不计 token
                nextRun = 0;
            }
            if (nextClosed + runTokens(nextRun) > maxTokens) {
                break;
            }
            closed = nextClosed;
            run = nextRun;
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * 把连续字母数字片段的长度换算成估算令牌数。
     * 上游为 TokenCounter.truncateToTokens。
     *
     * @param run 本方法计算或判断使用的 run 值
     * @return 单段连续字母数字的估算令牌数
     */
    private static int runTokens(int run) {
        return run == 0 ? 0 : (run + 3) / 4;
    }
}
