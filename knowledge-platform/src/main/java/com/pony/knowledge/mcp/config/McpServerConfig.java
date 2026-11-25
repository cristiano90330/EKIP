/* 按配置装配 MCP 认证过滤器、传输路由和同步工具服务。 */

package com.pony.knowledge.mcp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pony.knowledge.mcp.auth.McpApiKeyAuthFilter;
import com.pony.knowledge.mcp.auth.McpApiKeyService;
import com.pony.knowledge.mcp.bridge.McpIdentityContextExtractor;
import com.pony.knowledge.mcp.bridge.McpToolBridge;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import io.modelcontextprotocol.server.transport.WebMvcStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * 仅在 mcp.enabled=true 时装配 MCP 的认证过滤器、Streamable HTTP 传输、工具服务和路由。
 * 过滤器交由 SecurityConfig 接入安全链，传输层提取身份并校验来源，McpToolBridge 提供工具规格；
 * Spring 销毁服务 Bean 时调用指定的 closeGracefully 方法，关闭行为由 MCP SDK 执行。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "mcp", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class McpServerConfig {

    private final McpProperties mcpProperties;

    private final McpToolBridge mcpToolBridge;

    private final McpIdentityContextExtractor mcpIdentityContextExtractor;

    private final McpApiKeyService mcpApiKeyService;

    private final ObjectMapper objectMapper;

    /**
     * 构造 MCP 传输入口使用的凭证认证过滤器。
     * 上游为 Spring 容器组件装配。
     * 下游为 McpProperties.getEndpoint。
     *
     * @return 本次得到的 McpApiKeyAuthFilter 实例
     */
    @Bean
    public McpApiKeyAuthFilter mcpApiKeyAuthFilter() {
        return new McpApiKeyAuthFilter(mcpApiKeyService, objectMapper, mcpProperties.getEndpoint());
    }

    /**
     * 将 MCP 密钥过滤注册到服务器传输入口。
     * 上游为 Spring 容器组件装配。
     *
     * @param filter 本次操作使用的 McpApiKeyAuthFilter 组件或记录
     * @return 本次计算或查询得到的 FilterRegistrationBean&lt;McpApiKeyAuthFilter&gt; 结果
     */
    @Bean
    public FilterRegistrationBean<McpApiKeyAuthFilter> mcpApiKeyAuthFilterRegistration(
            McpApiKeyAuthFilter filter) {
        FilterRegistrationBean<McpApiKeyAuthFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /**
     * 装配 MCP 请求通道、超时和认证账号上下文提取。
     * 上游为 Spring 容器组件装配。
     * 下游为 McpProperties.getEndpoint、McpProperties.getSecurity。
     *
     * @return 本次计算或查询得到的 WebMvcStreamableServerTransportProvider 结果
     */
    @Bean
    public WebMvcStreamableServerTransportProvider mcpTransportProvider() {
        return WebMvcStreamableServerTransportProvider.builder()
                .mcpEndpoint(mcpProperties.getEndpoint())
                .contextExtractor(mcpIdentityContextExtractor)
                .securityValidator(DefaultServerTransportSecurityValidator.builder()
                        .allowedHosts(mcpProperties.getSecurity().getAllowedHosts())
                        .allowedOrigins(mcpProperties.getSecurity().getAllowedOrigins())
                        .build())
                .keepAliveInterval(mcpProperties.getKeepAliveInterval())
                .build();
    }

    /**
     * 创建 MCP 同步工具服务并注册允许发布的工具规格。
     * 上游为 Spring 容器组件装配。
     * 下游为 McpToolBridge.buildSpecifications。
     *
     * @param transport 本次操作所用的 WebMvcStreamableServerTransportProvider 数据
     * @return 本次计算或查询得到的 McpSyncServer 结果
     */
    @Bean(destroyMethod = "closeGracefully")
    public McpSyncServer mcpSyncServer(WebMvcStreamableServerTransportProvider transport) {
        return McpServer.sync(transport)
                .serverInfo(mcpProperties.getServerName(), mcpProperties.getServerVersion())
                .instructions(mcpProperties.getInstructions())
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .tools(mcpToolBridge.buildSpecifications())
                .requestTimeout(mcpProperties.getRequestTimeout())
                .build();
    }

    /**
     * 返回 MCP 传输层声明的 HTTP 路由。
     * 上游为 Spring 容器组件装配。
     *
     * @param transport 本次操作所用的 WebMvcStreamableServerTransportProvider 数据
     * @return 本次计算或查询得到的 RouterFunction&lt;ServerResponse&gt; 结果
     */
    @Bean
    public RouterFunction<ServerResponse> mcpRouterFunction(
            WebMvcStreamableServerTransportProvider transport) {
        return transport.getRouterFunction();
    }
}
