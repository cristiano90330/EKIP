/* 覆盖不同长度和字符组成的文本令牌估算边界。 */

package com.pony.knowledge.service.rag.generation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TokenCounter#truncateToTokens} 与 {@link TokenCounter#count} 必须自洽：
 * 截断结果再计数绝不超预算（否则「硬预算」就是假的）。
 */
class TokenCounterTest {

    private static final String MIXED = "Qwen3-Embedding-4B 检索速度 2560 维，混合 abcdef 与中文。";

    /** 通过 {@code TokenCounter.count}、{@code TokenCounter.truncateToTokens} 驱动该场景，布尔状态符合断言。 */
    @Test
    void truncateNeverExceedsBudget() {
        for (int budget = 0; budget <= TokenCounter.count(MIXED) + 5; budget++) {
            String cut = TokenCounter.truncateToTokens(MIXED, budget);
            assertTrue(TokenCounter.count(cut) <= Math.max(budget, 1),
                    "budget=" + budget + " 截断后 count=" + TokenCounter.count(cut));
            assertTrue(MIXED.startsWith(cut), "截断必须是原文本前缀：budget=" + budget);
        }
    }

    /** 通过 {@code TokenCounter.truncateToTokens}、{@code TokenCounter.count} 驱动该场景，实际结果与固定预期一致。 */
    @Test
    void truncateKeepsWholeTextWhenBudgetIsEnough() {
        assertEquals(MIXED, TokenCounter.truncateToTokens(MIXED, TokenCounter.count(MIXED)));
        assertEquals(MIXED, TokenCounter.truncateToTokens(MIXED, 10000));
    }

    /** 通过 {@code TokenCounter.truncateToTokens} 驱动该场景，实际结果与固定预期一致。 */
    @Test
    void truncateDegradesGracefullyOnEmptyInput() {
        assertEquals("", TokenCounter.truncateToTokens(null, 10));
        assertEquals("", TokenCounter.truncateToTokens("", 10));
        assertEquals("", TokenCounter.truncateToTokens(MIXED, 0));
    }

    /** 通过 {@code TokenCounter.count} 驱动该场景，实际结果与固定预期一致。 */
    @Test
    void countMatchesExistingConventions() {
        assertEquals(5, TokenCounter.count("五个汉字啊"));           // 中文 1 字 1 token
        assertEquals(1, TokenCounter.count("abcd"));               // 拉丁 4 字符 1 token
        assertEquals(2, TokenCounter.count("abcdefg"));            // 拉丁向上取整：(7+3)/4
        assertEquals(1, TokenCounter.count("，，，"));              // 标点不计 token，但有下限 1
        assertEquals(0, TokenCounter.count(""));                   // 空串是 0（无下限）
    }
}
