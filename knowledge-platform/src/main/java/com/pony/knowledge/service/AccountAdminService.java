/* 验证运维管理员权限，查询账号并管理启停和会话失效。 */

package com.pony.knowledge.service;

import com.pony.knowledge.audit.AuditAction;
import com.pony.knowledge.audit.AuditLogService;
import com.pony.knowledge.entity.User;
import com.pony.knowledge.entity.UserStatus;
import com.pony.knowledge.exception.ForbiddenException;
import com.pony.knowledge.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class AccountAdminService {
    private static final int PAGE_SIZE = 50;

    private final UserMapper users;
    private final AuditLogService audit;
    private final String adminUsername;

    /**
     * 保存验证运维管理员权限所需的依赖和初始配置。
     * 上游为 Spring 组件装配。
     *
     * @param users UserMapper，负责提供登录账号、启停版本、部门和管理列表的数据库操作
     * @param audit 本次操作使用的 AuditLogService 组件或记录
     * @param adminUsername 初始化或更新同名字段的值
     */
    public AccountAdminService(UserMapper users, AuditLogService audit,
                               @Value("${accounts.admin-username:}") String adminUsername) {
        this.users = users;
        this.audit = audit;
        this.adminUsername = adminUsername.strip();
    }

    /**
     * 按已配置的管理员用户名和账号状态判定管理权限。
     * 上游为 AuditLogQueryService.list、AccountAdminController.access。
     * 下游为 UserMapper.isEnabledAccountNamed。
     *
     * @param userId 用户 ID
     * @return 符合本方法描述的状态或完成条件时为 true
     */
    public boolean canManage(long userId) {
        return !adminUsername.isEmpty() && users.isEnabledAccountNamed(userId, adminUsername);
    }

    /**
     * 检查用户名是否保留给部署账号管理员。
     * 上游为 UserServiceImpl.register。
     * 下游为 UserMapper.isSameUsername。
     *
     * @param username 登录用户名
     * @return 条件是否成立（检查用户名是否保留给部署账号管理员）
     */
    public boolean isReservedUsername(String username) {
        return !adminUsername.isEmpty() && users.isSameUsername(username, adminUsername);
    }

    /**
     * 验证管理者身份后分页读取账号列表。
     * 上游为 AccountAdminController.list。
     * 下游为 UserMapper.listAccountsAfter。
     *
     * @param actorId 操作人用户 ID
     * @param afterId 上一页最后一个标识
     * @return 本次计算或查询得到的 AccountPage 结果
     */
    public AccountPage list(long actorId, long afterId) {
        requireAdmin(actorId);

        if (afterId < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "账号分页参数无效");
        }
        List<User> candidates = users.listAccountsAfter(afterId, PAGE_SIZE + 1);
        List<Account> accounts = candidates.stream().limit(PAGE_SIZE).map(user -> toAccount(user, actorId)).toList();
        Long nextAfterId = candidates.size() > PAGE_SIZE ? accounts.get(PAGE_SIZE - 1).id() : null;
        return new AccountPage(accounts, nextAfterId);
    }

    /**
     * 验证管理身份并更新目标账号状态及旧会话版本。
     * 上游为 AccountAdminController.setStatus。
     * 下游为 UserMapper.updateStatus、AuditLogService.record。
     *
     * @param actorId 操作人用户 ID
     * @param userId 用户 ID
     * @param enabled 期望的启用状态
     * @return 本次计算或查询得到的 Account 结果
     */
    public Account changeEnabled(long actorId, long userId, boolean enabled) {
        requireAdmin(actorId);

        if (actorId == userId) {
            throw new ForbiddenException("不能变更当前管理员账号的状态");
        }
        UserStatus status = enabled ? UserStatus.NORMAL : UserStatus.DISABLED;

        if (users.updateStatus(userId, status.getCode()) != 0) {
            audit.record(AuditAction.ACCOUNT_STATUS_CHANGED, "user", userId,
                    AuditLogService.details("enabled", enabled));
        }
        User user = users.selectAccountState(userId);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "账号不存在");
        }
        return toAccount(user, actorId);
    }

    /**
     * 拒绝由非部署管理员执行的账号管理操作。
     * 上游为 AccountAdminService.list、AccountAdminService.changeEnabled。
     * 下游为 AccountAdminService.canManage。
     *
     * @param userId 用户 ID
     */
    private void requireAdmin(long userId) {
        if (!canManage(userId)) {
            throw new ForbiddenException("只有账号管理员可以执行此操作");
        }
    }

    /**
     * 把数据库账号投影转换为管理员列表项。
     * 上游为 AccountAdminService.list、AccountAdminService.changeEnabled。
     * 下游为 User.getId、User.getUsername。
     *
     * @param user 账号数据
     * @param actorId 操作人用户 ID
     * @return 本次计算或查询得到的 Account 结果
     */
    static Account toAccount(User user, long actorId) {
        return new Account(user.getId(), user.getUsername(), user.getStatus() == UserStatus.NORMAL,
                user.getId() == actorId, user.getDepartment());
    }

    public record Account(long id, String username, boolean enabled, boolean currentAdmin, String department) { }

    public record AccountPage(List<Account> accounts, Long nextAfterId) { }
}
