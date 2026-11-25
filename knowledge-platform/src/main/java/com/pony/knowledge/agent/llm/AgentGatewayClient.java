/* 通过内部鉴权向 Python Agent 网关发起 JSON 和 SSE 请求，映射传输错误。 */

package com.pony.knowledge.agent.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pony.knowledge.request.RequestIdContext;
import com.pony.knowledge.resilience.AiAvailabilityGuard;
import com.pony.knowledge.resilience.AiFailureClassifier;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import com.pony.knowledge.modelmanagement.ModelHttpDeadline;

/**
 * Agent 决策与摘要访问 Python 的共用传输客户端，由 AgentLlmConfig 装配。
 * 本类维护 HTTP/1.1、内部令牌、请求标识、超时和响应反序列化，并与 AiServiceClient
 * 共享可用性状态；业务调用方根据抛出的传输异常决定降级或保留原有会话状态。
 */
public class AgentGatewayClient {

    private static final String REQUEST_ID_HEADER = "X-Request-ID";

    /** Python 内部接口使用的凭证头。 */
    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    private final AgentGatewayProperties properties;

    private final ObjectMapper objectMapper;

    /** 由装配器注入，与 Python INTERNAL_API_TOKEN 保持一致。 */
    private final String internalToken;

    private final Tracer tracer;

    /** 与普通 AI 调用出口共享的可用性状态。 */
    private final AiAvailabilityGuard guard;

    private final HttpClient httpClient;

    /**
     * 用注入依赖和构造参数建立此实例执行时使用的状态。
     * 下游调用 {@code HttpClient.newBuilder}、{@code Duration.ofMillis}。
     *
     * @param properties 本次调用传入的 {@code AgentGatewayProperties}
     * @param objectMapper 负责 JSON 协议编码和解码的映射器
     * @param internalToken 本次调用传入的 {@code String}
     * @param tracer 本次调用传入的 {@code Tracer}
     * @param guard 本次调用传入的 {@code AiAvailabilityGuard}
     */
    public AgentGatewayClient(AgentGatewayProperties properties, ObjectMapper objectMapper,
                              String internalToken, Tracer tracer, AiAvailabilityGuard guard) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.internalToken = internalToken;
        this.tracer = tracer;
        this.guard = guard;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .build();
    }

    /**
     * 向内部 Python 网关发送 JSON 请求，返回解析结果或包含稳定状态的传输异常。
     * 上游由 {@code GatewayAgentLlm 和 GatewaySummaryService}、{@code GatewayAgentLlm.respond}、{@code GatewaySummaryService.summarize} 调用。
     * 下游调用 {@code guard.acquire}、{@code ModelHttpDeadline.outerTimeout}、{@code objectMapper.writeValueAsString}。
     *
     * @param path 本次调用传入的 {@code String}
     * @param body 本次调用传入的 {@code Map<String, Object>}
     * @param type 本次调用传入的 {@code Class<T>}
     * @return 当前记录中的 {@code value}
     */
    public <T> T post(String path, Map<String, Object> body, Class<T> type) {
        AiAvailabilityGuard.Call circuitCall = guard.acquire();

        String requestId = currentRequestId();
        long startedAt = System.nanoTime();
        try {
            @SuppressWarnings("unchecked") Map<String,Object> execution = (Map<String,Object>)body.get("modelExecution");
            long timeoutMs = ModelHttpDeadline.outerTimeout(execution, properties.getReadTimeoutMs());
            String json = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(stripTrailingSlash(properties.getBaseUrl()) + path))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header(REQUEST_ID_HEADER, requestId)
                    .header(INTERNAL_TOKEN_HEADER, internalToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> http = ModelHttpDeadline.run(timeoutMs,
                    () -> httpClient.send(request, HttpResponse.BodyHandlers.ofString()), null);
            int status = http.statusCode();
            if (status >= 200 && status < 400) {
                circuitCall.success();
            } else if (status >= 500) {
                circuitCall.failure();
            } else {
                circuitCall.ignore();
            }
            if (status < 200 || status >= 300) {
                throw new AgentGatewayException(path, requestId, elapsedSince(startedAt),
                        "HTTP " + status + " requestId=" + requestId, null, status, failureCode(http.body(),status));
            }
            T value = objectMapper.readValue(http.body(), type);
            if (value == null) {
                throw new AgentGatewayException(path, requestId, elapsedSince(startedAt), "AI 服务返回空响应体");
            }
            return value;
        } catch (AgentGatewayException e) {
            throw e;
        } catch (InterruptedException e) {
            circuitCall.ignore();
            Thread.currentThread().interrupt();
            throw new AgentGatewayException(path, requestId, elapsedSince(startedAt),
                    "调用被中断（调用方已被取消）", e);
        } catch (Exception e) {
            if (AiFailureClassifier.isUnreachable(e)) {
                circuitCall.failure();
            } else {
                circuitCall.ignore();
            }
            throw new AgentGatewayException(path, requestId, elapsedSince(startedAt), describe(e), e);
        } finally {
            circuitCall.close();
        }
    }

    /**
     * 向 Python 网关发起有时限的流式请求，逐帧交给回调，异常时收拢为网关故障。
     * 上游由 {@code GatewayAgentLlm.respondStream} 调用。
     * 下游调用 {@code guard.acquire}、{@code ModelHttpDeadline.outerTimeout}、{@code objectMapper.writeValueAsString}。
     *
     * @param path 本次调用传入的 {@code String}
     * @param body 本次调用传入的 {@code Map<String, Object>}
     * @param handler 本次调用传入的 {@code SseEventHandler}
     */
    public void postSse(String path, Map<String, Object> body, SseEventHandler handler) {
        AiAvailabilityGuard.Call circuitCall = guard.acquire();

        String requestId = currentRequestId();
        long startedAt = System.nanoTime();
        try {
            @SuppressWarnings("unchecked") Map<String,Object> execution = (Map<String,Object>)body.get("modelExecution");
            long timeoutMs = ModelHttpDeadline.outerTimeout(execution, properties.getReadTimeoutMs());
            String json = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(stripTrailingSlash(properties.getBaseUrl()) + path))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header(REQUEST_ID_HEADER, requestId)
                    .header(INTERNAL_TOKEN_HEADER, internalToken)
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            AtomicReference<InputStream> stream = new AtomicReference<>();
            ModelHttpDeadline.run(timeoutMs, () -> {
                HttpResponse<InputStream> http = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
                stream.set(http.body());
                try (InputStream input = http.body()) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException("模型流已取消");
                    int status = http.statusCode();
                    if (status < 200 || status >= 300) {
                        if (status >= 500) circuitCall.failure(); else circuitCall.ignore();
                        throw new AgentGatewayException(path, requestId, elapsedSince(startedAt),
                                "HTTP " + status + " requestId=" + requestId, null, status,
                                failureCode(new String(input.readNBytes(8192),StandardCharsets.UTF_8),status));
                    }
                    circuitCall.success();
                    readSseFrames(input, handler);
                }
                return null;
            }, stream);
        } catch (AgentGatewayException e) {
            throw e;
        } catch (InterruptedException e) {
            circuitCall.ignore();
            Thread.currentThread().interrupt();
            throw new AgentGatewayException(path, requestId, elapsedSince(startedAt),
                    "调用被中断（调用方已被取消）", e);
        } catch (Exception e) {
            // 首个帧前就失败 = 一次真实的传输失败；帧中途失败由上层决定保留已收到的内容。
            if (AiFailureClassifier.isUnreachable(e)) {
                circuitCall.failure();
            } else {
                circuitCall.ignore();
            }
            throw new AgentGatewayException(path, requestId, elapsedSince(startedAt), describe(e), e);
        } finally {
            circuitCall.close();
        }
    }

    /**
     * 解析 SSE 文本行并分派完整事件，为模型决策保留正确的终止边界。
     * 上游由 {@code AgentGatewayClient.postSse} 调用。
     * 下游调用 {@code reader.readLine}、{@code data.length}、{@code handler.onEvent}。
     *
     * @param stream 本次调用传入的 {@code InputStream}
     * @param handler 本次调用传入的 {@code SseEventHandler}
     */
    private void readSseFrames(InputStream stream, SseEventHandler handler) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String eventName = null;
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (data.length() > 0) {
                        String event = eventName == null ? "message" : eventName;
                        handler.onEvent(event, data.toString());
                        if ("final".equals(event) || "error".equals(event)) return;
                    }
                    eventName = null;
                    data.setLength(0);
                    continue;
                }
                if (line.startsWith(":")) {
                    continue;
                }
                if (line.startsWith("event:")) {
                    eventName = line.substring("event:".length()).trim();
                } else if (line.startsWith("data:")) {
                    if (data.length() > 0) {
                        data.append('\n');
                    }
                    data.append(line.substring("data:".length()).trim());
                }
            }
            // 容错：上游若没发空行收尾，最后一帧仍然要派发。
            if (data.length() > 0) {
                handler.onEvent(eventName == null ? "message" : eventName, data.toString());
            }
        }
    }

    /**
     * 以容错方式读取失败响应正文，避免二次读取故障覆盖传输错误。
     * 下游调用 {@code in.readAllBytes}。
     *
     * @param stream 本次调用传入的 {@code InputStream}
     * @return 供后续处理使用的文本或标识
     */
    private String readQuietly(InputStream stream) {
        try (InputStream in = stream) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /** SSE 帧回调：{@code event} 为事件名，{@code data} 为已拼接的 data 文本。 */
    @FunctionalInterface
    public interface SseEventHandler {
        /**
         * 处理网关传来的一帧 SSE 事件；实现可抛出异常以中止读取。
         * 上游由 {@code AgentGatewayClient.readSseFrames} 调用。
         * @param event 当前帧的事件名称
         * @param data 由该帧全部 data 行拼接出的文本
         */
        void onEvent(String event, String data) throws Exception;
    }

    /**
     * 将异常压缩成用于追踪网关失败的简短诊断。
     * 上游由 {@code AgentGatewayClient.post}、{@code AgentGatewayClient.postSse} 调用。
     *
     * @param e 本次捕获的异常
     * @return 供后续处理使用的文本或标识
     */
    private static String describe(Exception e) { return e.getClass().getSimpleName(); }

    /**
     * 从 HTTP 状态或网关响应解析调用方可识别的失败原因码。
     * 上游由 {@code AgentGatewayClient.post}、{@code AgentGatewayClient.postSse} 调用。
     * 下游调用 {@code objectMapper.readTree}、{@code root.has}、{@code root.path}。
     *
     * @param body 本次调用传入的 {@code String}
     * @param status 本次调用传入的 {@code int}
     * @return 供后续处理使用的文本或标识
     */
    private String failureCode(String body,int status) {
        String fallback = status==504 || status==408 ? "model_timeout" : "model_unavailable";
        try {
            com.fasterxml.jackson.databind.JsonNode root=objectMapper.readTree(body);
            com.fasterxml.jackson.databind.JsonNode fields=root.has("detail") ? root.path("detail") : root;
            String code=fields.path("reasonCode").asText(fields.path("code").asText());
            return java.util.Set.of("model_not_configured","model_configuration_invalid","model_timeout",
                    "query_deadline_exceeded","query_capacity_rejected").contains(code) ? code : fallback;
        } catch (Exception ignored) { return fallback; }
    }

    /**
     * 读取当前请求的关联标识，供 Java 和 Python 诊断串联。
     * 上游由 {@code AgentGatewayClient.post}、{@code AgentGatewayClient.postSse} 调用。
     * 下游调用 {@code RequestIdContext.current}、{@code tracer.currentSpan}、{@code span.context}。
     *
     * @return 供后续处理使用的文本或标识
     */
    private String currentRequestId() {
        String requestId = RequestIdContext.current();
        if (requestId != null) {
            return requestId;
        }
        if (tracer != null) {
            Span span = tracer.currentSpan();
            if (span != null && span.context() != null && span.context().traceId() != null) {
                return span.context().traceId();
            }
        }
        return UUID.randomUUID().toString();
    }

    /**
     * 计算内部网关调用从单调时钟起点到现在的毫秒数。
     * 上游由 {@code AgentGatewayClient.post}、{@code AgentGatewayClient.postSse} 调用。
     *
     * @param startedAtNanos 以单调时钟记录的起始时间，单位纳秒
     * @return 按当前方法口径计算或读取的数值
     */
    private long elapsedSince(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }

    /**
     * 移除基础地址末尾的斜杠，供网关客户端追加固定路由。
     * 上游由 {@code AgentGatewayClient.post}、{@code AgentGatewayClient.postSse} 调用。
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
     * 将用于诊断的正文控制在给定长度以内。
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

    /** 携带接口路径、请求标识、耗时和原因的传输异常，供调用方处理并记录。 */
    public static class AgentGatewayException extends RuntimeException {

        private final String path;

        private final String requestId;

        private final long elapsedMs;

        private final String reason;

        private final int status;

        private final String reasonCode;

        /**
         * 将当前参数交给共有构造入口，并补齐这组重载省略的设置。
         *
         * @param path 本次调用传入的 {@code String}
         * @param requestId 本次调用传入的 {@code String}
         * @param elapsedMs 本次调用传入的 {@code long}
         * @param reason 诊断或终止原因
         */
        public AgentGatewayException(String path, String requestId, long elapsedMs, String reason) {
            this(path, requestId, elapsedMs, reason, null);
        }

        /**
         * 将当前参数交给共有构造入口，并补齐这组重载省略的设置。
         *
         * @param path 本次调用传入的 {@code String}
         * @param requestId 本次调用传入的 {@code String}
         * @param elapsedMs 本次调用传入的 {@code long}
         * @param reason 诊断或终止原因
         * @param cause 本次调用传入的 {@code Throwable}
         */
        public AgentGatewayException(String path, String requestId, long elapsedMs, String reason, Throwable cause) {
            this(path,requestId,elapsedMs,reason,cause,0);
        }

        /**
         * 将当前参数交给共有构造入口，并补齐这组重载省略的设置。
         *
         * @param path 本次调用传入的 {@code String}
         * @param requestId 本次调用传入的 {@code String}
         * @param elapsedMs 本次调用传入的 {@code long}
         * @param reason 诊断或终止原因
         * @param cause 本次调用传入的 {@code Throwable}
         * @param status 本次调用传入的 {@code int}
         */
        public AgentGatewayException(String path, String requestId, long elapsedMs, String reason, Throwable cause, int status) {
            this(path,requestId,elapsedMs,reason,cause,status,status==504 || status==408 ? "model_timeout" : "model_unavailable");
        }

        /**
         * 用注入依赖和构造参数建立此实例执行时使用的状态。
         *
         * @param path 本次调用传入的 {@code String}
         * @param requestId 本次调用传入的 {@code String}
         * @param elapsedMs 本次调用传入的 {@code long}
         * @param reason 诊断或终止原因
         * @param cause 本次调用传入的 {@code Throwable}
         * @param status 本次调用传入的 {@code int}
         * @param reasonCode 供调用方识别的稳定原因码
         */
        public AgentGatewayException(String path, String requestId, long elapsedMs, String reason, Throwable cause, int status, String reasonCode) {
            super(reason, cause);
            this.path = path;
            this.requestId = requestId;
            this.elapsedMs = elapsedMs;
            this.reason = reason;
            this.status = status;
            this.reasonCode = reasonCode;
        }

        /** 返回当前记录保存的 {@code path}。 */
        public String getPath() {
            return path;
        }

        /**
         * 返回当前记录保存的 {@code requestId}。
         * 上游由 {@code GatewayAgentLlm.degraded} 调用。
         */
        public String getRequestId() {
            return requestId;
        }

        /**
         * 返回当前记录保存的 {@code elapsedMs}。
         * 上游由 {@code GatewayAgentLlm.degraded} 调用。
         */
        public long getElapsedMs() {
            return elapsedMs;
        }

        /**
         * 返回当前记录保存的 {@code reason}。
         * 上游由 {@code GatewayAgentLlm.degraded} 调用。
         */
        public String getReason() {
            return reason;
        }

        /**
         * 返回当前记录保存的 {@code status}。
         * 上游由 {@code GatewayAgentLlm.degraded} 调用。
         */
        public int getStatus() { return status; }

        /**
         * 返回当前记录保存的 {@code reasonCode}。
         * 上游由 {@code GatewayAgentLlm.degraded} 调用。
         */
        public String getReasonCode() { return reasonCode; }
    }
}
