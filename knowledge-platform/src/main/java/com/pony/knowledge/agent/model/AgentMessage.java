/* 描述模型消息角色、正文和关联工具调用信息。 */

package com.pony.knowledge.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Agent 对话消息：system / user / assistant / tool 四种角色。
 * role=tool 时 toolCallId 对应 ToolCall.callId，toolName 标明来源工具。
 * role=assistant 且触发工具调用时，toolCalls 携带本次决策的一批工具调用
 * （OpenAI 格式要求一条 assistant 消息含全部 tool_calls，而非每条一个消息）。
 *
 * timestamp：消息时间戳（内存层持久化会话历史用，可按时间排序；LLM 往返不依赖它，可为 null）。
 *
 * 显式 @NoArgsConstructor+@AllArgsConstructor：@Data+@Builder 在本项目 Lombok 下不产生 Jackson
 * 可用的默认构造器，会导致 Redis 反序列化失败（同 KnowledgeSearchInput 的坑）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentMessage {

    private String role;

    private String content;

    /** role=tool 时对应的 ToolCall.callId */
    private String toolCallId;

    /** role=tool / assistant(触发调用) 时的工具名 */
    private String toolName;

    /** role=assistant 触发工具调用时的一批 ToolCall（可为空） */
    private List<ToolCall> toolCalls;

    /**
     * 本轮工具步骤（仅写进会话历史的 assistant 消息会带；回合内 transcript 里为空）。
     *
     * <p>与 {@link #toolCalls} 的区别：toolCalls 是**发给模型的协议结构**（回合内、必须与 tool 应答
     * 成对），steps 是**给下一轮看的痕迹**（跨轮、只有一行摘要、不参与协议配对）。
     * 旧数据没有该字段 → null，读取方按「无步骤」处理。
     */
    private List<ToolStep> steps;

    /** 消息时间戳（内存层记录会话历史用） */
    private Instant timestamp;
}
