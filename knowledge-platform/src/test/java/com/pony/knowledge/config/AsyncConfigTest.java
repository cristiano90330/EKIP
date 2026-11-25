/* 核对文档、检索与 Agent 线程池的形状和饱和时的交付行为。 */

package com.pony.knowledge.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P1-7（2026-09-18）：验证 AsyncConfig 显式注册的 taskScheduler Bean——
 * 不再依赖 @EnableScheduling 隐式默认 Bean（池大小 1、线程名不可辨认）。
 *
 * <p>P1-15（2026-09-18）追加：线程池参数与拒绝策略的意图固化。
 * 参数断言看着像「把常量抄一遍」，锁的其实是注释里写下的理由——有人顺手把 core 从 2 调成 10、
 * 或把队列改成无界，测试会立刻失败并逼他回去读那段注释。</p>
 *
 * <p>饱和用例用真实线程池：填满「max + queue」后提交第 106 个任务，
 * 断言它快速拒绝且不在上传/扫描提交线程同步运行，交给 WAITING 恢复补投。
 * 不打桩的理由：这个行为完全由「池容量 + 拒绝策略」的组合决定，
 * mock 一个 Executor 只会验证到「我们调用了它」，验证不到真实的组合行为。</p>
 */
class AsyncConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AsyncConfig.class)
            .withBean(TaskDecorator.class, () -> runnable -> runnable);

    /** 观察装饰器负责透传 trace/observation，这里用恒等装饰器顶替。 */
    private final AsyncConfig config = new AsyncConfig(runnable -> runnable);

    // ---------- P1-7 ----------

    /** 通过 {@code runner.run} 执行本例并核对输出或记录。 */
    @Test
    void exposesExplicitTaskSchedulerBean() {
        runner.run(ctx -> {
            TaskScheduler scheduler = ctx.getBean("taskScheduler", TaskScheduler.class);
            assertThat(scheduler).isInstanceOf(ThreadPoolTaskScheduler.class);
            assertThat(((ThreadPoolTaskScheduler) scheduler).getThreadNamePrefix()).isEqualTo("task-sched-");
        });
    }

    // ---------- P1-15 ----------

    /** 覆盖文档的相关边界。通过 {@code config.documentTaskExecutor} 执行本例并核对输出或记录。 */
    @Test
    void documentPoolKeepsDocumentedShape() {
        ThreadPoolExecutor pool = ((ThreadPoolTaskExecutor) config.documentTaskExecutor()).getThreadPoolExecutor();

        assertThat(pool.getCorePoolSize()).as("core=2：日常并发远低于 1，常驻线程只留两条").isEqualTo(2);
        assertThat(pool.getMaximumPoolSize()).as("max=5：上限由解析峰值内存决定，不是 CPU").isEqualTo(5);
        assertThat(pool.getQueue().remainingCapacity())
                .as("queue=100：有界是硬要求，无界队列会让拒绝策略永不触发、过载被掩盖")
                .isEqualTo(100);
        assertThat(pool.getRejectedExecutionHandler())
                .as("文档池拒绝后由 WAITING 恢复补投，不能让上传请求同步解析")
                .isInstanceOf(AsyncConfig.RejectWithWarningPolicy.class);
    }

    /** 覆盖文档的相关边界。通过 {@code config.documentTaskExecutor} 执行本例并核对输出或记录。 */
    @Test
    void documentPoolSaturationRejectsInsteadOfRunningOnCaller() throws Exception {
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) config.documentTaskExecutor();
        CountDownLatch release = new CountDownLatch(1);

        try {
            ThreadPoolExecutor pool = executor.getThreadPoolExecutor();
            // 容量 = max（正在跑的）+ queue（排队的）；填满后下一次提交就会触发拒绝策略
            int capacity = pool.getMaximumPoolSize() + pool.getQueue().remainingCapacity();

            for (int i = 0; i < capacity; i++) {
                executor.execute(() -> {
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }

            AtomicReference<String> ranOn = new AtomicReference<>("（未执行）");

            assertThatThrownBy(() -> executor.execute(() -> ranOn.set(Thread.currentThread().getName())))
                    .as("池满时快速拒绝，数据库任务留待 WAITING 恢复扫描")
                    .isInstanceOf(RejectedExecutionException.class);

            assertThat(ranOn.get())
                    .as("被拒绝任务不能在提交方同步执行")
                    .isEqualTo("（未执行）");

            release.countDown();
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }

    /** 覆盖失败的相关边界。通过 {@code config.ragRetrievalExecutor} 执行本例并核对输出或记录。 */
    @Test
    void retrievalPoolPrefersFastFailureOverBackpressure() {
        // 与文档池刻意相反：检索在用户等待的同步路径上，让 HTTP 线程自己跑检索只会放大延迟
        ThreadPoolExecutor pool = ((ThreadPoolTaskExecutor) config.ragRetrievalExecutor()).getThreadPoolExecutor();

        assertThat(pool.getCorePoolSize()).as("core=2 = 向量 + 关键词两路扇出").isEqualTo(2);
        assertThat(pool.getMaximumPoolSize()).isEqualTo(4);
        assertThat(pool.getQueue().remainingCapacity()).isEqualTo(50);
        assertThat(pool.getRejectedExecutionHandler())
                .as("检索池保持快速失败：宁可让前端重试，也不要陪着等")
                .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
    }

    /** 覆盖并行的相关边界。通过 {@code config.agentToolDispatchExecutor} 执行本例并核对输出或记录。 */
    @Test
    void dispatchPoolIsSizedByMaxParallelToolsNotByThroughput() {
        // 这里的数字是正确性约束（线程数 < 单 decision 的并行工具数 ⇒ 互相等待至卡死），不是性能调优
        ThreadPoolExecutor pool = ((ThreadPoolTaskExecutor) config.agentToolDispatchExecutor()).getThreadPoolExecutor();

        assertThat(pool.getCorePoolSize()).as("core=4 = 一次 decision 的最大并行工具数，调小会自锁").isEqualTo(4);
        assertThat(pool.getMaximumPoolSize()).isEqualTo(8);
        assertThat(pool.getQueue().remainingCapacity())
                .as("队列刻意小：排队的 dispatch 任务不推进任何工作，堆着没收益")
                .isEqualTo(20);
    }

    /** 通过 {@code config.summaryExecutor} 执行本例并核对输出或记录。 */
    @Test
    void summaryPoolIsSingleThreadedOnPurpose() {
        // core=1 不是「省资源」，而是同一会话的摘要必须串行，否则两份摘要并发覆盖
        ThreadPoolExecutor pool = ((ThreadPoolTaskExecutor) config.summaryExecutor()).getThreadPoolExecutor();

        assertThat(pool.getCorePoolSize()).as("core=1：同一会话摘要串行，避免并发写覆盖").isEqualTo(1);
        assertThat(pool.getMaximumPoolSize()).isEqualTo(2);
        assertThat(pool.getQueue().remainingCapacity()).isEqualTo(20);
    }
}
