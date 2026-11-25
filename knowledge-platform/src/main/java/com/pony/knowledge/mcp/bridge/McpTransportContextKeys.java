/* 维护 MCP 传输上下文所用的鉴权身份键。 */

package com.pony.knowledge.mcp.bridge;

/**
 * MCP 会话上下文键名。认证过滤器写入、桥接层读出，两端共用常量避免字符串漂移。
 */
public final class McpTransportContextKeys {

    /** 值为用户 id（Long）。 */
    public static final String USER_ID = "ekip.mcp.userId";

    /** 初始化 McpTransportContextKeys，供本文件定义的构建入口使用。 */
    private McpTransportContextKeys() {
    }
}
