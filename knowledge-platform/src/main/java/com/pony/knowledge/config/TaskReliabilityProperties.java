/* 保存解析任务的重试、租约和恢复扫描配置。 */

package com.pony.knowledge.config;

import lombok.Data;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "task.reliability")
public class TaskReliabilityProperties {

    /** transient 自动重试次数上限（含首次）；手动 retry 与恢复重跑不受限（总能再试一次）。 */
    private int maxAttempts = 3;

    private long backoffBaseMs = 5_000;

    private long backoffCapMs = 60_000;

    /** 单次执行的硬上限，续租最多延长到该截止时间。 */
    private long stuckTimeoutMs = 600_000;

    private long leaseDurationMs = 120_000;

    private long leaseRenewIntervalMs = 20_000;

    /**
     * 检查续租间隔、租约时长和卡住超时符合顺序。
     * 上游为 Spring 容器生命周期或应用事件。
     */
    @PostConstruct
    void validateLeaseTimings() {
        if (leaseRenewIntervalMs <= 0 || leaseDurationMs <= leaseRenewIntervalMs || stuckTimeoutMs <= leaseDurationMs) {
            throw new IllegalArgumentException("文档任务时间须满足 0 < lease-renew-interval-ms < lease-duration-ms < stuck-timeout-ms");
        }
    }

    private long orphanScanIntervalMs = 30_000;

    /** WAITING 投递恢复的独立年龄阈值（ms），与 PROCESSING 卡死阈值分开。 */
    private long waitingTimeoutMs = 60_000;

    /** 每轮启动/运行期恢复扫描最多读取的候选数。 */
    private int recoveryBatchSize = 100;

    /** 启动恢复 + 孤儿扫描总开关；测试环境必须 false（避免上下文加载时动共享库）。 */
    private boolean recoveryEnabled = true;

    /**
     * 按重试次数计算有上限的指数退避时间。
     * 上游为 DocumentProcessServiceImpl.processAttempt。
     *
     * @param attempt 本次执行的尝试编号
     * @return 按尝试次数和上限计算的退避毫秒数
     */
    public long backoffMs(int attempt) {
        long delay = backoffBaseMs;
        for (int i = 1; i < attempt; i++) {
            delay = Math.min(delay * 2, backoffCapMs);
        }
        return Math.min(delay, backoffCapMs);
    }
}
