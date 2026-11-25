/* 承载 Agent 最终答复、工具轨迹、来源、用量和业务终态。 */

package com.pony.knowledge.agent.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.pony.knowledge.agent.tool.approval.MemorySaveApproval;
import com.pony.knowledge.dto.SourceDTO;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AgentResponse {

    private String answer;

    /** 本轮实际送入模型的获准片段；仅表示来源，不声明答案逐句已核实。 */
    private List<SourceDTO> sources;

    /** 本轮知识工具的四态结果；未调用知识工具时为空。 */
    private String outcome;

    private String reasonCode;

    private StopReason stopReason;

    @JsonIgnore
    private MemorySaveApproval.Proposal memorySaveProposal;

    private MemorySaveApproval memoryApproval;

    private int iterations;

    private int toolCalls;

    /** 本回合所有工具调用的一次性记录（方式/结果/数据摘要），供观测与断言链路；可为空 */
    private List<ToolInvocation> toolInvocations;

    /**
     * 本回合累计 prompt token（所有轮次的 LLM 决策之和）。
     * 非成功终止（answer 为 null）时同样有值——这些 token 已经真实花掉，只是没产出答案。
     */
    private int promptTokens;

    /** 本回合累计 completion token。 */
    private int completionTokens;

    /**
     * 本回合是否以治理层降级收场（仅 {@code agent.llm.mode=python} 可能为 true）。
     *
     * <p>为 true 时 answer 是兜底文案、不是模型生成的回答：前端据此提示 AI 服务不可用，
     * 监控据此统计降级比例。与 {@code /rag/query} 响应的 degraded 同一语义。
     */
    private boolean degraded;

    /** 降级原因（治理层给出的原文，如 circuit open）。 */
    private String degradeReason;

    /** 最后一次决策实际服务的 provider（主 / 降级供应商名）；直连档为空串。 */
    private String providerUsed;

    /**
     * 本回合的 answer 是否由「兜底合成」产生（达到迭代上限 / 超出时间预算时，
     * 用已有工具结果再调一次「不给工具」的模型调用收尾）。
     *
     * <p>为 true 说明模型是被要求收尾作答的，回答可能承认证据不足；
     * 与正常终止区分开，便于前端提示与排查。
     */
    private boolean synthesized;

    /**
     * 一次工具调用摘要：工具名 + 是否成功 + 错误 + 数据摘要。
     * 不感知具体工具输出类型，保持 runtime 与具体 Tool 解耦。
     */
    @Data
    @Builder
    public static class ToolInvocation {

        private String toolName;

        private boolean success;

        private String errorMessage;

        /** 可选的知识工具四态；供完成回合的步骤恢复使用。 */
        private String outcome;

        private String reasonCode;

        /** 工具输出 data 的可读摘要（截断），供观测与断言 evidence 是否回流。 */
        private String dataSummary;

        /** LLM 发出的原始 JSON 参数（来自 ToolCall.arguments），供参数比对。 */
        private String arguments;
    }
}
