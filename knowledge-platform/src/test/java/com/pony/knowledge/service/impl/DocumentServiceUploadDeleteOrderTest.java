/* 验证文档上传和删除时数据库提交与 MinIO 操作的先后顺序。 */

package com.pony.knowledge.service.impl;

import com.pony.knowledge.audit.AuditLogService;
import com.pony.knowledge.audit.AuditAction;
import com.pony.knowledge.entity.Document;
import com.pony.knowledge.entity.DocumentStatus;
import com.pony.knowledge.entity.DocumentTask;
import com.pony.knowledge.entity.DocumentTaskStatus;
import com.pony.knowledge.exception.BusinessException;
import com.pony.knowledge.exception.ForbiddenException;
import com.pony.knowledge.mapper.DocumentMapper;
import com.pony.knowledge.mapper.DocumentTaskMapper;
import com.pony.knowledge.mapper.DocumentUploadReceiptMapper;
import com.pony.knowledge.service.task.TaskDispatchCoordinator;
import com.pony.knowledge.service.KnowledgeBaseAccessService;
import com.pony.knowledge.service.storage.MinioService;
import com.pony.knowledge.utils.CurrentUserUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import com.pony.knowledge.modelmanagement.index.KnowledgeIndexService;
import com.pony.knowledge.modelmanagement.index.IndexLifecycleException;
import org.springframework.http.HttpStatus;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.util.concurrent.RejectedExecutionException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * 上传与删除的两个关键顺序契约：
 * 1) 权限与类型校验必须在 MinIO 上传之前（否则非法请求会在对象存储留下无人认领的垃圾对象）；
 * 2) 删除必须先删数据库记录、再删对象文件，且文件删除失败不能让整个请求失败。
 */
class DocumentServiceUploadDeleteOrderTest {

    private final DocumentMapper documentMapper = mock(DocumentMapper.class);
    private final DocumentTaskMapper documentTaskMapper = mock(DocumentTaskMapper.class);
    private final DocumentUploadReceiptMapper uploadReceiptMapper = mock(DocumentUploadReceiptMapper.class);
    private final MinioService minioService = mock(MinioService.class);
    private final CurrentUserUtil currentUserUtil = mock(CurrentUserUtil.class);
    private final KnowledgeBaseAccessService access = mock(KnowledgeBaseAccessService.class);
    private final TaskDispatchCoordinator taskDispatchCoordinator = mock(TaskDispatchCoordinator.class);
    private final PlatformTransactionManager transactionManager = noopTxManager();
    private final AuditLogService audit = mock(AuditLogService.class);

    /**
     * 返回当前计算采用的 {@code tx}。
     *
     * @return 当前记录中的 {@code tx}
     */
    private static PlatformTransactionManager noopTxManager() {
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any(TransactionDefinition.class))).thenReturn(mock(TransactionStatus.class));
        return tx;
    }

    private final DocumentServiceImpl svc = new DocumentServiceImpl(
            documentMapper, minioService, currentUserUtil,
            documentTaskMapper, uploadReceiptMapper, taskDispatchCoordinator, "bucket",
            access, transactionManager, audit);

    private final KnowledgeIndexService indexes = mock(KnowledgeIndexService.class);

    /** 准备本组用例共享的测试数据和隔离环境。 */
    @BeforeEach void configureIndexBarrier() { svc.setKnowledgeIndexService(indexes); }

    /**
     * 在该类型内部准备后续运行所需的数据。
     * @param name 本次调用传入的 {@code String}
     * @return 调用方使用的 {@code MockMultipartFile}
     */
    private MockMultipartFile file(String name) {
        return new MockMultipartFile("file", name, "application/octet-stream", new byte[]{1});
    }

    /** 通过 {@code access.canMaintainDocuments}、{@code svc.upload} 驱动该场景，抛出 {@code BusinessException}，跳过受保护的调用。 */
    @Test
    void unsupportedExtensionIsRejectedBeforeAnythingReachesMinio() throws Exception {
        when(currentUserUtil.getUserId()).thenReturn(9L);
        when(access.canMaintainDocuments(9L, 5L)).thenReturn(true);

        assertThatThrownBy(() -> svc.upload(5L, file("payload.exe")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持的文档类型");

        verify(minioService, never()).upload(any());
        verify(documentMapper, never()).insert(any(Document.class));
        verifyUploadFailure(BusinessException.class);
    }

    /** 通过 {@code access.canMaintainDocuments}、{@code svc.upload} 驱动该场景，抛出 {@code ForbiddenException}，跳过受保护的调用。 */
    @Test
    void permissionCheckRunsBeforeUploadSoNoOrphanObjectIsWritten() throws Exception {
        when(currentUserUtil.getUserId()).thenReturn(9L);
        when(access.canMaintainDocuments(9L, 5L)).thenReturn(false);

        assertThatThrownBy(() -> svc.upload(5L, file("手册.pdf")))
                .isInstanceOf(ForbiddenException.class);

        verifyNoInteractions(minioService, documentMapper, documentTaskMapper,
                uploadReceiptMapper, taskDispatchCoordinator);
        verifyUploadFailure(ForbiddenException.class);
    }

    /** 通过 {@code access.canMaintainDocuments}、{@code svc.upload} 驱动该场景，抛出 {@code IndexLifecycleException}，跳过受保护的调用。 */
    @Test
    void maintenanceRejectsUploadBeforeWritingAnObjectOrDocument() throws Exception {
        when(currentUserUtil.getUserId()).thenReturn(9L);
        when(access.canMaintainDocuments(9L, 5L)).thenReturn(true);
        doThrow(new IndexLifecycleException(HttpStatus.CONFLICT, "index_maintenance_conflict", "知识库正在重建索引"))
                .when(indexes).assertWritable(5L);

        assertThatThrownBy(() -> svc.upload(5L, file("guide.pdf"))).isInstanceOf(IndexLifecycleException.class);

        verifyNoInteractions(minioService, documentMapper, documentTaskMapper, taskDispatchCoordinator);
        verifyUploadFailure(IndexLifecycleException.class);
    }

    /** 通过 {@code svc.delete} 驱动用例，抛出 {@code IndexLifecycleException}，跳过受保护的调用。 */
    @Test
    void maintenanceRejectsOwnedDocumentDeletionBeforeDatabaseAndObjectRemoval() throws Exception {
        ownedDocument();
        doThrow(new IndexLifecycleException(HttpStatus.CONFLICT, "index_maintenance_conflict", "知识库正在重建索引"))
                .when(indexes).acquireWriteBarrier(5L);

        assertThatThrownBy(() -> svc.delete(7L)).isInstanceOf(IndexLifecycleException.class);

        verify(documentMapper, never()).deleteById(any(Long.class));
        verifyNoInteractions(minioService);
        verifyDeleteFailure(IndexLifecycleException.class);
    }

    /** 通过 {@code svc.findUploadReceipt} 驱动该场景，抛出 {@code ForbiddenException}，跳过受保护的调用。 */
    @Test
    void receiptPermissionFailureDoesNotReadReceiptOrDocumentState() {
        when(currentUserUtil.getUserId()).thenReturn(9L);

        assertThatThrownBy(() -> svc.findUploadReceipt(5L, "request-1"))
                .isInstanceOf(ForbiddenException.class);

        verify(access).canMaintainDocuments(9L, 5L);
        verifyNoInteractions(minioService, documentMapper, documentTaskMapper,
                uploadReceiptMapper, taskDispatchCoordinator);
    }

    /** 通过 {@code svc.delete} 驱动用例，抛出 {@code ForbiddenException}，跳过受保护的调用。 */
    @Test
    void deleteWithoutOwnedDocumentDoesNotDeleteRowsOrObjects() {
        when(currentUserUtil.getUserId()).thenReturn(9L);

        assertThatThrownBy(() -> svc.delete(7L)).isInstanceOf(ForbiddenException.class);

        verify(documentMapper).selectOwnedDocument(7L, 9L);
        verify(documentMapper, never()).deleteById(any(Long.class));
        verifyNoInteractions(minioService, documentTaskMapper, uploadReceiptMapper,
                taskDispatchCoordinator, access);
        verifyDeleteFailure(ForbiddenException.class);
    }

    /** 通过 {@code access.canMaintainDocuments}、{@code minioService.upload} 驱动该场景，核对 {@code saved.getStatus()} 等于 {@code DocumentStatus.UPLOADED}、{@code task.getDocumentId()} 等于 {@code 77L}。 */
    @Test
    void acceptedUploadPersistsEnumStatusAndTriggersTask() throws Exception {
        when(currentUserUtil.getUserId()).thenReturn(9L);
        when(access.canMaintainDocuments(9L, 5L)).thenReturn(true);
        when(minioService.upload(any())).thenReturn("2f1c.pdf");
        when(documentMapper.insert(any(Document.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, Document.class).setId(77L);
            return 1;
        });
        when(documentTaskMapper.insert(any(DocumentTask.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, DocumentTask.class).setId(88L);
            return 1;
        });

        svc.upload(5L, file("手册.pdf"));

        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(documentMapper).insert(captor.capture());
        Document saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(DocumentStatus.UPLOADED);
        assertThat(saved.getFilePath()).isEqualTo("2f1c.pdf");
        // key 里去掉了文件名，但原始文件名仍完整留在库表里供下载与解析取扩展名
        assertThat(saved.getFileName()).isEqualTo("手册.pdf");

        ArgumentCaptor<DocumentTask> taskCaptor = ArgumentCaptor.forClass(DocumentTask.class);
        verify(documentTaskMapper).insert(taskCaptor.capture());
        DocumentTask task = taskCaptor.getValue();
        assertThat(task.getDocumentId()).isEqualTo(77L);
        assertThat(task.getStatus()).isEqualTo(DocumentTaskStatus.WAITING);
        verify(taskDispatchCoordinator).dispatch(88L);
        verify(audit).record(AuditAction.DOCUMENT_UPLOADED, "document", 77L,
                Map.of("knowledge_base_id", 5L, "file_name", "手册.pdf"));
        verifyNoMoreInteractions(audit);
    }

    /** 通过 {@code access.canMaintainDocuments}、{@code minioService.upload} 驱动该场景，核对输出和依赖调用。 */
    @Test
    void dispatchRejectedAfterCommit_doesNotReportUploadFailure() throws Exception {
        // 本章 3.1 P1-16 / 边界 4：⑥ 被线程池拒绝时 ④⑤ 已提交，再抛「上传失败」会让用户重传 → 重复文档。
        // 正确行为是记 ERROR 并正常返回，让 WAITING 任务等启动恢复。
        when(currentUserUtil.getUserId()).thenReturn(9L);
        when(access.canMaintainDocuments(9L, 5L)).thenReturn(true);
        when(minioService.upload(any())).thenReturn("2f1c.pdf");
        when(documentMapper.insert(any(Document.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, Document.class).setId(77L);
            return 1;
        });
        when(documentTaskMapper.insert(any(DocumentTask.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, DocumentTask.class).setId(88L);
            return 1;
        });
        doThrow(new RejectedExecutionException("pool full"))
                .when(taskDispatchCoordinator).dispatch(any());

        svc.upload(5L, file("手册.pdf"));       // ← 不抛异常

        verify(documentMapper).insert(any(Document.class));
        verify(documentTaskMapper).insert(any(DocumentTask.class));
        verify(audit).record(AuditAction.DOCUMENT_UPLOADED, "document", 77L,
                Map.of("knowledge_base_id", 5L, "file_name", "手册.pdf"));
        verifyNoMoreInteractions(audit);
    }

    /** 通过 {@code access.canMaintainDocuments}、{@code minioService.upload} 驱动该场景，抛出 {@code RuntimeException}，跳过受保护的调用。 */
    @Test
    void taskInsertFailureFailsTheUpload() throws Exception {
        // ④⑤ 同一事务：任务写不进去时整个上传失败（用户没看到成功，重传不会产生两份）
        when(currentUserUtil.getUserId()).thenReturn(9L);
        when(access.canMaintainDocuments(9L, 5L)).thenReturn(true);
        when(minioService.upload(any())).thenReturn("2f1c.pdf");
        when(documentMapper.insert(any(Document.class))).thenReturn(1);
        when(documentTaskMapper.insert(any(DocumentTask.class)))
                .thenThrow(new RuntimeException("document_task 写失败"));
        doThrow(new RuntimeException("should not be dispatched"))
                .when(taskDispatchCoordinator).dispatch(any());

        assertThatThrownBy(() -> svc.upload(5L, file("手册.pdf")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("文件上传失败");

        verify(taskDispatchCoordinator, never()).dispatch(any());
        verifyUploadFailure(RuntimeException.class);
    }

    /** 通过 {@code svc.delete} 驱动用例，核对输出和依赖调用。 */
    @Test
    void deleteRemovesRecordFirstEvenWhenObjectStorageFails() throws Exception {
        Document owned = new Document();
        owned.setId(7L);
        owned.setKnowledgeBaseId(5L);
        owned.setFilePath("2f1c.pdf");
        owned.setStatus(DocumentStatus.UPLOADED);
        when(currentUserUtil.getUserId()).thenReturn(9L);
        when(documentMapper.selectOwnedDocument(7L, 9L)).thenReturn(owned);
        when(documentMapper.deleteById(7L)).thenReturn(1);
        doThrow(new RuntimeException("minio down")).when(minioService).delete(anyString());

        svc.delete(7L);

        // 记录已删除 + 对象删除失败只记日志：顺序保证「文件没了但记录还在」的永久损坏不再可能出现
        verify(documentMapper).deleteById(7L);
        verify(minioService).delete("2f1c.pdf");
        verify(audit).record(AuditAction.KNOWLEDGE_DELETED, "document", 7L, Map.of("knowledge_base_id", 5L));
        verifyNoMoreInteractions(audit);
    }

    /** 通过 {@code svc.delete} 驱动用例，核对输出和依赖调用。 */
    @Test
    void deleteRemovesObjectAfterRecordOnHappyPath() throws Exception {
        Document owned = new Document();
        owned.setId(7L);
        owned.setKnowledgeBaseId(5L);
        owned.setFilePath("2f1c.pdf");
        when(currentUserUtil.getUserId()).thenReturn(9L);
        when(documentMapper.selectOwnedDocument(7L, 9L)).thenReturn(owned);
        when(documentMapper.deleteById(7L)).thenReturn(1);

        svc.delete(7L);

        InOrder order = inOrder(documentMapper, transactionManager, minioService);
        order.verify(documentMapper).deleteById(7L);
        order.verify(transactionManager).commit(any(TransactionStatus.class));
        order.verify(minioService).delete("2f1c.pdf");
    }

    /** 通过 {@code svc.delete} 驱动用例，抛出 {@code RuntimeException}，跳过受保护的调用。 */
    @Test
    void databaseDeleteFailureKeepsObjectForRetry() throws Exception {
        Document owned = ownedDocument();
        when(documentMapper.deleteById(7L)).thenThrow(new RuntimeException("cascade failed"));

        assertThatThrownBy(() -> svc.delete(7L)).hasMessageContaining("文档删除失败");

        verify(transactionManager).rollback(any(TransactionStatus.class));
        verify(minioService, never()).delete(owned.getFilePath());
        verifyDeleteFailure(RuntimeException.class);
    }

    /** 通过 {@code svc.delete} 驱动用例，抛出 {@code RuntimeException}，跳过受保护的调用。 */
    @Test
    void databaseCommitFailureKeepsObjectForRetry() throws Exception {
        Document owned = ownedDocument();
        when(documentMapper.deleteById(7L)).thenReturn(1);
        doThrow(new RuntimeException("commit failed")).when(transactionManager)
                .commit(any(TransactionStatus.class));

        assertThatThrownBy(() -> svc.delete(7L)).hasMessageContaining("文档删除失败");

        verify(minioService, never()).delete(owned.getFilePath());
        verifyDeleteFailure(RuntimeException.class);
    }

    /** 通过 {@code svc.delete} 驱动用例，抛出 {@code BusinessException}，跳过受保护的调用。 */
    @Test
    void zeroRowDeleteDoesNotRemoveObject() throws Exception {
        Document owned = ownedDocument();
        when(documentMapper.deleteById(7L)).thenReturn(0);

        assertThatThrownBy(() -> svc.delete(7L)).isInstanceOf(BusinessException.class);

        verify(minioService, never()).delete(owned.getFilePath());
        verifyDeleteFailure(BusinessException.class);
    }

    /** 通过 {@code access.canMaintainDocuments}、{@code minioService.upload} 驱动该场景，按异常路径结束，跳过受保护的调用。 */
    @Test
    void uploadCommitFailureIsAuditedAfterTransactionExitAndCleansItsObject() throws Exception {
        when(currentUserUtil.getUserId()).thenReturn(9L);
        when(access.canMaintainDocuments(9L, 5L)).thenReturn(true);
        when(minioService.upload(any())).thenReturn("uncommitted.pdf");
        when(documentMapper.insert(any(Document.class))).thenAnswer(call -> {
            call.getArgument(0, Document.class).setId(77L);
            return 1;
        });
        when(documentTaskMapper.insert(any(DocumentTask.class))).thenAnswer(call -> {
            call.getArgument(0, DocumentTask.class).setId(88L);
            return 1;
        });
        doThrow(new RuntimeException("commit failed")).when(transactionManager).commit(any(TransactionStatus.class));

        assertThatThrownBy(() -> svc.upload(5L, file("guide.pdf"))).hasMessage("文件上传失败");

        InOrder order = inOrder(transactionManager, minioService, audit);
        order.verify(transactionManager).commit(any(TransactionStatus.class));
        order.verify(minioService).delete("uncommitted.pdf");
        order.verify(audit).recordFailure(eq(AuditAction.DOCUMENT_UPLOADED), eq("document"), isNull(),
                any(Exception.class), eq(Map.of("knowledge_base_id", 5L)));
        verifyNoInteractions(taskDispatchCoordinator);
        verifyNoMoreInteractions(audit);
    }

    /**
     * 协调 {@code argThat} 完成当前阶段，供后续处理使用。
     * @param type 本次调用传入的 {@code Class<? extends Exception>}
     */
    private void verifyUploadFailure(Class<? extends Exception> type) {
        verify(audit).recordFailure(eq(AuditAction.DOCUMENT_UPLOADED), eq("document"), isNull(),
                argThat(type::isInstance), eq(Map.of("knowledge_base_id", 5L)));
        verifyNoMoreInteractions(audit);
    }

    /**
     * 协调 {@code argThat} 完成当前阶段，供后续处理使用。
     * @param type 本次调用传入的 {@code Class<? extends Exception>}
     */
    private void verifyDeleteFailure(Class<? extends Exception> type) {
        verify(audit).recordFailure(eq(AuditAction.KNOWLEDGE_DELETED), eq("document"), eq(7L),
                argThat(type::isInstance), isNull());
        verifyNoMoreInteractions(audit);
    }

    /**
     * 协调 {@code documentMapper.selectOwnedDocument} 完成当前阶段，供后续处理使用。
     * 下游调用 {@code documentMapper.selectOwnedDocument}。
     *
     * @return 当前记录中的 {@code owned}
     */
    private Document ownedDocument() {
        Document owned = new Document();
        owned.setId(7L);
        owned.setKnowledgeBaseId(5L);
        owned.setFilePath("2f1c.pdf");
        when(currentUserUtil.getUserId()).thenReturn(9L);
        when(documentMapper.selectOwnedDocument(7L, 9L)).thenReturn(owned);
        return owned;
    }
}
