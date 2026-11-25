/* 绑定 MCP 暴露路径、服务描述、请求期限与工具配置。 */

package com.pony.knowledge.mcp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * MCP Server 配置（application.yml 的 mcp.* 段）。
 * 默认值与 yml 一致，便于测试里单独 new 出来用。
 */
@Data
@Component
@ConfigurationProperties(prefix = "mcp")
public class McpProperties {

    /** 总开关：false 时整段服务端不装配，/mcp 无处理器。 */
    private boolean enabled = true;

    /** Streamable HTTP 端点。SecurityConfig 用同一精确路径放行。 */
    private String endpoint = "/mcp";

    private String serverName = "ekip-knowledge";

    private String serverVersion = "1.0.0";

    /** 客户端可见的使用说明（告诉 LLM 两个工具怎么选）。 */
    private String instructions;

    /** SSE keep-alive 间隔：长工具执行期间维持客户端读超时。 */
    private Duration keepAliveInterval = Duration.ofSeconds(15);

    /** MCP 请求超时，必须 > tool.timeout-ms（60s）。 */
    private Duration requestTimeout = Duration.ofSeconds(90);

    /** default-deny：不在此列的 AgentTool 一律不对外暴露。 */
    private List<String> exposedTools = new ArrayList<>();

    private Security security = new Security();

    @Data
    public static class Security {

        /** Host/Origin 白名单，防 DNS rebinding（MCP 规范要求）。 */
        private List<String> allowedHosts = new ArrayList<>(List.of("127.0.0.1:8080", "localhost:8080"));

        private List<String> allowedOrigins = new ArrayList<>();
    }
}
