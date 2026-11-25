/* 结合保留窗口和令牌阈值计算是否整理会话历史以及本次折叠条数。 */

package com.pony.knowledge.agent.memory.summary;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 摘要策略：何时压缩、一次折多少条。
 *   - maxTokens：会话 token 估算超过该值则进入压缩候选
 *   - retainRecent：压缩后保留的最近消息条数（Sliding Window 的窗口大小），多余的最旧消息被折入摘要
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SummaryPolicy {

    private int maxTokens;

    private int retainRecent;

    /**
     * 在已有消息超过保留窗口且令牌数量超过阈值时触发折叠。
     * 上游由 {@code SummaryCoordinator.summarizeGuarded} 调用。
     *
     * @param currentTokens 当前会话的输入令牌估算
     * @param messageCount 可参加预算判断的会话消息条数
     * @return 条件成立时为 true，其他状态按 false 处理
     */
    public boolean shouldSummarize(int currentTokens, int messageCount) {
        return messageCount > retainRecent && currentTokens > maxTokens;
    }

    /**
     * 从消息总量扣除近期保留窗口，计算本次允许折叠的旧消息条数。
     *
     * @param messageCount 可参加预算判断的会话消息条数
     * @return 按当前方法口径计算或读取的数值
     */
    public int foldCount(int messageCount) {
        return Math.max(0, messageCount - retainRecent);
    }
}
