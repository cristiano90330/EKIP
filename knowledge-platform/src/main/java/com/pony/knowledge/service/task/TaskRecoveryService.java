/* 在启动和周期扫描时找回可投递的等待或失去租约的文档任务。 */

package com.pony.knowledge.service.task;

import com.pony.knowledge.config.TaskReliabilityProperties;
import com.pony.knowledge.entity.DocumentTask;
import com.pony.knowledge.mapper.DocumentTaskMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 从数据库找回未完成的文档任务：应用就绪事件触发启动扫描，定时调度补偿等待过久或可能卡住的任务。
 * 两条恢复路径都通过 TaskDispatchCoordinator 投递。扫描可在多实例同时运行，
 * 工作线程认领时再次校验数据库租约与重试时间。
 */
@Component
public class TaskRecoveryService implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(TaskRecoveryService.class);

    private final DocumentTaskMapper taskMapper;
    private final TaskDispatchCoordinator dispatchCoordinator;
    private final TaskReliabilityProperties properties;
    private final Counter dispatchFailureCounter;
    private final AtomicLong waitingCursor = new AtomicLong();
    private final AtomicLong processingCursor = new AtomicLong();

    /**
     * 保存在启动和周期扫描时找回可投递的等待或失去租约的文档任务所需的依赖和初始配置。
     * 上游为 Spring 组件装配。
     *
     * @param taskMapper 本次操作使用的 DocumentTaskMapper 组件或记录
     * @param dispatchCoordinator 本次操作使用的 TaskDispatchCoordinator 组件或记录
     * @param properties 供本组件执行时读取的配置约束
     * @param meterRegistry 注册与记录监控指标的 Micrometer 注册表
     */
    public TaskRecoveryService(
            DocumentTaskMapper taskMapper,
            TaskDispatchCoordinator dispatchCoordinator,
            TaskReliabilityProperties properties,
            MeterRegistry meterRegistry) {
        this.taskMapper = taskMapper;
        this.dispatchCoordinator = dispatchCoordinator;
        this.properties = properties;
        this.dispatchFailureCounter = meterRegistry.counter("document.task.recovery.dispatch.failed");
    }

    /**
     * 应用就绪后按页扫描未完成且具备恢复条件的任务。
     * 下游为 TaskReliabilityProperties.isRecoveryEnabled、DocumentTaskMapper.findRecoverableTasks。
     *
     * @param event 用于区分 SSE 帧语义的事件名
     */
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (!properties.isRecoveryEnabled()) {
            log.info("任务恢复已禁用（recovery-enabled=false），跳过启动扫描");
            return;
        }

        int limit = batchSize();
        long afterId = 0L;
        int dispatched = 0;

        while (true) {
            List<DocumentTask> active;
            try {
                active = taskMapper.findRecoverableTasks(afterId, limit);
            } catch (RuntimeException e) {
                log.error("启动恢复读取活跃任务失败，后续运行期扫描会继续补偿", e);
                return;
            }
            if (active.isEmpty()) {
                break;
            }
            for (DocumentTask task : active) {
                if (safeDispatch(task, "startup")) dispatched++;
            }
            if (active.size() < limit) {
                break;
            }
            afterId = active.get(active.size() - 1).getId();
        }
        log.info("启动恢复扫描完成，接受投递 {} 个可恢复任务", dispatched);
    }

    /**
     * 周期性扫描超时等待任务和可接管的处理任务。
     * 上游为 应用的定时调度器。
     * 下游为 TaskReliabilityProperties.isRecoveryEnabled。
     */
    @Scheduled(fixedDelayString = "${task.reliability.orphan-scan-interval-ms:30000}")
    public void scanStuckTasks() {
        if (!properties.isRecoveryEnabled()) {
            return;
        }
        scanOnePage("WAITING", waitingCursor, true);
        scanOnePage("PROCESSING", processingCursor, false);
    }

    /**
     * 沿指定状态的分页游标检查一批任务，完成一轮时回到起点。
     * 上游为 TaskRecoveryService.scanStuckTasks。
     * 下游为 TaskRecoveryService.findPage、TaskRecoveryService.safeDispatch。
     *
     * @param state 本方法计算或判断使用的 state 值
     * @param cursor 上页留下的查询位置
     * @param waiting 是否查询处于等待队列的文档任务
     */
    private void scanOnePage(String state, AtomicLong cursor, boolean waiting) {
        // 查询保持稳定的 ID 顺序，让下一轮从本次已检查的位置继续。
        List<DocumentTask> candidates;

        try {
            long afterId = cursor.get();
            candidates = findPage(waiting, afterId);
            if (candidates.isEmpty() && afterId > 0L) {
                cursor.set(0L);
                candidates = findPage(waiting, 0L);
            }
        } catch (RuntimeException e) {
            log.error("{} 任务恢复扫描读取失败", state, e);
            return;
        }
        if (candidates.isEmpty()) {
            cursor.set(0L);
            return;
        }

        cursor.set(candidates.get(candidates.size() - 1).getId());
        int accepted = 0;

        for (DocumentTask task : candidates) {
            if (safeDispatch(task, state)) accepted++;
        }
        log.info("{} 恢复扫描：候选 {} 个，接受投递 {} 个，cursor={}",
                state, candidates.size(), accepted, cursor.get());
    }

    /**
     * 按等待或处理中状态选择恢复查询，并沿任务 ID 继续分页。
     * 上游为 TaskRecoveryService.scanOnePage。
     * 下游为 DocumentTaskMapper.findWaitingTasks、DocumentTaskMapper.findRecoverableProcessingTasks。
     *
     * @param waiting 是否查询处于等待队列的文档任务
     * @param afterId 上一页最后一个标识
     * @return 与过滤条件匹配的 List&lt;DocumentTask&gt; 数据集合
     */
    private List<DocumentTask> findPage(boolean waiting, long afterId) {
        return waiting
                ? taskMapper.findWaitingTasks(properties.getWaitingTimeoutMs(), afterId, batchSize())
                : taskMapper.findRecoverableProcessingTasks(afterId, batchSize());
    }

    /**
     * 提交一个恢复候选，隔离拒绝错误并记录投递统计。
     * 上游为 TaskRecoveryService.onApplicationEvent、TaskRecoveryService.scanOnePage。
     * 下游为 TaskDispatchCoordinator.dispatchRecovered。
     *
     * @param task 任务记录
     * @param source 本方法计算或判断使用的 source 值
     * @return 符合本方法描述的状态或完成条件时为 true
     */
    private boolean safeDispatch(DocumentTask task, String source) {
        try {
            return dispatchCoordinator.dispatchRecovered(task);
        } catch (RuntimeException e) {
            dispatchFailureCounter.increment();
            log.warn("{} 恢复投递失败，保留任务等待下次扫描 taskId={}", source, task.getId(), e);
            return false;
        }
    }

    /**
     * 返回设置中的扫描大小，确保每批至少检查一项。
     * 上游为 TaskRecoveryService.onApplicationEvent、TaskRecoveryService.findPage。
     * 下游为 TaskReliabilityProperties.getRecoveryBatchSize。
     *
     * @return 不小于 1 的任务扫描批次大小
     */
    private int batchSize() {
        return Math.max(1, properties.getRecoveryBatchSize());
    }

    /**
     * 应用初始化时提示已禁用文档任务恢复。
     * 上游为 Spring 容器生命周期或应用事件。
     * 下游为 TaskReliabilityProperties.isRecoveryEnabled。
     */
    @PostConstruct
    void warnIfDisabled() {
        if (!properties.isRecoveryEnabled()) {
            log.warn("⚠️ 任务恢复已禁用（task.reliability.recovery-enabled=false）："
                   + "启动恢复与运行期扫描均不生效，卡死任务需人工干预。生产环境请确认这是有意的。");
        }
    }
}
