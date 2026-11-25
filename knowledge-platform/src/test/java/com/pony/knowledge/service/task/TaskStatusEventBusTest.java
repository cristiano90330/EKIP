/* 验证任务状态 SSE 的订阅、快照排序、发布失败和连接清理。 */

package com.pony.knowledge.service.task;

import com.pony.knowledge.dto.DocumentTaskDTO;
import com.pony.knowledge.entity.DocumentTask;
import com.pony.knowledge.entity.DocumentTaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class TaskStatusEventBusTest {

    private TaskStatusEventBus bus;

    /** 准备本组用例共享的测试数据和隔离环境。 */
    @BeforeEach
    void setUp() {
        bus = new TaskStatusEventBus();
    }

    /**
     * 在该类型内部准备后续运行所需的数据。
     *
     * @return 调用方使用的 {@code Map<Long, ?>}
     */
    @SuppressWarnings("unchecked")
    private Map<Long, ?> subscribers() throws Exception {
        Field f = TaskStatusEventBus.class.getDeclaredField("subscribers");
        f.setAccessible(true);
        return (Map<Long, ?>) f.get(bus);
    }

    /**
     * 返回当前计算采用的 {@code t}。
     *
     * @param docId 本次调用传入的 {@code Long}
     * @param status 本次调用传入的 {@code DocumentTaskStatus}
     * @param autoRetryCount 本次调用传入的 {@code int}
     * @return 当前记录中的 {@code t}
     */
    private static DocumentTask task(Long docId, DocumentTaskStatus status, int autoRetryCount) {
        DocumentTask t = new DocumentTask();
        t.setDocumentId(docId);
        t.setStatus(status);
        t.setAutoRetryCount(autoRetryCount);
        return t;
    }

    /** 通过 {@code bus.subscribe}、{@code bus.publish} 驱动该场景，核对输出和依赖调用。 */
    @Test
    void publishFansOutToSubscribers() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        bus.subscribe(10L, emitter);
        bus.publish(task(10L, DocumentTaskStatus.PROCESSING, 1));
        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
    }

    /** 模拟跨节点重复任务通知，检查当前 SSE 订阅只收到一次已经提交的状态。 */
    @Test
    void repeatedCrossInstanceSignalsOnlySendOneCommittedSnapshot() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        var subscription = bus.subscribe(10L, emitter);
        bus.refresh(10L, () -> task(10L, DocumentTaskStatus.PROCESSING, 1));
        bus.refresh(10L, () -> task(10L, DocumentTaskStatus.SUCCESS, 1));
        bus.refresh(10L, () -> task(10L, DocumentTaskStatus.SUCCESS, 1));

        assertFalse(bus.sendInitialSnapshotIfUnchanged(subscription, new DocumentTaskDTO()));
        verify(emitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
    }

    /** 在订阅和首次快照之间插入状态事件，确认随后读到的快照不会造成旧状态覆盖。 */
    @Test
    void freshSnapshotIsSkippedWhenAnEventWasAlreadySentAfterSubscription() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        TaskStatusEventBus.Subscription subscription = bus.subscribe(10L, emitter);

        bus.publish(task(10L, DocumentTaskStatus.SUCCESS, 1));

        assertFalse(bus.sendInitialSnapshotIfUnchanged(subscription, new DocumentTaskDTO()),
                "已发出的终态事件优先，不能再追加一个可能更旧的 DB 快照");
        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    /** 通过 {@code bus.subscribe}、{@code bus.sendInitialSnapshotIfUnchanged} 驱动该场景，布尔状态符合断言。 */
    @Test
    void initialSnapshotIsSentWhenNoEventWonTheSubscriptionRace() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        TaskStatusEventBus.Subscription subscription = bus.subscribe(10L, emitter);

        assertTrue(bus.sendInitialSnapshotIfUnchanged(subscription, new DocumentTaskDTO()));
        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
    }

    /** 通过 {@code bus.publish}、{@code task} 驱动该场景，核对输出和依赖调用。 */
    @Test
    void publishWithoutSubscribersIsNoOp() {
        bus.publish(task(10L, DocumentTaskStatus.PROCESSING, 1)); // 不抛异常即可
    }

    /** 通过 {@code bus.subscribe}、{@code bus.unsubscribe} 驱动该场景，跳过受保护的调用。 */
    @Test
    void unsubscribeStopsDelivery() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        bus.subscribe(10L, emitter);
        bus.unsubscribe(10L, emitter);
        bus.publish(task(10L, DocumentTaskStatus.PROCESSING, 1));
        verify(emitter, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    /** 通过 {@code bus.subscribe}、{@code bus.unsubscribe} 驱动该场景，布尔状态符合断言，返回的数据、边界或顺序符合断言。 */
    @Test
    void unsubscribeLastSubscriberRemovesKey() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        bus.subscribe(10L, emitter);
        bus.unsubscribe(10L, emitter);
        // P1-6（2026-09-18）：computeIfPresent 返回 null 必须原子地移除整个条目，
        // 否则「最后一个退订 vs 新订阅」竞态会把新订阅连带删掉。
        assertTrue(subscribers().isEmpty(), "最后一个订阅者退订后 documentId 条目应被移除");
        // 条目被真正清除后，同一 documentId 重新订阅必须照常工作
        SseEmitter e2 = mock(SseEmitter.class);
        bus.subscribe(10L, e2);
        bus.publish(task(10L, DocumentTaskStatus.PROCESSING, 1));
        verify(e2).send(any(SseEmitter.SseEventBuilder.class));
    }

    /** 通过 {@code bus.subscribe}、{@code bus.unsubscribe} 驱动该场景，跳过受保护的调用，实际结果与固定预期一致。 */
    @Test
    void unsubscribeOneOfTwoKeepsEntry() throws Exception {
        SseEmitter e1 = mock(SseEmitter.class);
        SseEmitter e2 = mock(SseEmitter.class);
        bus.subscribe(10L, e1);
        bus.subscribe(10L, e2);
        bus.unsubscribe(10L, e1);
        assertEquals(1, subscribers().size(), "非最后一个订阅者退订后条目应保留");
        // 剩余订阅者仍能收到推送，被退订者不再收到
        bus.publish(task(10L, DocumentTaskStatus.PROCESSING, 1));
        verify(e2).send(any(SseEmitter.SseEventBuilder.class));
        verify(e1, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    /** 通过 {@code bus.subscribe}、{@code bus.publish} 驱动该场景，核对输出和依赖调用。 */
    @Test
    void failedSendRemovesEmitter() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        doThrow(new IOException("closed")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        bus.subscribe(10L, emitter);
        bus.publish(task(10L, DocumentTaskStatus.PROCESSING, 1));
        verify(emitter).complete();
        // 已移除：下次发布不再尝试发送
        bus.publish(task(10L, DocumentTaskStatus.PROCESSING, 1));
        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    /** 通过 {@code bus.subscribe}、{@code bus.publish} 驱动该场景，核对输出和依赖调用。 */
    @Test
    void disconnectedEmitterCleanupDoesNotBlockOtherSubscribersOrPublish() throws Exception {
        SseEmitter disconnected = mock(SseEmitter.class);
        SseEmitter connected = mock(SseEmitter.class);
        doThrow(new IllegalStateException("async request already errored"))
                .when(disconnected).send(any(SseEmitter.SseEventBuilder.class));
        doThrow(new IllegalStateException("async result already set"))
                .when(disconnected).complete();
        bus.subscribe(10L, disconnected);
        bus.subscribe(10L, connected);

        assertDoesNotThrow(() -> bus.publish(task(10L, DocumentTaskStatus.PROCESSING, 1)));
        verify(connected).send(any(SseEmitter.SseEventBuilder.class));
        verify(disconnected).complete();

        // The failed emitter was removed despite complete() throwing; later task events still flow.
        assertDoesNotThrow(() -> bus.publish(task(10L, DocumentTaskStatus.SUCCESS, 1)));
        verify(disconnected, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(connected, times(2)).send(any(SseEmitter.SseEventBuilder.class));
    }

    /** 通过 {@code bus.subscribe}、{@code bus.sendInitialSnapshotIfUnchanged} 驱动该场景，布尔状态符合断言。 */
    @Test
    void failedInitialSnapshotRemovesEmitterEvenWhenCompletionWithErrorThrows() throws Exception {
        SseEmitter disconnected = mock(SseEmitter.class);
        SseEmitter connected = mock(SseEmitter.class);
        doThrow(new IllegalStateException("async request already errored"))
                .when(disconnected).send(any(SseEmitter.SseEventBuilder.class));
        doThrow(new IllegalStateException("async result already set"))
                .when(disconnected).completeWithError(any(Throwable.class));
        TaskStatusEventBus.Subscription failed = bus.subscribe(10L, disconnected);
        bus.subscribe(10L, connected);

        assertFalse(assertDoesNotThrow(() ->
                bus.sendInitialSnapshotIfUnchanged(failed, new DocumentTaskDTO())));
        assertDoesNotThrow(() -> bus.publish(task(10L, DocumentTaskStatus.PROCESSING, 1)));

        verify(disconnected).completeWithError(any(Throwable.class));
        verify(disconnected, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(connected).send(any(SseEmitter.SseEventBuilder.class));
    }
}
