/* 把已有摘要和待折叠消息组成摘要模型输入。 */

package com.pony.knowledge.agent.memory.summary;

import com.pony.knowledge.agent.model.AgentMessage;

/**
 * 摘要 prompt 的装配。
 *
 * <p>摘要有两个实现：直连档 {@link LlmSummaryService} 与治理层档
 * {@link com.pony.knowledge.agent.llm.GatewaySummaryService}。
 * 「已有摘要放前面 + 逐条 role: content」这段拼装只有一份，
 * 避免两档喂给模型的输入分叉，使摘要质量差异无法归因。
 */
final class SummaryPromptBuilder {

    /** 保留私有构造入口，以便通过静态方法使用此类型。 */
    private SummaryPromptBuilder() {
    }

    /**
     * 将已保存的摘要及本次新增消息组织成摘要提示词的会话正文。
     * 上游由 {@code GatewaySummaryService.summarize}、{@code LlmSummaryService.summarize} 调用。
     * 下游调用 {@code sb.append}。
     *
     * @param snapshot 用于此次处理的会话或模型快照
     * @return 供后续处理使用的文本或标识
     */
    static String conversationOf(MemorySnapshot snapshot) {
        StringBuilder sb = new StringBuilder();
        if (snapshot.getSummary() != null && snapshot.getSummary().getSummary() != null
                && !snapshot.getSummary().getSummary().isBlank()) {
            sb.append("[已有摘要]\n").append(snapshot.getSummary().getSummary()).append("\n\n");
        }
        for (AgentMessage m : snapshot.getMessages()) {
            sb.append(m.getRole()).append(": ").append(m.getContent() == null ? "" : m.getContent()).append("\n");
        }
        return sb.toString();
    }
}
