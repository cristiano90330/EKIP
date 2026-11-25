/* 检查 MCP 请求认证、Key 状态、错误载荷和调用上下文。 */

package com.pony.knowledge.mcp.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpApiKeyAuthFilterTest {

    private static final String ENDPOINT = "/mcp";

    private McpApiKeyService mcpApiKeyService;

    private McpApiKeyAuthFilter filter;

    private MockHttpServletResponse response;

    private MockFilterChain chain;

    /** 每个用例前准备夹具。 */
    @BeforeEach
    void setUp() {
        mcpApiKeyService = mock(McpApiKeyService.class);
        filter = new McpApiKeyAuthFilter(mcpApiKeyService, new ObjectMapper(), ENDPOINT);
        response = new MockHttpServletResponse();
        chain = new MockFilterChain();
    }

    /** 回收当前用例的资源和上下文，调用 {@code SecurityContextHolder.clearContext}。 */
    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /**
     * 建立携带指定授权头的 MCP 测试请求。
     * 由 {@code missingHeaderIs401AndShortCircuits}、{@code nonBearerSchemeIs401} 调用。
     * @param authorizationHeader 本用例传入的{@code authorizationHeader}
     * 返回 {@code request}。
     */
    private MockHttpServletRequest mcpRequest(String authorizationHeader) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", ENDPOINT);
        if (authorizationHeader != null) {
            request.addHeader("Authorization", authorizationHeader);
        }
        return request;
    }

    /** 核对 {@code response.getStatus()} 与预期 {@code 200} 一致。调用路径经过 {@code request.addHeader}。 */
    @Test
    void otherPathsAreNotFiltered() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp/keys");
        request.addHeader("Authorization", "Bearer some.jwt.token");

        filter.doFilter(request, response, chain);

        assertNotNull(chain.getRequest(), "非 MCP 端点必须原样放行给 JWT 链路");
        assertEquals(200, response.getStatus());
        verify(mcpApiKeyService, never()).authenticate(any());
    }

    /** 覆盖缺失、响应头的相关边界。通过 {@code filter.doFilter} 执行本例并核对输出或记录。 */
    @Test
    void missingHeaderIs401AndShortCircuits() throws ServletException, IOException {
        filter.doFilter(mcpRequest(null), response, chain);

        assertUnauthorized("缺少访问凭证");
    }

    /** 通过 {@code filter.doFilter} 执行本例并核对输出或记录。 */
    @Test
    void nonBearerSchemeIs401() throws ServletException, IOException {
        filter.doFilter(mcpRequest("ekip_mcp_withoutBearer"), response, chain);

        assertUnauthorized("凭证格式错误");
    }

    /** 覆盖令牌的相关边界。通过 {@code filter.doFilter} 执行本例并核对输出或记录。 */
    @Test
    void jwtTokenOnMcpEndpointIs401WithoutHittingTheKeyStore() throws ServletException, IOException {
        filter.doFilter(mcpRequest("Bearer eyJhbGciOi.jwt.not.a.mcp.key"), response, chain);

        assertUnauthorized("凭证格式错误");
        verify(mcpApiKeyService, never()).authenticate(any());
    }

    /** 通过 {@code McpApiKeyGenerator.newPlainKey} 执行本例并核对输出或记录。 */
    @Test
    void deniedCredentialIs401WithReasonAndNoChain() throws ServletException, IOException {
        String plain = McpApiKeyGenerator.newPlainKey();
        when(mcpApiKeyService.authenticate(plain)).thenReturn(McpAuthResult.denied("凭证已吊销"));

        filter.doFilter(mcpRequest("Bearer " + plain), response, chain);

        assertUnauthorized("凭证已吊销");
    }

    /** 核对 {@code chain.getRequest()} 已提供。调用路径经过 {@code McpApiKeyGenerator.newPlainKey}。 */
    @Test
    void acceptedCredentialExposesIdentityAndContinuesChain() throws ServletException, IOException {
        String plain = McpApiKeyGenerator.newPlainKey();
        when(mcpApiKeyService.authenticate(plain)).thenReturn(
                McpAuthResult.granted(storedKeyFor(plain)));

        MockHttpServletRequest request = mcpRequest("Bearer " + plain);
        filter.doFilter(request, response, chain);

        assertNotNull(chain.getRequest());
        assertEquals(200, response.getStatus());
        assertEquals(42L, request.getAttribute(McpApiKeyAuthFilter.MCP_USER_ID_ATTRIBUTE));

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(authentication, "请求线程上的 SecurityContext 也要有身份");
        assertEquals(42L, authentication.getPrincipal());
    }

    /**
     * 根据原始 Key 创建对应哈希的存储夹具。
     * 由 {@code acceptedCredentialExposesIdentityAndContinuesChain} 调用。
     * 接收{@code plain}。
     * 返回 {@code key}。
     */
    private McpApiKey storedKeyFor(String plain) {
        McpApiKey key = new McpApiKey();
        key.setId(9L);
        key.setUserId(42L);
        key.setKeyHash(McpApiKeyGenerator.sha256Hex(plain));
        return key;
    }

    /**
     * 核对测试 MCP 请求返回的无权访问响应。
     * 由 {@code missingHeaderIs401AndShortCircuits}、{@code nonBearerSchemeIs401} 调用。
     * @param message 本用例传入的{@code message}
     */
    private void assertUnauthorized(String message) throws IOException {
        assertEquals(401, response.getStatus());
        assertNull(chain.getRequest(), "认证失败必须短路");
        assertTrue(response.getContentType() != null
                && response.getContentType().startsWith("application/json"));
        String body = response.getContentAsString();
        assertTrue(body.contains("\"code\":401"), body);
        assertTrue(body.contains(message), body);
    }
}
