/* 连接 Python 问答 SSE，校验执行策略并传递协议帧与失败终态。 */

package com.pony.knowledge.service.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pony.knowledge.request.RequestIdContext;
import com.pony.knowledge.resilience.AiAvailabilityGuard;
import com.pony.knowledge.resilience.AiFailureClassifier;
import com.pony.knowledge.service.ai.AiServiceClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import com.pony.knowledge.modelmanagement.ModelHttpDeadline;

/**
 * 转发 Python {@code POST /rag/query/stream} 的 SSE 帧。
 *
 * <p>为什么单独成类而不是加在 {@code AiServiceClient}：后者用 Spring {@code RestClient}，
 * 它的响应转换是「读完整个响应体」的语义，{{@code BodyHandlers.ofString()}} 那种用法会把流式
 * 变成一次性。这里用 JDK {@code HttpClient} + {@code ofInputStream()} 逐行读帧，
 * 且不改动既有客户端的构造器（55 处测试与调用方以它为准）。
 *
 * <p>与 {@code AiServiceClient} 共享同一个 {@link AiAvailabilityGuard}：同一份「AI 服务是否可用」
 * 状态，不能因为换了传输方式就出现两套熔断。
 *
 * <p>本类只做传输与帧切分，不解释业务字段：{@code stage / sources / delta / done / error}
 * 原样交给上层，由上层决定下发形态。
 */
@Component
public class RagStreamClient {

    private static final String REQUEST_ID_HEADER = "X-Request-ID";

    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    private static final int MAX_ERROR_BODY_BYTES = 64 * 1024;

    private static final Pattern SAFE_DIAGNOSTIC = Pattern.compile("[A-Za-z0-9_.:-]{1,128}");

    private final String baseUrl;

    private final String internalToken;

    private final ObjectMapper objectMapper;

    private final AiAvailabilityGuard guard;

    private final HttpClient httpClient;

    private final int readTimeoutMs;

    /**
     * 保存连接 Python 问答 SSE所需的依赖和初始配置。
     * 上游为 Spring 组件装配。
     * 下游为 HttpClient.newBuilder。
     *
     * @param baseUrl 下游接口地址
     * @param connectTimeoutMs 以毫秒计的时间限制
     * @param readTimeoutMs 以毫秒计的时间限制
     * @param internalToken 待传递或核对的令牌
     * @param objectMapper 序列化和解析 JSON 使用的组件
     * @param guard 本次操作使用的 AiAvailabilityGuard 组件或记录
     */
    public RagStreamClient(
            @Value("${ai.client.base-url:http://127.0.0.1:8000}") String baseUrl,
            @Value("${ai.client.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${ai.client.query-read-timeout-ms:${ai.client.read-timeout-ms}}") int readTimeoutMs,
            @Value("${ai.client.internal-token}") String internalToken,
            ObjectMapper objectMapper,
            AiAvailabilityGuard guard) {
        this.baseUrl = baseUrl;
        this.readTimeoutMs = readTimeoutMs;
        this.internalToken = internalToken;
        this.objectMapper = objectMapper;
        this.guard = guard;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
    }

    /**
     * 在连接超时与执行预算内读取 Python SSE，并将帧交给回调。
     * 上游为 KnowledgeChatStreamController.openStream。
     * 下游为 AiAvailabilityGuard.acquire、ModelHttpDeadline.outerTimeout。
     *
     * @param request 当前调用的请求参数
     * @param handler 接收解析后事件的处理函数
     */
    public void stream(AiServiceClient.RagQueryRequest request, FrameHandler handler) {
        AiAvailabilityGuard.Call circuitCall = guard.acquire();
        long startedAt = System.nanoTime();
        String requestId = currentRequestId();

        try {
            long timeoutMs = ModelHttpDeadline.outerTimeout(request.getModelExecution(),Math.max(readTimeoutMs,40000));
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(stripTrailingSlash(baseUrl) + "/rag/query/stream"))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header(REQUEST_ID_HEADER, requestId)
                    .header(INTERNAL_TOKEN_HEADER, internalToken)
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
                    .build();

            AtomicReference<InputStream> stream = new AtomicReference<>();
            ModelHttpDeadline.run(timeoutMs, () -> {
                HttpResponse<InputStream> response = httpClient.send(
                        httpRequest, HttpResponse.BodyHandlers.ofInputStream());
                stream.set(response.body());
                try (InputStream input = response.body()) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException("模型流已取消");
                    int status = response.statusCode();
                    if (status < 200 || status >= 300) {
                        if (status >= 500) circuitCall.failure(); else circuitCall.ignore();
                        ErrorFields fields = parseErrorFields(readQuietly(input));
                        String responseRequestId = response.headers().firstValue(REQUEST_ID_HEADER).orElse(null);
                        String outcome = allowedOutcome(fields.outcome())
                                ? fields.outcome()
                                : (status == 408 || status == 504 ? "timeout" : "dependency_unavailable");
                        String reasonCode = firstSafe(fields.reasonCode(), "ai_http_" + status);
                        String failedStage = firstSafe(fields.failedStage(),
                                status == 400 || status == 422 ? "ai.request_validation" : "ai.transport");
                        String effectiveRequestId = firstSafe(fields.requestId(), responseRequestId, requestId);
                        throw new RagStreamException(status, outcome, reasonCode, failedStage,
                                effectiveRequestId, "流式问答依赖失败：HTTP " + status);
                    }
                    circuitCall.success();
                    readFrames(input, guardedStrategy(request, handler, requestId), requestId);
                }
                return null;
            },stream);
        } catch (RagStreamException e) {
            throw e;
        } catch (InterruptedException e) {
            circuitCall.ignore();
            Thread.currentThread().interrupt();
            throw new RagStreamException(0, "cancelled", "request_cancelled", "ai.transport", requestId,
                    "流式问答被中断（调用方已取消）", e);
        } catch (java.net.http.HttpTimeoutException e) {
            if (AiFailureClassifier.isUnreachable(e)) circuitCall.failure(); else circuitCall.ignore();
            throw new RagStreamException(504, "timeout", "query_deadline_exceeded", "ai.transport",
                    requestId, "流式问答超过总预算", e);
        } catch (Exception e) {
            if (AiFailureClassifier.isUnreachable(e)) {
                circuitCall.failure();
            } else {
                circuitCall.ignore();
            }
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
            throw new RagStreamException(0, "dependency_unavailable", "ai_transport_error", "ai.transport",
                    requestId, "流式问答失败（" + elapsedMs + "ms）: " + e.getClass().getSimpleName(), e);
        } finally {
            circuitCall.close();
        }
    }

    /**
     * 包装流式回调，先验证上游实际执行策略再转发内容。
     * 上游为 RagStreamClient.stream。
     * 下游为 RagQueryRequest.getEvidenceMode。
     *
     * @param request 当前调用的请求参数
     * @param consumer 本次操作使用的 FrameHandler 组件或记录
     * @param requestId 关联客户端请求与日志的请求标识
     * @return 验证执行策略后才向原订阅者转发的帧处理器
     */
    private FrameHandler guardedStrategy(AiServiceClient.RagQueryRequest request, FrameHandler consumer, String requestId) {
        if (request.getEvidenceMode() == null && !"langchain".equals(request.getPipelineEngine())) return consumer;
        return new FrameHandler() {
            private boolean confirmed;

            /**
             * 将收到的 SSE 事件和数据交给当前订阅回调。
             * 上游为 RagStreamClient.readFrames。
             *
             * @param event 用于区分 SSE 帧语义的事件名
             * @param data 当前调用需要包装或转交的数据
             */
            @Override
            public void onFrame(String event, String data) throws Exception {
                if ("execution".equals(event)) {
                    verifyExecution(request, data, requestId);
                    confirmed = true;
                    return;
                }
                if ("delta".equals(event) || "sources".equals(event) || "done".equals(event)) {
                    if (!confirmed) throw strategyMismatch(requestId);
                    if ("done".equals(event)) verifyExecution(request, data, requestId);
                }
                consumer.onFrame(event, data);
            }
        };
    }

    /**
     * 核对 Python 实际使用的引擎、检索版本和请求显式指定的证据策略。
     * 下游为 RagQueryRequest.getPipelineEngine、RagQueryRequest.getVersion。
     *
     * @param request 当前调用的请求参数
     * @param data 当前调用需要包装或转交的数据
     * @param requestId 关联客户端请求与日志的请求标识
     */
    private void verifyExecution(AiServiceClient.RagQueryRequest request, String data, String requestId) {
        try {
            var execution = objectMapper.readTree(data);
            if (execution != null && request.getPipelineEngine().equals(execution.path("pipelineEngine").asText())
                    && request.getVersion().equals(execution.path("retrievalVersion").asText())
                    && (request.getEvidenceMode() == null
                        || request.getEvidenceMode().equals(execution.path("evidenceMode").asText()))) {
                return;
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
            // A malformed acknowledgement is a contract error before content is forwarded.
        }
        throw strategyMismatch(requestId);
    }

    /**
     * 构建服务端实际执行策略与请求不符的流式错误。
     * 上游为 RagStreamClient.verifyExecution。
     *
     * @param requestId 关联客户端请求与日志的请求标识
     * @return 本次计算或查询得到的 RagStreamException 结果
     */
    private static RagStreamException strategyMismatch(String requestId) {
        return new RagStreamException(503, "dependency_unavailable", "ai_contract_mismatch", "ai.response_contract",
                requestId, "AI 未确认本次问答的实际策略");
    }

    /**
     * 逐行解析 SSE 事件和数据，等待合法终态并识别流中断。
     * 上游为 RagStreamClient.stream。
     * 下游为 FrameHandler.onFrame。
     *
     * @param stream 待消费或关闭的上游响应流
     * @param handler 接收解析后事件的处理函数
     * @param requestId 关联客户端请求与日志的请求标识
     */
    private void readFrames(InputStream stream, FrameHandler handler, String requestId) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String eventName = null;
            StringBuilder data = new StringBuilder();
            String line;

            // 按 SSE 的空行边界聚合事件，避免将多行数据或心跳误当成生效结果。
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (data.length() > 0) {
                        String event = eventName == null ? "message" : eventName;
                        handler.onFrame(event, data.toString());
                        if (isTerminal(event)) return;
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
            if (data.length() > 0) {
                String event = eventName == null ? "message" : eventName;
                handler.onFrame(event, data.toString());
                if (isTerminal(event)) return;
            }
            throw new RagStreamException(503, "dependency_unavailable", "ai_stream_incomplete", "ai.stream",
                    requestId, "流式问答在终态前中断");
        }
    }

    /**
     * 判断事件是否会终止本次 SSE。
     * 上游为 RagStreamClient.readFrames。
     *
     * @param event 用于区分 SSE 帧语义的事件名
     * @return 条件是否成立（判断事件是否会终止本次 SSE）
     */
    private static boolean isTerminal(String event) {
        return "done".equals(event) || "error".equals(event);
    }

    /**
     * 限长读取上游错误体，读取异常或超长时返回空文本。
     * 上游为 RagStreamClient.stream。
     *
     * @param stream 待消费或关闭的上游响应流
     * @return 限长读取的 UTF-8 错误正文，超长或读取失败时为空文本
     */
    private String readQuietly(InputStream stream) {
        try {
            byte[] bytes = stream.readNBytes(MAX_ERROR_BODY_BYTES + 1);
            if (bytes.length > MAX_ERROR_BODY_BYTES) return "";
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 拾取可对外传播的上游错误字段并处理无效数据。
     * 上游为 RagStreamClient.stream。
     * 下游为 RagStreamClient.safeField。
     *
     * @param body 调用方传入的请求体
     * @return 包含允许公开的结果、原因和阶段的诊断字段
     */
    private ErrorFields parseErrorFields(String body) {
        if (body == null || body.isBlank()) return ErrorFields.EMPTY;
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode detail = root.path("detail");
            JsonNode error = root.path("error");
            return new ErrorFields(
                    safeField(root, detail, error, "outcome"),
                    safeField(root, detail, error, "reasonCode", "reason_code", "code"),
                    safeField(root, detail, error, "failedStage", "failed_stage"),
                    safeField(root, detail, error, "requestId", "request_id"));
        } catch (Exception ignored) {
            return ErrorFields.EMPTY;
        }
    }

    /**
     * 只保留符合允许格式的上游诊断字段。
     * 上游为 RagStreamClient.parseErrorFields。
     *
     * @param root 本次操作所用的 JsonNode 数据
     * @param detail 本次操作所用的 JsonNode 数据
     * @param error 本次执行捕获的异常
     * @param names 需要本方法按序读取或筛选的 String[] 数据
     * @return 通过允许格式校验的诊断字段，非法值为空
     */
    private static String safeField(JsonNode root, JsonNode detail, JsonNode error, String... names) {
        for (String name : names) {
            for (JsonNode source : new JsonNode[]{root, detail, error}) {
                JsonNode value = source == null ? null : source.get(name);
                if (value != null && value.isTextual()) {
                    String text = value.asText();
                    if (SAFE_DIAGNOSTIC.matcher(text).matches()) return text;
                }
            }
        }
        return null;
    }

    /**
     * 按流式失败协议筛选可以给调用方展示的结果状态。
     * 上游为 RagStreamClient.stream。
     *
     * @param outcome 查询或操作的业务结果状态
     * @return 流式错误协议允许展示的结果值
     */
    private static boolean allowedOutcome(String outcome) {
        return "timeout".equals(outcome) || "dependency_unavailable".equals(outcome);
    }

    /**
     * 按优先级挑选首个能公开展示的诊断信息。
     * 上游为 RagStreamClient.stream。
     *
     * @param values 需要本方法按序读取或筛选的 String[] 数据
     * @return 首个通过安全格式检查的非空字段
     */
    private static String firstSafe(String... values) {
        for (String value : values) {
            if (value != null && SAFE_DIAGNOSTIC.matcher(value).matches()) return value;
        }
        return null;
    }

    private record ErrorFields(String outcome, String reasonCode, String failedStage, String requestId) {
        private static final ErrorFields EMPTY = new ErrorFields(null, null, null, null);
    }

    /**
     * 选择转发给 AI 以及错误帧关联使用的请求标识。
     * 上游为 RagStreamClient.stream。
     * 下游为 RequestIdContext.current。
     *
     * @return 透传给 Python 的请求标识
     */
    private String currentRequestId() {
        String requestId = RequestIdContext.current();
        return requestId != null ? requestId : UUID.randomUUID().toString();
    }

    /**
     * 规整端点地址以免发生重复路径分隔。
     * 上游为 RagStreamClient.stream。
     *
     * @param url 本方法计算或判断使用的 url 值
     * @return 去掉末尾路径分隔符后的端点地址
     */
    private String stripTrailingSlash(String url) {
        if (url == null) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** SSE 帧回调。 */
    @FunctionalInterface
    public interface FrameHandler {
        /**
         * 将收到的 SSE 事件和数据交给当前订阅回调。
         * 上游为 RagStreamClient.readFrames。
         *
         * @param event 用于区分 SSE 帧语义的事件名
         * @param data 当前调用需要包装或转交的数据
         */
        void onFrame(String event, String data) throws Exception;
    }

    /** 流式问答的传输异常（携带 HTTP 状态，0 表示未拿到响应）。 */
    public static class RagStreamException extends RuntimeException {

        private final int status;

        private final String outcome;

        private final String reasonCode;

        private final String failedStage;

        private final String requestId;

        /**
         * 创建带状态和诊断信息的失败异常，供调用方映射响应。
         *
         * @param status 要读取或写入的状态
         * @param message 用于构建结果或异常的说明文字
         */
        public RagStreamException(int status, String message) {
            this(status, status == 408 || status == 504 ? "timeout" : "dependency_unavailable",
                    "ai_stream_error", "ai.transport", null, message, null);
        }

        /**
         * 创建带状态和诊断信息的失败异常，供调用方映射响应。
         *
         * @param status 要读取或写入的状态
         * @param message 用于构建结果或异常的说明文字
         * @param cause 当前流程捕获的 Throwable 异常
         */
        public RagStreamException(int status, String message, Throwable cause) {
            this(status, status == 408 || status == 504 ? "timeout" : "dependency_unavailable",
                    "ai_stream_error", "ai.transport", null, message, cause);
        }

        /**
         * 创建带状态和诊断信息的失败异常，供调用方映射响应。
         *
         * @param status 要读取或写入的状态
         * @param outcome 查询或操作的业务结果状态
         * @param reasonCode 用于区分失败或降级路径的原因码
         * @param failedStage 故障发生的执行阶段
         * @param requestId 关联客户端请求与日志的请求标识
         * @param message 用于构建结果或异常的说明文字
         */
        public RagStreamException(int status, String outcome, String reasonCode, String failedStage,
                                  String requestId, String message) {
            this(status, outcome, reasonCode, failedStage, requestId, message, null);
        }

        /**
         * 创建带状态和诊断信息的失败异常，供调用方映射响应。
         *
         * @param status 要读取或写入的状态
         * @param outcome 查询或操作的业务结果状态
         * @param reasonCode 用于区分失败或降级路径的原因码
         * @param failedStage 故障发生的执行阶段
         * @param requestId 关联客户端请求与日志的请求标识
         * @param message 用于构建结果或异常的说明文字
         * @param cause 当前流程捕获的 Throwable 异常
         */
        public RagStreamException(int status, String outcome, String reasonCode, String failedStage,
                                  String requestId, String message, Throwable cause) {
            super(message, cause);
            this.status = status;
            this.outcome = outcome;
            this.reasonCode = reasonCode;
            this.failedStage = failedStage;
            this.requestId = requestId;
        }

        /** 返回本次失败的 HTTP 状态或记录状态。 */
        public int getStatus() {
            return status;
        }

        /** 返回执行结果的业务分类。 */
        public String getOutcome() { return outcome; }

        /** 返回定位执行结果的原因码。 */
        public String getReasonCode() { return reasonCode; }

        /** 返回失败阶段。 */
        public String getFailedStage() { return failedStage; }

        /** 返回关联日志和观测记录的请求标识。 */
        public String getRequestId() { return requestId; }
    }
}
