/* 验证知识库管理对所有者、输入范围和成员可见性的处理。 */

package com.pony.knowledge.service.impl;

import com.pony.knowledge.audit.AuditLogService;
import com.pony.knowledge.audit.AuditAction;
import com.pony.knowledge.dto.KnowledgeBaseCreateDTO;
import com.pony.knowledge.entity.KnowledgeBase;
import com.pony.knowledge.exception.ForbiddenException;
import com.pony.knowledge.exception.UnauthorizedException;
import com.pony.knowledge.mapper.KnowledgeBaseMapper;
import com.pony.knowledge.mapper.KnowledgeBaseMemberMapper;
import com.pony.knowledge.utils.CurrentUserUtil;
import com.pony.knowledge.service.KnowledgeBaseAccessService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeBaseServiceImplTest {

    /** 通过 {@code service.create} 驱动用例，抛出 {@code UnauthorizedException}，跳过受保护的调用。 */
    @Test
    void createAuthenticationFailureIsAuditedWithoutWritingKnowledgeBase() {
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        CurrentUserUtil user = mock(CurrentUserUtil.class);
        AuditLogService audit = mock(AuditLogService.class);
        UnauthorizedException failure = new UnauthorizedException("expired");
        when(user.getUserId()).thenThrow(failure);
        var service = new KnowledgeBaseServiceImpl(mapper, user, mock(KnowledgeBaseMemberMapper.class),
                mock(KnowledgeBaseAccessService.class), audit);

        assertSame(failure, assertThrows(UnauthorizedException.class,
                () -> service.create(new KnowledgeBaseCreateDTO())));

        verifyNoInteractions(mapper);
        verify(audit).recordFailure(AuditAction.KB_CREATED, "knowledge_base", null, failure, null);
        verifyNoMoreInteractions(audit);
    }

    /** 通过 {@code service.create} 驱动用例，抛出 {@code RuntimeException}。 */
    @Test
    void createDatabaseFailureIsAuditedAndOriginalExceptionIsPreserved() {
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        CurrentUserUtil user = mock(CurrentUserUtil.class);
        AuditLogService audit = mock(AuditLogService.class);
        RuntimeException failure = new RuntimeException("db unavailable");
        when(user.getUserId()).thenReturn(9L);
        when(mapper.insert(any(KnowledgeBase.class))).thenThrow(failure);
        var service = new KnowledgeBaseServiceImpl(mapper, user, mock(KnowledgeBaseMemberMapper.class),
                mock(KnowledgeBaseAccessService.class), audit);

        assertSame(failure, assertThrows(RuntimeException.class,
                () -> service.create(new KnowledgeBaseCreateDTO())));

        verify(audit).recordFailure(AuditAction.KB_CREATED, "knowledge_base", null, failure, null);
        verifyNoMoreInteractions(audit);
    }

    /** 通过 {@code service.list} 驱动用例，布尔状态符合断言，实际结果与固定预期一致。 */
    @Test
    void list_returnsAccessibleUnion() {
        KnowledgeBaseMapper kbMapper = mock(KnowledgeBaseMapper.class);
        KnowledgeBaseMemberMapper memberMapper = mock(KnowledgeBaseMemberMapper.class);
        CurrentUserUtil cu = mock(CurrentUserUtil.class);
        when(cu.getUserId()).thenReturn(9L);

        KnowledgeBase owned = new KnowledgeBase(); owned.setId(1L); owned.setCreatorId(9L);
        KnowledgeBase memberKb = new KnowledgeBase(); memberKb.setId(2L); memberKb.setCreatorId(888L);

        when(kbMapper.selectByCreatorId(9L)).thenReturn(List.of(owned));
        when(memberMapper.selectMemberKbIds(9L)).thenReturn(List.of(2L));
        when(kbMapper.selectBatchIds(List.of(2L))).thenReturn(List.of(memberKb));

        KnowledgeBaseServiceImpl service = new KnowledgeBaseServiceImpl(kbMapper, cu, memberMapper, mock(KnowledgeBaseAccessService.class), mock(AuditLogService.class));

        List<KnowledgeBase> result = service.list();
        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(k -> k.getId().equals(1L)));
        assertTrue(result.stream().anyMatch(k -> k.getId().equals(2L)));
    }

    /** 通过 {@code service.list} 驱动用例，跳过受保护的调用，实际结果与固定预期一致。 */
    @Test
    void list_noMember_returnsOwnedOnly() {
        KnowledgeBaseMapper kbMapper = mock(KnowledgeBaseMapper.class);
        KnowledgeBaseMemberMapper memberMapper = mock(KnowledgeBaseMemberMapper.class);
        CurrentUserUtil cu = mock(CurrentUserUtil.class);
        when(cu.getUserId()).thenReturn(9L);
        KnowledgeBase owned = new KnowledgeBase(); owned.setId(1L);
        when(kbMapper.selectByCreatorId(9L)).thenReturn(List.of(owned));
        when(memberMapper.selectMemberKbIds(9L)).thenReturn(List.of());

        KnowledgeBaseServiceImpl service = new KnowledgeBaseServiceImpl(kbMapper, cu, memberMapper, mock(KnowledgeBaseAccessService.class), mock(AuditLogService.class));

        assertEquals(1, service.list().size());
        verify(kbMapper, never()).selectBatchIds(any());
    }

    /** 通过 {@code access.canManageKnowledgeBase}、{@code service.checkOwnership} 驱动该场景，抛出 {@code ForbiddenException}，跳过受保护的调用。 */
    @Test
    void checkOwnershipUsesManagementPermissionAndTrustedCurrentUser() {
        KnowledgeBaseMapper libraries = mock(KnowledgeBaseMapper.class);
        KnowledgeBaseMemberMapper members = mock(KnowledgeBaseMemberMapper.class);
        CurrentUserUtil currentUser = mock(CurrentUserUtil.class);
        KnowledgeBaseAccessService access = mock(KnowledgeBaseAccessService.class);
        when(currentUser.getUserId()).thenReturn(9L);
        when(access.canManageKnowledgeBase(9L, 7L)).thenReturn(true);
        KnowledgeBaseServiceImpl service = new KnowledgeBaseServiceImpl(libraries, currentUser, members, access,
                mock(AuditLogService.class));

        assertDoesNotThrow(() -> service.checkOwnership(7L));
        assertThrows(ForbiddenException.class, () -> service.checkOwnership(8L));

        verify(access).canManageKnowledgeBase(9L, 7L);
        verify(access).canManageKnowledgeBase(9L, 8L);
        verifyNoInteractions(libraries, members);
    }
}
