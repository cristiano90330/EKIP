/* 从进入 MCP 传输层的请求提取已经认证的账号。 */

package com.pony.knowledge.mcp.bridge;

import com.pony.knowledge.mcp.auth.McpApiKeyAuthFilter;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.function.ServerRequest;

import java.util.Map;

/**
 * 把认证过滤器留在请求上的身份，送进 MCP 会话上下文。
 *
 * 为什么不能直接在工具 handler 里读 SecurityContextHolder：handler 未必跑在 servlet 请求线程上
 * （mcp-core 走 reactor），ThreadLocal 到不了那里。McpTransportContext 才是 SDK 提供的
 * 线程安全、请求作用域的身份通道。
 */
@Component
public class McpIdentityContextExtractor
        implements McpTransportContextExtractor<ServerRequest> {

    /**
     * 把传输请求属性中已认证的账号复制到 MCP 上下文。
     *
     * @param request 当前调用的请求参数
     * @return 本次计算或查询得到的 McpTransportContext 结果
     */
    @Override
    public McpTransportContext extract(ServerRequest request) {
        Object userId = request.attribute(McpApiKeyAuthFilter.MCP_USER_ID_ATTRIBUTE).orElse(null);
        if (userId == null) {
            return McpTransportContext.EMPTY;
        }
        return McpTransportContext.create(Map.of(McpTransportContextKeys.USER_ID, userId));
    }
}
