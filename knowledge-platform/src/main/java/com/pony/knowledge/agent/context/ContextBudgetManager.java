/* 约定 Agent 上下文预算的解析和带预算消息的构造入口。 */

package com.pony.knowledge.agent.context;

/**
 * Context 预算管理入口：Runtime 与 AgentService 装配上下文的唯一调用点。
 * 在 ContextBuilder 之上叠加预算口径与观测：
 *   - build(request) 统一按 budget 裁剪，并记录估算/实际 token、是否裁剪、裁掉条数、耗时；
 *   - budget() 暴露当前预算（Runtime 每次重建上下文时需要）。
 * 只依赖 ContextBuilder、ContextBudget、AgentMetrics，不感知 Tool / RAG / Redis。
 */
public interface ContextBudgetManager {

    /**
     * 根据输入与模型约束装配上下文，同时对外提供预算和裁减诊断。
     * 上游由 {@code AgentService.executeTurn}、{@code DefaultAgentRuntime.buildWith}、{@code DefaultAgentRuntime.rebuildContext} 调用。
     *
     * @param request 身份、会话、提示词、当前问题及可选工具结果与预算
     * @return 模型消息及其用量和裁减诊断
     */
    ContextBuildResult build(ContextBuildRequest request);

    /**
     * 返回当前模型决策所用的消息预算，由服务层和运行时读取。
     * 上游由 {@code AgentService.executeTurn}、{@code DefaultAgentRuntime.callBudget}、{@code DefaultAgentRuntime.squeezeContext} 调用。
     *
     * @return 本轮模型消息可占用的令牌预算
     */
    ContextBudget budget();
}
