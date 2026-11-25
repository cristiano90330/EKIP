/* 基于服务商实际输入令牌平滑修正本地估算；可持久化校准样本。 */

package com.pony.knowledge.agent.llm;

import com.pony.knowledge.agent.memory.summary.TokenEstimator;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * token 估算系数的在线校准。
 *
 * <p>问题：{@link TokenEstimator} 用的是「中文 1 字 = 1 token」的保守近似，而 Qwen / DeepSeek 实际
 * 约 0.6 token/字。偏差不是「算错几十个 token」，而是**真实窗口还有 40% 余量时就开始裁剪**——
 * 在 6592 的可用提示词上等于白扔两千多 token。
 *
 * <p>做法：每次模型调用后，拿供应商返回的 {@code prompt_tokens}（权威值）除以我们自己的估算，
 * 得到本次比值，用指数滑动平均（α=0.3）修正系数。样本太短或比值离谱时**丢弃**：
 * <ul>
 *   <li>估算 &lt; {@link #MIN_SAMPLE_TOKENS}：固定开销占比过大，比值没有意义；</li>
 *   <li>比值落在 [{@value #MIN_RATIO}, {@value #MAX_RATIO}] 之外：多半是供应商缓存命中
 *       （prompt_tokens 只计未命中部分）或 usage 上报异常，拿去改系数会把口径带偏。</li>
 * </ul>
 *
 * <p>系数存 Redis（跨重启保留），读写失败只记日志不抛——校准是优化项，不该成为新的故障点。
 * 单模型部署下系数是进程级且全局的；多模型混用时需要改成按模型分桶（见 TokenEstimator 的说明）。
 */
@Component
public class TokenScaleCalibrator {

    private static final Logger log = LoggerFactory.getLogger(TokenScaleCalibrator.class);

    /** 可信样本的估算下限（token）：低于此值的提示词里固定开销占比过大。 */
    static final int MIN_SAMPLE_TOKENS = 256;

    /** 比值下限：低于它说明实际远小于估算，通常是缓存命中而非分词差异。 */
    static final double MIN_RATIO = 0.3;

    /** 比值上限：高于它说明实际远大于估算，通常是 usage 口径不同或估算漏了字段。 */
    static final double MAX_RATIO = 3.0;

    /** 平滑系数：单次观测只走 30%，避免一条异常样本把系数带飞。 */
    static final double SMOOTHING = 0.3;

    /** 变化小于此值就不落 Redis，省掉每轮一次写入。 */
    private static final double PERSIST_EPSILON = 0.005;

    /** Redis 键：与 Agent 记忆共用同一命名空间前缀，便于一起观察/清理。 */
    private static final String SCALE_KEY = "agent:memory:token-scale";

    private final StringRedisTemplate redis;

    private final AtomicLong observations = new AtomicLong();

    /**
     * 用注入依赖和构造参数建立此实例执行时使用的状态。
     * 下游调用 {@code TokenScaleCalibrator::scale}。
     *
     * @param redis 当前模块使用的 Redis 客户端
     * @param registry 本次调用传入的 {@code MeterRegistry}
     */
    public TokenScaleCalibrator(StringRedisTemplate redis, MeterRegistry registry) {
        this.redis = redis;
        Gauge.builder("agent.token.scale", this, TokenScaleCalibrator::scale)
                .description("token 估算校准系数（1.0 = 未校准；< 1 表示原估算偏高）")
                .register(registry);
    }

    /**
     * 在初始化阶段从持久化状态恢复先前有效的令牌校准值。
     * 下游调用 {@code redis.opsForValue}、{@code Double.parseDouble}、{@code TokenEstimator.scale}。
     */
    @PostConstruct
    void restore() {
        if (redis == null) {
            return;
        }
        try {
            String stored = redis.opsForValue().get(SCALE_KEY);
            if (stored != null && !stored.isBlank()) {
                TokenEstimator.setScale(Double.parseDouble(stored.trim()));
                log.info("token 估算系数已恢复：{}（来自 Redis {}）", TokenEstimator.scale(), SCALE_KEY);
            }
        } catch (Exception e) {
            // 读不到就按 1.0 起算：下一轮观测会重新学出来，不值得让启动失败。
            log.warn("token 估算系数读取失败，按 1.0 起算：{}", e.getMessage());
        }
    }

    /**
     * 读取当前用于将估算量换算到服务商口径的比例。
     * 上游由 {@code TokenScaleCalibrator.TokenScaleCalibrator} 调用。
     * 下游调用 {@code TokenEstimator.scale}。
     *
     * @return 按当前方法口径计算或读取的数值
     */
    public double scale() {
        return TokenEstimator.scale();
    }

    /**
     * 返回已接受的令牌用量校准样本数。
     *
     * @return 按当前方法口径计算或读取的数值
     */
    public long observations() {
        return observations.get();
    }

    /**
     * 过滤无效和过短样本，按估算与实际比值平滑更新校准比例。
     * 上游由 {@code DefaultAgentRuntime.calibrateTokenScale} 调用。
     * 下游调用 {@code TokenEstimator.scale}、{@code observations.incrementAndGet}、{@code Math.abs}。
     *
     * @param estimatedPromptTokens 本次调用传入的 {@code int}
     * @param actualPromptTokens 本次调用传入的 {@code int}
     */
    public void observe(int estimatedPromptTokens, int actualPromptTokens) {
        if (actualPromptTokens <= 0 || estimatedPromptTokens < MIN_SAMPLE_TOKENS) {
            return;
        }

        double ratio = (double) actualPromptTokens / estimatedPromptTokens;
        if (ratio < MIN_RATIO || ratio > MAX_RATIO) {
            log.debug("token 校准样本不可信，已丢弃：估算={} 实际={} 比值={}",
                    estimatedPromptTokens, actualPromptTokens, String.format("%.2f", ratio));
            return;
        }

        double current = TokenEstimator.scale();
        double next = current * (1 - SMOOTHING + SMOOTHING * ratio);
        if (Math.abs(next - current) < PERSIST_EPSILON) {
            return;
        }

        TokenEstimator.setScale(next);
        observations.incrementAndGet();
        persist(TokenEstimator.scale());
        log.debug("token 校准：估算={} 实际={} 比值={} 系数 {} → {}",
                estimatedPromptTokens, actualPromptTokens, String.format("%.2f", ratio),
                String.format("%.3f", current), String.format("%.3f", TokenEstimator.scale()));
    }

    /**
     * 将可继续使用的比例保存到 Redis，供重启后恢复。
     * 上游由 {@code TokenScaleCalibrator.observe} 调用。
     * 下游调用 {@code redis.opsForValue}。
     *
     * @param value 本次调用传入的 {@code double}
     */
    private void persist(double value) {
        if (redis == null) {
            return;
        }
        try {
            redis.opsForValue().set(SCALE_KEY, String.format("%.4f", value));
        } catch (Exception e) {
            log.warn("token 估算系数写入失败（不影响本轮）：{}", e.getMessage());
        }
    }
}
