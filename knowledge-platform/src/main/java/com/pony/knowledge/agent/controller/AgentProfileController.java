/* 提供自建 Agent 配置的列表、查询、创建、更新和删除接口。 */

package com.pony.knowledge.agent.controller;

import com.pony.knowledge.agent.profile.AgentProfileRequest;
import com.pony.knowledge.agent.profile.AgentProfileResponse;
import com.pony.knowledge.agent.profile.AgentProfileService;
import com.pony.knowledge.common.Result;
import com.pony.knowledge.utils.CurrentUserUtil;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 当前用户自己的 Agent 配置 CRUD；归属由 CurrentUserUtil + Service 双重限定。 */
@RestController
@RequestMapping("/agent/profiles")
@RequiredArgsConstructor
public class AgentProfileController {

    private final AgentProfileService agentProfileService;

    private final CurrentUserUtil currentUserUtil;

    /**
     * 用登录用户身份查询其可管理的 Agent 配置列表。
     * 上游由 {@code frontend/src/api/agent.ts 的 Agent 配置查询} 调用。
     * 下游调用 {@code Result.success}、{@code agentProfileService.list}。
     *
     * @return 调用方使用的 {@code Result<List<AgentProfileResponse>>}
     */
    @GetMapping
    public Result<List<AgentProfileResponse>> list() {
        return Result.success(agentProfileService.list(currentUserUtil.getUserId()));
    }

    /**
     * 读取当前用户拥有的指定配置，并包装为接口响应。
     * 下游调用 {@code Result.success}。
     *
     * @param id 目标自建 Agent 配置 ID
     * @return 调用方使用的 {@code Result<AgentProfileResponse>}
     */
    @GetMapping("/{id}")
    public Result<AgentProfileResponse> get(@PathVariable Long id) {
        return Result.success(agentProfileService.get(currentUserUtil.getUserId(), id));
    }

    /**
     * 验证客户端创建请求后，交由配置服务保存并返回公开配置。
     * 下游调用 {@code Result.success}、{@code agentProfileService.create}。
     *
     * @param request 当前入口的请求对象
     * @return 调用方使用的 {@code Result<AgentProfileResponse>}
     */
    @PostMapping
    public Result<AgentProfileResponse> create(@Valid @RequestBody AgentProfileRequest request) {
        return Result.success(agentProfileService.create(currentUserUtil.getUserId(), request));
    }

    /**
     * 按当前用户和路径中的配置 ID 更新参数，返回更新后的配置。
     * 下游调用 {@code Result.success}、{@code agentProfileService.update}。
     *
     * @param id 目标自建 Agent 配置 ID
     * @param request 当前入口的请求对象
     * @return 调用方使用的 {@code Result<AgentProfileResponse>}
     */
    @PutMapping("/{id}")
    public Result<AgentProfileResponse> update(@PathVariable Long id,
                                               @Valid @RequestBody AgentProfileRequest request) {
        return Result.success(agentProfileService.update(currentUserUtil.getUserId(), id, request));
    }

    /**
     * 按当前用户和配置 ID 删除自建 Agent，并返回接口确认。
     * 下游调用 {@code agentProfileService.delete}、{@code Result.success}。
     *
     * @param id 目标自建 Agent 配置 ID
     * @return 调用方使用的 {@code Result<String>}
     */
    @DeleteMapping("/{id}")
    public Result<String> delete(@PathVariable Long id) {
        agentProfileService.delete(currentUserUtil.getUserId(), id);
        return Result.success("Agent 配置已删除");
    }
}
