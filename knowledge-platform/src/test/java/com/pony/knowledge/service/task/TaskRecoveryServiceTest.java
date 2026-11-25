/* 验证启动与运行期的任务恢复、分页游标及投递失败隔离。 */

package com.pony.knowledge.service.task;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.pony.knowledge.config.TaskReliabilityProperties;
import com.pony.knowledge.entity.DocumentTask;
import com.pony.knowledge.mapper.DocumentTaskMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;

import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskRecoveryServiceTest {
    private final DocumentTaskMapper mapper = mock(DocumentTaskMapper.class);
    private final TaskDispatchCoordinator coordinator = mock(TaskDispatchCoordinator.class);
    private final TaskReliabilityProperties properties = new TaskReliabilityProperties();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    /**
     * 返回当前计算采用的 {@code t}。
     *
     * @param id 本次调用传入的 {@code long}
     * @return 当前记录中的 {@code t}
     */
    private static DocumentTask task(long id) {
        DocumentTask t = new DocumentTask();
        t.setId(id);
        t.setDocumentId(10L);
        return t;
    }

    /** 在该类型内部准备后续运行所需的数据。 */
    private TaskRecoveryService recovery() {
        return new TaskRecoveryService(mapper, coordinator, properties, registry);
    }

    /** 通过 {@code registry.get} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void startupPagesAllActiveTasksAndOneRejectedSubmissionDoesNotStopTheBatch() {
        properties.setRecoveryBatchSize(2);
        when(mapper.findRecoverableTasks(0L, 2)).thenReturn(List.of(task(1L), task(2L)));
        when(mapper.findRecoverableTasks(2L, 2)).thenReturn(List.of(task(3L)));
        when(coordinator.dispatchRecovered(task(1L))).thenThrow(new RejectedExecutionException("full"));
        when(coordinator.dispatchRecovered(task(2L))).thenReturn(true);
        when(coordinator.dispatchRecovered(task(3L))).thenReturn(true);

        recovery().onApplicationEvent(mock(ApplicationReadyEvent.class));

        verify(coordinator).dispatchRecovered(task(1L));
        verify(coordinator).dispatchRecovered(task(2L));
        verify(coordinator).dispatchRecovered(task(3L));
        verify(mapper).findRecoverableTasks(2L, 2);
        assertThat(registry.get("document.task.recovery.dispatch.failed").counter().count()).isEqualTo(1.0);
    }

    /** 模拟运行期补偿扫描，核对等待任务按年龄选择，执行中任务由数据库租约判断。 */
    @Test
    void runtimeUsesWaitingAgeAndDatabaseLeaseEligibility() {
        properties.setWaitingTimeoutMs(60_000);
        when(mapper.findWaitingTasks(60_000L, 0L, 100)).thenReturn(List.of(task(7L)));
        when(mapper.findRecoverableProcessingTasks(0L, 100)).thenReturn(List.of(task(8L)));

        recovery().scanStuckTasks();

        verify(coordinator).dispatchRecovered(task(7L));
        verify(coordinator).dispatchRecovered(task(8L));
    }

    /** 通过 {@code mapper.findWaitingTasks}、{@code mapper.findRecoverableProcessingTasks} 驱动该场景，跳过受保护的调用。 */
    @Test
    void rejectedWaitingTaskIsRevisitedWithoutRestartAndLaterCandidatesStillRun() {
        properties.setRecoveryBatchSize(2);
        when(mapper.findWaitingTasks(anyLong(), eq(0L), eq(2))).thenReturn(List.of(task(7L), task(8L)));
        when(mapper.findWaitingTasks(anyLong(), eq(8L), eq(2))).thenReturn(List.of());
        when(mapper.findRecoverableProcessingTasks(anyLong(), anyInt())).thenReturn(List.of());
        when(coordinator.dispatchRecovered(task(7L))).thenThrow(new RejectedExecutionException("full")).thenReturn(true);
        when(coordinator.dispatchRecovered(task(8L))).thenReturn(true);
        TaskRecoveryService recovery = recovery();

        recovery.scanStuckTasks();
        verify(coordinator).dispatchRecovered(task(8L));
        recovery.scanStuckTasks(); // End of the cursor pass resets and rereads the first page.

        verify(coordinator, times(2)).dispatchRecovered(task(7L));
        verify(coordinator, times(2)).dispatchRecovered(task(8L));
        verify(mapper, never()).claimProcessing(any(), anyLong(), anyLong());
    }

    /** 通过 {@code mapper.findWaitingTasks}、{@code mapper.findRecoverableProcessingTasks} 驱动该场景，核对输出和依赖调用。 */
    @Test
    void waitingScanReadFailureDoesNotPreventProcessingRecovery() {
        when(mapper.findWaitingTasks(anyLong(), anyLong(), anyInt())).thenThrow(new IllegalStateException("DB fault"));
        when(mapper.findRecoverableProcessingTasks(anyLong(), anyInt())).thenReturn(List.of(task(9L)));

        recovery().scanStuckTasks();

        verify(coordinator).dispatchRecovered(task(9L));
    }

    /** 通过 {@code recovery.onApplicationEvent}、{@code recovery.scanStuckTasks} 驱动该场景，跳过受保护的调用。 */
    @Test
    void disabledRecoveryReadsAndDispatchesNothing() {
        properties.setRecoveryEnabled(false);
        TaskRecoveryService recovery = recovery();
        recovery.onApplicationEvent(mock(ApplicationReadyEvent.class));
        recovery.scanStuckTasks();
        verifyNoInteractions(mapper, coordinator);
    }

    /** 通过 {@code captured.start}、{@code logger.addAppender} 驱动该场景，布尔状态符合断言。 */
    @Test
    void disabledRecoveryWarnsAtStartup() {
        properties.setRecoveryEnabled(false);
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(TaskRecoveryService.class);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        logger.addAppender(captured);
        try {
            recovery().warnIfDisabled();
            assertTrue(captured.list.stream().anyMatch(e ->
                    e.getLevel() == Level.WARN && e.getFormattedMessage().contains("任务恢复已禁用")));
        } finally {
            logger.detachAppender(captured);
            captured.stop();
        }
    }
}
