/* 在循环停止时，根据预算内的上下文尝试合成收尾答案。 */

package com.pony.knowledge.agent.runtime;

import com.pony.knowledge.agent.llm.AgentLlm;
import com.pony.knowledge.agent.model.AgentDecision;
import com.pony.knowledge.agent.model.AgentMessage;
import com.pony.knowledge.agent.model.AgentPrompts;
import com.pony.knowledge.agent.tool.ToolDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 兜底合成：回合被迫结束时，用已经拿到的工具结果再要一次答案。
 *
 * <p>对应 WeKnora 的 {@code handleMaxIterations} / {@code streamFinalAnswerToEventBus}：
 * 达到迭代上限、或时间预算耗尽时，如果本轮已经检索过，就追加一条「工具执行已结束，请直接作答」
 * 的指令，并**不给任何工具**再调一次模型（等价于 {@code tool_choice=none}）。
 *
 * <p>为什么值得多花一次调用：改造前这两种终止只返回 {@code answer=null}，用户看到的是
 * 「（本轮未能在最大轮次内得出结论）」——而那一刻模型其实已经握着检索结果，只是没被允许开口。
 *
 * <p>不做的事：不重试、不再调工具、不把工具输出提升成 user 消息（工具结果必须保留
 * tool 角色与 callId，否则下一轮上下文形状就错了）。
 */
public class FinalAnswerSynthesizer {

    private final AgentLlm agentLlm;

    /**
     * 用注入依赖和构造参数建立此实例执行时使用的状态。
     *
     * @param agentLlm 本次调用传入的 {@code AgentLlm}
     */
    public FinalAnswerSynthesizer(AgentLlm agentLlm) {
        this.agentLlm = agentLlm;
    }

    /**
     * 在循环准备结束时使用预算内的历史与工具结果请求收尾答案，通过回调提供可用增量。
     * 上游由 {@code DefaultAgentRuntime.finalizeTurn} 调用。
     * 下游调用 {@code agentLlm.respond}、{@code agentLlm.respondStream}。
     *
     * @param transcript 本次调用传入的 {@code List<AgentMessage>}
     * @param onDelta 本次调用传入的 {@code Consumer<String>}
     * @return 模型本轮的正文、工具调用和完成状态
     */
    public AgentDecision synthesize(List<AgentMessage> transcript, Consumer<String> onDelta) {
        List<AgentMessage> messages = new ArrayList<>(transcript);
        messages.add(AgentMessage.builder()
                .role("user")
                .content(AgentPrompts.FINALIZE_INSTRUCTION)
                .build());
        // 不给工具：既避免模型继续要求检索，也让这次调用在语义上只能产出正文。
        List<ToolDefinition> noTools = List.of();
        return onDelta == null
                ? agentLlm.respond(messages, noTools)
                : agentLlm.respondStream(messages, noTools, onDelta);
    }
}
