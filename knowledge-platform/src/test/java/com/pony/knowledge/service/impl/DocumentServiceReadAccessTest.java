/* 验证文档列表和切片查询限制在当前用户可读知识库。 */

package com.pony.knowledge.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pony.knowledge.audit.AuditLogService;
import com.pony.knowledge.dto.DocumentPageQueryDTO;
import com.pony.knowledge.entity.Document;
import com.pony.knowledge.exception.ForbiddenException;
import com.pony.knowledge.mapper.DocumentMapper;
import com.pony.knowledge.mapper.DocumentTaskMapper;
import com.pony.knowledge.mapper.DocumentUploadReceiptMapper;
import com.pony.knowledge.service.task.TaskDispatchCoordinator;
import com.pony.knowledge.service.KnowledgeBaseAccessService;
import com.pony.knowledge.service.storage.MinioService;
import com.pony.knowledge.utils.CurrentUserUtil;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DocumentServiceReadAccessTest {

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

    /**
     * 协调 {@code noopTxManager} 完成当前阶段，供后续处理使用。
     * 下游调用 {@code noopTxManager}。
     *
     * @param documentMapper 本次调用传入的 {@code DocumentMapper}
     * @param access 本次调用传入的 {@code KnowledgeBaseAccessService}
     * @param cu 本次调用传入的 {@code CurrentUserUtil}
     * @return 调用方使用的 {@code DocumentServiceImpl}
     */
    private DocumentServiceImpl newService(DocumentMapper documentMapper, KnowledgeBaseAccessService access,
            CurrentUserUtil cu) {
        return new DocumentServiceImpl(documentMapper, mock(MinioService.class), cu,
                mock(DocumentTaskMapper.class), mock(DocumentUploadReceiptMapper.class),
                mock(TaskDispatchCoordinator.class), "bucket",
                access, noopTxManager(), mock(AuditLogService.class));
    }

    /** 通过 {@code access.canRead}、{@code documentMapper.selectPageByKnowledgeBaseId} 驱动该场景，实际结果与固定预期一致。 */
    @Test
    void page_memberIsAllowed() {
        KnowledgeBaseAccessService access = mock(KnowledgeBaseAccessService.class);
        CurrentUserUtil cu = mock(CurrentUserUtil.class);
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        when(cu.getUserId()).thenReturn(9L);
        when(access.canRead(9L, 7L)).thenReturn(true);

        // 成员通过读校验后，应真正走到查询并拿到该知识库的文档
        Document doc = new Document();
        doc.setId(1L);
        doc.setKnowledgeBaseId(7L);
        Page<Document> resultPage = new Page<>();
        resultPage.setRecords(List.of(doc));
        when(documentMapper.selectPageByKnowledgeBaseId(any(Page.class), eq(7L))).thenReturn(resultPage);

        DocumentServiceImpl svc = newService(documentMapper, access, cu);
        DocumentPageQueryDTO dto = new DocumentPageQueryDTO();
        dto.setKnowledgeBaseId(7L);
        IPage<Document> result = svc.page(dto);   // member path reaches query and returns the doc
        assertNotNull(result);
        assertEquals(1, result.getRecords().size());
        assertEquals(7L, result.getRecords().get(0).getKnowledgeBaseId());
    }

    /** 通过 {@code access.canRead}、{@code svc.page} 驱动该场景，抛出 {@code ForbiddenException}，跳过受保护的调用。 */
    @Test
    void page_stranger_throws403() {
        KnowledgeBaseAccessService access = mock(KnowledgeBaseAccessService.class);
        CurrentUserUtil cu = mock(CurrentUserUtil.class);
        when(cu.getUserId()).thenReturn(9L);
        when(access.canRead(9L, 7L)).thenReturn(false);

        DocumentMapper documents = mock(DocumentMapper.class);
        DocumentServiceImpl svc = newService(documents, access, cu);
        DocumentPageQueryDTO dto = new DocumentPageQueryDTO();
        dto.setKnowledgeBaseId(7L);
        assertThrows(ForbiddenException.class, () -> svc.page(dto));
        verifyNoInteractions(documents);
    }
}
