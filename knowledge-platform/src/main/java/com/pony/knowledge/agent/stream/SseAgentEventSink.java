/* 把 Agent 事件与心跳写入 SSE，并同步关闭连接、运行取消与定时任务。 */

package com.pony.knowledge.agent.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 把 Agent 事件写成 SSE 帧：{@code event: <type>} + 单行 JSON {@code data: {...}}。
 *
 * <p>三条设计约束：
 * <ol>
 *   <li><b>发送失败不等于回合失败</b>：客户端关页面后 {@code emitter.send} 会抛异常，
 *       这里吞掉并把 sink 标记为关闭，同时触发 {@code onDisconnect}（用于取消回合）——
 *       让「用户走了」这件事以「取消」而不是「异常」的形式传到 runtime。</li>
 *   <li><b>心跳</b>：模型思考期可能几十秒没有正文，中间代理会判空闲断连。
 *       用一个共享的守护线程池每 N 秒发一条 SSE 注释帧（客户端忽略）。</li>
 *   <li><b>幂等收尾</b>：{@link #complete()} / {@link #completeWithError(Throwable)} 只生效一次，
 *       避免 runtime 终止路径与 onError 各调一次导致 {@code SseEmitter} 抛已完成的异常。</li>
 * </ol>
 *
 * <p>本类不依赖 Micrometer / Trace：观测在本轮改造中冻结，SSE 事件是运行期唯一线索，
 * 因此每个事件打一行 info 日志（参数已在上游截断）。
 */
public class SseAgentEventSink implements AgentEventSink {

    private static final Logger log = LoggerFactory.getLogger(SseAgentEventSink.class);

    /** 心跳线程池：所有 SSE 连接共享，守护线程不阻止 JVM 退出。 */
    private static final ScheduledExecutorService HEARTBEAT = Executors.newSingleThreadScheduledExecutor(
            runnable -> {
                Thread thread = new Thread(runnable, "agent-sse-heartbeat");
                thread.setDaemon(true);
                return thread;
            });

    private final SseEmitter emitter;

    private final ObjectMapper objectMapper;

    /** 客户端断开时运行一次：通常是 {@code context::cancel}。装配顺序决定它可能后于连接建立。 */
    private volatile Runnable onDisconnect;

    private final ScheduledFuture<?> heartbeat;

    /** 关闭标志：一旦置位，后续发送与收尾全部跳过（客户端可能只是网络抖动）。 */
    private volatile boolean closed;

    /** 收尾是否已执行，保证 complete/error 只有第一次生效。 */
    private volatile boolean finished;

    /**
     * 用注入依赖和构造参数建立此实例执行时使用的状态。
     * 下游调用 {@code HEARTBEAT.scheduleAtFixedRate}、{@code this::sendHeartbeat}。
     *
     * @param emitter 本次调用传入的 {@code SseEmitter}
     * @param objectMapper 负责 JSON 协议编码和解码的映射器
     * @param heartbeatMs 本次调用传入的 {@code long}
     * @param onDisconnect 本次调用传入的 {@code Runnable}
     */
    public SseAgentEventSink(SseEmitter emitter, ObjectMapper objectMapper, long heartbeatMs,
                             Runnable onDisconnect) {
        this.emitter = emitter;
        this.objectMapper = objectMapper;
        this.onDisconnect = onDisconnect;
        this.heartbeat = heartbeatMs <= 0 ? null : HEARTBEAT.scheduleAtFixedRate(
                this::sendHeartbeat, heartbeatMs, heartbeatMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 注册连接失活时要触发的回合取消动作。
     * 下游调用 {@code hook.run}。
     *
     * @param hook 本次调用传入的 {@code Runnable}
     */
    public void onDisconnect(Runnable hook) {
        this.onDisconnect = hook;
        if (closed && hook != null) {
            hook.run();
        }
    }

    /**
     * 关闭本次 SSE 事件接收并通知运行时取消与心跳停止。
     * 上游由 {@code AgentController.chatStream} 调用。
     * 下游调用 {@code markClosed}。
     *
     * @param reason 诊断或终止原因
     */
    public void abort(String reason) {
        if (!finished) {
            markClosed(reason);
        }
    }

    /**
     * 把事件写给当前 SSE 连接，发送失败时进入关闭和取消流程。
     * 上游由 {@code SseAgentEventSink.completeWithError} 调用。
     * 下游调用 {@code objectMapper.writeValueAsString}、{@code emitter.send}、{@code SseEmitter.event}。
     *
     * @param event 本次调用传入的 {@code AgentEvent}
     */
    @Override
    public void emit(AgentEvent event) {
        if (closed) {
            return;
        }
        try {
            String payload = objectMapper.writeValueAsString(event.getData());
            emitter.send(SseEmitter.event().name(event.getType()).data(payload));
            log.info("agent-sse event={} data={}", event.getType(), abbreviate(payload));
        } catch (Exception e) {
            // 客户端已断开：Mark 关闭 + 取消回合。这里不向上抛，避免把「用户关页面」变成回合失败。
            markClosed("发送事件失败（客户端可能已断开）: " + e.getMessage());
        }
    }

    /**
     * 成功结束 SSE 响应并收尾连接状态与心跳。
     * 上游由 {@code AgentController.chatStream} 调用。
     * 下游调用 {@code emitter.complete}、{@code cancelHeartbeat}。
     */
    public void complete() {
        if (finished) {
            return;
        }
        finished = true;
        cancelHeartbeat();
        if (!closed) {
            try {
                emitter.complete();
            } catch (Exception e) {
                log.debug("agent-sse complete 失败（可忽略）: {}", e.getMessage());
            }
        }
    }

    /**
     * 结束已发生错误的 SSE 响应，并清理心跳与连接状态。
     * 上游由 {@code AgentController.chatStream} 调用。
     * 下游调用 {@code emitter.complete}、{@code emit}、{@code cancelHeartbeat}。
     *
     * @param error 用于分类与诊断的异常
     */
    public void completeWithError(Throwable error) {
        if (finished) {
            return;
        }
        // 异常文本可能包含上游响应、地址或内部实现信息；SSE 只发送安全的通用终态。
        emit(AgentEvent.of(AgentEvent.ERROR, "message", "Agent 回合暂时无法完成，请稍后重试。"));
        finished = true;
        cancelHeartbeat();
        if (!closed) {
            try {
                // error 帧已表达终态，正常结束异步响应，避免再次分派成 JSON 错误。
                emitter.complete();
            } catch (Exception e) {
                log.debug("agent-sse completeWithError 失败（可忽略）: {}", e.getMessage());
            }
        }
    }

    /**
     * 向仍活动的连接发送心跳，帮助识别已失活的传输。
     * 上游由 {@code SseAgentEventSink.SseAgentEventSink} 调用。
     * 下游调用 {@code emitter.send}、{@code SseEmitter.event}、{@code markClosed}。
     */
    private void sendHeartbeat() {
        if (closed || finished) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().comment("ping"));
        } catch (Exception e) {
            markClosed("心跳发送失败（客户端可能已断开）: " + e.getMessage());
        }
    }

    /**
     * 原子切换为关闭状态，并统一清理心跳和断连回调。
     * 上游由 {@code SseAgentEventSink.abort}、{@code SseAgentEventSink.emit}、{@code SseAgentEventSink.sendHeartbeat} 调用。
     * 下游调用 {@code onDisconnect.run}、{@code cancelHeartbeat}。
     *
     * @param reason 诊断或终止原因
     */
    private void markClosed(String reason) {
        if (closed) {
            return;
        }
        closed = true;
        cancelHeartbeat();
        log.info("agent-sse 连接关闭: {}", reason);
        if (onDisconnect != null) {
            try {
                onDisconnect.run();
            } catch (Exception e) {
                log.warn("agent-sse 取消回合失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 取消服务端定时发送心跳的任务。
     * 上游由 {@code SseAgentEventSink.complete}、{@code SseAgentEventSink.completeWithError}、{@code SseAgentEventSink.markClosed} 调用。
     * 下游调用 {@code heartbeat.cancel}。
     */
    private void cancelHeartbeat() {
        if (heartbeat != null) {
            heartbeat.cancel(false);
        }
    }

    /**
     * 限制调试日志中 SSE 发送异常的详细文本长度。
     * 上游由 {@code SseAgentEventSink.emit} 调用。
     * 下游调用 {@code text.length}。
     *
     * @param text 待处理正文
     * @return 供后续处理使用的文本或标识
     */
    private static String abbreviate(String text) {
        if (text == null || text.length() <= 200) {
            return text;
        }
        return text.substring(0, 200) + "...";
    }
}
