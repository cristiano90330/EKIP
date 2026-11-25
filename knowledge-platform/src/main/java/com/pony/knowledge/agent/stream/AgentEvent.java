/* 包装发往页面的 Agent 事件类型和业务载荷。 */

package com.pony.knowledge.agent.stream;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次 Agent 回合对外发出的单个事件。
 *
 * <p>事件名固定为 8 种：{@code accepted / round / delta / tool_call / tool_result / answer / done / error}。
 * 载荷只用基本类型与集合，不直接序列化内部模型（{@code AgentMessage} / {@code ToolResult}），
 * 避免内部字段调整时悄悄改变对外契约。
 *
 * <p>非流式调用（{@code POST /agent/chat}）不产生事件：{@link AgentEventSink#NOOP} 会丢弃全部事件，
 * runtime 因此不需要区分「有没有人在听」以外的分支。
 */
public final class AgentEvent {

    /** 回合已受理（首个事件，客户端据此认为连接建立成功）。 */
    public static final String ACCEPTED = "accepted";

    /** 进入某一阶段：{@code think / act / observe / finalize}。 */
    public static final String ROUND = "round";

    /** 答案增量文本。 */
    public static final String DELTA = "delta";

    /** 即将执行的一次工具调用。 */
    public static final String TOOL_CALL = "tool_call";

    /** 一次工具调用的结果摘要。 */
    public static final String TOOL_RESULT = "tool_result";

    /** 终止轮确认的整段答案（前端以此为准落到答案区）。 */
    public static final String ANSWER = "answer";

    /** 回合正常终止，带 stopReason 与统计。 */
    public static final String DONE = "done";

    /** 回合以故障终止（依赖不可用 / 超时 / 内部错误）。 */
    public static final String ERROR = "error";

    private final String type;

    private final Map<String, Object> data;

    /**
     * 用注入依赖和构造参数建立此实例执行时使用的状态。
     *
     * @param type 本次调用传入的 {@code String}
     * @param data 本次调用传入的 {@code Map<String, Object>}
     */
    private AgentEvent(String type, Map<String, Object> data) {
        this.type = type;
        this.data = data;
    }

    /**
     * 用指定事件名称和载荷构造前端可读取的 Agent 事件。
     *
     * @param type 本次调用传入的 {@code String}
     * @param data 本次调用传入的 {@code Map<String, Object>}
     * @return 调用方使用的 {@code AgentEvent}
     */
    public static AgentEvent of(String type, Map<String, Object> data) {
        return new AgentEvent(type, data == null ? Map.of() : data);
    }

    /**
     * 用指定事件名称和载荷构造前端可读取的 Agent 事件。
     *
     * @param type 本次调用传入的 {@code String}
     * @param key 用于访问当前数据的存储键
     * @param value 本次调用传入的 {@code Object}
     * @return 调用方使用的 {@code AgentEvent}
     */
    public static AgentEvent of(String type, String key, Object value) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(key, value);
        return new AgentEvent(type, data);
    }

    /**
     * 返回当前记录保存的 {@code type}。
     * 上游由 {@code BufferedTerminalSink.emit}、{@code SseAgentEventSink.emit} 调用。
     */
    public String getType() {
        return type;
    }

    /**
     * 返回当前记录保存的 {@code data}。
     * 上游由 {@code SseAgentEventSink.emit} 调用。
     */
    public Map<String, Object> getData() {
        return data;
    }
}
