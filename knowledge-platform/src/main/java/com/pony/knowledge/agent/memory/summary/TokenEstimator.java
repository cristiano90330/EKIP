/* 按文本字符估算令牌数量，支持提供商用量校准。 */

package com.pony.knowledge.agent.memory.summary;

import com.pony.knowledge.agent.model.AgentMessage;
import com.pony.knowledge.service.rag.generation.TokenCounter;

import java.util.List;

/**
 * 会话 token 估算：复用 RAG 的 TokenCounter（中文 1 字≈1 token，4 字符≈1 token），
 * 与 Python 侧 {@code ai/app/llm/tokens.py} 的 count_tokens 口径一致。
 *
 * <p>在本口径之上再乘一个**校准系数**：{@link TokenCounter} 的中文「1 字 1 token」是保守近似，
 * 而 Qwen / DeepSeek 这类模型实测约 0.6 token/字——不作校准就会在真实窗口还有余量时就开始裁剪，
 * 在本项目的预算量级（数千 token）上等于白扔几百 token 的可用上下文。
 *
 * <p>系数由 {@code TokenScaleCalibrator} 用「供应商返回的 prompt_tokens ÷ 本类估算值」在线学习，
 * 存 Redis 跨重启保留。本类是纯静态的（ContextBuilder 与 SummaryCoordinator 都这样使用），
 * 因此系数是**进程级**的：单模型部署下这就是正确粒度；将来若同时接多个不同分词器的模型，
 * 这里需要改成按模型分桶。
 */
public final class TokenEstimator {

    /** 系数下限：低于它说明分母口径错得离谱，不是校准能修的问题。 */
    public static final double MIN_SCALE = 0.5;

    /** 系数上限：高于它同理（且会让我们倾向于超额发送）。 */
    public static final double MAX_SCALE = 1.5;

    private static volatile double scale = 1.0;

    /** 保留私有构造入口，以便通过静态方法使用此类型。 */
    private TokenEstimator() {
    }

    /**
     * 读出当前估算使用的校准比例。
     * 上游由 {@code TokenScaleCalibrator.observe}、{@code TokenScaleCalibrator.restore}、{@code TokenScaleCalibrator.scale} 调用。
     *
     * @return 当前记录中的 {@code scale}
     */
    public static double scale() {
        return scale;
    }

    /**
     * 设置可用的正数比例，供后续令牌估算按模型口径调整。
     * 上游由 {@code TokenScaleCalibrator.observe}、{@code TokenScaleCalibrator.restore} 调用。
     * 下游调用 {@code Double.isNaN}。
     *
     * @param value 本次调用传入的 {@code double}
     */
    public static void setScale(double value) {
        if (value <= 0 || Double.isNaN(value)) {
            scale = 1.0;
            return;
        }
        scale = Math.min(Math.max(value, MIN_SCALE), MAX_SCALE);
    }

    /**
     * 先估计给定正文的原始令牌量，再应用当前校准比例。
     * 上游由 {@code DefaultAgentRuntime.toolSchemaTokens}、{@code DefaultContextBuilder.longTermBlock}、{@code DefaultContextBuilder.shrink} 调用。
     * 下游调用 {@code TokenCounter.count}、{@code apply}。
     *
     * @param text 待处理正文
     * @return 按当前方法口径计算或读取的数值
     */
    public static int estimate(String text) {
        return apply(TokenCounter.count(text));
    }

    /**
     * 汇总一组消息的令牌估算并按配置比例对齐。
     * 上游由 {@code DefaultAgentRuntime.calibrateTokenScale}、{@code DefaultAgentRuntime.squeezeContext}、{@code SummaryCoordinator.doIncrementalSummarize} 调用。
     * 下游调用 {@code estimate}。
     *
     * @param messages 按照交互顺序提供的消息列表
     * @return 按当前方法口径计算或读取的数值
     */
    public static int estimateMessages(List<AgentMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (AgentMessage m : messages) {
            if (m.getContent() != null) {
                total += estimate(m.getContent());
            }
        }
        return total;
    }

    /**
     * 按字符类型估计文本的未校准令牌量。
     * 下游调用 {@code TokenCounter.count}。
     *
     * @param text 待处理正文
     * @return 按当前方法口径计算或读取的数值
     */
    public static int raw(String text) {
        return TokenCounter.count(text);
    }

    /**
     * 把本地原始估算换算为校准后的整数令牌数。
     * 上游由 {@code TokenEstimator.estimate} 调用。
     * 下游调用 {@code Math.round}。
     *
     * @param rawTokens 本次调用传入的 {@code int}
     * @return 按当前方法口径计算或读取的数值
     */
    private static int apply(int rawTokens) {
        double current = scale;
        if (current == 1.0 || rawTokens <= 0) {
            return rawTokens;
        }
        return Math.max(1, (int) Math.round(rawTokens * current));
    }
}
