/* 验证文档任务读取、手动重试及取消使用的用户权限边界。 */

package com.pony.knowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.pony.knowledge.dto.DocumentTaskDTO;
import com.pony.knowledge.entity.Document;
import com.pony.knowledge.entity.DocumentTask;
import com.pony.knowledge.entity.DocumentTaskStatus;
import com.pony.knowledge.exception.ForbiddenException;
import com.pony.knowledge.mapper.DocumentMapper;
import com.pony.knowledge.mapper.DocumentTaskMapper;
import com.pony.knowledge.mapper.DocumentUploadReceiptMapper;
import com.pony.knowledge.service.KnowledgeBaseAccessService;
import com.pony.knowledge.service.task.TaskDispatchCoordinator;
import com.pony.knowledge.service.task.TaskCompletionService;
import com.pony.knowledge.utils.CurrentUserUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import com.pony.knowledge.modelmanagement.index.KnowledgeIndexService;
import com.pony.knowledge.modelmanagement.index.IndexLifecycleException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DocumentTaskServiceAuthorizationTest {

    private final DocumentMapper documents = mock(DocumentMapper.class);
    private final DocumentTaskMapper tasks = mock(DocumentTaskMapper.class);
    private final TaskDispatchCoordinator dispatcher = mock(TaskDispatchCoordinator.class);
    private final KnowledgeBaseAccessService access = mock(KnowledgeBaseAccessService.class);
    private final CurrentUserUtil currentUser = mock(CurrentUserUtil.class);
    private final TaskCompletionService completion = mock(TaskCompletionService.class);
    private final DocumentUploadReceiptMapper receipts = mock(DocumentUploadReceiptMapper.class);
    private final DocumentTaskServiceImpl service = new DocumentTaskServiceImpl(
            tasks, dispatcher, documents, currentUser, access, completion, receipts);

    private final KnowledgeIndexService indexes = mock(KnowledgeIndexService.class);

    /** 准备本组用例共享的测试数据和隔离环境。 */
    @BeforeEach void configureIndexBarrier() { service.setKnowledgeIndexService(indexes); }

    /**
     * 返回当前计算采用的 {@code document}。
     *
     * @param id 本次调用传入的 {@code Long}
     * @param libraryId 本次调用传入的 {@code Long}
     * @return 当前记录中的 {@code document}
     */
    private Document document(Long id, Long libraryId) {
        Document document = new Document();
        document.setId(id);
        document.setKnowledgeBaseId(libraryId);
        return document;
    }

    /**
     * 返回当前计算采用的 {@code task}。
     *
     * @param documentId 目标文档 ID
     * @return 当前记录中的 {@code task}
     */
    private DocumentTask task(Long documentId) {
        DocumentTask task = new DocumentTask();
        task.setId(200L);
        task.setDocumentId(documentId);
        task.setStatus(DocumentTaskStatus.FAILED);
        return task;
    }

    /** 通过 {@code documents.selectById}、{@code access.canRead} 驱动该场景，跳过受保护的调用，实际结果与固定预期一致。 */
    @Test
    void memberCanReadLatestTaskOfAuthorizedDocument() {
        when(currentUser.getUserId()).thenReturn(9L);
        when(documents.selectById(100L)).thenReturn(document(100L, 7L));
        when(access.canRead(9L, 7L)).thenReturn(true);
        when(tasks.selectOne(any(QueryWrapper.class))).thenReturn(task(100L));

        DocumentTaskDTO result = service.getTask(100L);

        assertEquals(100L, result.getDocumentId());
        assertEquals("FAILED", result.getStatus());
        verify(access).canRead(9L, 7L);
        verifyNoInteractions(dispatcher);
    }

    /** 通过 {@code documents.selectById}、{@code access.canRead} 驱动该场景，抛出 {@code ForbiddenException}，跳过受保护的调用。 */
    @Test
    void taskReadChecksActualDocumentLibraryBeforeReadingTask() {
        when(currentUser.getUserId()).thenReturn(9L);
        when(documents.selectById(100L)).thenReturn(document(100L, 8L));
        when(access.canRead(9L, 7L)).thenReturn(true);

        assertThrows(ForbiddenException.class, () -> service.getTask(100L));

        verify(access).canRead(9L, 8L);
        verifyNoInteractions(tasks, dispatcher);
    }

    /** 构造输入与依赖返回值，抛出 {@code ForbiddenException}，跳过受保护的调用。 */
    @Test
    void missingDocumentDoesNotRevealTaskOrResolvePermissions() {
        when(currentUser.getUserId()).thenReturn(9L);

        assertThrows(ForbiddenException.class, () -> service.getTask(404L));

        verifyNoInteractions(access, tasks, dispatcher);
    }

    /** 通过 {@code service.retry} 驱动该场景，抛出 {@code ForbiddenException}，跳过受保护的调用。 */
    @Test
    void retryWithoutOwnedDocumentDoesNotReadOrModifyTaskOrDispatch() {
        when(currentUser.getUserId()).thenReturn(9L);

        assertThrows(ForbiddenException.class, () -> service.retry(100L));

        verify(documents).selectOwnedDocument(100L, 9L);
        verifyNoInteractions(access, tasks, dispatcher);
    }

    /** 通过 {@code access.canRead}、{@code service.cancel} 驱动该场景，抛出 {@code ForbiddenException}，跳过受保护的调用。 */
    @Test
    void memberCannotCancelTask() {
        when(currentUser.getUserId()).thenReturn(9L);
        when(access.canRead(9L, 7L)).thenReturn(true);

        assertThrows(ForbiddenException.class, () -> service.cancel(100L, 200L));

        verify(documents).selectOwnedDocument(100L, 9L);
        verifyNoInteractions(completion, tasks, dispatcher);
    }

    /** 通过 {@code documents.selectOwnedDocument}、{@code documents.selectByIdForUpdate} 驱动该场景，跳过受保护的调用。 */
    @Test
    void creatorRetryKeepsDocumentOwnershipSqlAndExistingTaskId() {
        when(currentUser.getUserId()).thenReturn(9L);
        when(documents.selectOwnedDocument(100L, 9L)).thenReturn(document(100L, 7L));
        when(documents.selectByIdForUpdate(100L)).thenReturn(document(100L, 7L));
        when(documents.updateById(any(Document.class))).thenReturn(1);
        when(tasks.selectOne(any(QueryWrapper.class))).thenReturn(task(100L));
        when(tasks.retryTask(200L)).thenReturn(1);

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.retry(100L);
            verifyNoInteractions(dispatcher);
            TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
        } finally { TransactionSynchronizationManager.clearSynchronization(); }

        var order = inOrder(documents, tasks, dispatcher);
        order.verify(documents).selectOwnedDocument(100L, 9L);
        order.verify(tasks).selectOne(any(QueryWrapper.class));
        order.verify(tasks).retryTask(200L);
        order.verify(dispatcher).dispatch(200L);
        verifyNoInteractions(access);
    }

    /** 重试已取消任务，验证旧任务保留取消终态，新任务承接上传回执。 */
    @Test
    void retryOfCancelledTaskKeepsOldIdTerminalAndMovesReceiptToNewTask() {
        DocumentTask cancelled = task(100L);
        cancelled.setStatus(DocumentTaskStatus.CANCELLED);
        cancelled.setManualRetryCount(0);
        cancelled.setTotalAttemptCount(3);
        when(currentUser.getUserId()).thenReturn(9L);
        when(documents.selectOwnedDocument(100L, 9L)).thenReturn(document(100L, 7L));
        when(documents.selectByIdForUpdate(100L)).thenReturn(document(100L, 7L));
        when(documents.updateById(any(Document.class))).thenReturn(1);
        when(tasks.selectOne(any(QueryWrapper.class))).thenReturn(cancelled);
        when(tasks.insert(any(DocumentTask.class))).thenAnswer(call -> {
            DocumentTask replacement = call.getArgument(0);
            assertEquals(DocumentTaskStatus.WAITING, replacement.getStatus());
            assertEquals(3, replacement.getTotalAttemptCount());
            replacement.setId(201L);
            return 1;
        });

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.retry(100L);
            verify(receipts).followRetriedTask(100L, 200L, 201L);
            verifyNoInteractions(dispatcher);
            TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
        } finally { TransactionSynchronizationManager.clearSynchronization(); }

        verify(tasks, never()).retryTask(200L);
        verify(dispatcher).dispatch(201L);
    }

    /** 通过 {@code documents.selectOwnedDocument}、{@code documents.selectById} 驱动该场景，抛出 {@code IndexLifecycleException}，跳过受保护的调用。 */
    @Test
    void maintenanceRejectsManualRetryBeforeChangingOrDispatchingTask() {
        when(currentUser.getUserId()).thenReturn(9L);
        when(documents.selectOwnedDocument(100L, 9L)).thenReturn(document(100L, 7L));
        when(documents.selectById(100L)).thenReturn(document(100L, 7L));
        doThrow(new IndexLifecycleException(HttpStatus.CONFLICT, "index_maintenance_conflict", "知识库正在重建索引"))
                .when(indexes).acquireWriteBarrier(7L);

        assertThrows(IndexLifecycleException.class, () -> service.retry(100L));

        verifyNoInteractions(tasks, dispatcher);
    }
}
