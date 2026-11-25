/* 从安全上下文读取当前认证账号 ID。 */

package com.pony.knowledge.utils;

import com.pony.knowledge.exception.UnauthorizedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 已认证业务请求读取当前用户的统一入口。
 * 只接受认证上下文中的 Long 用户 ID；缺失或类型不符时拒绝，具体资源权限仍由业务 Service 判断。
 */
@Component
public class CurrentUserUtil {

    private static final String UNAUTHORIZED_MESSAGE = "未认证或登录已过期";

    /**
     * 返回当前已认证的用户 ID。
     * 上游为 AgentMemoryController.list、AgentMemoryController.correct。
     * 下游为 CurrentUserUtil.getUserIdOrNull。
     *
     * @return 当前已认证的用户 ID
     */
    public Long getUserId() {
        Long userId = getUserIdOrNull();
        if (userId == null) {
            throw new UnauthorizedException(UNAUTHORIZED_MESSAGE);
        }
        return userId;
    }

    /**
     * 尽力从安全上下文提取用户 ID，匿名场景返回空。
     * 上游为 InMemoryTraceStore.save。
     * 下游为 SecurityContextHolder.getContext。
     *
     * @return 当前请求的认证账号 ID，缺少认证身份时为空
     */
    public Long getUserIdOrNull() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        return authentication.getPrincipal() instanceof Long userId ? userId : null;
    }
}
