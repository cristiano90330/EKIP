/* 管理登录用户的 Agent 会话及分页回合历史。 */

package com.pony.knowledge.agent.controller;

import com.pony.knowledge.agent.session.AgentSession;
import com.pony.knowledge.agent.session.AgentSessionService;
import com.pony.knowledge.common.Result;
import com.pony.knowledge.utils.CurrentUserUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 当前认证用户的会话目录；不接受客户端 userId 或策略指纹。 */
@RestController
@RequestMapping("/agent/sessions")
@RequiredArgsConstructor
public class AgentSessionController {
    private final AgentSessionService sessions;
    private final CurrentUserUtil currentUser;

    public record CreateRequest(Long agentId) { }

    /**
     * 从创建请求取 Agent 选择并按登录身份签发新会话。
     * 下游调用 {@code Result.success}、{@code sessions.create}、{@code request.agentId}。
     *
     * @param request 当前入口的请求对象
     * @return 调用方使用的 {@code Result<AgentSession.View>}
     */
    @PostMapping
    public Result<AgentSession.View> create(@RequestBody(required = false) CreateRequest request) {
        return Result.success(sessions.create(currentUser.getUserId(), request == null ? null : request.agentId()));
    }

    /**
     * 用当前用户身份、目录偏移和页长读取可见会话列表。
     * 下游调用 {@code Result.success}、{@code sessions.list}。
     *
     * @param offset 从结果边界起算的分页偏移
     * @param limit 最多返回或保留的条数
     * @return 调用方使用的 {@code Result<AgentSessionService.SessionPage>}
     */
    @GetMapping
    public Result<AgentSessionService.SessionPage> list(@RequestParam(defaultValue = "0") int offset,
                                                       @RequestParam(defaultValue = "30") int limit) {
        return Result.success(sessions.list(currentUser.getUserId(), offset, limit));
    }

    /**
     * 按当前用户、会话 ID 和分页参数读取已完成回合。
     * 上游由 {@code frontend/src/api/agent.ts 的会话历史查询} 调用。
     * 下游调用 {@code Result.success}、{@code sessions.history}。
     *
     * @param id 服务端签发的产品会话 ID
     * @param offset 从结果边界起算的分页偏移
     * @param limit 最多返回或保留的条数
     * @return 调用方使用的 {@code Result<AgentSessionService.HistoryPage>}
     */
    @GetMapping("/{id}/history")
    public Result<AgentSessionService.HistoryPage> history(@PathVariable String id,
                                                         @RequestParam(defaultValue = "0") int offset,
                                                         @RequestParam(defaultValue = "20") int limit) {
        return Result.success(sessions.history(currentUser.getUserId(), id, offset, limit));
    }

    /**
     * 按身份和会话 ID 请求清理会话，返回业务确认。
     * 下游调用 {@code sessions.delete}、{@code Result.success}。
     *
     * @param id 服务端签发的产品会话 ID
     * @return 调用方使用的 {@code Result<String>}
     */
    @DeleteMapping("/{id}")
    public Result<String> delete(@PathVariable String id) {
        sessions.delete(currentUser.getUserId(), id);
        return Result.success("会话已删除");
    }
}
