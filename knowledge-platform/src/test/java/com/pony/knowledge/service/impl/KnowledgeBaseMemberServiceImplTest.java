/* 验证知识库所有者添加或移除成员时的业务边界。 */

package com.pony.knowledge.service.impl;

import com.pony.knowledge.audit.AuditLogService;
import com.pony.knowledge.audit.AuditAction;
import com.pony.knowledge.dto.KnowledgeBaseMemberDTO;
import com.pony.knowledge.entity.KnowledgeBaseMember;
import com.pony.knowledge.entity.User;
import com.pony.knowledge.exception.BusinessException;
import com.pony.knowledge.exception.ForbiddenException;
import com.pony.knowledge.mapper.KnowledgeBaseMapper;
import com.pony.knowledge.mapper.KnowledgeBaseMemberMapper;
import com.pony.knowledge.mapper.UserMapper;
import com.pony.knowledge.service.KnowledgeBaseAccessService;
import com.pony.knowledge.utils.CurrentUserUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class KnowledgeBaseMemberServiceImplTest {
    private final AuditLogService audit = mock(AuditLogService.class);

    /**
     * 在该类型内部准备后续运行所需的数据。
     *
     * @param mm 本次调用传入的 {@code KnowledgeBaseMemberMapper}
     * @param km 本次调用传入的 {@code KnowledgeBaseMapper}
     * @param um 本次调用传入的 {@code UserMapper}
     * @param access 本次调用传入的 {@code KnowledgeBaseAccessService}
     * @return 调用方使用的 {@code KnowledgeBaseMemberServiceImpl}
     */
    private KnowledgeBaseMemberServiceImpl newService(KnowledgeBaseMemberMapper mm, KnowledgeBaseMapper km,
            UserMapper um, KnowledgeBaseAccessService access) {
        CurrentUserUtil currentUser = mock(CurrentUserUtil.class);
        when(currentUser.getUserId()).thenReturn(9L);
        return new KnowledgeBaseMemberServiceImpl(mm, um, access, currentUser, km, audit);
    }

    /**
     * 协调 {@code access.canManageMembers} 完成当前阶段，供后续处理使用。
     * 下游调用 {@code access.canManageMembers}。
     *
     * @return 当前记录中的 {@code access}
     */
    private KnowledgeBaseAccessService allowingAccess() {
        KnowledgeBaseAccessService access = mock(KnowledgeBaseAccessService.class);
        when(access.canManageMembers(9L, 7L)).thenReturn(true);
        return access;
    }

    /** 通过 {@code mm.existsMember}、{@code km.existsByIdAndCreatorId} 驱动该场景，核对输出和依赖调用。 */
    @Test
    void add_ownerCanAddMember_idempotent() {
        KnowledgeBaseMemberMapper mm = mock(KnowledgeBaseMemberMapper.class);
        KnowledgeBaseMapper km = mock(KnowledgeBaseMapper.class);
        KnowledgeBaseAccessService kb = allowingAccess();
        when(mm.existsMember(7L, 5L)).thenReturn(false);
        when(km.existsByIdAndCreatorId(7L, 5L)).thenReturn(false);
        UserMapper um = mock(UserMapper.class);
        User u = new User(); u.setId(5L); u.setUsername("alice");
        when(um.selectById(5L)).thenReturn(u);

        KnowledgeBaseMemberServiceImpl service = newService(mm, km, um, kb);
        service.add(7L, 5L);

        verify(kb).canManageMembers(9L, 7L);
        verify(mm).insert(any(KnowledgeBaseMember.class));
        verify(audit).record(AuditAction.KB_MEMBER_ADDED, "knowledge_base", 7L, Map.of("member_user_id", 5L));
        verifyNoMoreInteractions(audit);
    }

    /** 通过 {@code km.existsByIdAndCreatorId}、{@code newService} 驱动该场景，跳过受保护的调用。 */
    @Test
    void add_ownerSelf_isNoOp() {
        KnowledgeBaseMemberMapper mm = mock(KnowledgeBaseMemberMapper.class);
        KnowledgeBaseMapper km = mock(KnowledgeBaseMapper.class);
        when(km.existsByIdAndCreatorId(7L, 5L)).thenReturn(true);   // 5L 是 owner

        KnowledgeBaseMemberServiceImpl service = newService(mm, km, mock(UserMapper.class), allowingAccess());
        service.add(7L, 5L);

        verify(mm, never()).insert(any(KnowledgeBaseMember.class));
        verifyNoInteractions(audit);
    }

    /** 通过 {@code km.existsByIdAndCreatorId}、{@code mm.existsMember} 驱动该场景，跳过受保护的调用。 */
    @Test
    void add_alreadyMember_isNoOp() {
        KnowledgeBaseMemberMapper mm = mock(KnowledgeBaseMemberMapper.class);
        KnowledgeBaseMapper km = mock(KnowledgeBaseMapper.class);
        when(km.existsByIdAndCreatorId(7L, 5L)).thenReturn(false);   // 5L 非 owner
        when(mm.existsMember(7L, 5L)).thenReturn(true);              // 已是成员

        KnowledgeBaseMemberServiceImpl service = newService(mm, km, mock(UserMapper.class), allowingAccess());
        service.add(7L, 5L);

        verify(mm, never()).insert(any(KnowledgeBaseMember.class));
        verifyNoInteractions(audit);
    }

    /** 通过 {@code km.existsByIdAndCreatorId}、{@code mm.existsMember} 驱动该场景，抛出 {@code BusinessException}，跳过受保护的调用。 */
    @Test
    void add_unknownUser_throwsBusinessException() {
        KnowledgeBaseMemberMapper mm = mock(KnowledgeBaseMemberMapper.class);
        KnowledgeBaseMapper km = mock(KnowledgeBaseMapper.class);
        when(km.existsByIdAndCreatorId(7L, 999L)).thenReturn(false);
        when(mm.existsMember(7L, 999L)).thenReturn(false);
        UserMapper um = mock(UserMapper.class);
        when(um.selectById(999L)).thenReturn(null);

        KnowledgeBaseMemberServiceImpl service = newService(mm, km, um, allowingAccess());
        BusinessException failure = assertThrows(BusinessException.class, () -> service.add(7L, 999L));
        verify(mm, never()).insert(any(KnowledgeBaseMember.class));
        verify(audit).recordFailure(AuditAction.KB_MEMBER_ADDED, "knowledge_base", 7L, failure,
                Map.of("member_user_id", 999L));
        verifyNoMoreInteractions(audit);
    }

    /** 通过 {@code newService}、{@code verifyNoMoreInteractions} 驱动该场景，抛出 {@code ForbiddenException}，跳过受保护的调用。 */
    @Test
    void remove_nonOwner_throws403() {
        KnowledgeBaseAccessService kb = mock(KnowledgeBaseAccessService.class);
        KnowledgeBaseMemberMapper members = mock(KnowledgeBaseMemberMapper.class);
        UserMapper users = mock(UserMapper.class);
        KnowledgeBaseMemberServiceImpl service = newService(members,
                mock(KnowledgeBaseMapper.class), users, kb);
        ForbiddenException failure = assertThrows(ForbiddenException.class, () -> service.remove(7L, 5L));
        verifyNoInteractions(members, users);
        verify(audit).recordFailure(AuditAction.KB_MEMBER_REMOVED, "knowledge_base", 7L, failure,
                Map.of("member_user_id", 5L));
        verifyNoMoreInteractions(audit);
    }

    /** 通过 {@code newService}、{@code verifyNoMoreInteractions} 驱动该场景，抛出 {@code ForbiddenException}，跳过受保护的调用。 */
    @Test
    void add_nonOwnerDoesNotInspectTargetUserOrWriteMembership() {
        KnowledgeBaseAccessService access = mock(KnowledgeBaseAccessService.class);
        KnowledgeBaseMemberMapper members = mock(KnowledgeBaseMemberMapper.class);
        KnowledgeBaseMapper libraries = mock(KnowledgeBaseMapper.class);
        UserMapper users = mock(UserMapper.class);
        KnowledgeBaseMemberServiceImpl service = newService(members, libraries, users, access);

        ForbiddenException failure = assertThrows(ForbiddenException.class, () -> service.add(7L, 5L));

        verify(access).canManageMembers(9L, 7L);
        verifyNoInteractions(members, users, libraries);
        verify(audit).recordFailure(AuditAction.KB_MEMBER_ADDED, "knowledge_base", 7L, failure,
                Map.of("member_user_id", 5L));
        verifyNoMoreInteractions(audit);
    }

    /** 通过 {@code service.listMembers}、{@code newService} 驱动该场景，抛出 {@code ForbiddenException}，跳过受保护的调用。 */
    @Test
    void list_nonOwnerDoesNotReadMemberNames() {
        KnowledgeBaseAccessService access = mock(KnowledgeBaseAccessService.class);
        KnowledgeBaseMemberMapper members = mock(KnowledgeBaseMemberMapper.class);
        KnowledgeBaseMapper libraries = mock(KnowledgeBaseMapper.class);
        UserMapper users = mock(UserMapper.class);
        KnowledgeBaseMemberServiceImpl service = newService(members, libraries, users, access);

        assertThrows(ForbiddenException.class, () -> service.listMembers(7L));

        verifyNoInteractions(members, users, libraries);
    }

    /** 通过 {@code result.get} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void listMembers_returnsUsernames() {
        KnowledgeBaseMemberMapper mm = mock(KnowledgeBaseMemberMapper.class);
        KnowledgeBaseMember row = new KnowledgeBaseMember(); row.setKnowledgeBaseId(7L); row.setUserId(5L);
        when(mm.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(row));
        UserMapper um = mock(UserMapper.class);
        User u = new User(); u.setId(5L); u.setUsername("alice");
        when(um.selectBatchIds(List.of(5L))).thenReturn(List.of(u));

        KnowledgeBaseMemberServiceImpl service = newService(mm, mock(KnowledgeBaseMapper.class), um,
                allowingAccess());
        List<KnowledgeBaseMemberDTO> result = service.listMembers(7L);
        assertEquals(1, result.size());
        assertEquals(5L, result.get(0).getUserId());
        assertEquals("alice", result.get(0).getUsername());
    }

    /** 通过 {@code members.delete} 驱动用例，抛出 {@code RuntimeException}。 */
    @ParameterizedTest
    @ValueSource(strings = {"add", "remove"})
    void databaseFailureIsAuditedWithoutReplacingTheException(String operation) {
        KnowledgeBaseMemberMapper members = mock(KnowledgeBaseMemberMapper.class);
        UserMapper users = mock(UserMapper.class);
        RuntimeException failure = new RuntimeException("write failed");
        var service = newService(members, mock(KnowledgeBaseMapper.class), users, allowingAccess());
        if ("add".equals(operation)) {
            when(users.selectById(5L)).thenReturn(new User());
            when(members.insert(any(KnowledgeBaseMember.class))).thenThrow(failure);
        } else {
            when(members.delete(any(LambdaQueryWrapper.class))).thenThrow(failure);
        }

        assertSame(failure, assertThrows(RuntimeException.class, () -> {
            if ("add".equals(operation)) service.add(7L, 5L);
            else service.remove(7L, 5L);
        }));

        verify(audit).recordFailure("add".equals(operation) ? AuditAction.KB_MEMBER_ADDED : AuditAction.KB_MEMBER_REMOVED,
                "knowledge_base", 7L, failure, Map.of("member_user_id", 5L));
        verifyNoMoreInteractions(audit);
    }

    /** 通过 {@code users.selectById}、{@code members.insert} 驱动该场景，跳过受保护的调用。 */
    @Test
    void concurrentDuplicateDoesNotCreateAnotherMembershipAudit() {
        KnowledgeBaseMemberMapper members = mock(KnowledgeBaseMemberMapper.class);
        UserMapper users = mock(UserMapper.class);
        when(users.selectById(5L)).thenReturn(new User());
        when(members.insert(any(KnowledgeBaseMember.class))).thenThrow(new DuplicateKeyException("duplicate"));
        var service = newService(members, mock(KnowledgeBaseMapper.class), users, allowingAccess());

        assertDoesNotThrow(() -> service.add(7L, 5L));

        verifyNoInteractions(audit);
    }

    /** 通过 {@code newService}、{@code allowingAccess} 驱动该场景，跳过受保护的调用。 */
    @Test
    void removingAbsentMemberDoesNotCreateChangeAudit() {
        var service = newService(mock(KnowledgeBaseMemberMapper.class), mock(KnowledgeBaseMapper.class),
                mock(UserMapper.class), allowingAccess());

        service.remove(7L, 5L);

        verifyNoInteractions(audit);
    }
}
