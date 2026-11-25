/* 验证用户详情加载和账号启停状态映射。 */

package com.pony.knowledge.security;

import com.pony.knowledge.entity.User;
import com.pony.knowledge.entity.UserStatus;
import com.pony.knowledge.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * 登录期的用户加载（设计 §4.3 S2）。
 * 重点是 status → isEnabled() 的三态映射，尤其 status = null 的 fail-closed。
 */
@ExtendWith(MockitoExtension.class)
class UserDetailsServiceImplTest {

    @Mock
    private UserMapper userMapper;

    private UserDetailsServiceImpl service;

    /** 每个用例前准备夹具。 */
    @BeforeEach
    void setUp() {
        service = new UserDetailsServiceImpl(userMapper);
    }

    /**
     * 构造 {@code User} 供测试使用，使用 {@code u.setId}、{@code u.setUsername}。
     * 由 {@code normalStatusYieldsEnabledUserDetailsCarryingUserId}、{@code disabledStatusYieldsDisabledUserDetails} 调用。
     * @param id 目标记录 ID
     * @param username 测试用户名
     * @param status 拟使用的状态值
     * 返回 {@code u}。
     */
    private static User user(Long id, String username, UserStatus status) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        u.setPassword("$2a$10$hashedPasswordHashedPasswordHashedPasswordHashedPasswo");
        u.setStatus(status);
        return u;
    }

    /** 通过 {@code userMapper.selectByUsername} 执行本例并核对输出或记录。 */
    @Test
    void unknownUsernameThrowsUsernameNotFound() {
        when(userMapper.selectByUsername("nobody")).thenReturn(null);

        // 必须是 UsernameNotFoundException：DaoAuthenticationProvider 的 hideUserNotFoundExceptions
        // 默认 true，会把它转成 BadCredentialsException 并跑一次 mitigateAgainstTimingAttack()。
        // 换成业务异常就同时丢掉了统一文案与时序防护。
        assertThatThrownBy(() -> service.loadUserByUsername("nobody"))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    /** 通过 {@code userMapper.selectByUsername} 执行本例并核对输出或记录。 */
    @Test
    void normalStatusYieldsEnabledUserDetailsCarryingUserId() {
        when(userMapper.selectByUsername("alice")).thenReturn(user(7L, "alice", UserStatus.NORMAL));

        AuthenticatedUserDetails details = (AuthenticatedUserDetails) service.loadUserByUsername("alice");

        assertThat(details.isEnabled()).isTrue();
        // userId 必须由 UserDetails 直接携带，否则签 token 还要回查一次库
        assertThat(details.getUserId()).isEqualTo(7L);
        assertThat(details.getUsername()).isEqualTo("alice");
        assertThat(details.getPassword()).startsWith("$2a$");
        // 账号过期/锁定/凭证过期三项本项目没有概念，恒为 true
        assertThat(details.isAccountNonExpired()).isTrue();
        assertThat(details.isAccountNonLocked()).isTrue();
        assertThat(details.isCredentialsNonExpired()).isTrue();
    }

    /** 覆盖停用的相关边界。通过 {@code userMapper.selectByUsername} 执行本例并核对输出或记录。 */
    @Test
    void disabledStatusYieldsDisabledUserDetails() {
        when(userMapper.selectByUsername("bob")).thenReturn(user(8L, "bob", UserStatus.DISABLED));

        UserDetails details = service.loadUserByUsername("bob");

        // DefaultPreAuthenticationChecks 读到 false 就抛 DisabledException → 403
        assertThat(details.isEnabled()).isFalse();
    }

    /** 覆盖空值、停用的相关边界。通过 {@code userMapper.selectByUsername} 执行本例并核对输出或记录。 */
    @Test
    void nullStatusIsTreatedAsDisabled_failClosed() {
        // 历史行可能没有 status 值；宁可拒绝登录也不要默认放行（与 V8 的 default-deny 同一原则）
        when(userMapper.selectByUsername("legacy")).thenReturn(user(9L, "legacy", null));

        UserDetails details = service.loadUserByUsername("legacy");

        assertThat(details.isEnabled()).isFalse();
    }

    /** 覆盖空值的相关边界。通过 {@code userMapper.selectByUsername} 执行本例并核对输出或记录。 */
    @Test
    void authoritiesIsEmptyCollectionNotNull() {
        when(userMapper.selectByUsername("alice")).thenReturn(user(7L, "alice", UserStatus.NORMAL));

        UserDetails details = service.loadUserByUsername("alice");

        // 本项目没有 RBAC，授权走资源级权限模型；Spring 约定是空集合而不是 null
        assertThat(details.getAuthorities()).isNotNull().isEmpty();
    }
}
