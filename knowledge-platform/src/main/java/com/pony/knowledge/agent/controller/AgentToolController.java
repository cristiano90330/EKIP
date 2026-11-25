/* 展示已登记 Agent 工具的公开元数据。 */

package com.pony.knowledge.agent.controller;

import com.pony.knowledge.agent.tool.AgentToolResponse;
import com.pony.knowledge.agent.tool.ToolRegistry;
import com.pony.knowledge.common.Result;
import com.pony.knowledge.utils.CurrentUserUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 供认证用户配置 Agent；目录来自已完成条件装配的运行时注册表，不执行工具。 */
@RestController
@RequestMapping("/agent/tools")
@RequiredArgsConstructor
public class AgentToolController {

    private final ToolRegistry toolRegistry;

    private final CurrentUserUtil currentUserUtil;

    /**
     * 要求当前请求具有登录身份，再返回注册表中的工具公开目录。
     * 下游调用 {@code Result.success}、{@code toolRegistry.catalog}。
     *
     * @return 调用方使用的 {@code Result<List<AgentToolResponse>>}
     */
    @GetMapping
    public Result<List<AgentToolResponse>> list() {
        currentUserUtil.getUserId();
        return Result.success(toolRegistry.catalog());
    }
}
