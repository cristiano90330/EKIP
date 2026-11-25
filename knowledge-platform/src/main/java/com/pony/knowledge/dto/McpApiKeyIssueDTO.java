/* 接收新签发 MCP 密钥的显示名称。 */

package com.pony.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * MCP 密钥签发入参。身份来自 JWT（不在此声明 userId，服务端从 CurrentUserUtil 取）。
 */
@Data
public class McpApiKeyIssueDTO {

    @NotBlank(message = "name 不能为空")
    @Size(max = 100, message = "name 长度不能超过 100")
    private String name;
}
