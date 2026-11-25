/* 承载已认证的身份、获准知识库、工具白名单和本轮取消信号。 */

package com.pony.knowledge.agent.model;

import lombok.Builder;
import lombok.Data;
import com.pony.knowledge.service.rag.query.ChatMessage;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Agent 系统身份上下文：由 AgentService 从 JWT 与知识库授权解析，
 * 只携带系统身份，LLM 无法通过任何入参影响这些字段。
 */
@Data
@Builder
public class AgentExecutionContext {

    private static final Pattern MEMORY_SAVE_COMMAND = Pattern.compile(
            "(?is)^\\s*(?:请?记住|remember)\\s*[:：]\\s*(\\S.*)$");

    private Long userId;

    /**
     * 会话键，值为 {@code userId + ":" + sessionId} 而非客户端原样传入的 sessionId。
     * DefaultAgentRuntime 每次工具往返重建上下文时从此字段取键回读 memory，
     * 因此它必须是合成后的值。
     */
    private String sessionId;

    private String traceId;

    private Set<Long> accessibleKnowledgeBaseIds;

    /**
     * 本回合可执行的工具名。null 表示不受限（默认 Agent / 直连调用）；
     * 空集表示一个工具都不允许。Runtime 只把该集合暴露给模型，ToolExecutor 同时据此拒绝伪造调用。
     */
    private Set<String> allowedToolNames;

    /** 系统身份提示：Runtime 装配上下文时重新注入 system（身份来自宿主，非 Prompt 可控）。 */
    private String systemPrompt;

    /** 服务端解析的回答策略；省略策略的内部调用使用 grounded。 */
    @Builder.Default
    private String answerPolicy = "grounded";

    @Builder.Default
    private boolean memoryReadEnabled = true;

    @Builder.Default
    private boolean memoryWriteEnabled = true;

    /**
     * 综合已解析白名单与记忆读写开关，判断本轮是否允许调用指定工具。
     * 上游由 {@code DefaultAgentRuntime.executeLoop}、{@code MemorySaveTool.execute}、{@code ToolExecutor.execute} 调用。
     * 下游调用 {@code requestedMemoryContent}。
     *
     * @param name 本次调用传入的 {@code String}
     * @return 条件成立时为 true，其他状态按 false 处理
     */
    public boolean allowsTool(String name) {
        return (allowedToolNames == null || allowedToolNames.contains(name))
                && (!"memory_search".equals(name) || memoryReadEnabled)
                && (!"memory_save".equals(name) || memoryWriteEnabled && requestedMemoryContent() != null);
    }

    /**
     * 从当前问题提取明确的长期记忆保存内容，作为实际执行的授权依据。
     * 上游由 {@code AgentExecutionContext.allowsTool}、{@code AgentService.executeTurn}、{@code DefaultAgentRuntime.executeLoop} 调用。
     * 下游调用 {@code MEMORY_SAVE_COMMAND.matcher}、{@code command.matches}、{@code command.group}。
     *
     * @return 供后续处理使用的文本或标识
     */
    public String requestedMemoryContent() {
        var command = MEMORY_SAVE_COMMAND.matcher(currentQuery == null ? "" : currentQuery);
        return command.matches() ? command.group(1).strip() : null;
    }

    /** 当前问题：Runtime 在一次工具执行后重建上下文时作为 Required 的 currentQuery。 */
    private String currentQuery;

    /** Authorized prior user/assistant messages used to resolve the current evidence question. */
    @Builder.Default
    private List<ChatMessage> questionHistory = List.of();

    /** 请求取消信号（volatile，跨线程可见；并行工具线程也能读到） */
    private volatile boolean cancelled;

    /**
     * 设置本次回合取消标记，供模型与工具阶段的检查停止后续执行。
     * 上游由 {@code AgentService.executeTurn} 调用。
     */
    public void cancel() {
        this.cancelled = true;
    }
}
