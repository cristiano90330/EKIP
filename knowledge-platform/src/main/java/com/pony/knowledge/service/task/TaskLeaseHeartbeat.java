/* 周期续租正在处理的文档任务，并在执行结束时停止续租。 */

package com.pony.knowledge.service.task;

import com.pony.knowledge.config.TaskReliabilityProperties;
import com.pony.knowledge.entity.DocumentTask;
import com.pony.knowledge.mapper.DocumentTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/** Maintains a task lease while the worker is outside its short persistence transactions. */
@Component
public class TaskLeaseHeartbeat {
    private static final Logger log = LoggerFactory.getLogger(TaskLeaseHeartbeat.class);
    private final DocumentTaskMapper tasks;
    private final TaskScheduler scheduler;
    private final TaskReliabilityProperties properties;

    /**
     * 保存周期续租正在处理的文档任务所需的依赖和初始配置。
     * 上游为 Spring 组件装配。
     *
     * @param tasks 本次操作使用的 DocumentTaskMapper 组件或记录
     * @param scheduler 执行定时续租、恢复或重试的调度器
     * @param properties 供本组件执行时读取的配置约束
     */
    public TaskLeaseHeartbeat(DocumentTaskMapper tasks, @Qualifier("documentTaskLeaseScheduler") TaskScheduler scheduler,
                              TaskReliabilityProperties properties) {
        this.tasks = tasks;
        this.scheduler = scheduler;
        this.properties = properties;
    }

    /**
     * 周期续租指定尝试，返回可关闭的续租登记。
     * 上游为 DocumentProcessServiceImpl.process。
     * 下游为 DocumentTaskMapper.renewLease。
     *
     * @param attempt 处理尝试对应的任务快照
     * @return 在本次处理完成后可关闭的续租登记
     */
    public Registration maintain(DocumentTask attempt) {
        Duration interval = Duration.ofMillis(properties.getLeaseRenewIntervalMs());
        AtomicBoolean lost = new AtomicBoolean();
        var future = scheduler.scheduleWithFixedDelay(() -> {
            if (lost.get()) return;
            try {
                if (tasks.renewLease(attempt.getId(), attempt.getTotalAttemptCount(), properties.getLeaseDurationMs()) == 0) {
                    lost.set(true);
                }
            } catch (RuntimeException e) {
                // A later heartbeat can retry while the database lease is still valid.
                log.warn("文档任务续租失败 taskId={} attempt={}", attempt.getId(), attempt.getTotalAttemptCount(), e);
            }
        }, scheduler.getClock().instant().plus(interval), interval);
        return () -> future.cancel(false);
    }

    @FunctionalInterface
    public interface Registration extends AutoCloseable {
        /** 注销当前处理尝试的续租调度。 */
        @Override
        void close();
    }
}
