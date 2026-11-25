/* 承载持久化的一次完整 Agent 问答、结果与工具轨迹。 */

package com.pony.knowledge.agent.session;

import com.pony.knowledge.agent.model.StopReason;
import com.pony.knowledge.agent.model.ToolStep;
import com.pony.knowledge.dto.SourceDTO;
import java.util.List;

/** 只保存终止回合；步骤是有界摘要，来源保留 citationId，不保存工具全文。 */
public record AgentSessionTurn(String id, String question, String answer, List<ToolStep> steps,
                               List<SourceDTO> sources, String outcome, String reasonCode,
                               StopReason stopReason, boolean degraded, String degradeReason,
                               boolean synthesized, int iterations, int toolCalls,
                               int promptTokens, int completionTokens, long completedAt) { }
