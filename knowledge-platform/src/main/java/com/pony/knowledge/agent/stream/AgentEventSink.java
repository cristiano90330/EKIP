/* 定义运行时写出事件及感知接收方连接状态的接口。 */

package com.pony.knowledge.agent.stream;

/**
 * 事件出口。runtime 只认这个接口，不认 HTTP / SSE，也不认 Spring MVC。
 *
 * <p>两条实现：
 * <ul>
 *   <li>{@link #NOOP}：同步接口 {@code POST /agent/chat} 使用，事件全部丢弃，零成本；</li>
 *   <li>{@link SseAgentEventSink}：流式接口 {@code POST /agent/chat/stream} 使用，写 {@code SseEmitter}。</li>
 * </ul>
 *
 * <p>{@link #isActive()} 是 runtime 唯一需要分支的地方：只有真的有人在听时才走流式模型调用
 * （逐字转发），否则走同步调用——否则单测里的 mock LLM 与直连档都会被迫实现流式。
 */
public interface AgentEventSink {

    /** 丢弃全部事件：非流式调用与单测使用。 */
    AgentEventSink NOOP = new AgentEventSink() {
        /**
         * 将事件交给下游接收方。
         * @param event 本次调用传入的 {@code AgentEvent}
         */
        @Override
        public void emit(AgentEvent event) {
            // 非流式调用：无人在听，刻意什么都不做。
        }

        /** 检查当前输入是否满足该条件。 */
        @Override
        public boolean isActive() {
            return false;
        }
    };

    /**
     * 将当前回合生成的事件交给接收实现发送。
     * 上游由 {@code BufferedTerminalSink.emit}、{@code BufferedTerminalSink.flush}、{@code DefaultAgentRuntime.emitDelta} 调用。
     *
     * @param event 本次调用传入的 {@code AgentEvent}
     */
    void emit(AgentEvent event);

    /**
     * 告知运行时当前事件出口是否活动，用于选择是否启用流式调用。
     * 上游由 {@code AgentService.runLoop}、{@code BufferedTerminalSink.isActive}、{@code DefaultAgentRuntime.finalizeTurn} 调用。
     *
     * @return 当前记录中的 {@code true}
     */
    default boolean isActive() {
        return true;
    }
}
