/* 验证审计写入的操作者、动作和失败原因，以及审计故障对业务异常的隔离。 */

package com.pony.knowledge.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pony.knowledge.exception.BusinessException;
import com.pony.knowledge.exception.ForbiddenException;
import com.pony.knowledge.exception.UnauthorizedException;
import com.pony.knowledge.exception.UploadIdempotencyConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审计写入的三条契约：列填得对、独立事务、绝不外抛。
 * 这里的断言都是「改坏了会静默」的那几处 —— 审计失败不会让任何业务测试变红。
 */
class AuditLogServiceTest {

    private JdbcTemplate jdbcTemplate;
    private PlatformTransactionManager transactionManager;
    private AuditLogService service;

    /** 每个用例前准备夹具，调用 {@code transactionManager.getTransaction}。 */
    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
        service = new AuditLogService(jdbcTemplate, new ObjectMapper(), null, transactionManager);
    }

    /** 捕获审计新增记录的参数，核对操作者、动作、对象和调用请求 ID。 */
    @Test
    void writesActorActionTargetAndRequestId() {
        com.pony.knowledge.utils.CurrentUserUtil currentUser =
                mock(com.pony.knowledge.utils.CurrentUserUtil.class);
        when(currentUser.getUserId()).thenReturn(9L);
        service = new AuditLogService(jdbcTemplate, new ObjectMapper(), currentUser, transactionManager);

        try (var ignored = com.pony.knowledge.request.RequestIdContext.open("rid-42")) {
            service.record(AuditAction.KB_MEMBER_ADDED, "knowledge_base", 7L,
                    AuditLogService.details("member_user_id", 12L));
        }

        Object[] args = captureInsertArgs();
        assertEquals(9L, args[0], "actor 必须取认证上下文");
        assertEquals("rbac.member_added", args[1], "写入的是点分 code 而不是枚举名");
        assertEquals("knowledge_base", args[2]);
        assertEquals("7", args[3], "target_id 统一按字符串存");
        assertEquals("success", args[4], "省略 outcome 时默认 success");
        assertEquals("{\"member_user_id\":12}", args[5], "details 序列化为 jsonb 文本");
        assertEquals("rid-42", args[6], "request_id 必须带上，否则审计与日志无法关联");
    }

    /** 让审计数据库写入抛出异常，确认调用方能够继续处理原业务。 */
    @Test
    void swallowsDatabaseFailure() {
        when(jdbcTemplate.update(anyString(), any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertDoesNotThrow(() -> service.record(AuditAction.KNOWLEDGE_DELETED, "document", 3L, null));
    }

    /** 在没有认证对象的上下文写审计，核对操作者字段为空。 */
    @Test
    void recordsWithNullActorWhenUnauthenticated() {
        com.pony.knowledge.utils.CurrentUserUtil currentUser =
                mock(com.pony.knowledge.utils.CurrentUserUtil.class);
        when(currentUser.getUserId()).thenThrow(new UnauthorizedException("未认证或登录已过期"));
        service = new AuditLogService(jdbcTemplate, new ObjectMapper(), currentUser, transactionManager);

        assertDoesNotThrow(() -> service.record(AuditAction.API_KEY_REVOKED, "mcp_api_key", 5L, null));

        assertNull(captureInsertArgs()[0]);
    }

    /** 构造含空值的审计详情，核对只有有值字段进入详情。 */
    @Test
    void detailsSkipsNullValues() {
        Map<String, Object> details = AuditLogService.details("file_name", null, "knowledge_base_id", 4L);

        assertEquals(1, details.size());
        assertTrue(details.containsKey("knowledge_base_id"));
    }

    /** 为失败结果构造审计详情，检查原因码和允许写入的资源标识。接收{@code failure}、{@code outcome}、{@code reason}。 */
    @ParameterizedTest
    @MethodSource("failures")
    void failureOutcomeHasStableReasonAndOnlyAllowedResourceIds(Exception failure, String outcome, String reason)
            throws Exception {
        service.recordFailure(AuditAction.DOCUMENT_UPLOADED, "document", null, failure,
                Map.of("knowledge_base_id", 7L, "member_user_id", 12L,
                        "file_name", "sensitive", "api_key", "sensitive", "request_key", "sensitive"));

        Object[] args = captureInsertArgs();
        assertNull(args[3]);
        assertEquals(outcome, args[4]);
        var details = new ObjectMapper().readTree((String) args[5]);
        assertEquals(3, details.size());
        assertEquals(7L, details.get("knowledge_base_id").asLong());
        assertEquals(12L, details.get("member_user_id").asLong());
        assertEquals(reason, details.get("reason_code").asText());
        assertFalse(((String) args[5]).contains("sensitive"));
        ArgumentCaptor<TransactionDefinition> definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager).getTransaction(definition.capture());
        assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW, definition.getValue().getPropagationBehavior());
    }

    /** 提供用于检查审计失败原因归类的异常输入。返回 {@code Stream<Arguments>} 结果。 */
    private static Stream<Arguments> failures() {
        return Stream.of(
                Arguments.of(new ForbiddenException("sensitive"), "denied", "permission_denied"),
                Arguments.of(new UnauthorizedException("sensitive"), "denied", "authentication_required"),
                Arguments.of(new AccessDeniedException("sensitive"), "denied", "permission_denied"),
                Arguments.of(new AuthenticationCredentialsNotFoundException("sensitive"), "denied", "authentication_required"),
                Arguments.of(new BusinessException("sensitive"), "failed", "business_rejected"),
                Arguments.of(new UploadIdempotencyConflictException(), "failed", "idempotency_conflict"),
                Arguments.of(new IllegalStateException("sensitive"), "failed", "operation_failed"));
    }

    /** 让失败审计在提交时异常，确认不会替换原业务异常。 */
    @Test
    void failureAuditCommitErrorNeverReplacesTheBusinessError() {
        org.mockito.Mockito.doThrow(new DataAccessResourceFailureException("audit unavailable"))
                .when(transactionManager).commit(any());

        assertDoesNotThrow(() -> service.recordFailure(AuditAction.API_KEY_REVOKED, "mcp_api_key", 5L,
                new ForbiddenException("sensitive"), null));
    }

    /**
     * 获取审计持久化模拟调用传入的字段。
     * 由 {@code writesActorActionTargetAndRequestId}、{@code recordsWithNullActorWhenUnauthenticated} 调用。
     * 返回 {@code args.getValue()}。
     */
    private Object[] captureInsertArgs() {
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), args.capture());
        assertTrue(sql.getValue().contains("INSERT INTO audit_log"), "写入口只能是 audit_log");
        return args.getValue();
    }
}
