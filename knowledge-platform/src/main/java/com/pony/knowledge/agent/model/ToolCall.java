/* 承载模型选定的工具名称、调用标识与待解析参数。 */

package com.pony.knowledge.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * LLM 发出的工具调用：callId 与 ToolResult 一一对应，arguments 是 JSON 字符串，
 * 由 ToolExecutor 反序列化为对应 Tool 的 ToolInput 并做校验。
 * 消息持久化时内嵌于 AgentMessage，需默认构造器供 JSON 反序列化。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolCall {

    private String callId;

    private String toolName;

    /** LLM 返回的 JSON 字符串参数（只能包含 ToolInput 声明的字段） */
    private String arguments;
}
