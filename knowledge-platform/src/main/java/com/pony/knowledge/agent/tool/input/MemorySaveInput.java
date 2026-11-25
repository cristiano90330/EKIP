/* 定义长期记忆保存工具接收的内容和类型。 */

package com.pony.knowledge.agent.tool.input;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 长期记忆写入的入参。
 *
 * <p>没有 userId 字段，也不可能有：记忆归属只来自 {@code AgentExecutionContext}，
 * LLM 无法通过参数把内容写进别人的记忆里。
 *
 * <p>{@code kind} 只用 {@code @Size} 而不是 {@code @Pattern}：schema 生成器没有映射
 * Pattern（见 ToolSchemaGenerator 的映射表），写了也不会进 JSON-schema，模型看不到约束。
 * 合法值写在工具描述里，未识别值由服务端归一为 FACT。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class MemorySaveInput implements ToolInput {

    @NotBlank(message = "content 不能为空")
    @Size(max = 500, message = "content 长度不能超过 500")
    private String content;

    @Size(max = 20, message = "kind 长度不能超过 20")
    private String kind;
}
