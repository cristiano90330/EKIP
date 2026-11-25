/* 核对早期认证拒绝和安全过滤过程中的请求 ID 回传。 */

package com.pony.knowledge.request;

import com.pony.knowledge.config.SecurityConfig;
import com.pony.knowledge.exception.ForbiddenException;
import com.pony.knowledge.exception.GlobalExceptionHandler;
import com.pony.knowledge.security.JwtAuthenticationFilter;
import com.pony.knowledge.entity.User;
import com.pony.knowledge.entity.UserStatus;
import com.pony.knowledge.mapper.UserMapper;
import com.pony.knowledge.utils.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(RequestIdSecurityTest.ProbeController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, GlobalExceptionHandler.class,
        RequestIdSecurityTest.ProbeController.class})
class RequestIdSecurityTest {
    @Autowired MockMvc mvc;
    @MockBean JwtUtil jwtUtil;
    @MockBean UserMapper users;
    @MockBean UserDetailsService userDetailsService;
    @MockBean PasswordEncoder passwordEncoder;

    /** 核对 HTTP 401。调用路径经过 {@code mvc.perform}。 */
    @Test
    void securityRejectionKeepsRequestIdBeforeMvcBinding() throws Exception {
        mvc.perform(get("/request-id/probe").header("X-Request-ID", "unauthenticated"))
                .andExpect(status().isUnauthorized()).andExpect(header().string("X-Request-ID", "unauthenticated"));
        assertNull(RequestIdContext.current());
    }

    /** 核对 HTTP 200、HTTP 400、HTTP 403。调用路径经过 {@code jwtUtil.parseAccessToken}。 */
    @Test
    void validInvalidAndForbiddenMvcResponsesKeepRequestId() throws Exception {
        when(jwtUtil.parseAccessToken("valid")).thenReturn(new JwtUtil.AccessTokenInfo(7L, 0));
        User account = new User();
        account.setStatus(UserStatus.NORMAL);
        when(users.selectAccountState(7L)).thenReturn(account);
        mvc.perform(get("/request-id/probe").header("Authorization", "Bearer valid")
                        .header("X-Request-ID", "mvc-invalid"))
                .andExpect(status().isBadRequest()).andExpect(header().string("X-Request-ID", "mvc-invalid"));
        mvc.perform(get("/request-id/probe").param("value", "ok").header("Authorization", "Bearer valid")
                        .header("X-Request-ID", "mvc-success"))
                .andExpect(status().isOk()).andExpect(content().string("mvc-success"))
                .andExpect(header().string("X-Request-ID", "mvc-success"));
        mvc.perform(get("/request-id/probe").param("value", "deny").header("Authorization", "Bearer valid")
                        .header("X-Request-ID", "mvc-forbidden"))
                .andExpect(status().isForbidden()).andExpect(header().string("X-Request-ID", "mvc-forbidden"));
        mvc.perform(get("/request-id/probe").param("value", "fail").header("Authorization", "Bearer valid")
                        .header("X-Request-ID", "mvc-error"))
                .andExpect(status().isInternalServerError()).andExpect(header().string("X-Request-ID", "mvc-error"));
        assertNull(RequestIdContext.current());
    }

    @RestController
    static class ProbeController {
        /**
         * 构造 {@code String} 供测试使用，使用 {@code RequestIdContext.current}。
         * 接收{@code value}。
         * 返回 {@code RequestIdContext.current()}。
         */
        @GetMapping("/request-id/probe")
        String probe(@RequestParam String value) {
            if ("deny".equals(value)) throw new ForbiddenException("denied");
            if ("fail".equals(value)) throw new IllegalStateException("failure");
            return RequestIdContext.current();
        }
    }
}
