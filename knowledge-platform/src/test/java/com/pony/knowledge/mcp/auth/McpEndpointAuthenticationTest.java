/* 通过 MCP HTTP 入口验证 Key 和站内认证凭据的适用范围。 */

package com.pony.knowledge.mcp.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pony.knowledge.config.SecurityConfig;
import com.pony.knowledge.security.JwtAuthenticationFilter;
import com.pony.knowledge.entity.User;
import com.pony.knowledge.entity.UserStatus;
import com.pony.knowledge.mapper.UserMapper;
import com.pony.knowledge.utils.JwtUtil;
import io.jsonwebtoken.MalformedJwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MCP 端点在安全链上的路径与凭证一致性。
 *
 * <p>三处必须共用 {@code mcp.endpoint}：传输层注册路径、API Key 过滤器的匹配路径、安全链的放行路径。
 * 路径写死时，改成自定义端点的部署会在安全层被拒或在旧路径上留下匿名入口；
 * 认证链上 JWT 过滤器还会把同一 Bearer 当 JWT 解析失败并清掉已建立的身份。</p>
 */
@WebMvcTest(controllers = McpEndpointAuthenticationTest.ProbeController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class,
        McpEndpointAuthenticationTest.McpFilterConfiguration.class,
        McpEndpointAuthenticationTest.ProbeController.class})
@TestPropertySource(properties = "mcp.endpoint=/custom-mcp")
class McpEndpointAuthenticationTest {

    private static final String PLAIN_KEY = "ekip_mcp_test-credential";
    private static final String VALID_JWT = "valid-jwt";
    private static final long MCP_USER_ID = 42L;
    private static final long JWT_USER_ID = 7L;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private McpApiKeyService mcpApiKeyService;

    @MockitoBean
    private JwtUtil jwtUtil;

    @MockitoBean
    private UserMapper users;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @MockitoBean
    private PasswordEncoder passwordEncoder;

    /** 每个用例前准备夹具，调用 {@code mcpApiKeyService.authenticate}、{@code McpAuthResult.granted}。 */
    @BeforeEach
    void configureCredentials() {
        when(mcpApiKeyService.authenticate(PLAIN_KEY)).thenReturn(McpAuthResult.granted(keyFor(PLAIN_KEY)));
        // MCP 明文密钥不是 JWT：真实 JwtUtil 一定解析失败，这里保持同样的失败行为。
        // 先铺开「全部失败」，再用 doReturn 覆盖合法 JWT——顺序反了会连打桩调用本身都抛异常。
        doThrow(new MalformedJwtException("not a jwt")).when(jwtUtil).parseAccessToken(anyString());
        doReturn(new JwtUtil.AccessTokenInfo(JWT_USER_ID, 0)).when(jwtUtil).parseAccessToken(VALID_JWT);
        User account = new User();
        account.setStatus(UserStatus.NORMAL);
        when(users.selectAccountState(JWT_USER_ID)).thenReturn(account);
    }

    /** 核对 HTTP 200。调用路径经过 {@code mvc.perform}。 */
    @Test
    void configuredEndpointAcceptsMcpKeyAndKeepsIdentity() throws Exception {
        mvc.perform(get("/custom-mcp").header(HttpHeaders.AUTHORIZATION, "Bearer " + PLAIN_KEY))
                .andExpect(status().isOk())
                .andExpect(content().string(String.valueOf(MCP_USER_ID)));
    }

    /** 核对 HTTP 401。调用路径经过 {@code mvc.perform}。 */
    @Test
    void configuredEndpointRejectsMissingCredentialBeforeController() throws Exception {
        mvc.perform(get("/custom-mcp"))
                .andExpect(status().isUnauthorized());
    }

    /** 核对 HTTP 401。调用路径经过 {@code mvc.perform}。 */
    @Test
    void defaultPathIsNotPermittedWhenEndpointIsCustomized() throws Exception {
        mvc.perform(get("/mcp"))
                .andExpect(status().isUnauthorized());
    }

    /** 核对 HTTP 200、HTTP 401。调用路径经过 {@code mvc.perform}。 */
    @Test
    void keyManagementPathStillRequiresJwt() throws Exception {
        mvc.perform(get("/mcp/keys")).andExpect(status().isUnauthorized());
        mvc.perform(get("/mcp/keys").header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_JWT))
                .andExpect(status().isOk());
    }

    /**
     * 构造 {@code McpApiKey} 供测试使用，使用 {@code key.setId}、{@code key.setUserId}。
     * 由 {@code configureCredentials} 调用。
     * @param plain 本用例传入的{@code plain}
     * 返回 {@code key}。
     */
    private static McpApiKey keyFor(String plain) {
        McpApiKey key = new McpApiKey();
        key.setId(9L);
        key.setUserId(MCP_USER_ID);
        key.setKeyHash(McpApiKeyGenerator.sha256Hex(plain));
        return key;
    }

    /** 与 McpServerConfig 一致：过滤器读同一配置项，并关闭容器层的重复注册。 */
    @TestConfiguration
    static class McpFilterConfiguration {

        /**
         * 构造 {@code McpApiKeyAuthFilter} 供测试使用。
         * 接收{@code service}、{@code endpoint}。
         * 返回 {@code new McpApiKeyAuthFilter(service, new ObjectMapper(), endpoint)}。
         */
        @Bean
        McpApiKeyAuthFilter mcpApiKeyAuthFilter(McpApiKeyService service,
                                               @Value("${mcp.endpoint}") String endpoint) {
            return new McpApiKeyAuthFilter(service, new ObjectMapper(), endpoint);
        }

        /**
         * 构造 {@code FilterRegistrationBean<McpApiKeyAuthFilter>} 供测试使用，使用 {@code registration.setEnabled}。
         * @param filter 本用例传入的{@code filter}
         * 返回 {@code registration}。
         */
        @Bean
        FilterRegistrationBean<McpApiKeyAuthFilter> mcpApiKeyAuthFilterRegistration(McpApiKeyAuthFilter filter) {
            FilterRegistrationBean<McpApiKeyAuthFilter> registration = new FilterRegistrationBean<>(filter);
            registration.setEnabled(false);
            return registration;
        }
    }

    @RestController
    static class ProbeController {

        /** 构造 {@code String} 供测试使用，使用 {@code SecurityContextHolder.getContext}。返回 {@code String} 结果。 */
        @GetMapping("/custom-mcp")
        String customEndpoint() {
            return String.valueOf(SecurityContextHolder.getContext().getAuthentication().getPrincipal());
        }

        /** 构造 {@code String} 供测试使用。返回 {@code "default"}。 */
        @GetMapping("/mcp")
        String defaultEndpoint() {
            return "default";
        }

        /** 构造 {@code String} 供测试使用。返回 {@code "keys"}。 */
        @GetMapping("/mcp/keys")
        String keys() {
            return "keys";
        }
    }
}
