/* 验证令牌校准的输入过滤、比例边界、平滑和无 Redis 降级。 */

package com.pony.knowledge.agent.llm;

import com.pony.knowledge.agent.memory.summary.TokenEstimator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * token 估算校准（P2-2）。
 *
 * <p>这些用例守的是「校准只吸收可信样本」：样本太短、比值离谱（多半是供应商缓存命中）时必须丢弃，
 * 否则一条异常样本会把系数带飞，而系数错了不会报错——症状是「窗口还有余量就开始裁证据」。
 */
class TokenScaleCalibratorTest {

    private TokenScaleCalibrator calibrator;

    /** 准备本组用例共享的测试数据和隔离环境。 */
    @BeforeEach
    void setUp() {
        TokenEstimator.setScale(1.0);
        calibrator = new TokenScaleCalibrator(null, new SimpleMeterRegistry());
    }

    /** 在用例结束后释放该用例创建的资源。 */
    @AfterEach
    void tearDown() {
        TokenEstimator.setScale(1.0);       // 静态状态必须复位，否则污染同一 JVM 的其它用例
    }

    /** 通过 {@code TokenEstimator.scale}、{@code calibrator.scale} 驱动该场景，实际结果与固定预期一致。 */
    @Test
    void uncalibrated_isOne() {
        assertEquals(1.0, TokenEstimator.scale());
        assertEquals(1.0, calibrator.scale());
    }

    /** 通过 {@code calibrator.observe}、{@code TokenEstimator.scale} 驱动该场景，实际结果与固定预期一致。 */
    @Test
    void shortSample_isIgnored() {
        calibrator.observe(TokenScaleCalibrator.MIN_SAMPLE_TOKENS - 1, 100);

        assertEquals(1.0, TokenEstimator.scale(), "样本太短时固定开销占比过大，比值没有意义");
        assertEquals(0, calibrator.observations());
    }

    /** 通过 {@code calibrator.observe}、{@code TokenEstimator.scale} 驱动该场景，实际结果与固定预期一致。 */
    @Test
    void outOfRangeRatio_isIgnored() {
        // 实际远大于估算：多为 usage 口径不同，拿去改系数会把口径带偏
        calibrator.observe(1000, 5000);
        assertEquals(1.0, TokenEstimator.scale());
        assertEquals(0, calibrator.observations());

        // 实际远小于估算：多为供应商缓存命中（prompt_tokens 只计未命中部分）
        calibrator.observe(1000, 100);
        assertEquals(1.0, TokenEstimator.scale());
    }

    /** 通过 {@code calibrator.observe}、{@code TokenEstimator.scale} 驱动该场景，实际结果与固定预期一致。 */
    @Test
    void missingUsage_isIgnored() {
        calibrator.observe(4000, 0);
        assertEquals(1.0, TokenEstimator.scale(), "没返回 usage 就没有比值，任何猜测都会污染系数");
    }

    /** 通过 {@code calibrator.observe}、{@code TokenEstimator.scale} 驱动该场景，布尔状态符合断言，实际结果与固定预期一致。 */
    @Test
    void convergesTowardObservedRatio_withSmoothing() {
        // 比值 0.6（中文实测约 0.6 token/字）：一次观测只走 30%
        calibrator.observe(1000, 600);

        double expected = 1.0 * (1 - TokenScaleCalibrator.SMOOTHING
                + TokenScaleCalibrator.SMOOTHING * 0.6);
        assertEquals(expected, TokenEstimator.scale(), 1e-9);
        assertEquals(1, calibrator.observations());

        // 真实调用方传进来的估算值本身已乘过系数：系数越低，同一个提示词估出来越小、
        // 实测比值（actual/estimated）就越大，两者在「系数 = 真实比值」处达到平衡。
        for (int i = 0; i < 40; i++) {
            calibrator.observe((int) Math.round(1000 * TokenEstimator.scale()), 600);
        }
        assertTrue(Math.abs(TokenEstimator.scale() - 0.6) < 0.02,
                "多次观测后应收敛到实测比值，实际=" + TokenEstimator.scale());
    }

    /** 通过 {@code calibrator.observe}、{@code TokenEstimator.scale} 驱动该场景，实际结果与固定预期一致。 */
    @Test
    void clampsToConfiguredBounds() {
        for (int i = 0; i < 60; i++) {
            calibrator.observe(1000, 350);       // 比值 0.35，反复拉低
        }
        assertEquals(TokenEstimator.MIN_SCALE, TokenEstimator.scale(), 1e-9);

        TokenEstimator.setScale(1.0);
        for (int i = 0; i < 60; i++) {
            calibrator.observe(1000, 2900);      // 比值 2.9，反复拉高
        }
        assertEquals(TokenEstimator.MAX_SCALE, TokenEstimator.scale(), 1e-9);
    }

    /** 通过 {@code TokenEstimator.scale} 驱动该场景，实际结果与固定预期一致。 */
    @Test
    void invalidScaleValues_fallBackToOne() {
        TokenEstimator.setScale(0);
        assertEquals(1.0, TokenEstimator.scale());

        TokenEstimator.setScale(Double.NaN);
        assertEquals(1.0, TokenEstimator.scale());

        TokenEstimator.setScale(9.9);
        assertEquals(TokenEstimator.MAX_SCALE, TokenEstimator.scale(), "越界值夹到上限而不是丢弃");
    }

    /** 通过 {@code TokenEstimator.estimate} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void scaleAppliesToEstimate() {
        String text = "这是一段中文文本";                  // 8 字 = 原始 8 token
        int raw = TokenEstimator.estimate(text);
        assertEquals(8, raw);

        TokenEstimator.setScale(0.5);
        assertEquals(4, TokenEstimator.estimate(text), "系数 0.5 → 估算减半");
        assertEquals(8, TokenEstimator.raw(text), "raw 不做换算，供按原始口径截断的路径使用");
    }

    /** 通过 {@code calibrator.restore}、{@code calibrator.observe} 驱动该场景，实际结果与固定预期一致。 */
    @Test
    void withoutRedis_doesNotThrow() {
        calibrator.restore();
        calibrator.observe(1000, 600);
        assertEquals(1, calibrator.observations(), "没有 Redis 也照常学习（只是不跨重启保留）");
    }
}
