/* 承载文档列表的知识库范围和分页参数。 */

package com.pony.knowledge.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 文档分页查询参数，限定知识库范围并校验页码与单页数量。 */
@Data
public class DocumentPageQueryDTO {

    @NotNull(message = "知识库ID不能为空")
    private Long knowledgeBaseId;

    @Min(value = 1, message = "页码必须大于0")
    private long page = 1;

    @Min(value = 1, message = "每页数量必须大于0")
    @Max(value = 100, message = "每页最多100条")
    private long size = 10;

}
