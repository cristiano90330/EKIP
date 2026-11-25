/* 验证摘要折叠决策的令牌阈值、保留窗口和最少条数。 */

package com.pony.knowledge.agent.memory.summary;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SummaryPolicyTest {

    /** 通过 {@code policy.shouldSummarize} 驱动该场景，布尔状态符合断言。 */
    @Test
    void shouldSummarize_whenOverTokenAndFoldable() {
        SummaryPolicy policy = new SummaryPolicy(4, 2);
        assertTrue(policy.shouldSummarize(5, 3), "5 token>4 且 3 条>2 → 应压缩");
    }

    /** 通过 {@code policy.shouldSummarize} 驱动该场景，布尔状态符合断言。 */
    @Test
    void shouldNotSummarize_belowToken() {
        SummaryPolicy policy = new SummaryPolicy(4, 2);
        assertFalse(policy.shouldSummarize(3, 3), "3 token≤4 → 不压缩");
    }

    /** 通过 {@code policy.shouldSummarize} 驱动该场景，布尔状态符合断言。 */
    @Test
    void shouldNotSummarize_tooFewRecent() {
        SummaryPolicy policy = new SummaryPolicy(4, 2);
        assertFalse(policy.shouldSummarize(10, 2), "只有 2 条(≤retainRecent) → 不折");
    }

    /** 通过 {@code policy.foldCount} 驱动该场景，实际结果与固定预期一致。 */
    @Test
    void foldCount_excludesRetainedWindow() {
        SummaryPolicy policy = new SummaryPolicy(4, 2);
        assertEquals(3, policy.foldCount(5), "折 5-2=3 条最旧消息");
        assertEquals(0, policy.foldCount(2));
        assertEquals(0, policy.foldCount(1));
    }
}
