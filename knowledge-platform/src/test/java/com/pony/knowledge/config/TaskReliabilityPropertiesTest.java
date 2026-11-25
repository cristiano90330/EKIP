/* 核对文档恢复配置的初值以及指数退避的封顶行为。 */

package com.pony.knowledge.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TaskReliabilityPropertiesTest {

    private final TaskReliabilityProperties p = new TaskReliabilityProperties();

    /** 通过 {@code p.getMaxAttempts} 执行本例并核对输出或记录。 */
    @Test
    void defaultsMatchSpec() {
        assertThat(p.getMaxAttempts()).isEqualTo(3);
        assertThat(p.getStuckTimeoutMs()).isEqualTo(600_000L);
        assertThat(p.getOrphanScanIntervalMs()).isEqualTo(30_000L);
        assertThat(p.isRecoveryEnabled()).isTrue();
        assertThat(p.backoffMs(1)).isEqualTo(5_000L);
    }

    /** 通过 {@code p.backoffMs} 执行本例并核对输出或记录。 */
    @Test
    void backoffGrowsExponentiallyAndCaps() {
        assertThat(p.backoffMs(1)).isEqualTo(5_000L);
        assertThat(p.backoffMs(2)).isEqualTo(10_000L);
        assertThat(p.backoffMs(3)).isEqualTo(20_000L);
        assertThat(p.backoffMs(10)).isEqualTo(60_000L); // 封顶
    }
}