/* 定义文档目录工具的检索条件和分页控件。 */

package com.pony.knowledge.agent.tool.input;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 目录只接受分页参数；知识库范围由本回合身份上下文提供。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ListDocumentsInput implements ToolInput {
    @Min(0)
    @Max(1000000)
    private Integer offset;

    @Min(1)
    @Max(10)
    private Integer limit;
}
