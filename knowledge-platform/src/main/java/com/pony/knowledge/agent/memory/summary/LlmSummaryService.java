/* 通过 Agent 模型接口生成摘要，并验证完成原因和正文。 */

package com.pony.knowledge.agent.memory.summary;

import com.pony.knowledge.agent.llm.AgentLlmProperties;
import com.pony.knowledge.agent.llm.LlmClient;
import com.pony.knowledge.agent.llm.LlmResponse;
import com.pony.knowledge.agent.model.AgentPrompts;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 LLM 的摘要服务的直连档：把「已有摘要 + 待折消息」拼成对话，
 * 交给 LlmClient（OpenAI 兼容）产出浓缩要点，复用 agent 的 LlmClient 与 model 配置。
 *
 * <p>只在 {@code agent.llm.mode=direct} 时装配，与 {@link GatewaySummaryService}（默认档）二选一。
 * 直连档没有预算、熔断、重试退避、降级链与逐调用遥测。
 *
 * <p>产出不可用（空正文或被输出上限截断）时抛异常，由 SummaryCoordinator 统一转成降级归档，
 * 与本类是否走治理层无关。
 *
 * <p>两档共用同一份 prompt：system 见 {@link AgentPrompts#SUMMARY_SYSTEM}，
 * 对话拼装见 {@link SummaryPromptBuilder}。
 */
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent.llm.mode", havingValue = "direct")
public class LlmSummaryService implements SummaryService {

    private final LlmClient llmClient;

    private final AgentLlmProperties properties;

    /**
     * 构造摘要提示词并调用模型，返回保留折叠位置的可用摘要。
     * 下游调用 {@code SummaryPromptBuilder.conversationOf}、{@code llmClient.chatCompletions}、{@code SummaryValidation.requireUsable}。
     *
     * @param snapshot 用于此次处理的会话或模型快照
     * @return 带折叠位置的会话摘要
     */
    @Override
    public ConversationSummary summarize(MemorySnapshot snapshot) {
        String conversation = SummaryPromptBuilder.conversationOf(snapshot);

        Map<String, Object> system = new LinkedHashMap<>();
        system.put("role", "system");
        system.put("content", AgentPrompts.SUMMARY_SYSTEM);
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("role", "user");
        user.put("content", conversation);

        LlmResponse resp = llmClient.chatCompletions(properties.getModel(), List.of(system, user), null);

        // 空正文 / 撞输出上限被截断都判为不可用，抛给 Coordinator 走降级归档。
        // 判据与治理层档共用，避免「换开关之后是否接受截断摘要」出现两种口径。
        SummaryValidation.requireUsable(resp.getContent(), resp.getFinishReason());

        return ConversationSummary.builder()
                .sessionId(snapshot.getSessionId())
                .summary(resp.getContent())
                .summarizedMessageCount(snapshot.getMessageCount())
                .updatedAt(Instant.now())
                .build();
    }
}
