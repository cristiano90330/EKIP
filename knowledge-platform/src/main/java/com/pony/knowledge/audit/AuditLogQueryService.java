/* 校验账号管理员身份，分页查询并映射审计记录。 */

package com.pony.knowledge.audit;

import com.pony.knowledge.exception.ForbiddenException;
import com.pony.knowledge.service.AccountAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AuditLogQueryService {
    private static final int PAGE_SIZE = 50;

    private final JdbcTemplate jdbc;
    private final AccountAdminService accounts;

    /**
     * 要求账号管理员权限，按游标查询满足操作、结果和日期条件的审计记录。
     * 上游为 AuditLogController.list。
     * 下游为 AccountAdminService.canManage。
     *
     * @param userId 用户 ID
     * @param beforeId 要定位的关联标识
     * @param from 查询的起点条件
     * @param to 查询的结束条件
     * @param action 要查询或记录的审计操作码
     * @param outcome 查询或操作的业务结果状态
     * @return 满足条件的审计项目和可用的下一页位置
     */
    public Page list(long userId, Long beforeId, LocalDate from, LocalDate to, String action, String outcome) {
        if (!accounts.canManage(userId)) {
            throw new ForbiddenException("只有管理员可以查看操作审计");
        }
        if ((beforeId != null && beforeId <= 0) || (from != null && to != null && from.isAfter(to))
                || !validDate(from) || !validDate(to)
                || action.length() > 64 || !validOutcome(outcome)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "审计筛选参数无效");
        }

        StringBuilder sql = new StringBuilder("""
                SELECT a.id, a.actor_user_id, u.username, a.action, a.target_type,
                       a.target_id, a.outcome, a.request_id, a.created_at
                FROM audit_log a LEFT JOIN sys_user u ON u.id = a.actor_user_id
                WHERE 1 = 1
                """);
        List<Object> parameters = new ArrayList<>();
        if (beforeId != null) {
            sql.append(" AND a.id < ?");
            parameters.add(beforeId);
        }
        if (from != null) {
            sql.append(" AND a.created_at >= ?");
            parameters.add(from.atStartOfDay());
        }
        if (to != null) {
            sql.append(" AND a.created_at < ?");
            parameters.add(to.plusDays(1).atStartOfDay());
        }
        if (!action.isEmpty()) {
            sql.append(" AND a.action = ?");
            parameters.add(action);
        }
        if (!outcome.isEmpty()) {
            sql.append(" AND a.outcome = ?");
            parameters.add(outcome);
        }
        sql.append(" ORDER BY a.id DESC LIMIT ?");
        parameters.add(PAGE_SIZE + 1);

        List<Entry> matches = jdbc.query(sql.toString(), (rs, row) -> new Entry(
                rs.getLong("id"), rs.getObject("actor_user_id", Long.class), rs.getString("username"),
                rs.getString("action"), rs.getString("target_type"), rs.getString("target_id"),
                rs.getString("outcome"), rs.getString("request_id"), rs.getObject("created_at", LocalDateTime.class)),
                parameters.toArray());
        List<Entry> entries = matches.stream().limit(PAGE_SIZE).toList();
        Long nextBeforeId = matches.size() > PAGE_SIZE ? entries.get(PAGE_SIZE - 1).id() : null;
        return new Page(entries, nextBeforeId);
    }

    /**
     * 允许留空结果筛选条件，指定时要求结果码属于审计枚举。
     * 上游为 AuditLogQueryService.list。
     * 下游为 AuditOutcome.values。
     *
     * @param outcome 查询或操作的业务结果状态
     * @return 符合本方法描述的状态或完成条件时为 true
     */
    private boolean validOutcome(String outcome) {
        return outcome.isEmpty() || Arrays.stream(AuditOutcome.values()).anyMatch(value -> value.getCode().equals(outcome));
    }

    /**
     * 允许省略日期条件，指定时要求年份介于 1 到 9999。
     * 上游为 AuditLogQueryService.list。
     *
     * @param date 审计查询的可选日期边界
     * @return 符合本方法描述的状态或完成条件时为 true
     */
    private boolean validDate(LocalDate date) {
        return date == null || (date.getYear() >= 1 && date.getYear() <= 9999);
    }

    public record Entry(long id, Long actorUserId, String actorUsername, String action, String targetType,
                        String targetId, String outcome, String requestId, LocalDateTime createdAt) { }

    public record Page(List<Entry> entries, Long nextBeforeId) { }
}
