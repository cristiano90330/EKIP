/* 管理 JVM 内任务投递保留、执行器提交和延迟重试交接。 */

package com.pony.knowledge.service.task;

import com.pony.knowledge.entity.DocumentTask;
import com.pony.knowledge.entity.DocumentTaskStatus;
import com.pony.knowledge.service.DocumentProcessService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 统一接收上传、手动重试和恢复扫描的投递，将任务提交到 documentTaskExecutor。
 * 同一 taskId 在排队、执行和等待退避期间保留本 JVM 的槽位；重试通过替换槽位移交执行权，
 * 工作线程再获取 DocumentProcessService，由 PostgreSQL 认领决定本次执行权。
 */
@Component
public class TaskDispatchCoordinator {

    private static final Logger log = LoggerFactory.getLogger(TaskDispatchCoordinator.class);

    private final TaskExecutor executor;
    private final TaskScheduler scheduler;
    private final ObjectProvider<DocumentProcessService> processServiceProvider;
    private final TaskCompletionService completionService;
    private final Counter duplicateCounter;
    private final Counter rejectedCounter;
    private final ConcurrentMap<Long, Slot> reservations = new ConcurrentHashMap<>();

    /**
     * 保存管理 JVM 内任务投递保留、执行器提交和延迟重试交接所需的依赖和初始配置。
     * 上游为 Spring 组件装配。
     *
     * @param executor 本次操作所用的 TaskExecutor 数据
     * @param scheduler 执行定时续租、恢复或重试的调度器
     * @param processServiceProvider 本次操作所用的 ObjectProvider&lt;DocumentProcessService&gt; 数据
     * @param completionService 本次操作使用的 TaskCompletionService 组件或记录
     * @param meterRegistry 注册与记录监控指标的 Micrometer 注册表
     */
    public TaskDispatchCoordinator(
            @Qualifier("documentTaskExecutor") TaskExecutor executor,
            @Qualifier("taskScheduler") TaskScheduler scheduler,
            ObjectProvider<DocumentProcessService> processServiceProvider,
            TaskCompletionService completionService,
            MeterRegistry meterRegistry) {
        this.executor = executor;
        this.scheduler = scheduler;
        this.processServiceProvider = processServiceProvider;
        this.completionService = completionService;
        this.duplicateCounter = meterRegistry.counter("document.task.dispatch.duplicate");
        this.rejectedCounter = meterRegistry.counter("document.task.dispatch.rejected");
    }

    /**
     * 为任务 ID 建立本地投递保留并尝试交给后台执行器。
     * 上游为 DocumentServiceImpl.dispatchAfterCommit、DocumentTaskServiceImpl.dispatchRetriedTask。
     *
     * @param taskId 文档任务 ID
     * @return 本地保留成功提交给执行器时为 true
     */
    public boolean dispatch(Long taskId) {
        return dispatch(taskId, false);
    }

    /**
     * 投递恢复扫描发现的任务，必要时替换失效尝试的本地保留。
     * 上游为 TaskRecoveryService.safeDispatch。
     * 下游为 TaskDispatchCoordinator.dispatch、DocumentTask.getId。
     *
     * @param candidate 本次操作使用的 DocumentTask 组件或记录
     * @return 扫描到的任务被成功安排执行时为 true
     */
    boolean dispatchRecovered(DocumentTask candidate) {
        return dispatch(candidate.getId(), candidate.getStatus() == DocumentTaskStatus.PROCESSING);
    }

    /**
     * 为任务 ID 建立本地投递保留并尝试交给后台执行器。
     * 上游为 DocumentServiceImpl.dispatchAfterCommit、DocumentTaskServiceImpl.dispatchRetriedTask。
     * 下游为 TaskDispatchCoordinator.submitReserved。
     *
     * @param taskId 文档任务 ID
     * @param recoverRunning 本方法计算或判断使用的 recoverRunning 值
     * @return 本地保留成功提交给执行器时为 true
     */
    private boolean dispatch(Long taskId, boolean recoverRunning) {
        if (taskId == null) {
            throw new IllegalArgumentException("taskId is required");
        }
        Slot slot = new Slot();
        Slot current = reservations.putIfAbsent(taskId, slot);
        if (current != null && !(recoverRunning && current.running && reservations.replace(taskId, current, slot))) {
            duplicateCounter.increment();
            log.debug("任务已在队列、执行或等待重试，忽略重复投递 taskId={}", taskId);
            return false;
        }
        submitReserved(taskId, slot);
        return true;
    }

    /**
     * 登记延迟重试并移交本地执行保留，调度拒绝时释放保留。
     * 上游为 DocumentProcessServiceImpl.processAttempt。
     * 下游为 TaskCompletionService.recoverRejectedRetry。
     *
     * @param attempt 处理尝试对应的任务快照
     * @param delayMs 下次投递前的等待时长（毫秒）
     * @return 重试延迟回调已交给调度器时为 true
     */
    public boolean scheduleRetry(DocumentTask attempt, long delayMs) {
        if (attempt == null || attempt.getId() == null || delayMs < 0) {
            throw new IllegalArgumentException("attempt and non-negative delay are required");
        }
        Long taskId = attempt.getId();
        Slot retrySlot = new Slot();
        Slot current = reservations.get(taskId);
        boolean reserved = current == null
                ? reservations.putIfAbsent(taskId, retrySlot) == null
                : reservations.replace(taskId, current, retrySlot);

        if (!reserved) {
            duplicateCounter.increment();
            log.debug("任务已有待执行或待重试投递，忽略重复退避安排 taskId={}", taskId);
            return false;
        }

        try {
            var future = scheduler.schedule(
                    () -> {
                        try {
                            submitReserved(taskId, retrySlot);
                        } catch (RuntimeException rejection) {
                            completionService.recoverRejectedRetry(attempt, rejection);
                        }
                    }, Instant.now().plusMillis(delayMs));
            if (future == null) {
                throw new IllegalStateException("TaskScheduler did not accept retry task " + taskId);
            }
            return true;
        } catch (RuntimeException e) {
            reservations.remove(taskId, retrySlot);
            throw e;
        }
    }

    /**
     * 仅提交当前仍拥有保留的任务，结束后对照同一保留释放。
     * 上游为 TaskDispatchCoordinator.dispatch、TaskDispatchCoordinator.scheduleRetry。
     *
     * @param taskId 文档任务 ID
     * @param slot 本次操作使用的 Slot 组件或记录
     */
    private void submitReserved(Long taskId, Slot slot) {
        // 以当前槽位引用识别仍拥有投递权的任务，防止过期回调删除替换后的槽位。
        if (reservations.get(taskId) != slot) {
            return;
        }
        try {
            executor.execute(() -> {
                slot.running = true;
                try {
                    if (reservations.get(taskId) == slot) {
                        processServiceProvider.getObject().process(taskId);
                    }
                } finally {
                    reservations.remove(taskId, slot);
                }
            });
        } catch (RuntimeException e) {
            reservations.remove(taskId, slot);
            rejectedCounter.increment();
            log.warn("任务投递执行器拒绝任务，保留数据库状态等待恢复扫描 taskId={}", taskId, e);
            throw e;
        }
    }

    /**
     * 查询指定文档任务是否仍在当前 JVM 中保留投递。
     *
     * @param taskId 文档任务 ID
     * @return 符合本方法描述的状态或完成条件时为 true
     */
    boolean isReserved(Long taskId) {
        return reservations.containsKey(taskId);
    }

    /** 通过对象身份区分同一任务不同轮次的执行预留。 */
    private static final class Slot {
        private volatile boolean running;
    }
}
