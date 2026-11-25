/* 承载一次 Agent 消息构建所需的身份、提示词、历史和工具结果。 */

package com.pony.knowledge.agent.context;

import com.pony.knowledge.agent.model.AgentMessage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * ContextBuilder 输入：描述一次要装配的上下文有哪些素材。
 *   userId        —— 长期记忆的作用域（只来自认证上下文；null = 不注入长期记忆）
 *   sessionId     —— 用于经 MemoryManager 加载 Summary 与最近窗口
 *   currentQuery  —— 当前问题（Required，必进 Context；也是长期记忆的召回依据）
 *   systemPrompt  —— 系统身份（Required；身份来自宿主，非 Prompt/LLM 可控）
 *   toolMessages  —— 本回合已执行的工具调用与结果（由 Runtime 传入，Builder 不执行 Tool）
 *   budget        —— 上下文 token 预算（超预算裁剪，Required 不裁）
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class ContextBuildRequest {

    /**
     * 长期记忆的作用域。只可能来自认证上下文，装配器不用它做任何鉴权判断——
     * 记忆存储本身按 userId 分键，越权在数据层面就不可能。
     */
    private Long userId;

    /** 当前 Agent 的生效记忆读取策略，所有重建路径必须继续传递。 */
    @Builder.Default
    private boolean memoryReadEnabled = true;

    private String systemPrompt;

    private String sessionId;

    private String currentQuery;

    private List<AgentMessage> toolMessages;

    private ContextBudget budget;

    /** 最近窗口条数（Sliding Window 大小；默认 8） */
    @Builder.Default
    private int recentWindow = 8;
}
