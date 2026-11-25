/* 验证文档切片分页读取及向页面返回的来源字段。 */

package com.pony.knowledge.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pony.knowledge.audit.AuditLogService;
import com.pony.knowledge.dto.DocumentChunkDTO;
import com.pony.knowledge.dto.DocumentChunkPageQueryDTO;
import com.pony.knowledge.entity.Document;
import com.pony.knowledge.exception.ForbiddenException;
import com.pony.knowledge.mapper.DocumentMapper;
import com.pony.knowledge.mapper.DocumentTaskMapper;
import com.pony.knowledge.mapper.DocumentUploadReceiptMapper;
import com.pony.knowledge.service.KnowledgeBaseAccessService;
import com.pony.knowledge.service.storage.MinioService;
import com.pony.knowledge.service.task.TaskDispatchCoordinator;
import com.pony.knowledge.utils.CurrentUserUtil;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DocumentChunkReadTest {
    private final DocumentMapper documents = mock(DocumentMapper.class);
    private final CurrentUserUtil users = mock(CurrentUserUtil.class);
    private final KnowledgeBaseAccessService access = mock(KnowledgeBaseAccessService.class);
    private final DocumentServiceImpl service = new DocumentServiceImpl(documents, mock(MinioService.class),
            users, mock(DocumentTaskMapper.class), mock(DocumentUploadReceiptMapper.class),
            mock(TaskDispatchCoordinator.class), "bucket", access, mock(PlatformTransactionManager.class),
            mock(AuditLogService.class));

    /**
     * 协调 {@code documents.selectById} 完成当前阶段，供后续处理使用。
     * 下游调用 {@code documents.selectById}。
     */
    private void document() {
        Document document = new Document();
        document.setId(11L);
        document.setKnowledgeBaseId(7L);
        when(users.getUserId()).thenReturn(42L);
        when(documents.selectById(11L)).thenReturn(document);
    }

    /** 通过 {@code access.canRead}、{@code documents.selectChunkPage} 驱动该场景，核对 {@code request.getCurrent()} 等于 {@code 2}、{@code request.getSize()} 等于 {@code 20}。 */
    @Test void readableDocumentBindsBothIdsAndRequestedPage() {
        document();
        when(access.canRead(42L, 7L)).thenReturn(true);
        IPage<DocumentChunkDTO> result = new Page<>(2, 20, 30);
        when(documents.selectChunkPage(any(), eq(11L), eq(7L))).thenAnswer(call -> {
            Page<?> request = call.getArgument(0);
            assertThat(request.getCurrent()).isEqualTo(2);
            assertThat(request.getSize()).isEqualTo(20);
            return result;
        });
        DocumentChunkPageQueryDTO query = new DocumentChunkPageQueryDTO();
        query.setPage(2);
        assertThat(service.pageChunks(11L, query)).isSameAs(result);
        verify(access).canRead(42L, 7L);
    }

    /** 通过 {@code access.canRead}、{@code service.pageChunks} 驱动该场景，抛出 {@code ForbiddenException}，跳过受保护的调用。 */
    @Test void outsiderCannotReadChunkRows() {
        document();
        when(access.canRead(42L, 7L)).thenReturn(false);
        assertThatThrownBy(() -> service.pageChunks(11L, new DocumentChunkPageQueryDTO()))
                .isInstanceOf(ForbiddenException.class);
        verify(documents, never()).selectChunkPage(any(), any(), any());
    }

    /** 通过 {@code service.pageChunks}、{@code never} 驱动该场景，抛出 {@code ForbiddenException}，跳过受保护的调用。 */
    @Test void missingDocumentUsesTheSameDenialAndSkipsChunkQuery() {
        when(users.getUserId()).thenReturn(42L);
        assertThatThrownBy(() -> service.pageChunks(11L, new DocumentChunkPageQueryDTO()))
                .isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(access);
        verify(documents, never()).selectChunkPage(any(), any(), any());
    }

    /** 通过 {@code access.canRead}、{@code documents.selectChunkPage} 驱动该场景，抛出 {@code IllegalStateException}。 */
    @Test void databaseFailureIsNotReportedAsAnEmptyPage() {
        document();
        when(access.canRead(42L, 7L)).thenReturn(true);
        when(documents.selectChunkPage(any(), eq(11L), eq(7L))).thenThrow(new IllegalStateException("read failed"));
        assertThatThrownBy(() -> service.pageChunks(11L, new DocumentChunkPageQueryDTO()))
                .isInstanceOf(IllegalStateException.class).hasMessage("read failed");
    }
}
