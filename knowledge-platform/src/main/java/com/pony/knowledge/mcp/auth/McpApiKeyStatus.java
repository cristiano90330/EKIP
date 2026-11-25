/* 定义 MCP 密钥生效与吊销的数据库状态码。 */

package com.pony.knowledge.mcp.auth;

/**
 * MCP 密钥状态，与 mcp_api_key.status 列的取值一一对应（沿用 DocumentStatus 的 code 风格）。
 */
public enum McpApiKeyStatus {

    ACTIVE(0),

    REVOKED(1);

    private final int code;

    /**
     * 为枚举值绑定供持久化或接口展示使用的编码。
     *
     * @param code 持久化或对外公开的状态码
     */
    McpApiKeyStatus(int code) {
        this.code = code;
    }

    /** 返回持久化或对外的状态码。 */
    public int getCode() {
        return code;
    }
}
