/* 保存模型窗口扣除输出预留后的上下文预算。 */

package com.pony.knowledge.agent.context;

import lombok.Builder;
import lombok.Data;

/**
 * 上下文 token 预算。
 *
 * <p>{@code maxTokens} 是「提示词 + 生成 + 工具调用」三者共用的那一个窗口，
 * 而不是「提示词自身」的额度——这一点决定了各级预算之间的对齐关系：
 * <pre>
 *   maxTokens ≈ 模型可用的上下文窗口
 *   可用提示词 = maxTokens - reservedOutputTokens - reservedToolTokens
 *   工具结果上限 ≤ 可用提示词（否则单条结果永远装不进去，只能被整条丢弃）
 *   摘要阈值 &lt; 可用提示词（否则装配期裁剪先于摘要触发，会话退化为滑动窗口）
 * </pre>
 *
 * <p>这三条关系由 {@code AgentBudgetGuard} 在启动时校验，不做运行时静默容忍：
 * 参数一旦互相矛盾，症状是「证据被悄悄裁掉」或「整轮降级」，两者都不报错。
 */
@Data
@Builder
public class ContextBudget {

    private int maxTokens;

    private int reservedOutputTokens;

    private int reservedToolTokens;

    /**
     * 从模型总窗口扣除输出预留量，计算调用方可传入消息的上限。
     * 上游由 {@code DefaultContextBuilder.build} 调用。
     *
     * @return 按当前方法口径计算或读取的数值
     */
    public int allowedTokens() {
        return Math.max(0, maxTokens - reservedOutputTokens - reservedToolTokens);
    }
}
