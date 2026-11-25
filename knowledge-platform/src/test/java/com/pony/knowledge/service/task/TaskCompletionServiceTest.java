/* 验证任务与文档状态的事务更新和提交后的事件通知。 */

package com.pony.knowledge.service.task;

import com.pony.knowledge.config.TaskReliabilityProperties;
import com.pony.knowledge.entity.Document;
import com.pony.knowledge.entity.DocumentStatus;
import com.pony.knowledge.entity.DocumentTask;
import com.pony.knowledge.entity.DocumentTaskStatus;
import com.pony.knowledge.mapper.DocumentMapper;
import com.pony.knowledge.mapper.DocumentTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Transaction-manager call contracts only; real PostgreSQL rollback has separate integration tests. */
class TaskCompletionServiceTest {
    private final DocumentMapper documents = mock(DocumentMapper.class);
    private final DocumentTaskMapper tasks = mock(DocumentTaskMapper.class);
    private final TaskStatusEventBus events = mock(TaskStatusEventBus.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final TransactionStatus transaction = mock(TransactionStatus.class);
    private final TaskCompletionService completion = new TaskCompletionService(documents, tasks, events, manager,
            new TaskReliabilityProperties());
    private DocumentTask task;

    /** 准备本组用例共享的测试数据和隔离环境，通过 {@code documents.selectByIdForUpdate}、{@code documents.updateById} 初始化依赖。 */
    @BeforeEach
    void setUp() {
        Document document = new Document();
        document.setId(10L);
        task = new DocumentTask();
        task.setId(1L);
        task.setDocumentId(10L);
        task.setStatus(DocumentTaskStatus.PROCESSING);
        task.setTotalAttemptCount(1);
        when(manager.getTransaction(any())).thenReturn(transaction);
        when(documents.selectByIdForUpdate(10L)).thenReturn(document);
        when(documents.updateById(any(Document.class))).thenReturn(1);
        when(tasks.selectById(1L)).thenReturn(task);
    }

    /** 通过 {@code tasks.markSuccess}、{@code TransactionSynchronizationManager.initSynchronization} 驱动该场景，核对 {@code completion.completeAttempt(task, chunks)} 为 true、{@code patch.getValue().getId()} 等于 {@code 10L}。 */
    @Test
    void successChecksLeaseBeforeChunksAndPublishesAfterCommit() {
        when(tasks.markSuccess(1L, 1)).thenReturn(1);
        Runnable chunks = mock(Runnable.class);
        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThat(completion.completeAttempt(task, chunks)).isTrue();
            var patch = ArgumentCaptor.forClass(Document.class);
            var order = inOrder(documents, tasks, chunks);
            order.verify(documents).selectByIdForUpdate(10L);
            order.verify(tasks).markSuccess(1L, 1);
            order.verify(chunks).run();
            order.verify(documents).updateById(patch.capture());
            assertThat(patch.getValue().getId()).isEqualTo(10L);
            assertThat(patch.getValue().getStatus()).isEqualTo(DocumentStatus.COMPLETED);
            assertThat(patch.getValue().getFileName()).isNull();
            verifyNoInteractions(events);
            TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
            verify(events).publish(task);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /** 通过 {@code tasks.selectById}、{@code completion.completeAttempt} 驱动该场景，核对 {@code completion.completeAttempt(task, chunks)} 为 false。 */
    @Test
    void cancelledAttemptCannotWritePreparedChunksOrPublishSuccess() {
        DocumentTask cancelled = new DocumentTask();
        cancelled.setId(1L);
        cancelled.setDocumentId(10L);
        cancelled.setStatus(DocumentTaskStatus.CANCELLED);
        when(tasks.selectById(1L)).thenReturn(cancelled);
        Runnable chunks = mock(Runnable.class);

        assertThat(completion.completeAttempt(task, chunks)).isFalse();

        verify(tasks, never()).markSuccess(any(), anyInt());
        verifyNoInteractions(chunks, events);
    }

    /** 通过 {@code tasks.selectById}、{@code tasks.markCancelled} 驱动该场景，核对 {@code patch.getValue().getStatus()} 等于 {@code DocumentStatus.CANCELLED}。 */
    @Test
    void cancellationUpdatesBothRowsAndPublishesOnlyAfterCommit() {
        DocumentTask cancelled = new DocumentTask();
        cancelled.setId(1L);
        cancelled.setDocumentId(10L);
        cancelled.setStatus(DocumentTaskStatus.CANCELLED);
        when(tasks.selectById(1L)).thenReturn(task, cancelled);
        when(tasks.markCancelled(1L)).thenReturn(1);

        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThat(completion.cancel(1L, 10L).getStatus()).isEqualTo("CANCELLED");
            var patch = ArgumentCaptor.forClass(Document.class);
            var order = inOrder(documents, tasks);
            order.verify(documents).selectByIdForUpdate(10L);
            order.verify(tasks).markCancelled(1L);
            order.verify(documents).deleteChunks(10L);
            order.verify(documents).updateById(patch.capture());
            assertThat(patch.getValue().getStatus()).isEqualTo(DocumentStatus.CANCELLED);
            verifyNoInteractions(events);
            TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
            verify(events).publish(cancelled);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /** 通过 {@code tasks.markFailed}、{@code completion.markFailed} 驱动该场景，核对 {@code patch.getValue().getStatus()} 等于 {@code DocumentStatus.FAILED}、{@code definition.getValue().getPropagationBehavior()} 等于 {@code TransactionDefinition.PROPAGATION_REQUIRES_NEW}。 */
    @Test
    void failureUsesSameCommitBeforeNotificationContract() {
        when(tasks.markFailed(1L, 1, "invalid", "DOCUMENT_INVALID")).thenReturn(1);

        assertThat(completion.markFailed(task, "invalid", "DOCUMENT_INVALID")).isTrue();

        var patch = ArgumentCaptor.forClass(Document.class);
        var definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        var order = inOrder(tasks, documents, manager, events);
        order.verify(manager).getTransaction(definition.capture());
        order.verify(tasks).markFailed(1L, 1, "invalid", "DOCUMENT_INVALID");
        order.verify(documents).updateById(patch.capture());
        order.verify(manager).commit(transaction);
        order.verify(events).publish(task);
        assertThat(patch.getValue().getStatus()).isEqualTo(DocumentStatus.FAILED);
        assertThat(definition.getValue().getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 通过 {@code tasks.markWaitingAfterRetryScheduleFailure}、{@code ArgumentCaptor.forClass} 驱动该场景，核对 {@code patch.getValue().getStatus()} 等于 {@code DocumentStatus.UPLOADED}。 */
    @Test
    void rejectedRetryUsesAtomicWaitingTransitionAndStableReason() {
        when(tasks.markWaitingAfterRetryScheduleFailure(1L, 1,
                "后台任务队列暂时繁忙，等待恢复扫描后自动继续", "TASK_DISPATCH_REJECTED")).thenReturn(1);

        recoverRejectedRetry();

        var patch = ArgumentCaptor.forClass(Document.class);
        var order = inOrder(tasks, documents, manager, events);
        order.verify(tasks).markWaitingAfterRetryScheduleFailure(1L, 1,
                "后台任务队列暂时繁忙，等待恢复扫描后自动继续", "TASK_DISPATCH_REJECTED");
        order.verify(documents).updateById(patch.capture());
        order.verify(manager).commit(transaction);
        order.verify(events).publish(task);
        assertThat(patch.getValue().getStatus()).isEqualTo(DocumentStatus.UPLOADED);
    }

    /** 通过 {@code tasks.markFailed}、{@code documents.updateById} 驱动该场景，按异常路径结束，跳过受保护的调用。 */
    @Test
    void secondWriteFailureRequestsRollbackAndNeverPublishes() {
        when(tasks.markFailed(1L, 1, "invalid", "DOCUMENT_INVALID")).thenReturn(1);
        when(documents.updateById(any(Document.class))).thenThrow(new IllegalStateException("second write failed"));

        assertThatThrownBy(() -> completion.markFailed(task, "invalid", "DOCUMENT_INVALID")).hasMessage("second write failed");

        verify(manager).rollback(transaction);
        verify(manager, never()).commit(any());
        verifyNoInteractions(events);
    }

    /** 通过 {@code tasks.markFailed}、{@code documents.updateById} 驱动该场景，按异常路径结束，跳过受保护的调用。 */
    @Test
    void zeroRowDocumentWriteAlsoRequestsRollback() {
        when(tasks.markFailed(1L, 1, "invalid", "DOCUMENT_INVALID")).thenReturn(1);
        when(documents.updateById(any(Document.class))).thenReturn(0);

        assertThatThrownBy(() -> completion.markFailed(task, "invalid", "DOCUMENT_INVALID")).hasMessageContaining("affected no row");

        verify(manager).rollback(transaction);
        verifyNoInteractions(events);
    }

    /** 通过 {@code tasks.markFailed}、{@code completion.markFailed} 驱动该场景，按异常路径结束，跳过受保护的调用。 */
    @Test
    void failedCommitNeverPublishes() {
        when(tasks.markFailed(1L, 1, "invalid", "DOCUMENT_INVALID")).thenReturn(1);
        doThrow(new IllegalStateException("commit failed")).when(manager).commit(transaction);

        assertThatThrownBy(() -> completion.markFailed(task, "invalid", "DOCUMENT_INVALID")).hasMessage("commit failed");

        verifyNoInteractions(events);
    }

    /** 通过 {@code tasks.markFailed}、{@code completion.markFailed} 驱动该场景，跳过受保护的调用，布尔状态符合断言。 */
    @Test
    void failedNotificationCannotUndoCommittedResult() {
        when(tasks.markFailed(1L, 1, "invalid", "DOCUMENT_INVALID")).thenReturn(1);
        doThrow(new IllegalStateException("subscriber unavailable")).when(events).publish(task);

        assertThat(completion.markFailed(task, "invalid", "DOCUMENT_INVALID")).isTrue();

        verify(manager).commit(transaction);
        verify(manager, never()).rollback(any());
    }

    /** 通过 {@code documents.selectByIdForUpdate}、{@code completion.completeAttempt} 驱动该场景，跳过受保护的调用，布尔状态符合断言。 */
    @Test
    void deletedDocumentCannotBeRecreatedByCompletion() {
        when(documents.selectByIdForUpdate(10L)).thenReturn(null);

        assertThat(completion.completeAttempt(task, () -> {})).isFalse();

        verifyNoInteractions(tasks, events);
        verify(documents, never()).updateById(any(Document.class));
    }

    /** 通过 {@code tasks.selectById}、{@code completion.completeAttempt} 驱动该场景，跳过受保护的调用，布尔状态符合断言。 */
    @Test
    void mismatchedTaskCannotChangeAnotherDocument() {
        DocumentTask other = new DocumentTask();
        other.setId(1L);
        other.setDocumentId(11L);
        other.setTotalAttemptCount(1);
        other.setStatus(DocumentTaskStatus.PROCESSING);
        when(tasks.selectById(1L)).thenReturn(other);

        assertThat(completion.completeAttempt(task, () -> {})).isFalse();

        verify(tasks, never()).markSuccess(any(), anyInt());
        verify(documents, never()).updateById(any(Document.class));
        verifyNoInteractions(events);
    }

    /** 通过 {@code tasks.markSuccess}、{@code completion.completeAttempt} 驱动该场景，核对 {@code completion.completeAttempt(task, chunks)} 为 false。 */
    @Test
    void lostStateCompareAndSetCannotChangeDocumentOrPublish() {
        when(tasks.markSuccess(1L, 1)).thenReturn(0);

        Runnable chunks = mock(Runnable.class);
        assertThat(completion.completeAttempt(task, chunks)).isFalse();

        verify(documents, never()).updateById(any(Document.class));
        verifyNoInteractions(chunks, events);
    }

    /** 通过 {@code tasks.selectById}、{@code recoverRejectedRetry} 驱动该场景，跳过受保护的调用。 */
    @Test
    void rejectedRetryOfDeletedTaskCannotChangeDocument() {
        when(tasks.selectById(1L)).thenReturn(null);

        recoverRejectedRetry();

        verify(documents, never()).updateById(any(Document.class));
        verifyNoInteractions(events);
    }

    /** 通过 {@code tasks.markWaitingAfterRetryScheduleFailure}、{@code anyInt} 驱动该场景，跳过受保护的调用。 */
    @Test
    void rejectedRetryWithTerminalStateCannotResetDocument() {
        when(tasks.markWaitingAfterRetryScheduleFailure(any(), anyInt(), any(), any())).thenReturn(0);

        recoverRejectedRetry();

        verify(documents, never()).updateById(any(Document.class));
        verifyNoInteractions(events);
    }

    /** 通过 {@code tasks.markWaitingAfterRetryScheduleFailure}、{@code documents.updateById} 驱动该场景，按异常路径结束，跳过受保护的调用。 */
    @Test
    void rejectedRetryTransitionFailureStillEscapesForRecovery() {
        when(tasks.markWaitingAfterRetryScheduleFailure(any(), anyInt(), any(), any())).thenReturn(1);
        when(documents.updateById(any(Document.class))).thenReturn(0);

        assertThatThrownBy(this::recoverRejectedRetry).hasMessageContaining("affected no row");

        verify(manager).rollback(transaction);
        verifyNoInteractions(events);
    }

    /** 协调 {@code completion.recoverRejectedRetry} 完成当前阶段，供后续处理使用。 */
    private void recoverRejectedRetry() {
        completion.recoverRejectedRetry(task, new RejectedExecutionException("queue saturated"));
    }
}
