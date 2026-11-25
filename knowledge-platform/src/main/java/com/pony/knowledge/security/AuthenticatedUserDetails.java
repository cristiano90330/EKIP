/* 向认证框架暴露账号身份、启停状态和会话版本。 */

package com.pony.knowledge.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * 登录认证使用的账号快照，包含数据库用户 ID、密码摘要和启用状态。
 * DaoAuthenticationProvider 使用它校验账号密码，登录流程再取用户 ID 签发令牌；
 * 后续 JWT 请求的 principal 是 Long 用户 ID，不是本对象。
 */
public class AuthenticatedUserDetails implements UserDetails {

    private final Long userId;

    private final String username;

    /** 仅供登录密码核验使用的 BCrypt 摘要。 */
    private final String password;

    private final boolean enabled;
    private final long sessionVersion;

    /**
     * 保存向认证框架暴露账号身份、启停状态和会话版本所需的依赖和初始配置。
     *
     * @param userId 用户 ID
     * @param username 登录用户名
     * @param password 输入的密码
     * @param enabled 期望的启用状态
     * @param sessionVersion 账号会话版本
     */
    public AuthenticatedUserDetails(Long userId, String username, String password, boolean enabled, long sessionVersion) {
        this.userId = userId;
        this.username = username;
        this.password = password;
        this.enabled = enabled;
        this.sessionVersion = sessionVersion;
    }

    /** 返回当前已认证的用户 ID。 */
    public Long getUserId() {
        return userId;
    }

    /** 返回当前账号的会话版本。 */
    public long getSessionVersion() {
        return sessionVersion;
    }

    /** 返回认证框架可用的账号授权集合。 */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of();
    }

    /** 返回认证组件用于校验的密码摘要。 */
    @Override
    public String getPassword() {
        return password;
    }

    /** 返回提交认证时使用的账号名称。 */
    @Override
    public String getUsername() {
        return username;
    }

    /**
     * 向认证框架声明账号没有独立的过期时间限制。
     *
     * @return 固定返回 true
     */
    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    /**
     * 向认证框架声明独立账号锁定策略由登录限流处理。
     *
     * @return 固定返回 true
     */
    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    /**
     * 向认证框架声明账号密码没有独立的过期配置。
     *
     * @return 固定返回 true
     */
    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    /**
     * 返回数据库账号映射得到的启用标记。
     *
     * @return 符合本方法描述的状态或完成条件时为 true
     */
    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
