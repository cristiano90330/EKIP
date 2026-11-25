/* 映射 MCP 密钥摘要、归属、状态及使用时间。 */

package com.pony.knowledge.mcp.auth;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * MCP 客户端访问凭证记录，只持久化密钥哈希与用于管理的元数据，不保存明文。
 */
@Data
@TableName("mcp_api_key")
public class McpApiKey {

    private Long id;

    private Long userId;

    /** SHA-256(明文密钥) 的小写十六进制，64 字符。 */
    private String keyHash;

    /** 明文前 12 字符，仅供列表辨认；不可还原明文。 */
    private String keyPrefix;

    private String name;

    /** 0=ACTIVE 1=REVOKED，取值见 {@link McpApiKeyStatus}。 */
    private Integer status;

    /** null 表示永不过期。 */
    private LocalDateTime expiresAt;

    private LocalDateTime lastUsedTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
