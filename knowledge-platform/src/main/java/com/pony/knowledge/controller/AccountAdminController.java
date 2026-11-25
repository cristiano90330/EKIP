/* 提供管理员访问标记、账号列表和启停接口。 */

package com.pony.knowledge.controller;

import com.pony.knowledge.common.Result;
import com.pony.knowledge.service.AccountAdminService;
import com.pony.knowledge.utils.CurrentUserUtil;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/user/account-admin")
@RequiredArgsConstructor
public class AccountAdminController {
    private final AccountAdminService accounts;
    private final CurrentUserUtil currentUser;

    /**
     * 向当前登录账号返回是否具备管理入口权限。
     * 上游为 HTTP GET /user/account-admin/access。
     * 下游为 Result.success、AccountAdminService.canManage。
     *
     * @return 包含 Boolean 数据的业务响应
     */
    @GetMapping("/access")
    public Result<Boolean> access() {
        return Result.success(accounts.canManage(currentUser.getUserId()));
    }

    /**
     * 根据当前用户和分页位置查询可管理的账号。
     * 上游为 HTTP GET /user/account-admin。
     * 下游为 Result.success、AccountAdminService.list。
     *
     * @param afterId 上一页最后一个标识
     * @return 包含 AccountAdminService.AccountPage 数据的业务响应
     */
    @GetMapping
    public Result<AccountAdminService.AccountPage> list(@RequestParam(name = "afterId", defaultValue = "0") long afterId) {
        return Result.success(accounts.list(currentUser.getUserId(), afterId));
    }

    /**
     * 请求管理员启停目标账号并更新其会话状态。
     * 上游为 HTTP PUT /user/account-admin/{userId}/status。
     * 下游为 Result.success、AccountAdminService.changeEnabled。
     *
     * @param userId 用户 ID
     * @param request 当前调用的请求参数
     * @return 包含 AccountAdminService.Account 数据的业务响应
     */
    @PutMapping("/{userId}/status")
    public Result<AccountAdminService.Account> setStatus(@PathVariable long userId,
                                                         @Valid @RequestBody AccountStatusRequest request) {
        return Result.success(accounts.changeEnabled(currentUser.getUserId(), userId, request.enabled()));
    }

    public record AccountStatusRequest(@NotNull(message = "请指定账号是否启用") Boolean enabled) { }
}
