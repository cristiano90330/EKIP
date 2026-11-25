/* 检查当前用户读取只接受认证上下文中的受信任身份。 */

package com.pony.knowledge.utils;

import com.pony.knowledge.exception.UnauthorizedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 升级项 P0-4：认证信息缺失或形态不对时，必须抛语义明确的 401 异常，
 * 而不是让 NPE / ClassCastException 冒到全局处理（后者会把 Java 内部类名透给前端）。
 */
class CurrentUserUtilTest {

    private static final List<SimpleGrantedAuthority> ROLE_USER = List.of(new SimpleGrantedAuthority("ROLE_USER"));

    private final CurrentUserUtil util = new CurrentUserUtil();

    /** 回收当前用例的资源和上下文，调用 {@code SecurityContextHolder.clearContext}。 */
    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** 通过 {@code SecurityContextHolder.getContext} 执行本例并核对输出或记录。 */
    @Test
    void returnsUserIdWhenAuthenticated() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(42L, null, ROLE_USER));

        assertThat(util.getUserId()).isEqualTo(42L);
    }

    /** 覆盖认证的相关边界。通过 {@code util.getUserId} 执行本例并核对输出或记录。 */
    @Test
    void throwsUnauthorizedWhenNoAuthentication() {
        // 未带 token 直接访问：getAuthentication() 返回 null —— 改造前是 NPE（message 为 null）
        assertThatThrownBy(() -> util.getUserId())
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("未认证或登录已过期");
    }

    /** 覆盖匿名的相关边界。通过 {@code SecurityContextHolder.getContext} 执行本例并核对输出或记录。 */
    @Test
    void throwsUnauthorizedWhenPrincipalIsAnonymousString() {
        // 匿名请求：principal 是 "anonymousUser" —— 改造前是 ClassCastException，且 message 泄露类名
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken("key", "anonymousUser", ROLE_USER));

        assertThatThrownBy(() -> util.getUserId())
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageNotContaining("cannot be cast");
    }
}
