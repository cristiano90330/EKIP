/* 定义文档阅读工具支持的文档、检索目的、范围和分页参数。 */

package com.pony.knowledge.agent.tool.input;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * LLM 能控制文档 ID、阅读目标及有界游标；query 模式保留相关片段检索。
 * 不声明 userId 或知识库范围；授权只来自 AgentExecutionContext。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class ReadDocumentInput implements ToolInput {

    @NotNull(message = "documentId 不能为空")
    @Min(value = 1, message = "documentId 必须大于 0")
    private Long documentId;

    @NotBlank(message = "query 不能为空")
    @Size(max = 200, message = "query 长度不能超过 200")
    private String query;

    @Min(value = 1, message = "limit 必须大于 0")
    @Max(value = ReadDocumentLimits.MAX_LIMIT,
            message = "limit 不能超过 " + ReadDocumentLimits.MAX_LIMIT)
    private Integer limit;

    /** query（默认）返回相关片段；page 按稳定块序读取一个完整的有界窗口。 */
    @Size(max = 10)
    private String mode;

    @Min(0)
    @Max(1000000)
    private Integer offset;

    @Min(0)
    @Max(100000000)
    private Integer contentOffset;

    /** 可选的有限上下文终点，继续时原样复制。 */
    @Min(0)
    @Max(1000000)
    private Integer endOffset;

    @Min(1)
    private Long aroundChunkId;

    @Min(0)
    @Max(2)
    private Integer contextRadius;
}
