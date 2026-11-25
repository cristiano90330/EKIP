/* 验证 MCP Key 的签发、列表、撤销及账号归属。 */

package com.pony.knowledge.mcp.auth;

import com.pony.knowledge.audit.AuditLogService;
import com.pony.knowledge.audit.AuditAction;
import com.pony.knowledge.dto.McpApiKeyIssueDTO;
import com.pony.knowledge.exception.ForbiddenException;
import com.pony.knowledge.vo.McpApiKeyIssuedVO;
import com.pony.knowledge.vo.McpApiKeyVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class McpApiKeyServiceImplTest {

    private static final Long OWNER = 42L;

    private static final Long KEY_ID = 7L;

    private McpApiKeyMapper mapper;

    private McpApiKeyServiceImpl service;
    private AuditLogService audit;

    /** 每个用例前准备夹具。 */
    @BeforeEach
    void setUp() {
        mapper = mock(McpApiKeyMapper.class);
        audit = mock(AuditLogService.class);
        service = new McpApiKeyServiceImpl(mapper, audit);
    }

    /**
     * 构造 {@code McpApiKey} 供测试使用，使用 {@code key.setUserId}、{@code key.setId}。
     * 由 {@code authenticate_grantsAndRecordsLastUsed_whenActive}、{@code authenticate_deniesRevokedKey} 调用。
     * @param hash 本用例传入的{@code hash}
     * @param status 拟使用的状态值
     * @param expiresAt 本用例传入的{@code expiresAt}
     * 返回 {@code key}。
     */
    private McpApiKey storedKey(String hash, McpApiKeyStatus status, LocalDateTime expiresAt) {
        McpApiKey key = new McpApiKey();
        key.setUserId(OWNER);
        key.setId(KEY_ID);
        key.setKeyHash(hash);
        key.setKeyPrefix("ekip_mcp_abc");
        key.setName("cursor-local");
        key.setStatus(status.getCode());
        key.setExpiresAt(expiresAt);
        return key;
    }

    /** 核对 {@code issued.getApiKey().startsWith("ekip_mcp_")} 为真。调用路径经过 {@code service.issue}。 */
    @Test
    void issue_returnsPlaintext_butPersistsHashNotPlaintext() {
        McpApiKeyIssuedVO issued = service.issue(OWNER, issueDto("cursor-local"));

        assertTrue(issued.getApiKey().startsWith("ekip_mcp_"));
        assertEquals(issued.getApiKey().substring(0, 12), issued.getKeyPrefix());

        ArgumentCaptor<McpApiKey> saved = ArgumentCaptor.forClass(McpApiKey.class);
        verify(mapper).insert(saved.capture());
        McpApiKey entity = saved.getValue();

        assertEquals(McpApiKeyGenerator.sha256Hex(issued.getApiKey()), entity.getKeyHash());
        assertEquals(64, entity.getKeyHash().length());
        assertFalse(entity.getKeyHash().contains("ekip_mcp_"), "入库的必须是哈希，不能带明文特征");
        assertFalse(entity.toString().contains(issued.getApiKey()), "实体不得携带明文");
        assertEquals(OWNER, entity.getUserId());
        assertEquals(Integer.valueOf(McpApiKeyStatus.ACTIVE.getCode()), entity.getStatus());
    }

    /** 核对 {@code result.isAuthenticated()} 为真。调用路径经过 {@code McpApiKeyGenerator.newPlainKey}。 */
    @Test
    void authenticate_grantsAndRecordsLastUsed_whenActive() {
        String plain = McpApiKeyGenerator.newPlainKey();
        String hash = McpApiKeyGenerator.sha256Hex(plain);
        when(mapper.selectByKeyHash(hash)).thenReturn(storedKey(hash, McpApiKeyStatus.ACTIVE, null));

        McpAuthResult result = service.authenticate(plain);

        assertTrue(result.isAuthenticated());
        assertEquals(OWNER, result.userId());
        assertEquals(KEY_ID, result.keyId());
        verify(mapper).touchLastUsed(eq(KEY_ID), any(LocalDateTime.class));
    }

    /** 核对 {@code result.isAuthenticated()} 为假。调用路径经过 {@code mapper.selectByKeyHash}。 */
    @Test
    void authenticate_deniesUnknownHash() {
        when(mapper.selectByKeyHash(any())).thenReturn(null);

        McpAuthResult result = service.authenticate(McpApiKeyGenerator.newPlainKey());

        assertFalse(result.isAuthenticated());
        assertEquals("凭证无效", result.denyReason());
        verify(mapper, never()).touchLastUsed(anyLong(), any());
    }

    /** 核对 {@code service.authenticate("ekip_mcp_whatever").denyReason()} 与预期 {@code "\u51ed\u8bc1\u5df2\u540a\u9500"} 一致。 */
    @Test
    void authenticate_deniesRevokedKey() {
        when(mapper.selectByKeyHash(any()))
                .thenReturn(storedKey("a".repeat(64), McpApiKeyStatus.REVOKED, null));

        assertEquals("凭证已吊销", service.authenticate("ekip_mcp_whatever").denyReason());
        verify(mapper, never()).touchLastUsed(anyLong(), any());
    }

    /** 核对 {@code service.authenticate("ekip_mcp_whatever").denyReason()} 与预期 {@code "\u51ed\u8bc1\u5df2\u8fc7\u671f"} 一致。 */
    @Test
    void authenticate_deniesExpiredKey() {
        when(mapper.selectByKeyHash(any())).thenReturn(
                storedKey("a".repeat(64), McpApiKeyStatus.ACTIVE, LocalDateTime.now().minusSeconds(1)));

        assertEquals("凭证已过期", service.authenticate("ekip_mcp_whatever").denyReason());
        verify(mapper, never()).touchLastUsed(anyLong(), any());
    }

    /**
     * 核对 {@code service.authenticate("ekip_mcp_whatever").isAuthenticated()} 为真。
     * 调用路径经过 {@code mapper.selectByKeyHash}。
     */
    @Test
    void authenticate_allowsFutureExpiry() {
        when(mapper.selectByKeyHash(any())).thenReturn(
                storedKey("a".repeat(64), McpApiKeyStatus.ACTIVE, LocalDateTime.now().plusDays(1)));

        assertTrue(service.authenticate("ekip_mcp_whatever").isAuthenticated());
    }

    /** 断言抛出 {@code ForbiddenException}。调用路径经过 {@code mapper.selectById}。 */
    @Test
    void revoke_throwsForbidden_forAnotherUsersKey() {
        when(mapper.selectById(KEY_ID))
                .thenReturn(storedKey("c".repeat(64), McpApiKeyStatus.ACTIVE, null));

        ForbiddenException failure = assertThrows(ForbiddenException.class, () -> service.revoke(99L, KEY_ID));
        verify(mapper, never()).updateById(any(McpApiKey.class));
        verify(audit).recordFailure(AuditAction.API_KEY_REVOKED, "mcp_api_key", KEY_ID, failure, null);
        verifyNoMoreInteractions(audit);
    }

    /**
     * 核对 {@code updated.getValue().getStatus()} 与预期 {@code Integer.valueOf(McpApiKeyStatus.REVOKED.getCode())} 一致。
     * 调用路径经过 {@code mapper.selectById}。
     */
    @Test
    void revoke_marksRevoked_forOwner() {
        when(mapper.selectById(KEY_ID))
                .thenReturn(storedKey("d".repeat(64), McpApiKeyStatus.ACTIVE, null));

        service.revoke(OWNER, KEY_ID);

        ArgumentCaptor<McpApiKey> updated = ArgumentCaptor.forClass(McpApiKey.class);
        verify(mapper).updateById(updated.capture());
        assertEquals(Integer.valueOf(McpApiKeyStatus.REVOKED.getCode()), updated.getValue().getStatus());
    }

    /** 核对 {@code vos.size()} 与预期 {@code 1} 一致。调用路径经过 {@code mapper.selectList}。 */
    @Test
    void list_exposesFingerprintOnly_neitherPlaintextNorHash() {
        String hash = "e".repeat(64);
        when(mapper.selectList(any())).thenReturn(List.of(storedKey(hash, McpApiKeyStatus.ACTIVE, null)));

        List<McpApiKeyVO> vos = service.list(OWNER);

        assertEquals(1, vos.size());
        McpApiKeyVO vo = vos.get(0);
        assertEquals("ekip_mcp_abc", vo.getKeyPrefix());
        assertEquals("ACTIVE", vo.getStatus());
        assertFalse(vo.toString().contains(hash), "列表输出不得包含哈希");

        List<String> fields = Arrays.stream(McpApiKeyVO.class.getDeclaredFields())
                .map(Field::getName)
                .collect(Collectors.toList());
        assertFalse(fields.contains("keyHash"));
        assertFalse(fields.contains("apiKey"));
    }

    /**
     * 构造 {@code McpApiKeyIssueDTO} 供测试使用，使用 {@code dto.setName}。
     * 由 {@code issue_returnsPlaintext_butPersistsHashNotPlaintext}、{@code failedIssueDoesNotAuditGeneratedKeyOrName} 调用。
     * @param name 属性或资源名称
     * 返回 {@code dto}。
     */
    private McpApiKeyIssueDTO issueDto(String name) {
        McpApiKeyIssueDTO dto = new McpApiKeyIssueDTO();
        dto.setName(name);
        return dto;
    }

    /** 断言抛出 {@code RuntimeException}。调用路径经过 {@code mapper.insert}。 */
    @Test
    void failedIssueDoesNotAuditGeneratedKeyOrName() {
        RuntimeException failure = new RuntimeException("database failed");
        when(mapper.insert(any(McpApiKey.class))).thenThrow(failure);

        assertSame(failure, assertThrows(RuntimeException.class, () -> service.issue(OWNER, issueDto("private"))));

        verify(audit).recordFailure(AuditAction.API_KEY_CREATED, "mcp_api_key", null, failure, null);
        verifyNoMoreInteractions(audit);
    }

    /** 断言抛出 {@code RuntimeException}。调用路径经过 {@code mapper.selectById}。 */
    @Test
    void failedRevokeAuditsRequestedIdAndPreservesException() {
        when(mapper.selectById(KEY_ID)).thenReturn(storedKey("d".repeat(64), McpApiKeyStatus.ACTIVE, null));
        RuntimeException failure = new RuntimeException("database failed");
        when(mapper.updateById(any(McpApiKey.class))).thenThrow(failure);

        assertSame(failure, assertThrows(RuntimeException.class, () -> service.revoke(OWNER, KEY_ID)));

        verify(audit).recordFailure(AuditAction.API_KEY_REVOKED, "mcp_api_key", KEY_ID, failure, null);
        verifyNoMoreInteractions(audit);
    }
}
