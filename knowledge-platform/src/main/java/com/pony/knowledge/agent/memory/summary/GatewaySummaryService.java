/* 用已登记的摘要模型调用 Python 网关并校验输出可用于后续上下文。 */

package com.pony.knowledge.agent.memory.summary;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.pony.knowledge.agent.exception.AgentException;
import com.pony.knowledge.agent.llm.AgentGatewayClient;
import com.pony.knowledge.agent.llm.AgentGatewayClient.AgentGatewayException;
import com.pony.knowledge.agent.model.AgentPrompts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 会话摘要的治理层档（默认档）：把「旧摘要 + 待折消息」发给 AI 服务的
 * {@code POST /agent/summarize}，由 Python 的 {@code ModelGate}（purpose=summarize）
 * 包住超时、重试退避、熔断、降级链与逐调用遥测。
 *
 * <p>失败时抛 {@link AgentException}，与决策档相反，这是有意的不对称：
 * <ul>
 *   <li>决策降级直接决定用户这一轮看到什么，必须变成 200 与一句可读文案（GatewayAgentLlm）；</li>
 *   <li>摘要是回合结束后的异步优化，用户不在等它，交给 SummaryCoordinator 统一收口：
 *       记 {@code agent.summary.failure}，并写入一份<b>降级归档</b>（原始对话摘录）后照常推进游标，
 *       使同一批消息不会每回合重折一次。下一次摘要成功时归档被正常摘要取代。</li>
 * </ul>
 * 本类不能返回 null，否则 Coordinator 在 {@code cs.setSessionId(...)} 处 NPE，
 * 失败原因会从「LLM 降级」退化成空指针。
 *
 * <p>prompt 与直连档逐字段相同（{@link AgentPrompts#SUMMARY_SYSTEM} +
 * {@link SummaryPromptBuilder}）；HTTP 传输与决策链路共用 {@link AgentGatewayClient}。
 */
@Service
public class GatewaySummaryService implements SummaryService {

    private static final Logger log = LoggerFactory.getLogger(GatewaySummaryService.class);

    private static final String PATH = "/agent/summarize";

    private final AgentGatewayClient client;

    /**
     * 用注入依赖和构造参数建立此实例执行时使用的状态。
     *
     * @param client 本次调用传入的 {@code AgentGatewayClient}
     */
    public GatewaySummaryService(AgentGatewayClient client) {
        this.client = client;
    }

    private com.pony.knowledge.modelmanagement.ModelRegistryService modelRegistry;

    /**
     * 注入摘要用途的模型注册表，供有会话模型上下文的请求解析。
     *
     * @param models 读取模型用途及执行配置的注册服务
     */
    @org.springframework.beans.factory.annotation.Autowired
    public void setModelRegistry(com.pony.knowledge.modelmanagement.ModelRegistryService models) { this.modelRegistry=models; }

    /**
     * 将现有摘要和待折叠消息交给 Python 摘要接口，校验回包后返回整理结果。
     * 下游调用 {@code SummaryPromptBuilder.conversationOf}、{@code com.pony.knowledge.modelmanagement.ModelManagementException.unavailable}、{@code modelRegistry.resolveForPurpose}。
     *
     * @param snapshot 用于此次处理的会话或模型快照
     * @return 带折叠位置的会话摘要
     */
    @Override
    public ConversationSummary summarize(MemorySnapshot snapshot) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("system_prompt", AgentPrompts.SUMMARY_SYSTEM);
        body.put("user_prompt", SummaryPromptBuilder.conversationOf(snapshot));
        if (modelRegistry != null) {
            if (snapshot.getOwnerId() == null || snapshot.getSummaryModelId() == null)
                throw com.pony.knowledge.modelmanagement.ModelManagementException.unavailable("model_not_configured: 摘要未绑定模型");
            Map<String,Object> execution=new LinkedHashMap<>();
            execution.put("schemaVersion",1); execution.put("budgetMs",30000);
            execution.put("bindings",Map.of("summarize",modelRegistry.resolveForPurpose(
                    snapshot.getOwnerId(),"summarize",snapshot.getSummaryModelId())));
            execution.put("indexScopes",java.util.List.of());
            body.put("modelExecution",execution);
        }

        SummarizeResponse response;
        try {
            response = client.post(PATH, body, SummarizeResponse.class);
        } catch (AgentGatewayException e) {
            log.warn("会话摘要走治理层失败 sessionId={} requestId={} 耗时={}ms 原因={}",
                    snapshot.getSessionId(), e.getRequestId(), e.getElapsedMs(), e.getReason());
            throw new AgentException("会话摘要 LLM 调用失败：" + e.getReason(), e);
        }

        if (response.degraded) {
            // 治理层判降级（预算 / 熔断 / 降级链走完）：同样按失败处理，让游标不动、下轮重试。
            log.warn("会话摘要被治理层降级 sessionId={} 原因={}", snapshot.getSessionId(), response.degrade_reason);
            throw new AgentException("会话摘要被治理层降级：" + nullToEmpty(response.degrade_reason));
        }
        if (response.content == null || response.content.isBlank()) {
            // 200 但没有正文：与降级同等对待。摘要写空串会覆盖掉上一版好摘要
            // （ContextBuilder 读到空摘要比没有摘要更糟），因此宁可不动游标下轮重来。
            throw new AgentException("会话摘要返回空正文（degraded=false），按失败处理以便下轮重试");
        }
        // 撞输出上限被截断同样不可用：截断摘要读起来像正常摘要，但后半段永久丢失，
        // 而它此后是该批历史的唯一记忆。口径与直连档共用（SummaryValidation）。
        SummaryValidation.requireUsable(response.content, response.finish_reason);

        return ConversationSummary.builder()
                .sessionId(snapshot.getSessionId())
                .summary(response.content)
                .summarizedMessageCount(snapshot.getMessageCount())
                .updatedAt(Instant.now())
                .build();
    }

    /**
     * 把缺失的摘要文本转换为空串，供模型请求构造。
     * 上游由 {@code GatewaySummaryService.summarize} 调用。
     *
     * @param s 本次调用传入的 {@code String}
     * @return 供后续处理使用的文本或标识
     */
    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** /agent/summarize 的响应体（snake_case 与 Python 契约一致，见 ai/app/agent/schema.py）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SummarizeResponse {

        public String content;

        @JsonProperty("prompt_tokens")
        public Integer prompt_tokens;

        @JsonProperty("completion_tokens")
        public Integer completion_tokens;

        public boolean degraded;

        @JsonProperty("degrade_reason")
        public String degrade_reason;

        @JsonProperty("provider_used")
        public String provider_used;

        /** 供应商 finish_reason（stop / length …）；截断判据见 SummaryValidation。 */
        @JsonProperty("finish_reason")
        public String finish_reason;
    }
}
