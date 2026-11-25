/* 定义模型可以提交给知识库问答工具的提问及候选数量。 */

package com.pony.knowledge.agent.tool.input;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * LLM 能控制的问答参数（question / topK）。
 * 同 KnowledgeSearchInput：不声明任何身份/权限字段。
 *
 * 显式 @NoArgsConstructor + @AllArgsConstructor：@Data+@Builder 在本项目 Lombok 下
 * 不产生 Jackson 可用的默认构造器。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class KnowledgeAskInput
        implements ToolInput {

    @NotBlank(message = "question 不能为空")
    @Size(max = 500, message = "question 长度不能超过 500")
    private String question;

    @Max(value = 10, message = "topK 不能超过 10")
    private Integer topK;
}
