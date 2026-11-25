/* 验证任务流的订阅、快照竞争、超时和异常回调清理。 */

package com.pony.knowledge.controller;

import com.pony.knowledge.dto.DocumentTaskDTO;
import com.pony.knowledge.entity.DocumentTask;
import com.pony.knowledge.entity.DocumentTaskStatus;
import com.pony.knowledge.service.DocumentService;
import com.pony.knowledge.service.DocumentTaskService;
import com.pony.knowledge.service.task.TaskStatusEventBus;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentControllerTaskStreamRaceTest {

    /** 在订阅后补读任务快照时注入终态，确认旧快照无法覆盖已经推送的终态。 */
    @Test
    void subscribesBeforeFreshReadAndDoesNotSendStaleSnapshotAfterTerminalEvent() {
        DocumentService documents = mock(DocumentService.class);
        DocumentTaskService tasks = mock(DocumentTaskService.class);
        TaskStatusEventBus bus = spy(new TaskStatusEventBus());
        DocumentController controller = new DocumentController(documents, tasks, bus);
        DocumentTaskDTO snapshot = new DocumentTaskDTO();
        AtomicInteger reads = new AtomicInteger();
        AtomicBoolean snapshotWasSent = new AtomicBoolean(true);
        when(tasks.getTask(12L)).thenAnswer(invocation -> {
            if (reads.incrementAndGet() == 2) {
                bus.publish(task(12L, DocumentTaskStatus.SUCCESS, 1));
            }
            return snapshot;
        });
        doAnswer(invocation -> {
            boolean sent = (boolean) invocation.callRealMethod();
            snapshotWasSent.set(sent);
            return sent;
        }).when(bus).sendInitialSnapshotIfUnchanged(any(), eq(snapshot));

        SseEmitter emitter = controller.streamTask(12L);

        assertNotNull(emitter);
        assertFalse(snapshotWasSent.get(), "新事件已先发给订阅者，旧快照必须被抑制");
        InOrder order = inOrder(tasks, bus);
        order.verify(tasks).getTask(12L); // 先鉴权和确认任务存在
        order.verify(bus).subscribe(eq(12L), any(SseEmitter.class));
        order.verify(tasks).getTask(12L); // 订阅后重新读取当前快照
        order.verify(bus).sendInitialSnapshotIfUnchanged(any(), eq(snapshot));
    }

    /** 触发 SSE 超时回调，确认传输结束且任务订阅已移除。 */
    @Test
    void timeoutCompletesEmitterAndRemovesTaskSubscription() throws Exception {
        DocumentService documents = mock(DocumentService.class);
        DocumentTaskService tasks = mock(DocumentTaskService.class);
        TaskStatusEventBus bus = spy(new TaskStatusEventBus());
        DocumentController controller = new DocumentController(documents, tasks, bus);
        when(tasks.getTask(12L)).thenReturn(new DocumentTaskDTO());

        SseEmitter emitter = controller.streamTask(12L);
        AtomicBoolean completeCalled = new AtomicBoolean();
        installHandler(emitter, "complete", completeCalled, false);
        triggerTimeout(emitter);

        assertTrue(completeCalled.get(), "正常超时应调用 emitter.complete() 结束 SSE");
        verify(bus).unsubscribe(any(TaskStatusEventBus.Subscription.class));
    }

    /** 让流超时后的完成回调抛出异常，确认异常被隔离且订阅仍清理。 */
    @Test
    void timeoutCompletionFailureDoesNotEscapeAndStillRemovesSubscription() throws Exception {
        DocumentService documents = mock(DocumentService.class);
        DocumentTaskService tasks = mock(DocumentTaskService.class);
        TaskStatusEventBus bus = spy(new TaskStatusEventBus());
        DocumentController controller = new DocumentController(documents, tasks, bus);
        when(tasks.getTask(12L)).thenReturn(new DocumentTaskDTO());

        SseEmitter emitter = controller.streamTask(12L);
        AtomicBoolean completeCalled = new AtomicBoolean();
        installHandler(emitter, "complete", completeCalled, true);

        assertDoesNotThrow(() -> triggerTimeout(emitter));
        assertTrue(completeCalled.get(), "超时完成回调失败前仍应尝试完成 SSE");
        verify(bus).unsubscribe(any(TaskStatusEventBus.Subscription.class));
    }

    /** 同时制造读取首个快照和错误收尾失败，确认保留首个失败。 */
    @Test
    void completionWithErrorFailureDoesNotReplaceInitialSnapshotFailure() throws Exception {
        DocumentService documents = mock(DocumentService.class);
        DocumentTaskService tasks = mock(DocumentTaskService.class);
        TaskStatusEventBus bus = spy(new TaskStatusEventBus());
        DocumentController controller = new DocumentController(documents, tasks, bus);
        IllegalStateException snapshotFailure = new IllegalStateException("initial snapshot failed");
        when(tasks.getTask(12L)).thenReturn(new DocumentTaskDTO());
        doAnswer(invocation -> {
            SseEmitter emitter = invocation.getArgument(1);
            installHandler(emitter, "completeWithError", new AtomicBoolean(), true);
            return invocation.callRealMethod();
        }).when(bus).subscribe(eq(12L), any(SseEmitter.class));
        doThrow(snapshotFailure).when(bus)
                .sendInitialSnapshotIfUnchanged(any(), any(DocumentTaskDTO.class));

        IllegalStateException escaped = assertThrows(IllegalStateException.class,
                () -> controller.streamTask(12L));

        assertSame(snapshotFailure, escaped,
                "completeWithError 的容器异常不应替换初始快照异常");
        verify(bus).unsubscribe(any(TaskStatusEventBus.Subscription.class));
    }

    /**
     * 利用测试响应触发 SSE 超时回调。
     * 由 {@code timeoutCompletesEmitterAndRemovesTaskSubscription}、{@code timeoutCompletionFailureDoesNotEscapeAndStillRemovesSubscription} 调用。
     * @param emitter 本用例传入的{@code emitter}
     */
    private static void triggerTimeout(SseEmitter emitter) throws Exception {
        Field callback = ResponseBodyEmitter.class.getDeclaredField("timeoutCallback");
        callback.setAccessible(true);
        ((Runnable) callback.get(emitter)).run();
    }

    /**
     * 登记可观察的 SSE 完成处理器，可按需让它抛出异常。
     * 由 {@code timeoutCompletesEmitterAndRemovesTaskSubscription}、{@code timeoutCompletionFailureDoesNotEscapeAndStillRemovesSubscription} 调用。
     * @param emitter 本用例传入的{@code emitter}
     * @param observedMethod 本用例传入的{@code observedMethod}
     * @param observed 本用例传入的{@code observed}
     * @param shouldThrow 本用例传入的{@code shouldThrow}
     */
    private static void installHandler(SseEmitter emitter, String observedMethod,
                                       AtomicBoolean observed, boolean shouldThrow) throws Exception {
        Class<?> handlerType = Class.forName(
                "org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter$Handler");
        Object handler = Proxy.newProxyInstance(handlerType.getClassLoader(),
                new Class<?>[]{handlerType},
                (proxy, method, args) -> {
                    if (method.getName().equals(observedMethod)) {
                        observed.set(true);
                    }
                    if (shouldThrow && method.getName().equals(observedMethod)) {
                        throw new IllegalStateException("async request already errored");
                    }
                    return null;
                });
        Field handlerField = ResponseBodyEmitter.class.getDeclaredField("handler");
        handlerField.setAccessible(true);
        handlerField.set(emitter, handler);
    }

    /**
     * 构造 {@code DocumentTask} 供测试使用，使用 {@code task.setDocumentId}、{@code task.setStatus}。
     * 由 {@code subscribesBeforeFreshReadAndDoesNotSendStaleSnapshotAfterTerminalEvent} 调用。
     * @param documentId 文档 ID
     * @param status 拟使用的状态值
     * @param attempt 本用例传入的{@code attempt}
     * 返回 {@code task}。
     */
    private static DocumentTask task(Long documentId, DocumentTaskStatus status, int attempt) {
        DocumentTask task = new DocumentTask();
        task.setDocumentId(documentId);
        task.setStatus(status);
        task.setAutoRetryCount(attempt);
        return task;
    }
}
