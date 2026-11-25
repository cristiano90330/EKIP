/* 验证中文、拉丁文和多条消息的令牌估算。 */

package com.pony.knowledge.agent.memory.summary;

import com.pony.knowledge.agent.model.AgentMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TokenEstimatorTest {

    /** 通过 {@code TokenEstimator.estimate} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void estimate_chineseCharPerToken() {
        assertEquals(2, TokenEstimator.estimate("你好"), "中文 1 字≈1 token");
        assertEquals(7, TokenEstimator.estimate("企业知识库助手"));
    }

    /** 通过 {@code TokenEstimator.estimate} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void estimate_latinFourCharsPerToken() {
        assertEquals(2, TokenEstimator.estimate("hello"), "5 个拉丁字符≈(5+3)/4=2 token");
    }

    /** 通过 {@code TokenEstimator.estimateMessages} 驱动该场景，实际结果与固定预期一致。 */
    @Test
    void estimateMessages_sumsContentTokens() {
        List<AgentMessage> msgs = List.of(
                AgentMessage.builder().role("user").content("你好").build(),
                AgentMessage.builder().role("assistant").content("企业").build());
        assertEquals(4, TokenEstimator.estimateMessages(msgs));
    }
}
