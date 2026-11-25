/* 验证文档任务本地去重、重试接力、执行拒绝和恢复占位。 */

package com.pony.knowledge.service.task;

import com.pony.knowledge.entity.DocumentTask;
import com.pony.knowledge.entity.DocumentTaskStatus;
import com.pony.knowledge.config.AsyncConfig;
import com.pony.knowledge.service.DocumentProcessService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TaskDispatchCoordinatorTest {
    private final DocumentProcessService process = mock(DocumentProcessService.class);
    private final TaskScheduler scheduler = mock(TaskScheduler.class);
    private final TaskCompletionService completionService = mock(TaskCompletionService.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private ThreadPoolTaskExecutor executor;

    /**
     * 在该类型内部准备后续运行所需的数据。
     *
     * @param taskExecutor 本次调用传入的 {@code TaskExecutor}
     * @return 调用方使用的 {@code TaskDispatchCoordinator}
     */
    @SuppressWarnings("unchecked")
    private TaskDispatchCoordinator coordinator(TaskExecutor taskExecutor) {
        ObjectProvider<DocumentProcessService> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(process);
        return new TaskDispatchCoordinator(taskExecutor, scheduler, provider, completionService, registry);
    }

    /**
     * 协调 {@code executor.initialize} 完成当前阶段，供后续处理使用。
     * 下游调用 {@code executor.initialize}。
     *
     * @param queueSize 本次调用传入的 {@code int}
     * @return 当前记录中的 {@code executor}
     */
    private ThreadPoolTaskExecutor pool(int queueSize) {
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(queueSize);
        executor.setRejectedExecutionHandler(new AsyncConfig.RejectWithWarningPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(3);
        executor.initialize();
        return executor;
    }

    /** 在用例结束后释放该用例创建的资源。 */
    @AfterEach
    void close() {
        if (executor != null) executor.shutdown();
        registry.close();
    }

    /** 通过 {@code started.countDown}、{@code release.await} 驱动该场景，核对 {@code coordinator.dispatch(1L)} 为 true、{@code coordinator.dispatch(2L)} 为 true。 */
    @Test
    void runningAndQueuedTasksAreReservedBeforeDatabaseClaim() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> { started.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)); return null; })
                .when(process).process(1L);
        TaskDispatchCoordinator coordinator = coordinator(pool(1));
        try {
            assertThat(coordinator.dispatch(1L)).isTrue();
            assertTrue(started.await(3, TimeUnit.SECONDS));
            assertThat(coordinator.dispatch(2L)).isTrue();
            assertThat(coordinator.dispatch(2L)).isFalse();
            assertThat(coordinator.dispatch(1L)).isFalse();
            verify(process, never()).process(2L);
            assertThat(coordinator.isReserved(2L)).isTrue();
        } finally {
            release.countDown();
            executor.shutdown();
        }

        verify(process, times(1)).process(1L);
        verify(process, times(1)).process(2L);
        assertThat(coordinator.isReserved(1L)).isFalse();
        assertThat(coordinator.isReserved(2L)).isFalse();
    }

    /** 通过 {@code registry.get} 驱动用例，核对 {@code coordinator.isReserved(2L)} 为 false、{@code coordinator.dispatch(2L)} 为 true。 */
    @Test
    void saturatedExecutorRejectsWithoutRunningOnCallerAndReleasesReservation() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> { started.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)); return null; })
                .when(process).process(1L);
        TaskDispatchCoordinator coordinator = coordinator(pool(0));
        try {
            coordinator.dispatch(1L);
            assertTrue(started.await(3, TimeUnit.SECONDS));
            assertThatThrownBy(() -> coordinator.dispatch(2L)).isInstanceOf(RejectedExecutionException.class);
            verify(process, never()).process(2L);
            assertThat(coordinator.isReserved(2L)).isFalse();
            assertThat(registry.get("document.task.dispatch.rejected").counter().count()).isEqualTo(1);
        } finally {
            release.countDown();
        }

        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (executor.getActiveCount() > 0 && System.nanoTime() < until) Thread.sleep(5);
        assertThat(coordinator.dispatch(2L)).isTrue();
        executor.shutdown();
        verify(process, times(1)).process(2L);
        assertThat(coordinator.isReserved(2L)).isFalse();
    }

    /** 让正在运行的任务交接给延迟重试，核对占位随重试转移并在末次尝试结束时释放。 */
    @Test
    void runningWorkerHandsReservationToScheduledRetryAndFinalAttemptReleasesIt() {
        List<Runnable> queued = new ArrayList<>();
        List<Runnable> timed = new ArrayList<>();
        TaskExecutor taskExecutor = queued::add;
        when(scheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(call -> {
            timed.add(call.getArgument(0));
            return mock(ScheduledFuture.class);
        });
        TaskDispatchCoordinator coordinator = coordinator(taskExecutor);
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(call -> {
            if (attempts.incrementAndGet() == 1) coordinator.scheduleRetry(attempt(1L), 5000);
            return null;
        }).when(process).process(1L);

        coordinator.dispatch(1L);
        queued.get(0).run();
        assertThat(coordinator.isReserved(1L)).isTrue();
        assertThat(coordinator.dispatch(1L)).isFalse();
        timed.get(0).run();
        assertThat(queued).hasSize(2);
        assertThat(coordinator.dispatch(1L)).isFalse();
        queued.get(1).run();
        assertThat(attempts.get()).isEqualTo(2);
        assertThat(coordinator.isReserved(1L)).isFalse();
    }

    /** 通过 {@code queued.get} 驱动用例，核对 {@code coordinator.isReserved(1L)} 为 false、{@code coordinator.dispatch(1L)} 为 true。 */
    @Test
    void rejectedRetryTimerLeavesTaskAvailableForRecoveryDispatch() {
        List<Runnable> queued = new ArrayList<>();
        TaskDispatchCoordinator coordinator = coordinator(queued::add);
        when(scheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenThrow(new RejectedExecutionException("scheduler closed"));
        doAnswer(call -> {
            assertThatThrownBy(() -> coordinator.scheduleRetry(attempt(1L), 5000)).isInstanceOf(RejectedExecutionException.class);
            return null;
        }).when(process).process(1L);

        coordinator.dispatch(1L);
        queued.get(0).run();
        assertThat(coordinator.isReserved(1L)).isFalse();
        assertThat(coordinator.dispatch(1L)).isTrue();
        assertThat(queued).hasSize(2);
    }

    /** 通过 {@code queued.get}、{@code timed.get} 驱动用例，核对 {@code coordinator.isReserved(22L)} 为 false。 */
    @Test
    void rejectedDelayedRetrySubmitMarksTaskForRecoveryInsteadOfLeavingItProcessing() {
        List<Runnable> queued = new ArrayList<>();
        List<Runnable> timed = new ArrayList<>();
        AtomicInteger submissionCount = new AtomicInteger();
        TaskExecutor taskExecutor = task -> {
            if (submissionCount.getAndIncrement() == 0) {
                queued.add(task);
            } else {
                throw new RejectedExecutionException("pool saturated");
            }
        };
        when(scheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(call -> {
            timed.add(call.getArgument(0));
            return mock(ScheduledFuture.class);
        });
        doAnswer(call -> {
            coordinatorRef[0].scheduleRetry(attempt(22L), 1000);
            return null;
        }).when(process).process(22L);
        TaskDispatchCoordinator coordinator = coordinator(taskExecutor);
        coordinatorRef[0] = coordinator;

        coordinator.dispatch(22L);
        queued.get(0).run();
        timed.get(0).run();

        verify(completionService).recoverRejectedRetry(eq(attempt(22L)), any(RejectedExecutionException.class));
        assertThat(coordinator.isReserved(22L)).isFalse();
        verify(process, times(1)).process(22L); // dispatch rejection is not another worker claim/attempt
    }

    private final TaskDispatchCoordinator[] coordinatorRef = new TaskDispatchCoordinator[1];

    /** 通过 {@code coordinator.dispatch}、{@code coordinator.scheduleRetry} 驱动该场景，抛出 {@code IllegalArgumentException}，跳过受保护的调用。 */
    @Test
    void invalidRequestsNeverReserveOrScheduleTasks() {
        TaskDispatchCoordinator coordinator = coordinator(mock(TaskExecutor.class));
        assertThatThrownBy(() -> coordinator.dispatch(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> coordinator.scheduleRetry(null, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> coordinator.scheduleRetry(attempt(1L), -1)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(scheduler, process, completionService);
    }

    /** 通过 {@code scheduler.schedule}、{@code coordinator.scheduleRetry} 驱动该场景，核对 {@code coordinator.isReserved(1L)} 为 false。 */
    @Test
    void unacceptedRetryTimerReleasesReservationWithoutRunningProcess() {
        TaskDispatchCoordinator coordinator = coordinator(mock(TaskExecutor.class));
        when(scheduler.schedule(any(Runnable.class), any(Instant.class))).thenReturn(null);

        assertThatThrownBy(() -> coordinator.scheduleRetry(attempt(1L), 5000)).isInstanceOf(IllegalStateException.class);

        assertThat(coordinator.isReserved(1L)).isFalse();
        verifyNoInteractions(process);
    }

    /** 通过 {@code timed.get}、{@code queued.get} 驱动用例，核对 {@code coordinator.scheduleRetry(attempt(1L), 0)} 为 true、{@code queued} 为空。 */
    @Test
    void zeroDelayStillUsesSchedulerAndWorkerQueue() {
        List<Runnable> queued = new ArrayList<>();
        List<Runnable> timed = new ArrayList<>();
        when(scheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(call -> {
            timed.add(call.getArgument(0));
            return mock(ScheduledFuture.class);
        });
        TaskDispatchCoordinator coordinator = coordinator(queued::add);

        assertThat(coordinator.scheduleRetry(attempt(1L), 0)).isTrue();
        assertThat(queued).isEmpty();
        verifyNoInteractions(process);
        timed.get(0).run();
        queued.get(0).run();

        verify(process).process(1L);
        assertThat(coordinator.isReserved(1L)).isFalse();
    }

    /** 让恢复扫描处理失去租约的本地工作位，确认只安排一个替代执行。 */
    @Test
    void recoveryReplacesRunningSlotButDoesNotDuplicateItsQueuedReplacement() {
        List<Runnable> queued = new ArrayList<>();
        TaskDispatchCoordinator coordinator = coordinator(queued::add);
        DocumentTask expired = attempt(1L);
        expired.setStatus(DocumentTaskStatus.PROCESSING);
        doAnswer(call -> {
            assertThat(coordinator.dispatchRecovered(expired)).isTrue();
            assertThat(coordinator.dispatchRecovered(expired)).isFalse();
            return null;
        }).doNothing().when(process).process(1L);

        coordinator.dispatch(1L);
        queued.get(0).run();
        assertThat(coordinator.isReserved(1L)).isTrue();
        queued.get(1).run();

        verify(process, times(2)).process(1L);
        assertThat(coordinator.isReserved(1L)).isFalse();
    }

    /**
     * 返回当前计算采用的 {@code attempt}。
     *
     * @param id 本次调用传入的 {@code long}
     * @return 当前记录中的 {@code attempt}
     */
    private static DocumentTask attempt(long id) {
        DocumentTask attempt = new DocumentTask();
        attempt.setId(id);
        attempt.setTotalAttemptCount(1);
        return attempt;
    }
}
