/* 验证工作节点只在持有租约期间续租并结束失权续租任务。 */

package com.pony.knowledge.service.task;

import com.pony.knowledge.config.TaskReliabilityProperties;
import com.pony.knowledge.entity.DocumentTask;
import com.pony.knowledge.mapper.DocumentTaskMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.ScheduledFuture;

import static org.mockito.Mockito.*;

class TaskLeaseHeartbeatTest {
    /** 先让心跳更新遭遇临时数据库错误，再让续租失效，检查两种情形下的继续或停止。 */
    @Test
    void heartbeatRetriesDatabaseFailureButStopsAfterLeaseLoss() {
        DocumentTaskMapper tasks = mock(DocumentTaskMapper.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        Instant now = Instant.parse("2026-11-21T00:00:00Z");
        when(scheduler.getClock()).thenReturn(Clock.fixed(now, ZoneOffset.UTC));
        var tick = ArgumentCaptor.forClass(Runnable.class);
        doReturn(future).when(scheduler).scheduleWithFixedDelay(tick.capture(),
                eq(now.plusSeconds(20)), eq(Duration.ofSeconds(20)));
        when(tasks.renewLease(3L, 2, 120_000L))
                .thenThrow(new IllegalStateException("temporary database failure")).thenReturn(1, 0);
        var attempt = new DocumentTask();
        attempt.setId(3L);
        attempt.setTotalAttemptCount(2);

        try (var registration = new TaskLeaseHeartbeat(tasks, scheduler, new TaskReliabilityProperties()).maintain(attempt)) {
            for (int i = 0; i < 4; i++) tick.getValue().run();
        }

        verify(tasks, times(3)).renewLease(3L, 2, 120_000L);
        verify(future).cancel(false);
    }
}
