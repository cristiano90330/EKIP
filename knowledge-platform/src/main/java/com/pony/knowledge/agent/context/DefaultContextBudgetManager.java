/* 读取当前用途的模型窗口并记录上下文裁减与超预算指标。 */

package com.pony.knowledge.agent.context;

import com.pony.knowledge.agent.observability.AgentMetrics;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Context 预算管理器实现。
 *
 * 作为 Runtime 与 AgentService 装配上下文的统一入口，在 ContextBuilder 产出结果后
 * 立即把预算执行情况打进 AgentMetrics：估算与实际 token、是否裁剪、整条丢弃条数、
 * 内容截断条数、耗时，以及裁剪后仍超预算的次数与 token 数。
 * 本身不执行 Tool、不访问 Redis。
 */
@Component
@RequiredArgsConstructor
public class DefaultContextBudgetManager implements ContextBudgetManager {

    private final ContextBuilder contextBuilder;

    private final ContextBudget contextBudget;

    private final AgentMetrics agentMetrics;

    /**
     * 调用上下文构建器生成本轮消息，并记录预算、裁减及超限指标。
     * 下游调用 {@code request.toBuilder}、{@code agentMetrics.contextBuildTimer}、{@code agentMetrics.recordContextEstimated}。
     *
     * @param request 身份、会话、提示词、当前问题及可选工具结果与预算
     * @return 模型消息及其用量和裁减诊断
     */
    @Override
    public ContextBuildResult build(ContextBuildRequest request) {
        ContextBudget effective = request.getBudget() != null ? request.getBudget() : budget();
        ContextBuildRequest effectiveRequest = request.toBuilder().budget(effective).build();

        long start = System.nanoTime();
        ContextBuildResult result = contextBuilder.build(effectiveRequest);
        long elapsedNanos = System.nanoTime() - start;
        agentMetrics.contextBuildTimer().record(elapsedNanos, TimeUnit.NANOSECONDS);

        agentMetrics.recordContextEstimated(result.getTokensBefore());
        agentMetrics.recordContextFinal(result.getTokensUsed());
        agentMetrics.recordContextTruncated(result.isTrimmed() ? 1 : 0);
        agentMetrics.recordContextDropped(result.getDroppedMessages());
        agentMetrics.recordContextContentTruncated(result.getTruncatedMessages());
        // 裁剪后仍超预算 = Required 段自身撑破预算，单独计数，不做静默处理
        if (result.getOverBudgetTokens() > 0) {
            agentMetrics.countContextBudgetExceeded();
            agentMetrics.recordContextOverBudgetTokens(result.getOverBudgetTokens());
        }

        return result;
    }

    /**
     * 读取本轮已选模型的上下文窗口和输出令牌配置，解析实际预算。
     * 上游由 {@code DefaultContextBudgetManager.build} 调用。
     * 下游调用 {@code com.pony.knowledge.modelmanagement.ModelExecutionContext.current}、{@code n.intValue}。
     *
     * @return 本轮模型消息可占用的令牌预算
     */
    @Override
    public ContextBudget budget() {
        var execution = com.pony.knowledge.modelmanagement.ModelExecutionContext.current();
        if (execution != null && execution.get("bindings") instanceof java.util.Map<?, ?> bindings
                && bindings.get("agent") instanceof java.util.Map<?, ?> model) {
            int window = model.get("contextWindow") instanceof Number n ? n.intValue() : 8192;
            int output = model.get("maxOutputTokens") instanceof Number n ? n.intValue() : 1024;
            return ContextBudget.builder().maxTokens(window).reservedOutputTokens(output)
                    .reservedToolTokens(contextBudget.getReservedToolTokens()).build();
        }
        return contextBudget;
    }
}
