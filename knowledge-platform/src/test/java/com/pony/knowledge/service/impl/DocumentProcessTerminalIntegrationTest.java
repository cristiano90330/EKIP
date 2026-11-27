/* 验证文档任务状态提交、重试拒绝补偿和旧尝试无法写回。 */

package com.pony.knowledge.service.impl;

import com.pony.knowledge.entity.Document;
import com.pony.knowledge.entity.DocumentStatus;
import com.pony.knowledge.entity.DocumentTask;
import com.pony.knowledge.entity.DocumentTaskStatus;
import com.pony.knowledge.mapper.DocumentTaskMapper;
import com.pony.knowledge.service.DocumentChunkService;
import com.pony.knowledge.service.DocumentProcessService;
import com.pony.knowledge.service.ai.AiServiceClient;
import com.pony.knowledge.service.task.TaskStatusEventBus;
import com.pony.knowledge.service.task.TaskCompletionService;
import com.pony.knowledge.service.task.TaskDispatchCoordinator;
import com.pony.knowledge.mapper.DocumentMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.sql.DataSource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/** Fault injection into the second terminal write, through the real process call site. */
@SpringBootTest(properties = "task.reliability.recovery-enabled=false")
class DocumentProcessTerminalIntegrationTest {
    @Autowired private DocumentProcessService process;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DocumentTaskMapper tasks;
    @Autowired private TaskCompletionService completion;
    @Autowired private DataSource dataSource;
    @Autowired private DocumentMapper documents;
    @Autowired private PlatformTransactionManager transactions;
    @MockitoBean private AiServiceClient ai;
    @MockitoBean private DocumentChunkService chunks;
    @MockitoBean private TaskStatusEventBus events;
    @MockitoBean private TaskDispatchCoordinator dispatchCoordinator;

    private Long kbId;
    private Long documentId;
    private Long taskId;
    private String guardFunction;
    private String guardTrigger;
    private final List<DocumentTaskStatus> published = new ArrayList<>();

    /** 准备本组用例共享的测试数据和隔离环境，通过 {@code jdbc.queryForObject}、{@code UUID.randomUUID} 初始化依赖。 */
    @BeforeEach
    void seed() {
        kbId = jdbc.queryForObject("INSERT INTO knowledge_base(name, creator_id) VALUES (?, 999998) RETURNING id",
                Long.class, "terminal-" + UUID.randomUUID());
        documentId = jdbc.queryForObject("""
                INSERT INTO document(knowledge_base_id, file_name, file_path, status)
                VALUES (?, 'terminal.pdf', 'terminal.pdf', 0) RETURNING id
                """, Long.class, kbId);
        taskId = jdbc.queryForObject("INSERT INTO document_task(document_id, status) VALUES (?, 0) RETURNING id",
                Long.class, documentId);
        when(ai.parseDocument(anyLong(), anyString(), anyString(), anyString(), any()))
                .thenReturn(new AiServiceClient.ParseResult());
        when(chunks.completeTask(any(), anyLong(), any())).thenAnswer(invocation ->
                new TransactionTemplate(transactions).execute(status ->
                        completion.completeAttempt(invocation.getArgument(0), () -> {})));
        doAnswer(call -> {
            DocumentTask task = call.getArgument(0);
            published.add(task.getStatus());
            if (task.getStatus() == DocumentTaskStatus.SUCCESS || task.getStatus() == DocumentTaskStatus.FAILED
                    || task.getStatus() == DocumentTaskStatus.WAITING) {
                if (task.getStatus() != DocumentTaskStatus.SUCCESS) {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                }
                assertThat(tasks.selectById(taskId).getStatus()).isEqualTo(task.getStatus());
                assertThat(documents.selectById(documentId).getStatus()).isEqualTo(
                        switch (task.getStatus()) {
                            case SUCCESS -> DocumentStatus.COMPLETED;
                            case FAILED -> DocumentStatus.FAILED;
                            default -> DocumentStatus.UPLOADED;
                        });
            }
            return null;
        }).when(events).publish(any(DocumentTask.class));
    }

    /** 在用例结束后释放该用例创建的资源。 */
    @AfterEach
    void cleanup() {
        if (guardTrigger != null) jdbc.execute("DROP TRIGGER IF EXISTS " + guardTrigger + " ON document");
        if (guardFunction != null) jdbc.execute("DROP FUNCTION IF EXISTS " + guardFunction + "()");
        jdbc.update("DELETE FROM document_task WHERE document_id = ?", documentId);
        jdbc.update("DELETE FROM document WHERE id = ?", documentId);
        jdbc.update("DELETE FROM knowledge_base WHERE id = ?", kbId);
    }

    /** 让完成事务的文档更新失败，核对任务成功标记同时回滚。 */
    @Test
    void secondSuccessWriteFailureCannotLeaveSuccessTaskWithProcessingDocument() {
        failTerminalDocumentWrites(false);

        process.process(taskId);

        assertThat(tasks.selectById(taskId).getStatus()).isEqualTo(DocumentTaskStatus.FAILED);
        assertThat(documents.selectById(documentId).getStatus()).isEqualTo(DocumentStatus.FAILED);
        assertThat(published).containsExactly(DocumentTaskStatus.PROCESSING, DocumentTaskStatus.FAILED);
    }

    /** 通过 {@code jdbc.update} 驱动用例，核对 {@code tasks.selectById(taskId).getStatus()} 等于 {@code DocumentTaskStatus.PROCESSING}、{@code documents.selectById(documentId).getStatus()} 等于 {@code DocumentStatus.PROCESSING}。 */
    @Test
    void failedFailureWriteAlsoRollsBackAndLeavesTaskRecoverable() {
        failTerminalDocumentWrites(true);

        assertThatThrownBy(() -> process.process(taskId)).hasMessageContaining("injected terminal write failure");

        assertThat(tasks.selectById(taskId).getStatus()).isEqualTo(DocumentTaskStatus.PROCESSING);
        assertThat(documents.selectById(documentId).getStatus()).isEqualTo(DocumentStatus.PROCESSING);
        assertThat(tasks.findRecoverableTasks(taskId - 1, 1)).extracting(DocumentTask::getId).doesNotContain(taskId);
        jdbc.update("UPDATE document_task SET lease_expires_at=clock_timestamp()-INTERVAL '1 second' WHERE id=?", taskId);
        assertThat(tasks.findRecoverableTasks(taskId - 1, 1)).extracting(DocumentTask::getId).contains(taskId);
        assertThat(published).containsExactly(DocumentTaskStatus.PROCESSING);
    }

    /** 通过 {@code process.process}、{@code tasks.selectById} 驱动该场景，核对 {@code tasks.selectById(taskId).getStatus()} 等于 {@code DocumentTaskStatus.SUCCESS}、{@code documents.selectById(documentId).getStatus()} 等于 {@code DocumentStatus.COMPLETED}。 */
    @Test
    void successfulProcessPublishesOnlyAfterBothTerminalRowsCommit() {
        process.process(taskId);

        assertThat(tasks.selectById(taskId).getStatus()).isEqualTo(DocumentTaskStatus.SUCCESS);
        assertThat(documents.selectById(documentId).getStatus()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(published).containsExactly(DocumentTaskStatus.PROCESSING, DocumentTaskStatus.SUCCESS);
    }

    /** 通过 {@code tx.execute} 驱动用例，核对 {@code completed} 为 false、{@code tasks.selectById(taskId).getStatus()} 等于 {@code DocumentTaskStatus.CANCELLED}。 */
    @Test
    void cancelledAttemptCannotWritePreparedChunksOrPublishSuccess() {
        DocumentTask attempt = completion.claim(taskId, documentId);
        TransactionTemplate tx = new TransactionTemplate(transactions);
        assertThat(tx.execute(status -> completion.cancel(taskId, documentId)).getStatus()).isEqualTo("CANCELLED");

        Boolean completed = tx.execute(status -> completion.completeAttempt(attempt,
                () -> { throw new AssertionError("cancelled attempt must not write chunks"); }));

        assertThat(completed).isFalse();
        assertThat(tasks.selectById(taskId).getStatus()).isEqualTo(DocumentTaskStatus.CANCELLED);
        assertThat(documents.selectById(documentId).getStatus()).isEqualTo(DocumentStatus.CANCELLED);
        assertThat(published).containsExactly(DocumentTaskStatus.PROCESSING, DocumentTaskStatus.CANCELLED);
    }

    /** 通过 {@code completion.claim}、{@code tasks.selectById} 驱动该场景，核对 {@code tasks.selectById(taskId).getStatus()} 等于 {@code DocumentTaskStatus.PROCESSING}、{@code documents.selectById(documentId).getStatus()} 等于 {@code DocumentStatus.PROCESSING}。 */
    @Test
    void zeroRowDocumentWriteRollsBackSuccess() {
        DocumentTask attempt = completion.claim(taskId, documentId);
        suppressTerminalSuccessWrite();

        assertThatThrownBy(() -> complete(attempt)).hasMessageContaining("affected no row");

        assertThat(tasks.selectById(taskId).getStatus()).isEqualTo(DocumentTaskStatus.PROCESSING);
        assertThat(documents.selectById(documentId).getStatus()).isEqualTo(DocumentStatus.PROCESSING);
        assertThat(published).containsExactly(DocumentTaskStatus.PROCESSING);
    }

    /** 通过 {@code completion.claim}、{@code completion.markFailed} 驱动该场景，核对 {@code complete(attempt)} 为 false、{@code tasks.selectById(taskId).getStatus()} 等于 {@code DocumentTaskStatus.FAILED}。 */
    @Test
    void terminalCasLoserDoesNotChangeDocumentOrPublish() {
        DocumentTask attempt = completion.claim(taskId, documentId);
        assertThat(completion.markFailed(attempt, "failed", "PROCESSING_FAILED")).isTrue();

        assertThat(complete(attempt)).isFalse();

        assertThat(tasks.selectById(taskId).getStatus()).isEqualTo(DocumentTaskStatus.FAILED);
        assertThat(documents.selectById(documentId).getStatus()).isEqualTo(DocumentStatus.FAILED);
        assertThat(published).containsExactly(DocumentTaskStatus.PROCESSING, DocumentTaskStatus.FAILED);
    }

    /** 通过 {@code jdbc.update} 驱动用例，核对 {@code complete(attempt)} 为 false、{@code tasks.selectById(taskId)} 为空值。 */
    @Test
    void deletedDocumentCompletionIsNoOp() {
        DocumentTask attempt = completion.claim(taskId, documentId);
        jdbc.update("DELETE FROM document WHERE id=?", documentId);

        assertThat(complete(attempt)).isFalse();

        assertThat(tasks.selectById(taskId)).isNull();
        assertThat(published).containsExactly(DocumentTaskStatus.PROCESSING);
    }

    /** 由独立数据库连接读取终态通知，检查事件到达时任务和文档均已提交。 */
    @Test
    void terminalEventSeesCommittedRowsFromIndependentConnection() {
        DocumentTask attempt = completion.claim(taskId, documentId);
        doAnswer(call -> {
            try (var connection = dataSource.getConnection();
                 var statement = connection.prepareStatement("""
                         SELECT t.status, d.status FROM document_task t JOIN document d ON d.id=t.document_id
                         WHERE t.id=?
                         """)) {
                statement.setLong(1, taskId);
                try (var rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).isEqualTo(2);
                    assertThat(rows.getInt(2)).isEqualTo(2);
                }
            }
            published.add(call.getArgument(0, DocumentTask.class).getStatus());
            return null;
        }).when(events).publish(any(DocumentTask.class));

        assertThat(complete(attempt)).isTrue();

        assertThat(published).containsExactly(DocumentTaskStatus.PROCESSING, DocumentTaskStatus.SUCCESS);
    }

    /** 通过 {@code jdbc.update} 驱动用例，核对 {@code tasks.selectById(taskId).getStatus()} 等于 {@code DocumentTaskStatus.PROCESSING}、{@code documents.selectById(documentId).getStatus()} 等于 {@code DocumentStatus.PROCESSING}。 */
    @Test
    void retryScheduleRejectionCannotLeaveWaitingTaskWithProcessingDocument() {
        when(ai.parseDocument(anyLong(), anyString(), anyString(), anyString(), any()))
                .thenThrow(new org.springframework.web.client.ResourceAccessException("temporary parser outage"));
        doThrow(new java.util.concurrent.RejectedExecutionException("retry timer unavailable"))
                .when(dispatchCoordinator).scheduleRetry(any(), anyLong());
        createGuardFunction("IF NEW.id = " + documentId + " AND NEW.status = 0 THEN RETURN NULL; END IF;");

        assertThatThrownBy(() -> process.process(taskId)).hasMessageContaining("affected no row");

        assertThat(tasks.selectById(taskId).getStatus()).isEqualTo(DocumentTaskStatus.PROCESSING);
        assertThat(documents.selectById(documentId).getStatus()).isEqualTo(DocumentStatus.PROCESSING);
        jdbc.update("UPDATE document_task SET retry_not_before=clock_timestamp()-INTERVAL '1 second' WHERE id=?", taskId);
        assertThat(tasks.findRecoverableTasks(taskId - 1, 1)).extracting(DocumentTask::getId).contains(taskId);
        assertThat(published).containsExactly(DocumentTaskStatus.PROCESSING);
    }

    /** 通过 {@code completion.recoverRejectedRetry}、{@code tasks.selectById} 驱动该场景，核对 {@code tasks.selectById(taskId).getStatus()} 等于 {@code DocumentTaskStatus.PROCESSING}、{@code documents.selectById(documentId).getStatus()} 等于 {@code DocumentStatus.PROCESSING}。 */
    @Test
    void delayedRetryRejectionCannotCommitWaitingWhenDocumentWriteAffectsNoRow() {
        DocumentTask attempt = queueRetry();
        createGuardFunction("IF NEW.id = " + documentId + " AND NEW.status = 0 THEN RETURN NULL; END IF;");

        assertThatThrownBy(() -> completion.recoverRejectedRetry(attempt,
                new java.util.concurrent.RejectedExecutionException("worker pool saturated")))
                .hasMessageContaining("affected no row");

        assertThat(tasks.selectById(taskId).getStatus()).isEqualTo(DocumentTaskStatus.PROCESSING);
        assertThat(documents.selectById(documentId).getStatus()).isEqualTo(DocumentStatus.PROCESSING);
        assertThat(published).containsExactly(DocumentTaskStatus.PROCESSING);
    }

    /** 通过 {@code ai.parseDocument}、{@code process.process} 驱动该场景，核对 {@code task.getStatus()} 等于 {@code DocumentTaskStatus.WAITING}、{@code documents.selectById(documentId).getStatus()} 等于 {@code DocumentStatus.UPLOADED}。 */
    @Test
    void retryScheduleRejectionCommitsBothWaitingRowsAndKeepsParserFailure() {
        when(ai.parseDocument(anyLong(), anyString(), anyString(), anyString(), any()))
                .thenThrow(new org.springframework.web.client.ResourceAccessException("temporary parser outage"));
        doThrow(new java.util.concurrent.RejectedExecutionException("retry timer unavailable"))
                .when(dispatchCoordinator).scheduleRetry(any(), anyLong());
        assertWaitingEventSeesCommittedRows();

        process.process(taskId);

        DocumentTask task = tasks.selectById(taskId);
        assertThat(task.getStatus()).isEqualTo(DocumentTaskStatus.WAITING);
        assertThat(task.getErrorCode()).isEqualTo("TASK_DISPATCH_REJECTED");
        assertThat(task.getLastErrorCode()).isEqualTo("AI_UNAVAILABLE");
        assertThat(task.getLastErrorMessage()).isEqualTo("temporary parser outage");
        assertThat(documents.selectById(documentId).getStatus()).isEqualTo(DocumentStatus.UPLOADED);
        assertThat(published).containsExactly(DocumentTaskStatus.PROCESSING, DocumentTaskStatus.WAITING);
    }

    /** 在线程池拒绝延迟重试后，从事件回调确认等待状态已经提交。 */
    @Test
    void delayedRetryRejectionCommitsWaitingRowsBeforePublishing() {
        DocumentTask attempt = queueRetry();
        assertWaitingEventSeesCommittedRows();

        completion.recoverRejectedRetry(attempt,
                new java.util.concurrent.RejectedExecutionException("worker pool saturated"));

        DocumentTask task = tasks.selectById(taskId);
        assertThat(task.getStatus()).isEqualTo(DocumentTaskStatus.WAITING);
        assertThat(task.getErrorCode()).isEqualTo("TASK_DISPATCH_REJECTED");
        assertThat(task.getLastErrorCode()).isEqualTo("AI_UNAVAILABLE");
        assertThat(documents.selectById(documentId).getStatus()).isEqualTo(DocumentStatus.UPLOADED);
        assertThat(published).containsExactly(DocumentTaskStatus.PROCESSING, DocumentTaskStatus.WAITING);
    }

    /** 发起并发任务认领，再模拟租约到期接管，确认旧尝试的切片无法提交。 */
    @Test
    void simultaneousClaimsHaveOneWinnerAndTakeoverFencesOldChunks() throws Exception {
        var workers = Executors.newFixedThreadPool(2);
        var start = new CyclicBarrier(2);
        try {
            var first = workers.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return completion.claim(taskId, documentId);
            });
            var second = workers.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return completion.claim(taskId, documentId);
            });
            var attempts = java.util.stream.Stream.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS))
                    .filter(java.util.Objects::nonNull).toList();
            assertThat(attempts).hasSize(1);
            DocumentTask oldAttempt = attempts.get(0);
            assertThat(oldAttempt.getTotalAttemptCount()).isEqualTo(1);

            jdbc.update("UPDATE document_task SET lease_expires_at=clock_timestamp()-INTERVAL '1 second' WHERE id=?", taskId);
            DocumentTask newAttempt = completion.claim(taskId, documentId);
            assertThat(newAttempt.getTotalAttemptCount()).isEqualTo(2);
            Boolean oldCompleted = new TransactionTemplate(transactions).execute(status -> completion.completeAttempt(oldAttempt,
                    () -> { throw new AssertionError("expired attempt must not write chunks"); }));
            assertThat(oldCompleted).isFalse();
            assertThat(complete(newAttempt)).isTrue();
            assertThat(published).containsExactly(DocumentTaskStatus.PROCESSING, DocumentTaskStatus.PROCESSING,
                    DocumentTaskStatus.SUCCESS);
        } finally {
            workers.shutdownNow();
        }
    }

    /**
     * 协调 {@code completion.completeAttempt} 完成当前阶段，供后续处理使用。
     * @param attempt 本次调用传入的 {@code DocumentTask}
     * @return 调用方使用的 {@code Boolean}
     */
    private Boolean complete(DocumentTask attempt) {
        return new TransactionTemplate(transactions).execute(status -> completion.completeAttempt(attempt, () -> {}));
    }

    /** 通过 {@code completion.claim}、{@code tasks.recordRetryFailure} 驱动该场景，实际结果与固定预期一致。 */
    private DocumentTask queueRetry() {
        DocumentTask attempt = completion.claim(taskId, documentId);
        assertThat(tasks.recordRetryFailure(taskId, attempt.getTotalAttemptCount(), 60_000,
                "temporary parser outage", "AI_UNAVAILABLE")).isEqualTo(1);
        return attempt;
    }

    /** 通过 {@code TransactionSynchronizationManager.isActualTransactionActive}、{@code connection.prepareStatement} 驱动该场景，核对 {@code TransactionSynchronizationManager.isActualTransactionActive()} 为 false、{@code rows.next()} 为 true。 */
    private void assertWaitingEventSeesCommittedRows() {
        doAnswer(call -> {
            DocumentTask snapshot = call.getArgument(0);
            if (snapshot.getStatus() == DocumentTaskStatus.WAITING) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                try (var connection = dataSource.getConnection();
                     var statement = connection.prepareStatement("""
                             SELECT t.status, d.status FROM document_task t JOIN document d ON d.id=t.document_id
                             WHERE t.id=?
                             """)) {
                    statement.setLong(1, taskId);
                    try (var rows = statement.executeQuery()) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getInt(1)).isEqualTo(0);
                        assertThat(rows.getInt(2)).isEqualTo(0);
                    }
                }
            }
            published.add(snapshot.getStatus());
            return null;
        }).when(events).publish(any(DocumentTask.class));
    }

    /**
     * 协调 {@code createGuardFunction} 完成当前阶段，供后续处理使用。
     * @param includeFailure 本次调用传入的 {@code boolean}
     */
    private void failTerminalDocumentWrites(boolean includeFailure) {
        createGuardFunction("IF NEW.id = " + documentId + " AND NEW.status IN (2"
                + (includeFailure ? ", 3" : "") + ") THEN RAISE EXCEPTION 'injected terminal write failure'; END IF;");
    }

    /** 协调 {@code createGuardFunction} 完成当前阶段，供后续处理使用。 */
    private void suppressTerminalSuccessWrite() {
        createGuardFunction("IF NEW.id = " + documentId + " AND NEW.status = 2 THEN RETURN NULL; END IF;");
    }

    /**
     * 创建当前入口需要的对象或持久化记录。
     * 下游调用 {@code UUID.randomUUID}、{@code jdbc.execute}。
     *
     * @param condition 本次调用传入的 {@code String}
     */
    private void createGuardFunction(String condition) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        guardFunction = "terminal_guard_" + suffix;
        guardTrigger = "terminal_guard_trigger_" + suffix;
        jdbc.execute("CREATE FUNCTION " + guardFunction + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + condition + " RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER " + guardTrigger + " BEFORE UPDATE ON document FOR EACH ROW EXECUTE FUNCTION "
                + guardFunction + "()");
    }
}
