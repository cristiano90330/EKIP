/* 区分 MCP 密钥认证成功的账号身份和拒绝原因。 */

package com.pony.knowledge.mcp.auth;

/**
 * 密钥认证结论。denied 时必须带可区分的原因（供 401 响应体告诉客户端下一步动作）。
 *
 * @param keyId      命中的密钥 id（认证通过时有值）
 * @param userId     密钥归属用户 id（认证通过时有值）
 * @param denyReason 拒绝原因；null 表示认证通过
 */
public record McpAuthResult(Long keyId, Long userId, String denyReason) {

    /**
     * 携带密钥 ID 与账号 ID 构建已获准的认证结果。
     * 上游为 McpApiKeyServiceImpl.authenticate。
     * 下游为 McpApiKey.getId、McpApiKey.getUserId。
     *
     * @param key 本次操作使用的 McpApiKey 组件或记录
     * @return 本次得到的 McpAuthResult 实例
     */
    public static McpAuthResult granted(McpApiKey key) {
        return new McpAuthResult(key.getId(), key.getUserId(), null);
    }

    /**
     * 携带具体的拒绝原因构建未获准的认证结果。
     * 上游为 McpApiKeyServiceImpl.authenticate。
     *
     * @param reason 解释本次状态或拒绝的原因
     * @return 本次得到的 McpAuthResult 实例
     */
    public static McpAuthResult denied(String reason) {
        return new McpAuthResult(null, null, reason);
    }

    /**
     * 判断认证结果是否对应可使用的账号身份。
     *
     * @return 条件是否成立（判断认证结果是否对应可使用的账号身份）
     */
    public boolean isAuthenticated() {
        return denyReason == null;
    }
}
