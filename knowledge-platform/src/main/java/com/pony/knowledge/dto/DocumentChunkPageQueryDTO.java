/* 约束文档切片浏览的分页请求。 */

package com.pony.knowledge.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/** 有界切片浏览参数。 */
@Data
public class DocumentChunkPageQueryDTO {
    @Min(value = 1, message = "页码必须大于0")
    private int page = 1;

    @Min(value = 1, message = "每页数量必须大于0")
    @Max(value = 100, message = "每页最多100条")
    private int size = 20;
}
