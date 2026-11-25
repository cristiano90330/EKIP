/* 查询会话内待确认的长期记忆申请并提交用户决定。 */

package com.pony.knowledge.agent.controller;

import com.pony.knowledge.agent.tool.approval.MemorySaveApproval;
import com.pony.knowledge.agent.tool.approval.MemorySaveApprovalService;
import com.pony.knowledge.common.Result;
import com.pony.knowledge.utils.CurrentUserUtil;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/agent/sessions/{sessionId}/memory-approvals/{approvalId}")
@RequiredArgsConstructor
public class MemorySaveApprovalController {
    private final CurrentUserUtil currentUser;
    private final MemorySaveApprovalService approvals;

    /**
     * 用当前用户和路径中的会话、申请 ID 查询长期记忆提议。
     * 上游由 {@code frontend/src/api/agent.ts 的申请查询入口} 调用。
     * 下游调用 {@code Result.success}。
     *
     * @param sessionId 受当前用户与策略约束的服务端会话 ID
     * @param approvalId 目标审批申请 ID
     * @return 调用方使用的 {@code Result<MemorySaveApproval>}
     */
    @GetMapping
    public Result<MemorySaveApproval> get(@PathVariable("sessionId") String sessionId,
                                          @PathVariable("approvalId") UUID approvalId) {
        return Result.success(approvals.get(currentUser.getUserId(), sessionId, approvalId));
    }

    /**
     * 读取用户在审批卡上的选择并提交审批服务，返回该申请的最新状态。
     * 上游由 {@code MemorySaveApprovalCard.vue 的用户确认操作} 调用。
     * 下游调用 {@code Result.success}、{@code approvals.decide}、{@code request.decision}。
     *
     * @param sessionId 受当前用户与策略约束的服务端会话 ID
     * @param approvalId 目标审批申请 ID
     * @param request 当前入口的请求对象
     * @return 调用方使用的 {@code Result<MemorySaveApproval>}
     */
    @PostMapping("/decision")
    public Result<MemorySaveApproval> decide(@PathVariable("sessionId") String sessionId,
                                             @PathVariable("approvalId") UUID approvalId,
                                             @Valid @RequestBody DecisionRequest request) {
        return Result.success(approvals.decide(currentUser.getUserId(), sessionId, approvalId, request.decision()));
    }

    public record DecisionRequest(@NotNull MemorySaveApproval.Decision decision) { }
}
