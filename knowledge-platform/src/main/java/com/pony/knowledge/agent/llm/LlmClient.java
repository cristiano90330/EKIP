/* 封装直接聊天模型的 HTTP 调用以及回复、工具调用和用量解析。 */

package com.pony.knowledge.agent.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pony.knowledge.agent.exception.AgentException;
import com.pony.knowledge.agent.model.ToolCall;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容 chat.completions 客户端（DeepSeek / 通义千问 DashScope / 智谱 / OpenAI 通用）。
 * 统一：POST {baseUrl}/chat/completions，Bearer 鉴权，HTTP/1.1，带 connect/read timeout。
 * 只负责收发与规范化，不感知具体供应商 —— 供应商差异（baseUrl / model / key）由配置注入。
 */
public class LlmClient {

    private final HttpClient httpClient;

    private final ObjectMapper objectMapper;

    private final String apiKey;

    private final String baseUrl;

    private final Duration readTimeout;

    /**
     * 用注入依赖和构造参数建立此实例执行时使用的状态。
     * 下游调用 {@code Duration.ofMillis}、{@code HttpClient.newBuilder}、{@code stripTrailingSlash}。
     *
     * @param apiKey 本次调用传入的 {@code String}
     * @param baseUrl 本次调用传入的 {@code String}
     * @param connectTimeoutMs 本次调用传入的 {@code int}
     * @param readTimeoutMs 本次调用传入的 {@code int}
     * @param objectMapper 负责 JSON 协议编码和解码的映射器
     */
    public LlmClient(
            String apiKey,
            String baseUrl,
            int connectTimeoutMs,
            int readTimeoutMs,
            ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.readTimeout = Duration.ofMillis(readTimeoutMs);
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                // 与 AiServiceClient 一致：强制 HTTP/1.1，避免 HTTP/2 Upgrade 头被部分服务解析出错
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
    }

    /**
     * 把指定模型、消息与工具组成兼容请求并返回解析后的模型回复。
     * 上游由 {@code LlmSummaryService.summarize}、{@code OpenAiAgentLlm.respond} 调用。
     * 下游调用 {@code post}、{@code toLlmResponse}。
     *
     * @param model 本次调用传入的 {@code String}
     * @param messages 按照交互顺序提供的消息列表
     * @param tools 本次调用传入的 {@code List<Map<String, Object>>}
     * @return 提供商回复及其工具调用和用量
     */
    public LlmResponse chatCompletions(
            String model,
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools) {

        if (apiKey == null || apiKey.isBlank()) {
            throw new AgentException("未配置 agent.llm.api-key（LLM_API_KEY 或根 .env），无法调用 LLM");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
        }

        body.put("tool_choice", "auto");
        body.put("stream", false);
        body.put("temperature", 0.0);

        ChatCompletionsResponse response = post(body);
        return toLlmResponse(response);
    }

    /**
     * 向 OpenAI 兼容聊天路由发送请求，读取模型响应对象。
     * 上游由 {@code LlmClient.chatCompletions} 调用。
     * 下游调用 {@code objectMapper.writeValueAsString}、{@code HttpRequest.newBuilder}、{@code URI.create}。
     *
     * @param body 本次调用传入的 {@code Map<String, Object>}
     * @return 调用方使用的 {@code ChatCompletionsResponse}
     */
    private ChatCompletionsResponse post(Map<String, Object> body) {
        try {
            String json = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .timeout(readTimeout)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> http = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (http.statusCode() < 200 || http.statusCode() >= 300) {
                throw new AgentException(
                        "LLM 调用失败 status=" + http.statusCode() + " body=" + truncate(http.body(), 300));
            }
            return objectMapper.readValue(http.body(), ChatCompletionsResponse.class);
        } catch (AgentException e) {
            throw e;
        } catch (Exception e) {
            throw new AgentException("LLM 调用异常: " + e.getMessage(), e);
        }
    }

    /**
     * 从网关响应提取选项正文、模型工具调用与令牌用量。
     * 上游由 {@code LlmClient.chatCompletions} 调用。
     *
     * @param response 本次调用传入的 {@code ChatCompletionsResponse}
     * @return 提供商回复及其工具调用和用量
     */
    private LlmResponse toLlmResponse(ChatCompletionsResponse response) {
        if (response == null || response.choices == null || response.choices.isEmpty()) {
            throw new AgentException("LLM 返回异常：choices 为空");
        }

        ChatCompletionsResponse.Choice choice = response.choices.get(0);
        ChatCompletionsResponse.Message message = choice.message;

        List<ToolCall> toolCalls = new ArrayList<>();
        if (message != null && message.tool_calls != null) {
            for (OpenAiToolCall outer : message.tool_calls) {
                if (outer == null || outer.function == null) {
                    continue;
                }
                toolCalls.add(ToolCall.builder()
                        .callId(outer.id)
                        .toolName(outer.function.name)
                        .arguments(outer.function.arguments)
                        .build());
            }
        }

        TokenUsage usage = null;
        if (response.usage != null) {
            usage = TokenUsage.builder()
                    .promptTokens(response.usage.prompt_tokens == null ? 0 : response.usage.prompt_tokens)
                    .completionTokens(response.usage.completion_tokens == null ? 0 : response.usage.completion_tokens)
                    .build();
        }

        return LlmResponse.builder()
                .content(message == null ? null : message.content)
                .toolCalls(toolCalls)
                .usage(usage)
                .finishReason(choice.finish_reason)
                .build();
    }

    /**
     * 标准化基础地址尾部，便于追加接口路径。
     * 上游由 {@code LlmClient.LlmClient} 调用。
     * 下游调用 {@code url.endsWith}、{@code url.length}。
     *
     * @param url 本次调用传入的 {@code String}
     * @return 供后续处理使用的文本或标识
     */
    private String stripTrailingSlash(String url) {
        if (url == null) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * 限制外部响应诊断文本的可见长度。
     * 上游由 {@code LlmClient.post} 调用。
     * 下游调用 {@code s.length}。
     *
     * @param s 本次调用传入的 {@code String}
     * @param max 本次调用传入的 {@code int}
     * @return 供后续处理使用的文本或标识
     */
    private String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "...";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ChatCompletionsResponse {

        public String id;

        public List<Choice> choices;

        public Usage usage;

        @JsonIgnoreProperties(ignoreUnknown = true)
        static class Choice {

            public Message message;

            public String finish_reason;
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        static class Message {

            public String content;

            public List<OpenAiToolCall> tool_calls;
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        static class Usage {

            @JsonProperty("prompt_tokens")
            public Integer prompt_tokens;

            @JsonProperty("completion_tokens")
            public Integer completion_tokens;

            @JsonProperty("total_tokens")
            public Integer total_tokens;
        }
    }
}
