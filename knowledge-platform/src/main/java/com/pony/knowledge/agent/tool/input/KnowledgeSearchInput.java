/* 定义模型可以提交给知识检索工具的查询及候选数量。 */

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

/**
 * LLM 能控制的检索参数（query / topK）。
 * 这里不声明任何身份/权限字段——userId / accessibleKnowledgeBaseIds
 * 只来自 AgentExecutionContext，LLM 无法通过参数控制权限。
 *
 * 显式声明 @NoArgsConstructor + @AllArgsConstructor：@Data+@Builder 在本项目 Lombok
 * 下不产生 Jackson 可用的默认构造器，会导致 JSON 反序列化失败。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class KnowledgeSearchInput
        implements ToolInput {

    @NotBlank(message = "query 不能为空")
    @Size(max = 200, message = "query 长度不能超过 200")
    private String query;

    // 上界与下界都必须写：@Max 对负数恒成立，只有 @Max(20) 时 topK=-1 能穿过整条校验链，
    // 一路传到 Python 检索侧的 SQL LIMIT。
    // @Min 同时会被 ToolSchemaGenerator 映射成 schema 的 minimum，让模型也知道下界——schema 与校验同源。
    @Min(value = 1, message = "topK 必须大于 0")
    @Max(value = 20, message = "topK 不能超过 20")
    private Integer topK;
}
