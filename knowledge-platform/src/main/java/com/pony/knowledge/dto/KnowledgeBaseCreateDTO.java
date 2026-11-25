/* 校验知识库创建或更新请求中的名称和描述。 */

package com.pony.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建知识库的 HTTP 输入。名称和描述的校验由 Controller 的 Valid 触发；
 * 创建者 ID 由 Service 从认证上下文取得，不属于本请求体。
 */
@Data
public class KnowledgeBaseCreateDTO {

    @NotBlank(message = "知识库名称不能为空")
    @Size(max = 100, message = "知识库名称不能超过100个字符")
    private String name;

    @Size(max = 500, message = "知识库描述不能超过500个字符")
    private String description;
}
