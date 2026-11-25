/* 从认证和请求上下文采集操作者与请求标识，独立写入业务审计记录。 */

package com.pony.knowledge.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pony.knowledge.exception.BusinessException;
import com.pony.knowledge.exception.ForbiddenException;
import com.pony.knowledge.exception.UnauthorizedException;
import com.pony.knowledge.exception.UploadIdempotencyConflictException;
import com.pony.knowledge.request.RequestIdContext;
import com.pony.knowledge.utils.CurrentUserUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 操作审计的唯一写入口：把「谁 / 何时 / 对什么 / 做了什么 / 结果」写进 append-only 的 {@code audit_log}。
 *
 * <p>三条硬约束，改这个类之前先读：
 * <ol>
 *   <li><b>绝不向调用方抛异常。</b> 审计失败只能记 ERROR —— 审计是旁路，不能让「删不了文档」变成
 *       「因为写不了审计」。注意这里 catch 必须包在 {@link TransactionTemplate#execute} <b>外面</b>：
 *       在 PostgreSQL 里一次失败的 INSERT 会让整个事务 abort，事务内 catch 住异常也救不回提交。</li>
 *   <li><b>独立事务（REQUIRES_NEW）。</b> 业务事务回滚不应连带抹掉审计；业务事务也不该因为审计
 *       的 SQL 失败而变成 rollback-only。用显式 TransactionTemplate 而不是 @Transactional，
 *       是因为 @Transactional 的提交失败发生在方法返回之后，try/catch 拦不到（会漏到调用方）。</li>
 *   <li><b>写入口唯一。</b> 不要在业务代码里直接 INSERT audit_log —— 那样 requestId / actor
 *       两列必然有人漏填，而「查不到 actor 的行」和「没人操作过」在事后无法区分。</li>
 * </ol>
 *
 * <p>成功事件在业务状态提交之后记录；拒绝和失败由对应服务的异常出口记录，
 * 事务操作应在 execute 抛出之后记录，避免将尚未提交的变更写成成功。
 */
@Component
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    private static final String INSERT_SQL = """
            INSERT INTO audit_log (actor_user_id, action, target_type, target_id, outcome, details, request_id)
            VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final CurrentUserUtil currentUserUtil;
    private final TransactionTemplate auditTransaction;

    /**
     * 保存从认证和请求上下文采集操作者与请求标识所需的依赖和初始配置。
     * 上游为 Spring 组件装配。
     *
     * @param jdbcTemplate 负责数据库 SQL 的 JDBC 组件
     * @param objectMapper 序列化和解析 JSON 使用的组件
     * @param currentUserUtil 本次操作使用的 CurrentUserUtil 组件或记录
     * @param transactionManager 用于创建或参与数据库事务的事务管理器
     */
    public AuditLogService(JdbcTemplate jdbcTemplate,
                           ObjectMapper objectMapper,
                           CurrentUserUtil currentUserUtil,
                           PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.currentUserUtil = currentUserUtil;
        this.auditTransaction = new TransactionTemplate(transactionManager);
        this.auditTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 取得当前操作者与请求标识，写入独立审计事务。
     * 上游为 MemorySaveApprovalService.recordDecision、AuditLogService.recordFailure。
     *
     * @param action 要查询或记录的审计操作码
     * @param targetType 本次处理的资源或反序列化目标类型
     * @param targetId 要定位的关联标识
     * @param details 补充描述这次操作的键和值
     */
    public void record(AuditAction action, String targetType, Object targetId, Map<String, Object> details) {
        record(action, targetType, targetId, AuditOutcome.SUCCESS, details);
    }

    /**
     * 将异常归类为拒绝或失败，并将结果交给审计写入。
     * 上游为 McpApiKeyServiceImpl.issue、McpApiKeyServiceImpl.revoke。
     * 下游为 AuditLogService.record。
     *
     * @param action 要查询或记录的审计操作码
     * @param targetType 本次处理的资源或反序列化目标类型
     * @param targetId 要定位的关联标识
     * @param failure 本次执行捕获的异常
     * @param resourceIds 相关记录的 ID 列表
     */
    public void recordFailure(AuditAction action, String targetType, Object targetId,
                              Exception failure, Map<String, Object> resourceIds) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (resourceIds != null) {
            for (String key : new String[]{"knowledge_base_id", "member_user_id"}) {
                Object value = resourceIds.get(key);
                if (value instanceof Long || value instanceof Integer) details.put(key, value);
            }
        }
        AuditOutcome outcome = AuditOutcome.FAILED;
        String reason = "operation_failed";

        if (failure instanceof UnauthorizedException || failure instanceof AuthenticationException) {
            outcome = AuditOutcome.DENIED;
            reason = "authentication_required";
        } else if (failure instanceof ForbiddenException || failure instanceof AccessDeniedException) {
            outcome = AuditOutcome.DENIED;
            reason = "permission_denied";
        } else if (failure instanceof UploadIdempotencyConflictException) {
            reason = "idempotency_conflict";
        } else if (failure instanceof BusinessException) {
            reason = "business_rejected";
        }
        details.put("reason_code", reason);
        record(action, targetType, targetId, outcome, details);
    }

    /**
     * 取得当前操作者与请求标识，写入独立审计事务。
     * 上游为 MemorySaveApprovalService.recordDecision、AuditLogService.recordFailure。
     * 下游为 RequestIdContext.current。
     *
     * @param action 要查询或记录的审计操作码
     * @param targetType 本次处理的资源或反序列化目标类型
     * @param targetId 要定位的关联标识
     * @param outcome 查询或操作的业务结果状态
     * @param details 补充描述这次操作的键和值
     */
    public void record(AuditAction action,
                       String targetType,
                       Object targetId,
                       AuditOutcome outcome,
                       Map<String, Object> details) {
        if (action == null) {
            log.warn("审计调用缺少 action，已跳过：target={}:{}", targetType, targetId);
            return;
        }
        String outcomeCode = outcome == null ? AuditOutcome.SUCCESS.getCode() : outcome.getCode();
        String target = targetId == null ? null : String.valueOf(targetId);
        String requestId = RequestIdContext.current();
        String json = toJson(details);
        Long actor = currentActorId();

        try {
            auditTransaction.executeWithoutResult(status ->
                    jdbcTemplate.update(INSERT_SQL, actor, action.getCode(), targetType, target, outcomeCode, json, requestId));
        } catch (Exception e) {
            // 只记 ERROR，不外抛。带上 action/target，方便在日志里补一条「审计缺了一行」的证据。
            log.error("审计写入失败（不影响业务）：action={} target={}:{} outcome={} actor={}",
                    action.getCode(), targetType, target, outcomeCode, actor, e);
        }
    }

    /**
     * 从安全上下文尽力读取审计操作者。
     * 上游为 AuditLogService.record。
     * 下游为 CurrentUserUtil.getUserId。
     *
     * @return 可识别的审计操作者 ID，无认证上下文时可能为空
     */
    private Long currentActorId() {
        try {
            return currentUserUtil.getUserId();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 把交替传入的审计键和值组装成附加详情。
     * 上游为 MemorySaveApprovalService.recordDecision、McpApiKeyServiceImpl.issue。
     *
     * @param kv 需要本方法按序读取或筛选的 Object[] 数据
     * @return 由调用方提供的成对键值构成的审计详情
     */
    public static Map<String, Object> details(Object... kv) {
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("details 需要成对的 key/value");
        }
        Map<String, Object> map = new LinkedHashMap<>();

        for (int i = 0; i < kv.length; i += 2) {
            if (kv[i] != null && kv[i + 1] != null) {
                map.put(String.valueOf(kv[i]), kv[i + 1]);
            }
        }
        return map;
    }

    /**
     * 将审计详情序列化为持久化的 JSON。
     * 上游为 AuditLogService.record。
     *
     * @param details 补充描述这次操作的键和值
     * @return 可存入审计 details 字段的 JSON 字符串
     */
    private String toJson(Map<String, Object> details) {
        if (details == null || details.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(details);
        } catch (Exception e) {
            log.warn("审计详情序列化失败，details 记为 null：keys={}", details.keySet(), e);
            return null;
        }
    }
}
