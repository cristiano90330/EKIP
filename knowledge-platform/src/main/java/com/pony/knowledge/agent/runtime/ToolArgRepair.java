/* 修补模型工具参数中常见的 JSON 围栏、尾逗号与闭合缺失。 */

package com.pony.knowledge.agent.runtime;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 工具参数 JSON 的有限修复（对应 WeKnora 的 {@code tools.RepairJSON}）。
 *
 * <p>只修三类**最常见且可判定**的模型手误，不做「猜字段」：
 * <ol>
 *   <li>模型把参数包在 Markdown 代码块里（{@code ```json … ```}）；</li>
 *   <li>对象/数组末尾多一个逗号（{@code {"a":1,}}）；</li>
 *   <li>输出在 JSON 中途被截断，括号不配对（补上缺少的 {@code }} / {@code ]}）。</li>
 * </ol>
 *
 * <p>刻意不做的事：单引号转双引号、缺引号的裸标识符、字段名纠错。
 * 那类「修复」会凭空造出模型没表达过的参数，等于让工具拿错输入执行——宁可直接失败，
 * 由模型收到错误提示后换一种方式重试。
 */
public final class ToolArgRepair {

    /** 保留私有构造入口，以便通过静态方法使用此类型。 */
    private ToolArgRepair() {
    }

    /**
     * 按顺序清理模型参数的代码围栏、尾逗号和缺失闭合符号，返回尝试修正后的 JSON。
     * 上游由 {@code ToolActExecutor.prepare} 调用。
     * 下游调用 {@code stripFences}、{@code stripTrailingCommas}、{@code balanceBrackets}。
     *
     * @param raw 尚待解析或归一化的原始值
     * @return 供后续处理使用的文本或标识
     */
    public static String repair(String raw) {
        if (raw == null) {
            return null;
        }
        String text = stripFences(raw.trim());
        if (text.isEmpty()) {
            return null;
        }
        text = stripTrailingCommas(text);
        text = balanceBrackets(text);
        return text.isEmpty() ? null : text;
    }

    /**
     * 移除模型在参数外包裹的 Markdown 代码围栏。
     * 上游由 {@code ToolArgRepair.repair} 调用。
     * 下游调用 {@code text.indexOf}、{@code body.lastIndexOf}。
     *
     * @param text 待处理正文
     * @return 供后续处理使用的文本或标识
     */
    private static String stripFences(String text) {
        if (!text.startsWith("```")) {
            return text;
        }
        int firstNewline = text.indexOf('\n');
        if (firstNewline < 0) {
            return text;
        }
        String body = text.substring(firstNewline + 1);
        int fence = body.lastIndexOf("```");
        return fence >= 0 ? body.substring(0, fence).trim() : body.trim();
    }

    /**
     * 移除对象或数组闭合前的尾逗号，保留字符串内同形字符。
     * 上游由 {@code ToolArgRepair.repair} 调用。
     * 下游调用 {@code text.length}、{@code text.charAt}、{@code out.append}。
     *
     * @param text 待处理正文
     * @return 供后续处理使用的文本或标识
     */
    private static String stripTrailingCommas(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                out.append(c);
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                out.append(c);
                continue;
            }
            if (c == ',' && nextMeaningfulIsCloser(text, i + 1)) {
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    /**
     * 从指定位置跳过空白后判断下一有效字符是否为对象或数组闭合符。
     * 上游由 {@code ToolArgRepair.stripTrailingCommas} 调用。
     * 下游调用 {@code text.length}、{@code text.charAt}、{@code Character.isWhitespace}。
     *
     * @param text 待处理正文
     * @param from 包含的起始位置
     * @return 条件成立时为 true，其他状态按 false 处理
     */
    private static boolean nextMeaningfulIsCloser(String text, int from) {
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            return c == '}' || c == ']';
        }
        // 逗号后面什么都没有：属于截断，交给括号补齐处理（补之前不能被逗号挡住）。
        return true;
    }

    /**
     * 跟踪文本中未闭合的数组或对象并补齐对应结束符，保留字符串内容。
     * 上游由 {@code ToolArgRepair.repair} 调用。
     * 下游调用 {@code text.length}、{@code text.charAt}、{@code stack.push}。
     *
     * @param text 待处理正文
     * @return 供后续处理使用的文本或标识
     */
    private static String balanceBrackets(String text) {
        Deque<Character> stack = new ArrayDeque<>();
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> inString = true;
                case '{' -> stack.push('}');
                case '[' -> stack.push(']');
                case '}', ']' -> {
                    if (!stack.isEmpty() && stack.peek() == c) {
                        stack.pop();
                    }
                }
                default -> {
                    // 其它字符与括号配对无关
                }
            }
        }
        if (stack.isEmpty() && !inString) {
            return text;
        }

        StringBuilder out = new StringBuilder(text);
        if (inString) {
            // 字符串被截断：先闭合引号，再补括号，否则补出来的仍是非法 JSON。
            out.append('"');
        }
        while (!stack.isEmpty()) {
            out.append(stack.pop());
        }

        return out.toString();
    }
}
