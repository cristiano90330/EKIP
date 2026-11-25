/* 装配认证服务和 HTTP 安全过滤链，划分普通账号接口与 MCP 入口。 */

package com.pony.knowledge.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pony.knowledge.common.Result;
import com.pony.knowledge.mcp.auth.McpApiKeyAuthFilter;
import com.pony.knowledge.request.RequestIdFilter;
import com.pony.knowledge.security.JwtAuthenticationFilter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

import java.io.IOException;

/**
 * 装配登录认证组件和 HTTP 安全过滤链。登录通过 AuthenticationManager 校验账号密码，
 * 后续请求由 JwtAuthenticationFilter 建立用户身份；这里只声明入口认证规则，
 * 知识库成员、文档归属等资源权限由相应业务 Service 检查。
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    /** 按 MCP 开关获取可选的 API Key 过滤器。 */
    private final ObjectProvider<McpApiKeyAuthFilter> mcpApiKeyAuthFilterProvider;

    /**
     * MCP 协议端点路径，与 McpProperties.endpoint 读取同一配置项 {@code mcp.endpoint}。
     * 这里直接取配置值而不是注入属性对象：安全链只需一个路径字符串，
     * 也避免切片测试为 MCP 配置对象额外准备 Bean。
     */
    @Value("${mcp.endpoint:/mcp}")
    private String mcpEndpoint;

    private final UserDetailsService userDetailsService;

    private final PasswordEncoder passwordEncoder;

    /**
     * 注册覆盖 HTTP 初次请求及异步分派的请求标识过滤器。
     * 上游为 Spring 容器组件装配。
     *
     * @return 本次计算或查询得到的 FilterRegistrationBean&lt;RequestIdFilter&gt; 结果
     */
    @Bean
    public FilterRegistrationBean<RequestIdFilter> requestIdFilterRegistration() {
        FilterRegistrationBean<RequestIdFilter> registration = new FilterRegistrationBean<>(new RequestIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setAsyncSupported(true);
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
        return registration;
    }

    /**
     * 组合数据库账号查询和密码编码器，创建登录认证提供方。
     * 上游为 Spring 容器组件装配。
     *
     * @return 本次计算或查询得到的 DaoAuthenticationProvider 结果
     */
    @Bean
    public DaoAuthenticationProvider daoAuthenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return provider;
    }

    /**
     * 暴露供账号登录服务使用的认证管理器。
     * 上游为 Spring 容器组件装配。
     *
     * @param daoAuthenticationProvider 本次操作所用的 DaoAuthenticationProvider 数据
     * @return 本次计算或查询得到的 AuthenticationManager 结果
     */
    @Bean
    public AuthenticationManager authenticationManager(DaoAuthenticationProvider daoAuthenticationProvider) {
        return new ProviderManager(daoAuthenticationProvider);
    }

    /**
     * 禁止 JWT 过滤器由 Servlet 默认流程重复注册，交给安全过滤链装配。
     * 上游为 Spring 容器组件装配。
     *
     * @param filter 本次操作使用的 JwtAuthenticationFilter 组件或记录
     * @return 本次计算或查询得到的 FilterRegistrationBean&lt;JwtAuthenticationFilter&gt; 结果
     */
    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtAuthenticationFilterRegistration(
            JwtAuthenticationFilter filter) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /**
     * 为后端 API 配置认证范围、错误响应和 HTTP 安全过滤顺序。
     * 上游为 Spring 容器组件装配。
     * 下游为 SecurityConfig.writeUnauthorized。
     *
     * @param http 本次操作所用的 HttpSecurity 数据
     * @return 本次计算或查询得到的 SecurityFilterChain 结果
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // 启用状态决定「是否需要放行 MCP 端点」，路径本身只以配置项 mcp.endpoint 为准：
        // 传输层、API Key 过滤器与安全链必须引用同一个值，否则自定义端点会与放行规则脱节，
        // 旧的默认路径也会继续留在匿名白名单里。
        McpApiKeyAuthFilter mcpApiKeyAuthFilter = mcpApiKeyAuthFilterProvider.getIfAvailable();

        http
                // 关闭 CSRF 校验，并为 API 响应设置安全头。
                .csrf(csrf -> csrf.disable())
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; frame-ancestors 'none'; object-src 'none'; base-uri 'self'"))
                        .referrerPolicy(referrer -> referrer
                                .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
                // 认证状态由当前请求携带的凭证重建，不存入 HttpSession。
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    // 登录、注册公开；刷新和登出使用各自的刷新凭证。
                    auth.requestMatchers("/user/register", "/user/login").permitAll()
                            .requestMatchers("/user/refresh", "/user/logout").permitAll()
                            // 允许错误转发，保留原始错误的响应语义。
                            .requestMatchers("/error").permitAll()
                            // 公开健康与基础观测端点，其余 Actuator 入口要求认证。
                            .requestMatchers("/actuator/health", "/actuator/health/**",
                                             "/actuator/info", "/actuator/prometheus").permitAll()
                            .requestMatchers("/actuator/**").authenticated();
                    // 只放行精确的协议端点本身；/mcp/keys 等子路径仍要求 JWT，不会因前缀放行被一并匿名开放。
                    if (mcpApiKeyAuthFilter != null && mcpEndpoint != null && !mcpEndpoint.isBlank()) {
                        auth.requestMatchers(mcpEndpoint).permitAll();
                    }
                    auth.anyRequest().authenticated();
                })
                .exceptionHandling(ex -> ex.authenticationEntryPoint(
                        (request, response, authException) -> writeUnauthorized(response)))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable());
        http.addFilterBefore(
                jwtAuthenticationFilter,
                UsernamePasswordAuthenticationFilter.class
        );
        // 启用时将 MCP 凭证过滤器置于 JWT 过滤器之前。
        if (mcpApiKeyAuthFilter != null) {
            http.addFilterBefore(mcpApiKeyAuthFilter, JwtAuthenticationFilter.class);
        }
        return http.build();
    }

    /**
     * 在安全过滤链拒绝匿名请求时写入结构化 HTTP 响应。
     * 上游为 SecurityConfig.securityFilterChain。
     *
     * @param response 即将交给客户端的 HTTP 响应
     * @throws IOException 底层流或响应无法读取或写入时
     */
    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                OBJECT_MAPPER.writeValueAsString(new Result<>(401, "未认证或登录已过期", null))
        );
    }

}
