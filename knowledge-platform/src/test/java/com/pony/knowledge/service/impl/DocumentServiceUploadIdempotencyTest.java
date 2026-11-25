/* 验证上传回执复用、并发键冲突及多余对象回收。 */

package com.pony.knowledge.service.impl;

import com.pony.knowledge.audit.AuditLogService;
import com.pony.knowledge.audit.AuditAction;
import com.pony.knowledge.dto.DocumentUploadReceiptDTO;
import com.pony.knowledge.entity.Document;
import com.pony.knowledge.entity.DocumentStatus;
import com.pony.knowledge.entity.DocumentTask;
import com.pony.knowledge.entity.DocumentTaskStatus;
import com.pony.knowledge.entity.DocumentUploadReceiptRecord;
import com.pony.knowledge.exception.UploadIdempotencyConflictException;
import com.pony.knowledge.mapper.DocumentMapper;
import com.pony.knowledge.mapper.DocumentTaskMapper;
import com.pony.knowledge.mapper.DocumentUploadReceiptMapper;
import com.pony.knowledge.mapper.KnowledgeBaseMapper;
import com.pony.knowledge.service.KnowledgeBaseAccessService;
import com.pony.knowledge.service.storage.MinioService;
import com.pony.knowledge.service.task.TaskDispatchCoordinator;
import com.pony.knowledge.utils.CurrentUserUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import com.pony.knowledge.modelmanagement.index.KnowledgeIndexService;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class DocumentServiceUploadIdempotencyTest {

    private final DocumentMapper documentMapper = mock(DocumentMapper.class);
    private final DocumentTaskMapper taskMapper = mock(DocumentTaskMapper.class);
    private final DocumentUploadReceiptMapper receiptMapper = mock(DocumentUploadReceiptMapper.class);
    private final MinioService minio = mock(MinioService.class);
    private final CurrentUserUtil currentUser = mock(CurrentUserUtil.class);
    private final KnowledgeBaseAccessService access = mock(KnowledgeBaseAccessService.class);
    private final TaskDispatchCoordinator dispatch = mock(TaskDispatchCoordinator.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final DocumentServiceImpl service = new DocumentServiceImpl(documentMapper, minio,
            currentUser, taskMapper, receiptMapper, dispatch, "bucket",
            access, noopTxManager(), audit);

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

    private final KnowledgeIndexService indexes = mock(KnowledgeIndexService.class);

    /** 准备本组用例共享的测试数据和隔离环境。 */
    @BeforeEach void configureIndexBarrier() { service.setKnowledgeIndexService(indexes); }

    /**
     * 在该类型内部准备后续运行所需的数据。
     * @param contents 本次调用传入的 {@code String}
     * @return 调用方使用的 {@code MockMultipartFile}
     */
    private MockMultipartFile file(String contents) {
        return new MockMultipartFile("file", "guide.pdf", "application/pdf", contents.getBytes());
    }

    /**
     * 返回当前计算采用的 {@code receipt}。
     *
     * @return 当前记录中的 {@code receipt}
     */
    private DocumentUploadReceiptRecord winner() {
        DocumentUploadReceiptRecord receipt = new DocumentUploadReceiptRecord();
        receipt.setRequestKey("client-req-1");
        receipt.setContentSha256("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
        receipt.setDocumentId(77L);
        receipt.setTaskId(88L);
        return receipt;
    }

    /**
     * 更新当前对象的相应属性。
     * 下游调用 {@code documentMapper.selectById}、{@code taskMapper.selectById}。
     */
    private void setReceiptTargetsPresent() {
        Document document = new Document();
        document.setStatus(DocumentStatus.UPLOADED);
        DocumentTask task = new DocumentTask();
        task.setStatus(DocumentTaskStatus.WAITING);
        when(documentMapper.selectById(77L)).thenReturn(document);
        when(taskMapper.selectById(88L)).thenReturn(task);
    }

    /** 通过 {@code access.canMaintainDocuments} 更新当前对象的相应属性。 */
    private void setAuthorized() {
        when(currentUser.getUserId()).thenReturn(9L);
        when(access.canMaintainDocuments(9L, 5L)).thenReturn(true);
    }

    /** 重复提交相同请求键和文件，检查返回原回执且没有再次上传对象。 */
    @Test
    void sameKeyAndSameContentReusesExistingReceiptWithoutUploadingAgain() throws Exception {
        setAuthorized();
        setReceiptTargetsPresent();
        when(receiptMapper.find(9L, 5L, "client-req-1")).thenReturn(winner());

        DocumentUploadReceiptDTO receipt = service.upload(5L, file("hello"), "client-req-1");

        assertThat(receipt.isReused()).isTrue();
        assertThat(receipt.getDocumentId()).isEqualTo(77L);
        assertThat(receipt.getTaskId()).isEqualTo(88L);
        verify(minio, never()).upload(any());
        verify(dispatch, never()).dispatch(any());
        verifyNoInteractions(audit);
    }

    /** 以原请求键提交不同文件内容，验证产生冲突且对象存储不收到写入。 */
    @Test
    void sameKeyWithDifferentContentConflictsBeforeMinioUpload() throws Exception {
        setAuthorized();
        DocumentUploadReceiptRecord previous = winner();
        previous.setContentSha256("0".repeat(64));
        when(receiptMapper.find(9L, 5L, "client-req-1")).thenReturn(previous);

        assertThatThrownBy(() -> service.upload(5L, file("hello"), "client-req-1"))
                .isInstanceOf(UploadIdempotencyConflictException.class);
        verify(minio, never()).upload(any());
        verify(audit).recordFailure(eq(AuditAction.DOCUMENT_UPLOADED), eq("document"), isNull(),
                argThat(error -> error instanceof UploadIdempotencyConflictException),
                eq(Map.of("knowledge_base_id", 5L)));
        verifyNoMoreInteractions(audit);
    }

    /** 模拟数据库重复键竞争，检查输掉请求的对象被清理且复用先提交的回执。 */
    @Test
    void concurrentDuplicateReservationCleansUpLosingMinioObject() throws Exception {
        setAuthorized();
        setReceiptTargetsPresent();
        when(receiptMapper.find(9L, 5L, "client-req-1")).thenReturn(null, winner());
        when(receiptMapper.reserve(9L, 5L, "client-req-1",
                "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824")).thenReturn(0);
        when(minio.upload(any())).thenReturn("losing-object.pdf");

        DocumentUploadReceiptDTO receipt = service.upload(5L, file("hello"), "client-req-1");

        assertThat(receipt.isReused()).isTrue();
        assertThat(receipt.getDocumentId()).isEqualTo(77L);
        verify(documentMapper, never()).insert(any(Document.class));
        verify(taskMapper, never()).insert(any(DocumentTask.class));
        verify(minio).delete("losing-object.pdf");
        verify(dispatch, never()).dispatch(any());
        verifyNoInteractions(audit);
    }

    /** 模拟上传提交后的回执读取故障，检查异常处理保留已提交的数据与对象。 */
    @Test
    void receiptLookupFailureDoesNotMisreportAnAlreadyCommittedUpload() {
        setAuthorized();
        when(receiptMapper.find(9L, 5L, "client-req-1")).thenReturn(winner());
        when(documentMapper.selectById(77L)).thenThrow(new RuntimeException("lookup failed"));

        assertThatThrownBy(() -> service.upload(5L, file("hello"), "client-req-1"))
                .hasMessage("文件上传失败");

        verifyNoInteractions(audit, minio, dispatch);
    }
}
