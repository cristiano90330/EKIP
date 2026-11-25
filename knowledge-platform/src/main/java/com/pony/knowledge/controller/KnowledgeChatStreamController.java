/* 承接单轮与会话的固定流程问答，将 Python SSE 转发给浏览器。 */

package com.pony.knowledge.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pony.knowledge.service.KnowledgeBaseAccessService;
import com.pony.knowledge.service.rag.PythonRagPipeline;
import com.pony.knowledge.service.rag.RagStreamClient;
import com.pony.knowledge.service.rag.RagStrategy;
import com.pony.knowledge.service.rag.RagSessionService;
import com.pony.knowledge.service.ai.AiServiceClient;
import com.pony.knowledge.utils.CurrentUserUtil;
import com.pony.knowledge.validation.RagInputRules;
import com.pony.knowledge.validation.RagQuestion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.validation.Valid;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单轮及会话问答的固定流程 SSE 入口。
 *
 * <p>会话入口先写入完成回合及获准证据，再发送终态。断连、拒绝执行和上游失败均释放会话租约。
 */
@RestController
@RequestMapping("/knowledge")
public class KnowledgeChatStreamController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeChatStreamController.class);

    private final CurrentUserUtil currentUserUtil;

    private final KnowledgeBaseAccessService knowledgeBaseAccessService;

    private final PythonRagPipeline pythonRagPipeline;

    private final RagStreamClient ragStreamClient;

    private final ObjectMapper objectMapper;

    private final Executor sseExecutor;

    private final long streamTimeoutMs;

    private RagSessionService sessions;

    /**
     * 注入 SSE 会话入口使用的回合与历史管理服务。
     *
     * @param sessions RagSessionService，负责管理服务端问答会话与回合租用，保存有权限读取的历史
     */
    @Autowired
    public void setRagSessions(RagSessionService sessions) { this.sessions = sessions; }

    /**
     * 保存承接单轮与会话的固定流程问答所需的依赖和初始配置。
     * 上游为 Spring 组件装配。
     *
     * @param currentUserUtil 本次操作使用的 CurrentUserUtil 组件或记录
     * @param knowledgeBaseAccessService KnowledgeBaseAccessService，负责约定知识库可读范围和创建者操作的权限判定
     * @param pythonRagPipeline 本次操作使用的 PythonRagPipeline 组件或记录
     * @param ragStreamClient 本次操作使用的 RagStreamClient 组件或记录
     * @param objectMapper 序列化和解析 JSON 使用的组件
     * @param sseExecutor 运行 SSE 回合并负责排队的工作执行器
     * @param streamTimeoutMs 以毫秒计的时间限制
     */
    public KnowledgeChatStreamController(CurrentUserUtil currentUserUtil,
                                         KnowledgeBaseAccessService knowledgeBaseAccessService,
                                         PythonRagPipeline pythonRagPipeline,
                                         RagStreamClient ragStreamClient,
                                         ObjectMapper objectMapper,
                                         @Qualifier("agentTurnExecutor") Executor sseExecutor,
                                         @Value("${ai.stream.timeout-ms:285000}") long streamTimeoutMs) {
        this.currentUserUtil = currentUserUtil;
        this.knowledgeBaseAccessService = knowledgeBaseAccessService;
        this.pythonRagPipeline = pythonRagPipeline;
        this.ragStreamClient = ragStreamClient;
        this.objectMapper = objectMapper;
        this.sseExecutor = sseExecutor;
        this.streamTimeoutMs = streamTimeoutMs;
    }

    /**
     * 解析登录用户的可读范围和页面策略，开启不关联历史的固定流程 SSE。
     * 上游为 HTTP POST /knowledge/ask/stream。
     * 下游为 KnowledgeBaseAccessService.resolveReadableIds、RagStrategy.fromRequest。
     *
     * @param request 当前调用的请求参数
     * @return 持续发送进度或结果、完成后关闭的 SSE 连接
     */
    @PostMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter askStream(@Valid @RequestBody KnowledgeChatController.AskRequest request) {
        validateFixed(request);
        Integer topKValue = request.getTopK();
        int topK = topKValue == null ? RagInputRules.DEFAULT_TOP_K : topKValue;
        List<Long> knowledgeBaseIds = List.copyOf(
                knowledgeBaseAccessService.resolveReadableIds(currentUserUtil.getUserId()));
        RagStrategy strategy = RagStrategy.fromRequest(request.getStrategy());
        var queryRequest = strategy == RagStrategy.DEFAULT
                ? pythonRagPipeline.buildRequest(knowledgeBaseIds, request.getQuestion(), List.of(), topK, true, "fixed")
                : pythonRagPipeline.buildRequest(knowledgeBaseIds, request.getQuestion(), List.of(), topK, "fixed", strategy);
        return openStream(queryRequest, null);
    }

    /**
     * 锁定指定会话的当前回合，以会话范围和历史触发固定流程 SSE。
     * 上游为 HTTP POST /knowledge/sessions/{id}/ask/stream。
     * 下游为 RagSessionService.begin、PythonRagPipeline.buildRequest。
     *
     * @param id 要处理的记录 ID
     * @param request 当前调用的请求参数
     * @return 持续发送进度或结果、完成后关闭的 SSE 连接
     */
    @PostMapping(value = "/sessions/{id}/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamSession(@PathVariable UUID id, @Valid @RequestBody KnowledgeChatController.AskRequest request) {
        validateFixed(request);
        var lease = sessions.begin(currentUserUtil.getUserId(), id);
        try {
            int topK = request.getTopK() == null ? RagInputRules.DEFAULT_TOP_K : request.getTopK();
            var query = pythonRagPipeline.buildRequest(lease.scope(), request.getQuestion(), sessions.context(lease),
                    topK, "fixed", RagStrategy.fromRequest(request.getStrategy()));
            return openStream(query, lease);
        } catch (RuntimeException failure) {
            releaseLease(lease);
            throw failure;
        }
    }

    /**
     * 在建立 SSE 前拒绝不支持的引擎选择。
     * 上游为 KnowledgeChatStreamController.askStream、KnowledgeChatStreamController.streamSession。
     * 下游为 AskRequest.getPipelineEngine。
     *
     * @param request 当前调用的请求参数
     */
    private static void validateFixed(KnowledgeChatController.AskRequest request) {
        String engine = request.getPipelineEngine();
        if (engine != null && !"fixed".equals(engine)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "流式问答仅支持 pipelineEngine=fixed");
        }
    }

    /**
     * 转发获准的 RAG 事件，处理断流、失败终态和会话租约释放。
     * 上游为 KnowledgeChatStreamController.askStream、KnowledgeChatStreamController.streamSession。
     * 下游为 RagStreamClient.stream、RagSessionService.complete。
     *
     * @param queryRequest 包含授权范围、执行策略和生成预算的 Python RAG 请求
     * @param lease 本次取得的处理或问答回合租约
     * @return 持续发送进度或结果、完成后关闭的 SSE 连接
     */
    private SseEmitter openStream(AiServiceClient.RagQueryRequest queryRequest, RagSessionService.TurnLease lease) {
        SseEmitter emitter = new SseEmitter(streamTimeoutMs);
        AtomicBoolean closed = new AtomicBoolean(false);
        AtomicBoolean finished = new AtomicBoolean(false);
        AtomicBoolean leaseReleased = new AtomicBoolean(false);
        Runnable release = () -> {
            if (lease != null && leaseReleased.compareAndSet(false, true)) releaseLease(lease);
        };

        // 后台线程在收到事件和终态时更新会话，回调在连接关闭后统一释放回合。
        FutureTask<Void> work = new FutureTask<>(() -> {
                try {
                    ragStreamClient.stream(queryRequest, (event, data) -> {
                        if (closed.get()) {
                            // 客户端已走：抛出让传输层停下，别再往下算。
                            throw new IllegalStateException("client disconnected");
                        }
                        if (lease != null && "done".equals(event)) {
                            ObjectNode answer = (ObjectNode) objectMapper.readTree(data);
                            answer.set("sources", "success".equals(answer.path("outcome").asText())
                                    && answer.path("evidenceSources").isArray()
                                    ? answer.path("evidenceSources") : objectMapper.createArrayNode());
                            answer.remove("evidenceSources");
                            sessions.complete(lease, queryRequest.getQuestion(), answer);
                        }
                        try {
                            emitter.send(SseEmitter.event().name(event).data(data));
                        } catch (java.io.IOException | IllegalStateException sendFailure) {
                            closed.set(true);
                            throw sendFailure;
                        }
                    });
                    finished.set(true);
                    emitter.complete();
                } catch (Exception e) {
                    finished.set(true);
                    if (!closed.get()) {
                        boolean timeout = isTimeout(e);
                        RagStreamClient.RagStreamException streamFailure = findStreamFailure(e);
                        String outcome = timeout ? "timeout" : "dependency_unavailable";
                        String reasonCode = streamFailure == null ? outcome : streamFailure.getReasonCode();
                        String requestId = streamFailure == null ? null : streamFailure.getRequestId();
                        log.warn("RAG 流式问答异常终止 outcome={} reasonCode={} requestId={}",
                                outcome, reasonCode, requestId);
                        try {
                            Map<String, Object> payload = new LinkedHashMap<>();
                            payload.put("code", outcome);
                            payload.put("outcome", outcome);
                            payload.put("message", timeout ? "问答请求超过总预算" : "问答依赖暂不可用");
                            putIfPresent(payload, "reasonCode", reasonCode);
                            putIfPresent(payload, "failedStage", streamFailure == null
                                    ? null : streamFailure.getFailedStage());
                            putIfPresent(payload, "requestId", requestId);
                            emitter.send(SseEmitter.event().name("error")
                                    .data(objectMapper.writeValueAsString(payload)));
                            emitter.complete();
                        } catch (Exception ignored) {
                            // 写失败交给容器的异步错误通知，不重复 completeWithError。
                            closed.set(true);
                        }
                    }
                } finally {
                    release.run();
                }
                return null;
        });
        Runnable cancel = () -> {
            closed.set(true);
            if (!finished.get()) work.cancel(true);
            release.run();
        };
        emitter.onTimeout(cancel);
        emitter.onError(error -> cancel.run());
        emitter.onCompletion(cancel);

        try {
            sseExecutor.execute(work);
        } catch (RejectedExecutionException e) {
            work.cancel(false);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "并发已满，请稍后重试");
        }
        return emitter;
    }

    /**
     * 归还已占用的问答会话回合。
     * 上游为 KnowledgeChatStreamController.streamSession、KnowledgeChatStreamController.openStream。
     * 下游为 RagSessionService.end、TurnLease.id。
     *
     * @param lease 本次取得的处理或问答回合租约
     */
    private void releaseLease(RagSessionService.TurnLease lease) {
        try { sessions.end(lease); }
        catch (RuntimeException failure) { log.warn("普通问答会话租约释放失败 sessionId={}", lease.id(), failure); }
    }

    /**
     * 辨认直接或封装在异常链中的超时。
     * 上游为 KnowledgeChatStreamController.openStream。
     *
     * @param error 本次执行捕获的异常
     * @return 异常链含已知网络或执行超时时为 true
     */
    private static boolean isTimeout(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.net.http.HttpTimeoutException
                    || cause instanceof java.util.concurrent.TimeoutException
                    || cause instanceof java.net.SocketTimeoutException
                    || cause instanceof RagStreamClient.RagStreamException stream
                       && ("timeout".equals(stream.getOutcome()) || stream.getStatus() == 408 || stream.getStatus() == 504)
                    || cause instanceof com.pony.knowledge.exception.RagQueryFailureException rag
                       && "timeout".equals(rag.getOutcome())) return true;
        }
        return false;
    }

    /**
     * 从异常链提取含状态、原因和阶段的流式失败。
     * 上游为 KnowledgeChatStreamController.openStream。
     *
     * @param error 本次执行捕获的异常
     * @return 最先找到的流式异常，异常链无匹配时为空
     */
    private static RagStreamClient.RagStreamException findStreamFailure(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof RagStreamClient.RagStreamException stream) return stream;
        }
        return null;
    }

    /**
     * 仅在诊断值非空时附加到事件数据。
     * 上游为 KnowledgeChatStreamController.openStream。
     *
     * @param payload 含当前操作所需属性的映射
     * @param key 本方法计算或判断使用的 key 值
     * @param value 待校验或转换的值
     */
    private static void putIfPresent(Map<String, Object> payload, String key, String value) {
        if (value != null && !value.isBlank()) payload.put(key, value);
    }
}
