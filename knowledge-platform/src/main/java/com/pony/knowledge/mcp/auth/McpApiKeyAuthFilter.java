/* 在 MCP 请求进入工具服务前验证持有者凭证并写入账号身份。 */

package com.pony.knowledge.mcp.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pony.knowledge.common.Result;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * MCP 端点的长期凭证认证：把「非 JWT 的 Bearer ekip_mcp_xxx」变成与 JWT 链路同形的
 * SecurityContext（principal = userId）+ 请求属性，供桥接层送入 MCP 会话上下文。
 *
 * 刻意不做成 @Component：它由 McpServerConfig 按 mcp.enabled 条件装配，
 * 并用 FilterRegistrationBean 关闭容器自动注册（本过滤器写 401、回写 last_used_time，不幂等）。
 */
@RequiredArgsConstructor
public class McpApiKeyAuthFilter extends OncePerRequestFilter {

    /** 认证成功后写入的用户 id 请求属性名（桥接层据此提取身份）。 */
    public static final String MCP_USER_ID_ATTRIBUTE = "ekip.mcp.userId";

    private static final String BEARER_PREFIX = "Bearer ";

    private final McpApiKeyService mcpApiKeyService;

    private final ObjectMapper objectMapper;

    private final String mcpEndpoint;

    /**
     * 只对配置的 MCP 传输入口启用 MCP 凭证认证。
     * 上游为 Spring HTTP 过滤链。
     *
     * @param request 进入 HTTP 过滤链的当前请求
     * @return 符合本方法描述的状态或完成条件时为 true
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 只拦 MCP 端点本身；/mcp/keys（签发接口）继续走 JWT
        return !mcpEndpoint.equals(request.getRequestURI());
    }

    /**
     * 读取并验证 MCP Bearer 凭证，将认证账号附给后续传输处理。
     * 上游为 Spring HTTP 过滤链。
     * 下游为 McpApiKeyService.authenticate。
     *
     * @param request 进入 HTTP 过滤链的当前请求
     * @param response 即将交给客户端的 HTTP 响应
     * @param filterChain 剩余的 HTTP 过滤链
     * @throws IOException 底层流或响应无法读取或写入时
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header == null) {
            reject(response, "缺少访问凭证");
            return;
        }
        if (!header.startsWith(BEARER_PREFIX)) {
            reject(response, "凭证格式错误");
            return;
        }
        String plainKey = header.substring(BEARER_PREFIX.length());

        if (!plainKey.startsWith(McpApiKeyGenerator.PLAIN_PREFIX)) {
            // 挡掉拿 JWT 来试 MCP 端点的情况，认证语义不混用
            reject(response, "凭证格式错误");
            return;
        }

        McpAuthResult result = mcpApiKeyService.authenticate(plainKey);

        if (!result.isAuthenticated()) {
            reject(response, result.denyReason());
            return;
        }

        request.setAttribute(MCP_USER_ID_ATTRIBUTE, result.userId());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(result.userId(), null, null));

        filterChain.doFilter(request, response);
    }

    /**
     * 以适合 MCP 请求的 HTTP 状态和 JSON 拒绝无效凭证。
     * 上游为 McpApiKeyAuthFilter.doFilterInternal。
     * 下游为 SecurityContextHolder.clearContext。
     *
     * @param response 即将交给客户端的 HTTP 响应
     * @param message 用于构建结果或异常的说明文字
     * @throws IOException 底层流或响应无法读取或写入时
     */
    private void reject(HttpServletResponse response, String message) throws IOException {
        SecurityContextHolder.clearContext();
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                objectMapper.writeValueAsString(new Result<>(401, message, null)));
    }
}
