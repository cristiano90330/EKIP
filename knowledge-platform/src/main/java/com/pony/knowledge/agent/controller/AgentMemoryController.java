/* 通过当前用户身份提供长期记忆查询、修正和删除入口。 */

package com.pony.knowledge.agent.controller;

import com.pony.knowledge.agent.memory.longterm.LongTermMemoryService;
import com.pony.knowledge.common.Result;
import com.pony.knowledge.utils.CurrentUserUtil;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 当前认证用户的长期记忆管理；请求不接受身份或来源会话字段。 */
@RestController
@RequestMapping("/agent/memories")
@RequiredArgsConstructor
public class AgentMemoryController {
    private final LongTermMemoryService memories;
    private final CurrentUserUtil currentUser;

    public record CorrectionRequest(@NotBlank String content, String kind) { }

    /**
     * 按登录身份列出可在页面管理的长期记忆和开关状态。
     * 上游由 {@code AgentMemoryManager.vue 的个人记忆查询} 调用。
     * 下游调用 {@code Result.success}、{@code memories.managementList}。
     *
     * @return 调用方使用的 {@code Result<LongTermMemoryService.ManagementView>}
     */
    @GetMapping
    public Result<LongTermMemoryService.ManagementView> list() {
        return Result.success(memories.managementList(currentUser.getUserId()));
    }

    /**
     * 将路径中的记忆 ID、新内容和类型交给管理服务修正，返回更新后的记录。
     * 上游由 {@code AgentMemoryManager.vue 的记忆修改} 调用。
     * 下游调用 {@code Result.success}、{@code memories.correct}、{@code request.content}。
     *
     * @param id 本次调用传入的 {@code String}
     * @param request 当前入口的请求对象
     * @return 调用方使用的 {@code Result<LongTermMemoryService.ItemView>}
     */
    @PutMapping("/{id}")
    public Result<LongTermMemoryService.ItemView> correct(@PathVariable String id,
                                                         @Valid @RequestBody CorrectionRequest request) {
        return Result.success(memories.correct(currentUser.getUserId(), id, request.content(), request.kind()));
    }

    /**
     * 按当前用户和路径中的记忆 ID 发起删除，返回页面可消费的业务结果。
     * 上游由 {@code AgentMemoryManager.vue 的记忆删除} 调用。
     * 下游调用 {@code memories.delete}、{@code Result.success}。
     *
     * @param id 本次调用传入的 {@code String}
     * @return 调用方使用的 {@code Result<String>}
     */
    @DeleteMapping("/{id}")
    public Result<String> delete(@PathVariable String id) {
        memories.delete(currentUser.getUserId(), id);
        return Result.success("记忆已删除");
    }
}
