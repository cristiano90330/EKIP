/* 接收审计查询过滤条件并返回当前管理员可访问的结果页。 */

package com.pony.knowledge.controller;

import com.pony.knowledge.audit.AuditLogQueryService;
import com.pony.knowledge.common.Result;
import com.pony.knowledge.utils.CurrentUserUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/audit/logs")
@RequiredArgsConstructor
public class AuditLogController {
    private final AuditLogQueryService audit;
    private final CurrentUserUtil currentUser;

    /**
     * 将审计筛选条件和当前登录身份交给管理员审计查询。
     * 上游为 HTTP GET /audit/logs。
     * 下游为 Result.success、AuditLogQueryService.list。
     *
     * @param beforeId 要定位的关联标识
     * @param from 查询的起点条件
     * @param to 查询的结束条件
     * @param action 要查询或记录的审计操作码
     * @param outcome 查询或操作的业务结果状态
     * @return 包含 AuditLogQueryService.Page 数据的业务响应
     */
    @GetMapping
    public Result<AuditLogQueryService.Page> list(
            @RequestParam(name = "beforeId", required = false) Long beforeId,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "action", defaultValue = "") String action,
            @RequestParam(name = "outcome", defaultValue = "") String outcome) {
        return Result.success(audit.list(currentUser.getUserId(), beforeId, from, to, action, outcome));
    }
}
