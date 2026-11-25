/* 在一轮运行中累积模型消息、工具执行、用量、来源和终态。 */

package com.pony.knowledge.agent.runtime;

import com.pony.knowledge.agent.model.AgentDecision;
import com.pony.knowledge.agent.model.AgentMessage;
import com.pony.knowledge.agent.model.AgentResponse;
import com.pony.knowledge.agent.model.StopReason;
import com.pony.knowledge.agent.tool.approval.MemorySaveApproval;
import com.pony.knowledge.dto.SourceDTO;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一个回合（{@code run} 一次调用）的可变状态。
 *
 * <p>改造前这些变量散落在 {@code DefaultAgentRuntime.run} 的局部变量里，阶段一多就失去边界。
 * 收进一个对象后：① 阶段方法共用一份状态，不用长参数列表；② 无进展护栏（空回复次数、
 * 连续截断轮次、连续相同内容）有了明确归属；③ 终止原因只在这里落一次。
 *
 * <p>不持有身份（{@code userId} / 权限）——身份只在 {@code AgentExecutionContext}，
 * 本类只被 runtime 内部读写。
 */
@Getter
public class AgentTurnState {

    /** 当前正在装配的完整对话（system + 摘要 + 窗口 + 当前问题 + 本回合工具往返）。 */
    @Setter
    private List<AgentMessage> transcript;

    /** 本回合累积的「assistant tool_calls + tool 结果」，供上下文预算统一裁剪。 */
    private final List<AgentMessage> turnTool = new ArrayList<>();

    /** 本回合的工具调用记录（对外响应与断言用）。 */
    private final List<AgentResponse.ToolInvocation> invocations = new ArrayList<>();

    /** 本轮知识工具返回的候选；只有完整 JSON 出现在后续模型输入里才公开。 */
    private final List<SourceCandidate> sourceCandidates = new ArrayList<>();

    private final Map<String, SourceDTO> visibleSources = new LinkedHashMap<>();

    @Setter
    private String outcome;

    @Setter
    private String reasonCode;

    @Setter
    private boolean knowledgeToolExecuted;

    @Setter
    private boolean forcedKnowledgeSearch;

    public record SourceCandidate(String callId, String serialized, SourceDTO source) { }

    /** 已执行过工具调用的轮次数：兜底合成「有没有素材」的判据。 */
    @Setter
    private int toolRounds;

    /** 已完成的轮次数（每完成一次 Think 且推进到 NEXT 才 +1）。 */
    @Setter
    private int round;

    /** 已发起的工具调用总数（含本轮未执行就被上限拦下的）。 */
    @Setter
    private int toolCalls;

    private int promptTokens;

    private int completionTokens;

    /** 终止原因：由终止路径写入，{@code null} 表示回合尚未结束。 */
    @Setter
    private StopReason stopReason;

    @Setter
    private MemorySaveApproval.Proposal memorySaveProposal;

    @Setter
    private String finalAnswer;

    /** A successful data tool supplied the final text directly, including literal user content. */
    @Setter
    private boolean toolRenderedAnswer;

    /** 答案是否由兜底合成产生。 */
    @Setter
    private boolean synthesized;

    /** 是否已得出终局（有答案或已命中终止护栏）。 */
    @Setter
    private boolean complete;

    /** 本回合是否以治理层降级收场。 */
    @Setter
    private boolean degraded;

    /** 降级原因（治理层原文，如 circuit open）。 */
    @Setter
    private String degradeReason;

    /** 最后一次决策实际服务的 provider。 */
    @Setter
    private String providerUsed;

    // ---------- 无进展护栏计数 ----------

    /** 空回复 nudge 已重试次数。 */
    @Setter
    private int emptyRetries;

    /** 连续在输出上限处被截断的轮次数。 */
    @Setter
    private int consecutiveLengthRounds;

    /**
     * 本回合是否已经做过一次「上下文溢出恢复」。
     *
     * <p>每回合只救一次：压不动时再试只会得到同样的降级，白白多付一次模型调用。
     * 也防止「压小 → 又溢出 → 再压」反复抖动。
     */
    @Setter
    private boolean overflowRetried;

    /**
     * 用注入依赖和构造参数建立此实例执行时使用的状态。
     *
     * @param messages 按照交互顺序提供的消息列表
     */
    public AgentTurnState(List<AgentMessage> messages) {
        this.transcript = new ArrayList<>(messages);
    }

    /**
     * 将当前模型决策返回的用量计入本轮已消耗的输入和输出令牌。
     * 上游由 {@code DefaultAgentRuntime.recordUsage} 调用。
     *
     * @param decision 待处理的决策及其终态信息
     */
    public void addUsage(AgentDecision decision) {
        if (decision == null) {
            return;
        }
        promptTokens += decision.getPromptTokens();
        completionTokens += decision.getCompletionTokens();
    }

    /**
     * 根据本轮调用轨迹判断是否已经执行过工具。
     * 上游由 {@code DefaultAgentRuntime.finalizeTurn} 调用。
     *
     * @return 条件成立时为 true，其他状态按 false 处理
     */
    public boolean anyToolExecuted() {
        return toolRounds > 0;
    }
}
