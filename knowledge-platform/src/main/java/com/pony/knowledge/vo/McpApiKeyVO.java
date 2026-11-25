/* 向密钥所有者公开可查看的状态、前缀和使用时间。 */

package com.pony.knowledge.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 列表用密钥视图：只含指纹 keyPrefix，绝不含明文与哈希。
 */
@Data
@Builder
public class McpApiKeyVO {

    private Long id;

    private String name;

    private String keyPrefix;

    /** ACTIVE / REVOKED。 */
    private String status;

    private LocalDateTime expiresAt;

    private LocalDateTime lastUsedTime;

    private LocalDateTime createTime;
}
