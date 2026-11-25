/* 验证 JWT 过滤器在异步请求重新分派时的认证行为。 */

package com.pony.knowledge.security;

import com.pony.knowledge.config.SecurityConfig;
import com.pony.knowledge.entity.User;
import com.pony.knowledge.entity.UserStatus;
import com.pony.knowledge.mapper.UserMapper;
import com.pony.knowledge.utils.JwtUtil;
import io.jsonwebtoken.MalformedJwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.atomic.AtomicReference;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@WebMvcTest(controllers = JwtAuthenticationAsyncDispatchTest.AsyncStreamProbeController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class,
        JwtAuthenticationAsyncDispatchTest.AsyncStreamConfiguration.class})
class JwtAuthenticationAsyncDispatchTest {

    private static final String VALID_TOKEN = "valid-token";
    private static final String INVALID_TOKEN = "invalid-token";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtUtil jwtUtil;

    @MockitoBean
    private UserMapper users;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @MockitoBean
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AsyncStreamProbeController probeController;

    /** 每个用例前准备夹具，调用 {@code jwtUtil.parseAccessToken}、{@code account.setStatus}。 */
    @BeforeEach
    void configureJwt() {
        when(jwtUtil.parseAccessToken(VALID_TOKEN)).thenReturn(new JwtUtil.AccessTokenInfo(999_904L, 0));
        User account = new User();
        account.setStatus(UserStatus.NORMAL);
        when(users.selectAccountState(999_904L)).thenReturn(account);
        doThrow(new MalformedJwtException("invalid token"))
                .when(jwtUtil).parseAccessToken(INVALID_TOKEN);
    }

    /** 核对 HTTP 200。调用路径经过 {@code probeController.completeCurrentStream}。 */
    @Test
    void validBearerTokenRemainsAuthenticatedOnAsyncDispatch() throws Exception {
        MvcResult initial = startStream(VALID_TOKEN);

        probeController.completeCurrentStream();

        mockMvc.perform(asyncDispatch(initial))
                .andExpect(status().isOk());
        verify(jwtUtil, times(2)).parseAccessToken(VALID_TOKEN);
    }

    /** 核对 HTTP 401。 */
    @Test
    void missingBearerTokenOnInitialRequestReturns401() throws Exception {
        mockMvc.perform(get("/__test/security/async-stream")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isUnauthorized());
    }

    /** 核对 HTTP 401。调用路径经过 {@code initial.getRequest}。 */
    @Test
    void invalidBearerTokenOnAsyncDispatchStillReturns401() throws Exception {
        MvcResult initial = startStream(VALID_TOKEN);
        initial.getRequest().removeHeader(HttpHeaders.AUTHORIZATION);
        initial.getRequest().addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + INVALID_TOKEN);

        probeController.completeCurrentStream();

        mockMvc.perform(asyncDispatch(initial))
                .andExpect(status().isUnauthorized());
        verify(jwtUtil).parseAccessToken(INVALID_TOKEN);
    }

    /**
     * 启动隔离测试资源，使用 {@code status}、{@code request}。
     * 由 {@code validBearerTokenRemainsAuthenticatedOnAsyncDispatch}、{@code invalidBearerTokenOnAsyncDispatchStillReturns401} 调用。
     * @param token 本用例传入的{@code token}
     * 返回 {@code MvcResult} 结果。
     */
    private MvcResult startStream(String token) throws Exception {
        return mockMvc.perform(get("/__test/security/async-stream")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();
    }

    @TestConfiguration
    static class AsyncStreamConfiguration {

        /** 构造 {@code AsyncStreamProbeController} 供测试使用。返回 {@code new AsyncStreamProbeController()}。 */
        @Bean
        AsyncStreamProbeController asyncStreamProbeController() {
            return new AsyncStreamProbeController();
        }
    }

    @RestController
    static class AsyncStreamProbeController {

        private final AtomicReference<SseEmitter> currentEmitter = new AtomicReference<>();

        /** 构造 {@code SseEmitter} 供测试使用，使用 {@code currentEmitter.set}。返回 {@code emitter}。 */
        @GetMapping(path = "/__test/security/async-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        SseEmitter stream() {
            SseEmitter emitter = new SseEmitter(10_000L);
            currentEmitter.set(emitter);
            return emitter;
        }

        /** 在测试中处理 {@code completeCurrentStream} 阶段，使用 {@code currentEmitter.getAndSet}、{@code emitter.complete}。 */
        void completeCurrentStream() {
            SseEmitter emitter = currentEmitter.getAndSet(null);
            if (emitter == null) {
                throw new IllegalStateException("No async stream is active");
            }
            emitter.complete();
        }
    }
}
