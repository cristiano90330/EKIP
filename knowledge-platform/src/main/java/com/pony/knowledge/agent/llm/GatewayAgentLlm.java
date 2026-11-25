/* 把 Agent 消息、工具定义和模型快照转换为网关协议，并解码决策终态。 */

package com.pony.knowledge.agent.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pony.knowledge.agent.llm.AgentGatewayClient.AgentGatewayException;
import com.pony.knowledge.agent.model.AgentDecision;
import com.pony.knowledge.agent.model.AgentMessage;
import com.pony.knowledge.agent.tool.ToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * AgentLlm 的治理层档，也是默认档：一次决策发 AI 服务，由 Python 的 {@code ModelGate}
 * 包住预算、熔断、重试退避、降级链与遥测。
 *
 * <p>两条路径共用一份报文（{@link OpenAiPayloadMapper}）与一套降级语义：
 * <ul>
 *   <li>{@code POST /agent/decide}：同步档，一次拿到完整决策；</li>
 *   <li>{@code POST /agent/decide/stream}：流式档，正文逐字回调，最后用 {@code final} 帧给出
 *       tool_calls / finish_reason / usage。</li>
 * </ul>
 *
 * <p>本类永不抛异常。传输失败（连不上 / 超时 / 非 2xx）一律翻成 degraded 决策，
 * 由 Runtime 走 LLM_DEGRADED 出口返回 200 与一句可读文案。4xx 同样按降级处理，
 * 但把状态码写进原因：{@code mode=python} 配到不含该端点的镜像会得到 404，
 * 那是部署错配，不应让整条对话线崩掉。
 *
 * <p>流式 {@code delta} 只是临时内容；只有 {@code final} 帧确认工具调用、停止原因与用量。
 * 连接断开、错误帧或缺失 {@code final} 时返回 degraded，不能把半段正文当最终答案。
 *
 * <p>与会话摘要档 {@link GatewaySummaryService} 的失败处置故意不同：摘要失败不该给用户看，
 * 只需本轮不折叠、下轮重试。同一个传输层，两种失败语义，各自对应各自的调用性质。
 */
public class GatewayAgentLlm implements AgentLlm {

    private static final Logger log = LoggerFactory.getLogger(GatewayAgentLlm.class);

    private static final String PATH = "/agent/decide";

    private static final String STREAM_PATH = "/agent/decide/stream";

    private final AgentGatewayClient client;

    private final ObjectMapper objectMapper;

    /**
     * 将当前参数交给共有构造入口，并补齐这组重载省略的设置。
     *
     * @param client 本次调用传入的 {@code AgentGatewayClient}
     */
    public GatewayAgentLlm(AgentGatewayClient client) {
        this(client, new ObjectMapper());
    }

    /**
     * 用注入依赖和构造参数建立此实例执行时使用的状态。
     *
     * @param client 本次调用传入的 {@code AgentGatewayClient}
     * @param objectMapper 负责 JSON 协议编码和解码的映射器
     */
    public GatewayAgentLlm(AgentGatewayClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    /**
     * 将 Agent 消息和工具声明发送到同步决策接口，并将响应转换为一轮决策。
     * 下游调用 {@code client.post}、{@code payload}、{@code degraded}。
     *
     * @param messages 按照交互顺序提供的消息列表
     * @param tools 本次调用传入的 {@code List<ToolDefinition>}
     * @return 模型本轮的正文、工具调用和完成状态
     */
    @Override
    public AgentDecision respond(List<AgentMessage> messages, List<ToolDefinition> tools) {
        try {
            DecideResponse response = client.post(PATH, payload(messages, tools), DecideResponse.class);
            if (response.degraded) {
                // 治理层自己判的降级（预算超限 / 熔断 / 降级链走完）——HTTP 是 200，原因在响应体里
                return degraded(response.degrade_reason, response.provider_used, 0L, "");
            }
            return toDecision(response.content, response.tool_calls, response.prompt_tokens,
                    response.completion_tokens, response.provider_used, response.finish_reason);
        } catch (AgentGatewayException e) {
            // 传输层失败（连不上 / 超时 / 非 2xx）同样按降级处理，不把失败冒成用户的 500
            return degraded(e);
        }
    }

    /**
     * 调用模型流式决策接口并分派正文事件，依据网关终态返回最终决策。
     * 下游调用 {@code client.postSse}、{@code objectMapper.readValue}、{@code streamedChars.addAndGet}。
     *
     * @param messages 按照交互顺序提供的消息列表
     * @param tools 本次调用传入的 {@code List<ToolDefinition>}
     * @param onDelta 本次调用传入的 {@code Consumer<String>}
     * @return 模型本轮的正文、工具调用和完成状态
     */
    @Override
    public AgentDecision respondStream(List<AgentMessage> messages, List<ToolDefinition> tools,
                                       Consumer<String> onDelta) {
        AtomicInteger streamedChars = new AtomicInteger();
        AtomicReference<AgentDecision> terminal = new AtomicReference<>();
        try {
            client.postSse(STREAM_PATH, payload(messages, tools), (event, data) -> {
                switch (event) {
                    case "delta" -> {
                        DeltaFrame frame = objectMapper.readValue(data, DeltaFrame.class);
                        if (frame.text != null && !frame.text.isEmpty()) {
                            streamedChars.addAndGet(frame.text.length());
                            if (onDelta != null) {
                                onDelta.accept(frame.text);
                            }
                        }
                    }
                    case "final" -> terminal.set(toDecisionFromFinal(
                            objectMapper.readValue(data, FinalFrame.class)));
                    case "error" -> {
                        com.fasterxml.jackson.databind.JsonNode failure = objectMapper.readTree(data);
                        boolean timeout = "timeout".equals(failure.path("code").asText())
                                || "model_timeout".equals(failure.path("reasonCode").asText());
                        terminal.set(degraded(timeout ? "模型执行超时" : "模型流式执行失败", "", 0L, "",
                                timeout ? "timeout" : "dependency_unavailable", timeout ? "model_timeout" : "model_unavailable"));
                    }
                    default -> {
                        // 未知帧忽略：向前兼容将来新增的事件（thinking / usage 等）
                    }
                }
            });
        } catch (AgentGatewayException e) {
            log.warn("→ AI 服务 {} 流中断，丢弃未确认的 {} 字符 requestId={} 原因={}",
                    STREAM_PATH, streamedChars.get(), e.getRequestId(), e.getReason());
            return degraded(e);
        } catch (Exception e) {
            log.warn("→ AI 服务 {} 流解析失败，丢弃未确认的 {} 字符: {}",
                    STREAM_PATH, streamedChars.get(), e.getClass().getSimpleName());
            return degraded("流式决策响应无法解析", "", 0L, "");
        }

        AgentDecision decision = terminal.get();
        if (decision != null) {
            return decision;
        }

        log.warn("→ AI 服务 {} 未发送 final 帧，丢弃未确认的 {} 字符",
                STREAM_PATH, streamedChars.get());
        return degraded("流式决策缺少 final 帧", "", 0L, "");
    }

    /**
     * 组装网关决策需要的模型快照、消息序列、工具与用途控制。
     * 上游由 {@code GatewayAgentLlm.respond}、{@code GatewayAgentLlm.respondStream} 调用。
     * 下游调用 {@code OpenAiPayloadMapper.toOpenAiMessages}、{@code OpenAiPayloadMapper.toOpenAiTools}、{@code com.pony.knowledge.modelmanagement.ModelExecutionContext.current}。
     *
     * @param messages 按照交互顺序提供的消息列表
     * @param tools 本次调用传入的 {@code List<ToolDefinition>}
     * @return 当前记录中的 {@code body}
     */
    private Map<String, Object> payload(List<AgentMessage> messages, List<ToolDefinition> tools) {
        Map<String, Object> body = new LinkedHashMap<>();
        // 报文与直连档逐字段相同（同一个 OpenAiPayloadMapper），只是收件人从供应商换成 AI 服务。
        body.put("messages", OpenAiPayloadMapper.toOpenAiMessages(messages));
        body.put("tools", OpenAiPayloadMapper.toOpenAiTools(tools));
        Map<String,Object> execution = com.pony.knowledge.modelmanagement.ModelExecutionContext.current();
        if (execution != null) body.put("modelExecution", execution);
        return body;
    }

    /**
     * 读取网关返回的正文、工具调用、用量与结束原因并构建决策对象。
     * 上游由 {@code GatewayAgentLlm.respond}、{@code GatewayAgentLlm.toDecisionFromFinal} 调用。
     * 下游调用 {@code OpenAiToolCall.toToolCalls}。
     *
     * @param content 待处理的正文内容
     * @param toolCalls 本次调用传入的 {@code List<OpenAiToolCall>}
     * @param promptTokens 本次调用传入的 {@code Integer}
     * @param completionTokens 本次调用传入的 {@code Integer}
     * @param providerUsed 本次调用传入的 {@code String}
     * @param finishReason 模型提供的响应结束原因
     * @return 模型本轮的正文、工具调用和完成状态
     */
    private AgentDecision toDecision(String content, List<OpenAiToolCall> toolCalls,
                                     Integer promptTokens, Integer completionTokens,
                                     String providerUsed, String finishReason) {
        return AgentDecision.builder()
                .content(content)
                .toolCalls(OpenAiToolCall.toToolCalls(toolCalls))
                .promptTokens(promptTokens == null ? 0 : promptTokens)
                .completionTokens(completionTokens == null ? 0 : completionTokens)
                .providerUsed(providerUsed == null ? "" : providerUsed)
                .finishReason(finishReason)
                .build();
    }

    /**
     * 将已读取的最终事件转换为安全交付的模型决策，保留回复及用量。
     * 上游由 {@code GatewayAgentLlm.respondStream} 调用。
     * 下游调用 {@code degraded}、{@code toDecision}。
     *
     * @param frame 本次调用传入的 {@code FinalFrame}
     * @return 模型本轮的正文、工具调用和完成状态
     */
    private AgentDecision toDecisionFromFinal(FinalFrame frame) {
        if (frame.degraded) {
            return degraded(frame.degrade_reason, frame.provider_used, 0L, "");
        }
        return toDecision(frame.content, frame.tool_calls, frame.prompt_tokens,
                frame.completion_tokens, frame.provider_used, frame.finish_reason);
    }

    /**
     * 将网关故障和请求诊断映射为降级决策，供运行时按业务终态处理。
     * 下游调用 {@code degraded}。
     *
     * @param reason 诊断或终止原因
     * @param provider 本次调用传入的 {@code String}
     * @param elapsedMs 本次调用传入的 {@code long}
     * @param requestId 本次调用传入的 {@code String}
     * @return 模型本轮的正文、工具调用和完成状态
     */
    private AgentDecision degraded(String reason, String provider, long elapsedMs, String requestId) {
        boolean capacity = "model_context_capacity_exceeded".equals(reason)
                || "model_prompt_budget_exceeded".equals(reason);
        return degraded(reason,provider,elapsedMs,requestId,"dependency_unavailable",
                capacity ? "model_context_capacity_exceeded" : "model_unavailable");
    }

    /**
     * 将网关故障和请求诊断映射为降级决策，供运行时按业务终态处理。
     * 下游调用 {@code degraded}。
     *
     * @param error 用于分类与诊断的异常
     * @return 模型本轮的正文、工具调用和完成状态
     */
    private AgentDecision degraded(AgentGatewayException error) {
        boolean timeout = error.getStatus() == 504 || error.getStatus() == 408;
        Throwable cause = error;
        for (int depth=0; cause != null && depth<20; depth++, cause=cause.getCause()) {
            if (cause instanceof java.net.http.HttpConnectTimeoutException) break;
            if (cause instanceof java.net.http.HttpTimeoutException || cause instanceof java.net.SocketTimeoutException) { timeout=true; break; }
        }
        return degraded(error.getReason(),"",error.getElapsedMs(),error.getRequestId(),
                timeout ? "timeout" : "dependency_unavailable",timeout ? "model_timeout" : error.getReasonCode());
    }

    /**
     * 将网关故障和请求诊断映射为降级决策，供运行时按业务终态处理。
     *
     * @param reason 诊断或终止原因
     * @param provider 本次调用传入的 {@code String}
     * @param elapsedMs 本次调用传入的 {@code long}
     * @param requestId 本次调用传入的 {@code String}
     * @param outcome 问答或检索的业务终态
     * @param reasonCode 供调用方识别的稳定原因码
     * @return 模型本轮的正文、工具调用和完成状态
     */
    private AgentDecision degraded(String reason, String provider, long elapsedMs, String requestId,
                                   String outcome, String reasonCode) {
        log.warn("→ AI 服务 {} 降级 requestId={} 耗时={}ms 原因={}", PATH, requestId, elapsedMs, reason);
        return AgentDecision.builder()
                .content(null)
                .toolCalls(List.of())
                .promptTokens(0)
                .completionTokens(0)
                .degraded(true)
                .degradeReason(reason == null ? "" : reason)
                .outcome(outcome)
                .reasonCode(reasonCode)
                .providerUsed(provider == null ? "" : provider)
                .build();
    }

    /**
     * 裁减网关诊断用文本，限制错误信息长度。
     * 下游调用 {@code text.length}。
     *
     * @param text 待处理正文
     * @return 供后续处理使用的文本或标识
     */
    private static String abbreviate(String text) {
        return text == null || text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }

    /** /agent/decide 的响应体（snake_case 与 Python 契约一致，见 ai/app/agent/schema.py）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DecideResponse {

        public String content;

        public List<OpenAiToolCall> tool_calls;

        @JsonProperty("prompt_tokens")
        public Integer prompt_tokens;

        @JsonProperty("completion_tokens")
        public Integer completion_tokens;

        public boolean degraded;

        @JsonProperty("degrade_reason")
        public String degrade_reason;

        @JsonProperty("provider_used")
        public String provider_used;

        @JsonProperty("finish_reason")
        public String finish_reason;
    }

    /** 流式 delta 帧：只有正文增量。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DeltaFrame {

        public String text;
    }

    /** 流式 final 帧：与同步响应同形（含 tool_calls / finish_reason / usage）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FinalFrame {

        public String content;

        public List<OpenAiToolCall> tool_calls;

        @JsonProperty("prompt_tokens")
        public Integer prompt_tokens;

        @JsonProperty("completion_tokens")
        public Integer completion_tokens;

        public boolean degraded;

        @JsonProperty("degrade_reason")
        public String degrade_reason;

        @JsonProperty("provider_used")
        public String provider_used;

        @JsonProperty("finish_reason")
        public String finish_reason;
    }
}
