/* 提供部门列表、增删改与账号部门归属的管理接口。 */

package com.pony.knowledge.controller;

import com.pony.knowledge.common.Result;
import com.pony.knowledge.service.AccountAdminService;
import com.pony.knowledge.service.DepartmentAdminService;
import com.pony.knowledge.utils.CurrentUserUtil;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/user/account-admin")
@RequiredArgsConstructor
public class DepartmentAdminController {
    private final DepartmentAdminService departments;
    private final CurrentUserUtil currentUser;

    /**
     * 为当前管理员列出可分配的部门。
     * 上游为 HTTP GET /user/account-admin/departments。
     * 下游为 Result.success、DepartmentAdminService.list。
     *
     * @return 包含 List&lt;DepartmentAdminService.Department&gt; 数据的业务响应
     */
    @GetMapping("/departments")
    public Result<List<DepartmentAdminService.Department>> list() {
        return Result.success(departments.list(currentUser.getUserId()));
    }

    /**
     * 接收管理员请求并建立新部门。
     * 上游为 HTTP POST /user/account-admin/departments。
     * 下游为 Result.success、DepartmentAdminService.create。
     *
     * @param input 本次操作使用的 DepartmentName 组件或记录
     * @return 包含 DepartmentAdminService.Department 数据的业务响应
     */
    @PostMapping("/departments")
    public Result<DepartmentAdminService.Department> create(@Valid @RequestBody DepartmentName input) {
        return Result.success(departments.create(currentUser.getUserId(), input.name()));
    }

    /**
     * 按部门 ID 和名称更新现有部门。
     * 上游为 HTTP PUT /user/account-admin/departments/{departmentId}。
     * 下游为 Result.success、DepartmentAdminService.rename。
     *
     * @param departmentId 部门 ID
     * @param input 本次操作使用的 DepartmentName 组件或记录
     * @return 包含 DepartmentAdminService.Department 数据的业务响应
     */
    @PutMapping("/departments/{departmentId}")
    public Result<DepartmentAdminService.Department> rename(@PathVariable long departmentId,
                                                             @Valid @RequestBody DepartmentName input) {
        return Result.success(departments.rename(currentUser.getUserId(), departmentId, input.name()));
    }

    /**
     * 删除指定部门并解除受影响的部门归属。
     * 上游为 HTTP DELETE /user/account-admin/departments/{departmentId}。
     * 下游为 DepartmentAdminService.delete、Result.success。
     *
     * @param departmentId 部门 ID
     * @return 包含 String 数据的业务响应
     */
    @DeleteMapping("/departments/{departmentId}")
    public Result<String> delete(@PathVariable long departmentId) {
        departments.delete(currentUser.getUserId(), departmentId);
        return Result.success("部门已删除");
    }

    /**
     * 为目标账号设置或清除所属部门。
     * 上游为 HTTP PUT /user/account-admin/{userId}/department。
     * 下游为 Result.success、DepartmentAdminService.assign。
     *
     * @param userId 用户 ID
     * @param input 本次操作使用的 DepartmentAssignment 组件或记录
     * @return 包含 AccountAdminService.Account 数据的业务响应
     */
    @PutMapping("/{userId}/department")
    public Result<AccountAdminService.Account> assign(@PathVariable long userId,
                                                       @Valid @RequestBody DepartmentAssignment input) {
        return Result.success(departments.assign(currentUser.getUserId(), userId, input.departmentId()));
    }

    public record DepartmentName(@NotBlank @Size(max = 100) String name) { }
    public record DepartmentAssignment(@Positive Long departmentId) { }
}
