/* 验证用户对自有及共享知识库的读写权限和范围解析。 */

package com.pony.knowledge.service.impl;

import com.pony.knowledge.entity.KnowledgeBase;
import com.pony.knowledge.mapper.KnowledgeBaseMapper;
import com.pony.knowledge.mapper.KnowledgeBaseMemberMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeBaseAccessServiceImplTest {

    /** 通过 {@code mapper.selectByCreatorId}、{@code service.resolveReadableIds} 驱动该场景，实际结果与固定预期一致。 */
    @Test
    void resolveReadableIds_returnsCreatorKbIds() {
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        KnowledgeBaseMemberMapper memberMapper = mock(KnowledgeBaseMemberMapper.class);
        KnowledgeBase kb1 = new KnowledgeBase(); kb1.setId(1L);
        KnowledgeBase kb2 = new KnowledgeBase(); kb2.setId(2L);
        when(mapper.selectByCreatorId(9L)).thenReturn(List.of(kb1, kb2));

        KnowledgeBaseAccessServiceImpl service = new KnowledgeBaseAccessServiceImpl(mapper, memberMapper);

        assertEquals(Set.of(1L, 2L), service.resolveReadableIds(9L));
    }

    /** 通过 {@code mapper.existsByIdAndCreatorId} 驱动该场景，布尔状态符合断言。 */
    @Test
    void canRead_trueWhenCreator() {
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        KnowledgeBaseMemberMapper memberMapper = mock(KnowledgeBaseMemberMapper.class);
        when(mapper.existsByIdAndCreatorId(7L, 9L)).thenReturn(true);
        assertTrue(new KnowledgeBaseAccessServiceImpl(mapper, memberMapper).canRead(9L, 7L));
    }

    /** 通过 {@code mapper.existsByIdAndCreatorId} 驱动该场景，布尔状态符合断言。 */
    @Test
    void canRead_falseWhenNotCreator() {
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        KnowledgeBaseMemberMapper memberMapper = mock(KnowledgeBaseMemberMapper.class);
        when(mapper.existsByIdAndCreatorId(7L, 9L)).thenReturn(false);
        assertFalse(new KnowledgeBaseAccessServiceImpl(mapper, memberMapper).canRead(9L, 7L));
    }

    /** 通过 {@code mapper.selectByCreatorId}、{@code memberMapper.selectMemberKbIds} 驱动该场景，实际结果与固定预期一致。 */
    @Test
    void resolveReadableIds_unionsCreatorAndMember() {
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        KnowledgeBaseMemberMapper memberMapper = mock(KnowledgeBaseMemberMapper.class);
        KnowledgeBase kb = new KnowledgeBase(); kb.setId(1L);
        when(mapper.selectByCreatorId(9L)).thenReturn(List.of(kb));
        when(memberMapper.selectMemberKbIds(9L)).thenReturn(List.of(1L, 2L, 3L));

        KnowledgeBaseAccessServiceImpl service = new KnowledgeBaseAccessServiceImpl(mapper, memberMapper);

        assertEquals(Set.of(1L, 2L, 3L), service.resolveReadableIds(9L));
    }

    /** 通过 {@code mapper.existsByIdAndCreatorId}、{@code memberMapper.existsMember} 驱动该场景，布尔状态符合断言。 */
    @Test
    void canRead_trueWhenMember() {
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        KnowledgeBaseMemberMapper memberMapper = mock(KnowledgeBaseMemberMapper.class);
        when(mapper.existsByIdAndCreatorId(7L, 9L)).thenReturn(false);
        when(memberMapper.existsMember(7L, 9L)).thenReturn(true);
        assertTrue(new KnowledgeBaseAccessServiceImpl(mapper, memberMapper).canRead(9L, 7L));
    }

    /** 通过 {@code mapper.existsByIdAndCreatorId}、{@code memberMapper.existsMember} 驱动该场景，布尔状态符合断言。 */
    @Test
    void canRead_falseForStranger() {
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        KnowledgeBaseMemberMapper memberMapper = mock(KnowledgeBaseMemberMapper.class);
        when(mapper.existsByIdAndCreatorId(7L, 9L)).thenReturn(false);
        when(memberMapper.existsMember(7L, 9L)).thenReturn(false);
        assertFalse(new KnowledgeBaseAccessServiceImpl(mapper, memberMapper).canRead(9L, 7L));
    }

    /** 通过 {@code mapper.existsByIdAndCreatorId}、{@code service.canMaintainDocuments} 驱动该场景，跳过受保护的调用，布尔状态符合断言。 */
    @Test
    void creatorCanPerformAllMaintenanceOperations() {
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        KnowledgeBaseMemberMapper members = mock(KnowledgeBaseMemberMapper.class);
        when(mapper.existsByIdAndCreatorId(7L, 9L)).thenReturn(true);
        KnowledgeBaseAccessServiceImpl service = new KnowledgeBaseAccessServiceImpl(mapper, members);

        assertTrue(service.canMaintainDocuments(9L, 7L));
        assertTrue(service.canManageMembers(9L, 7L));
        assertTrue(service.canEvaluate(9L, 7L));
        assertTrue(service.canManageKnowledgeBase(9L, 7L));
        verifyNoInteractions(members);
    }

    /** 通过 {@code members.existsMember}、{@code service.canRead} 驱动该场景，布尔状态符合断言。 */
    @Test
    void memberReadPermissionDoesNotGrantAnyMaintenancePermission() {
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        KnowledgeBaseMemberMapper members = mock(KnowledgeBaseMemberMapper.class);
        when(members.existsMember(7L, 9L)).thenReturn(true);
        KnowledgeBaseAccessServiceImpl service = new KnowledgeBaseAccessServiceImpl(mapper, members);

        assertTrue(service.canRead(9L, 7L));
        assertFalse(service.canMaintainDocuments(9L, 7L));
        assertFalse(service.canManageMembers(9L, 7L));
        assertFalse(service.canEvaluate(9L, 7L));
        assertFalse(service.canManageKnowledgeBase(9L, 7L));
    }

    /** 通过 {@code mapper.existsByIdAndCreatorId}、{@code service.canMaintainDocuments} 驱动该场景，布尔状态符合断言。 */
    @Test
    void ownershipOfOneLibraryDoesNotGrantAccessToAnother() {
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        KnowledgeBaseMemberMapper members = mock(KnowledgeBaseMemberMapper.class);
        when(mapper.existsByIdAndCreatorId(7L, 9L)).thenReturn(true);
        KnowledgeBaseAccessServiceImpl service = new KnowledgeBaseAccessServiceImpl(mapper, members);

        assertTrue(service.canMaintainDocuments(9L, 7L));
        assertFalse(service.canRead(9L, 8L));
        assertFalse(service.canMaintainDocuments(9L, 8L));
        assertFalse(service.canManageMembers(9L, 8L));
        assertFalse(service.canEvaluate(9L, 8L));
        assertFalse(service.canManageKnowledgeBase(9L, 8L));
    }

    /** 通过 {@code java.util.Arrays.asList}、{@code service.resolveReadableIds} 驱动该场景，跳过受保护的调用，布尔状态符合断言。 */
    @Test
    void invalidIdentityOrResourceIsDeniedWithoutDatabaseLookup() {
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        KnowledgeBaseMemberMapper members = mock(KnowledgeBaseMemberMapper.class);
        KnowledgeBaseAccessServiceImpl service = new KnowledgeBaseAccessServiceImpl(mapper, members);

        for (Long invalid : java.util.Arrays.asList(null, 0L, -1L)) {
            assertTrue(service.resolveReadableIds(invalid).isEmpty());
            assertFalse(service.canRead(invalid, 7L));
            assertFalse(service.canMaintainDocuments(invalid, 7L));
            assertFalse(service.canManageMembers(invalid, 7L));
            assertFalse(service.canEvaluate(invalid, 7L));
            assertFalse(service.canManageKnowledgeBase(invalid, 7L));
            assertFalse(service.canRead(9L, invalid));
            assertFalse(service.canMaintainDocuments(9L, invalid));
            assertFalse(service.canManageMembers(9L, invalid));
            assertFalse(service.canEvaluate(9L, invalid));
            assertFalse(service.canManageKnowledgeBase(9L, invalid));
        }
        verifyNoInteractions(mapper, members);
    }

}
