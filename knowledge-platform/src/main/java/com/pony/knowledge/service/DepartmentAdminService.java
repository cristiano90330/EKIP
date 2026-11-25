/* 校验管理员权限并维护部门与账号部门归属。 */

package com.pony.knowledge.service;

import com.pony.knowledge.exception.ForbiddenException;
import com.pony.knowledge.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
@RequiredArgsConstructor
public class DepartmentAdminService {
    private final AccountAdminService accounts;
    private final UserMapper users;
    private final JdbcTemplate jdbc;

    /**
     * 核对账号管理权限后读取部门列表。
     * 上游为 DepartmentAdminController.list。
     * 下游为 DepartmentAdminService.requireAdmin。
     *
     * @param actor 发起本次操作的账号 ID
     * @return 与过滤条件匹配的 List&lt;Department&gt; 数据集合
     */
    public List<Department> list(long actor) {
        requireAdmin(actor);
        return jdbc.query("SELECT id, name FROM department ORDER BY name, id",
                (rs, row) -> new Department(rs.getLong("id"), rs.getString("name")));
    }

    /**
     * 规范化部门名并保存新部门。
     * 上游为 DepartmentAdminController.create。
     * 下游为 DepartmentAdminService.requireAdmin。
     *
     * @param actor 发起本次操作的账号 ID
     * @param name 记录对外显示的名称
     * @return 本次计算或查询得到的 Department 结果
     */
    public Department create(long actor, String name) {
        requireAdmin(actor);
        String value = name.strip();
        try {
            Long id = jdbc.queryForObject("INSERT INTO department(name) VALUES (?) RETURNING id", Long.class, value);
            return new Department(id, value);
        } catch (DuplicateKeyException duplicate) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "部门名称已存在");
        }
    }

    /**
     * 更新部门名称并同步已分配账号的名称字段。
     * 上游为 DepartmentAdminController.rename。
     * 下游为 DepartmentAdminService.requireAdmin、DepartmentAdminService.missingDepartment。
     *
     * @param actor 发起本次操作的账号 ID
     * @param id 部门 ID
     * @param name 记录对外显示的名称
     * @return 本次计算或查询得到的 Department 结果
     */
    public Department rename(long actor, long id, String name) {
        requireAdmin(actor);
        String value = name.strip();
        try {
            if (jdbc.update("UPDATE department SET name=? WHERE id=?", value, id) != 1) throw missingDepartment();
            return new Department(id, value);
        } catch (DuplicateKeyException duplicate) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "部门名称已存在");
        }
    }

    /**
     * 删除部门记录并清除关联账号的部门字段。
     * 上游为 DepartmentAdminController.delete。
     * 下游为 DepartmentAdminService.requireAdmin、DepartmentAdminService.missingDepartment。
     *
     * @param actor 发起本次操作的账号 ID
     * @param id 部门 ID
     */
    public void delete(long actor, long id) {
        requireAdmin(actor);
        if (jdbc.update("DELETE FROM department WHERE id=?", id) != 1) throw missingDepartment();
    }

    /**
     * 检查账号与部门后更新指定用户归属。
     * 上游为 DepartmentAdminController.assign。
     * 下游为 UserMapper.updateDepartment、AccountAdminService.toAccount。
     *
     * @param actor 发起本次操作的账号 ID
     * @param userId 用户 ID
     * @param departmentId 部门 ID
     * @return 本次计算或查询得到的 AccountAdminService.Account 结果
     */
    @Transactional
    public AccountAdminService.Account assign(long actor, long userId, Long departmentId) {
        requireAdmin(actor);
        String name = null;
        if (departmentId != null) {
            List<String> names = jdbc.queryForList("SELECT name FROM department WHERE id=? FOR KEY SHARE",
                    String.class, departmentId);
            if (names.isEmpty()) throw missingDepartment();
            name = names.get(0);
        }
        if (users.updateDepartment(userId, name) != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "账号不存在");
        }
        return AccountAdminService.toAccount(users.selectAccountState(userId), actor);
    }

    /**
     * 拒绝非账号管理员修改部门或账号归属。
     * 上游为 DepartmentAdminService.list、DepartmentAdminService.create。
     * 下游为 AccountAdminService.canManage。
     *
     * @param actor 发起本次操作的账号 ID
     */
    private void requireAdmin(long actor) {
        if (!accounts.canManage(actor)) throw new ForbiddenException("只有管理员可以管理部门");
    }

    /**
     * 构建部门不存在或已被删除的业务异常。
     * 上游为 DepartmentAdminService.rename、DepartmentAdminService.delete。
     *
     * @return 本次计算或查询得到的 ResponseStatusException 结果
     */
    private static ResponseStatusException missingDepartment() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "部门不存在");
    }

    public record Department(long id, String name) { }
}
