/* 接收 Agent 同步和 SSE 问答请求并管理流式任务生命周期。 */

package com.pony.knowledge.agent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pony.knowledge.agent.model.AgentRequest;
import com.pony.knowledge.agent.model.AgentResponse;
import com.pony.knowledge.agent.service.AgentService;
import com.pony.knowledge.agent.stream.SseAgentEventSink;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 将对话请求交给 AgentService；当前用户与知识库访问范围由服务端解析。
 *
 * <p>两个入口共用同一段编排，只换出口：
 * <ul>
 *   <li>{@code POST /agent/chat}：完整 JSON（既有契约，行为不变）；</li>
 *   <li>{@code POST /agent/chat/stream}：SSE，事件见 {@code AgentEvent}。</li>
 * </ul>
 *
 * <p>流式入口的三条约定：
 * <ol>
 *   <li>先返回 emitter，再把回合提交到 {@code agentTurnExecutor}——SSE 不允许「先算完再建流」；</li>
 *   <li>线程池拒绝立即可见：直接发一帧 error，不让客户端挂着等一个不会开始的回合；</li>
 *   <li>超时/异常一律走 sink 收尾，保证 {@code SseEmitter} 不会被重复 complete。</li>
 * </ol>
 */
@RestController
@RequestMapping("/agent")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final AgentService agentService;

    private final ObjectMapper objectMapper;

    private final Executor agentTurnExecutor;

    /** 单条流的存活上限：必须大于 agent.budget-time-ms，否则兜底合成还没跑完连接就被回收。 */
    private final long streamTimeoutMs;

    /** 心跳间隔：模型思考期可能几十秒没有正文，靠注释帧防中间层判空闲。 */
    private final long heartbeatMs;

    /**
     * 用注入依赖和构造参数建立此实例执行时使用的状态。
     *
     * @param agentService 本次调用传入的 {@code AgentService}
     * @param objectMapper 负责 JSON 协议编码和解码的映射器
     * @param agentTurnExecutor 本次调用传入的 {@code Executor}
     * @param streamTimeoutMs 本次调用传入的 {@code long}
     * @param heartbeatMs 本次调用传入的 {@code long}
     */
    public AgentController(AgentService agentService,
                           ObjectMapper objectMapper,
                           @Qualifier("agentTurnExecutor") Executor agentTurnExecutor,
                           @Value("${agent.stream.timeout-ms:180000}") long streamTimeoutMs,
                           @Value("${agent.stream.heartbeat-ms:15000}") long heartbeatMs) {
        this.agentService = agentService;
        this.objectMapper = objectMapper;
        this.agentTurnExecutor = agentTurnExecutor;
        this.streamTimeoutMs = streamTimeoutMs;
        this.heartbeatMs = heartbeatMs;
    }

    /**
     * 接收经过请求校验的同步问答，由 AgentService.chat 执行后包装为 HTTP 结果。
     * 上游由 {@code frontend/src/api/agent.ts 的同步 Agent 请求} 调用。
     * 下游调用 {@code agentService.chat}。
     *
     * @param request 包含服务端会话 ID、当前问题及可选 Agent 配置 ID 的问答请求
     * @return 带答案、工具轨迹、来源和停止原因的回合结果
     */
    @PostMapping("/chat")
    public AgentResponse chat(
            @Valid @RequestBody AgentRequest request) {

        return agentService.chat(request);
    }

    /**
     * 为浏览器问答建立 SSE 响应和独立工作任务，并在完成、取消或传输失败时收尾。
     * 上游由 {@code frontend/src/api/agent.ts 的 SSE Agent 请求} 调用。
     * 下游调用 {@code emitter.onTimeout}、{@code sink.abort}、{@code emitter.onError}。
     *
     * @param request 包含服务端会话 ID、当前问题及可选 Agent 配置 ID 的问答请求
     * @return 当前记录中的 {@code emitter}
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@Valid @RequestBody AgentRequest request) {
        SseEmitter emitter = new SseEmitter(streamTimeoutMs);
        SseAgentEventSink sink = new SseAgentEventSink(emitter, objectMapper, heartbeatMs, null);

        emitter.onTimeout(() -> sink.abort("SSE 超时（超过 " + streamTimeoutMs + "ms）"));
        emitter.onError(error -> sink.abort("SSE 传输异常: " + error.getMessage()));
        emitter.onCompletion(() -> sink.abort("SSE 连接已结束"));

        try {
            agentTurnExecutor.execute(() -> {
                try {
                    agentService.chatStream(request, sink);
                    sink.complete();
                } catch (Exception e) {
                    log.warn("Agent 流式回合异常终止: {}", e.getMessage(), e);
                    sink.completeWithError(e);
                }
            });
        } catch (RejectedExecutionException e) {
            sink.completeWithError(new IllegalStateException("Agent 并发已满，请稍后重试"));
        }

        return emitter;
    }
}
