/* 校验访问令牌和最新账号会话版本，按需建立请求身份。 */

package com.pony.knowledge.security;

import com.pony.knowledge.entity.User;
import com.pony.knowledge.mapper.UserMapper;
import com.pony.knowledge.mcp.auth.McpApiKeyAuthFilter;
import com.pony.knowledge.utils.JwtUtil;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 从 Bearer 访问令牌建立当前请求的认证上下文。JwtUtil 负责签名、有效期和用途校验，
 * 同时检查账号状态和会话版本，成功后将 Long 用户 ID 作为 principal，供 CurrentUserUtil 读取。
 * 凭证缺失或无效时继续过滤链，由 SecurityConfig 的路径规则决定是否返回 401。
 * 同一请求若已由其他凭证过滤器（MCP API Key）建立身份，则不再解析 Bearer，
 * 避免把非 JWT 凭证解析失败的结果回写成「未认证」。
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final JwtUtil jwtUtil;
    private final UserMapper users;

    /**
     * 保存校验访问令牌和最新账号会话版本所需的依赖和初始配置。
     * 上游为 Spring 组件装配。
     *
     * @param jwtUtil 本次操作使用的 JwtUtil 组件或记录
     * @param users UserMapper，负责提供登录账号、启停版本、部门和管理列表的数据库操作
     */
    public JwtAuthenticationFilter(JwtUtil jwtUtil, UserMapper users) {
        this.jwtUtil = jwtUtil;
        this.users = users;
    }

    /**
     * 为异步分派选择是否继续运行 JWT 身份过滤。
     * 上游为 Spring HTTP 过滤链。
     *
     * @return 固定返回 false
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    /**
     * 解析访问令牌并结合账号状态及会话版本建立 Spring Security 身份。
     * 上游为 Spring HTTP 过滤链。
     * 下游为 JwtUtil.parseAccessToken、UserMapper.selectAccountState。
     *
     * @param request 进入 HTTP 过滤链的当前请求
     * @param response 即将交给客户端的 HTTP 响应
     * @param filterChain 剩余的 HTTP 过滤链
     * @throws IOException 底层流或响应无法读取或写入时
     */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain)
            throws ServletException, IOException {

        // MCP 端点上的 Bearer 是长期 API Key 而非 JWT，身份已由 McpApiKeyAuthFilter 建立。
        // 若这里仍按 JWT 解析，必然失败并清空认证上下文，把已认证成功的协议调用降级为未认证。
        if (request.getAttribute(McpApiKeyAuthFilter.MCP_USER_ID_ATTRIBUTE) != null) {
            filterChain.doFilter(request, response);
            return;
        }

        String header = request.getHeader("Authorization");

        if (header == null || !header.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = header.substring(7);

        try {
            JwtUtil.AccessTokenInfo info = jwtUtil.parseAccessToken(token);
            User user = users.selectAccountState(info.userId());
            if (user != null && user.acceptsSession(info.sessionVersion())) {
                // principal 保持为 Long 用户 ID，与 CurrentUserUtil 和 MCP 认证路径约定一致。
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(info.userId(), null, null));
            } else {
                SecurityContextHolder.clearContext();
            }
        } catch (ExpiredJwtException e) {
            log.debug("JWT 已过期: uri={}", request.getRequestURI());
            SecurityContextHolder.clearContext();
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("JWT 校验失败({}): uri={}, remoteAddr={}",
                    e.getClass().getSimpleName(), request.getRequestURI(), request.getRemoteAddr());
            SecurityContextHolder.clearContext();
        } catch (Exception e) {
            log.error("访问令牌或账号状态校验失败: uri={}", request.getRequestURI(), e);
            SecurityContextHolder.clearContext();
        }

        filterChain.doFilter(request, response);
    }
}
