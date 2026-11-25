/* 保存输入和输出令牌用量并计算总量。 */

package com.pony.knowledge.agent.llm;

import lombok.Builder;
import lombok.Data;

/**
 * 一次 LLM 调用的 token 用量（部分供应商不返回 usage 时按 0 计）。
 */
@Data
@Builder
public class TokenUsage {

    private int promptTokens;

    private int completionTokens;

    /**
     * 将模型输入与输出令牌数相加得到本次总量。
     *
     * @return 按当前方法口径计算或读取的数值
     */
    public int getTotal() {
        return promptTokens + completionTokens;
    }
}
