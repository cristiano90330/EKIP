/* 承载构建后的消息、令牌估算与裁减诊断。 */

package com.pony.knowledge.agent.context;

import com.pony.knowledge.agent.model.AgentMessage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * ContextBuilder 输出：最终有序的上下文消息与预算裁剪统计。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContextBuildResult {

    /** 装配好、按顺序、已裁到预算内的上下文消息 */
    private List<AgentMessage> messages;

    /** 裁剪前估算 token（与 tokensUsed 对比即知裁掉多少） */
    private int tokensBefore;

    /** 裁剪后实际占用 token */
    private int tokensUsed;

    /** 是否发生了裁剪（整条丢弃或内容截断，任一发生即为 true） */
    private boolean trimmed;

    /** 被整条丢弃的消息条数（最近窗口 + 被整组丢弃的工具往返） */
    private int droppedMessages;

    /**
     * 内容被截断而非丢弃的消息条数。
     *
     * <p>与 {@link #droppedMessages} 分开计数：丢弃丢的是「一条消息的全部信息」，
     * 截断只丢尾部，证据头 70% 仍然在上下文里。两者混在一个指标里会掩盖「证据是否真的进了模型」。
     */
    private int truncatedMessages;

    /**
     * 裁剪后仍然超出预算的 token 数（0 = 未击穿，常态）。
     *
     * <p>非 0 只有一个来源：system + summary + currentQuery 这三个 Required 段自身就超过 allowed，
     * 而它们按定义不可裁。工具往返无论多重都能被截断或整组丢弃，因此不会留在这里。
     * 该值由 DefaultContextBudgetManager 转成 agent.context.budget_exceeded 与 .tokens 两个 counter，
     * 不做静默处理。
     */
    private int overBudgetTokens;
}
