/* 定义模型可以提交给长期记忆检索工具的查询和条数。 */

package com.pony.knowledge.agent.tool.input;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/** 长期记忆检索的入参（只检索当前用户自己的记忆，作用域由执行上下文决定）。 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class MemorySearchInput implements ToolInput {

    @NotBlank(message = "query 不能为空")
    @Size(max = 200, message = "query 长度不能超过 200")
    private String query;

    @Min(value = 1, message = "limit 必须大于 0")
    @Max(value = 10, message = "limit 不能超过 10")
    private Integer limit;
}
