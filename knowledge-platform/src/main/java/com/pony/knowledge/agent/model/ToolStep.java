/* 保存 Agent 历史和页面可展示的工具调用摘要。 */

package com.pony.knowledge.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 一轮工具调用的历史痕迹：工具名 + 成败 + 一行结果摘要。
 *
 * <p>刻意不含工具输出的原文：历史里放全文的代价是每次装配都要为它付 token，
 * 而跨轮对话真正需要的是「查过什么、大概查到几条」，不是逐字引用（逐字引用本来就该重新检索，
 * 知识库内容可能已经变了）。容量与截断规则见 {@link ToolSteps}。
 *
 * <p>本类只进会话历史（{@code AgentMessage.steps}），不进 LLM 的工具契约，
 * 因此不参与 schema 生成。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolStep {

    private String tool;

    private boolean ok;

    private String outcome;

    private String reasonCode;

    /** 一行结果摘要（失败时为错误原因），已按字符上限截断。 */
    private String summary;
}
