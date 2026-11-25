/* 定义将会话记忆、当前提问与工具轨迹装配成模型消息的接口。 */

package com.pony.knowledge.agent.context;

/**
 * Context 装配契约：把 Summary、最近窗口（Sliding Window）、当前问题与本轮工具结果
 * 统一装配到 token 预算内。素材由调用方传入，摘要与窗口经 MemoryManager 读取；
 * 本类不执行 Tool、不访问 Redis。
 */
public interface ContextBuilder {

    /**
     * 把上下文构建请求映射为待发模型消息及裁减统计。
     * 上游由 {@code DefaultContextBudgetManager.build} 调用。
     *
     * @param request 身份、会话、提示词、当前问题及可选工具结果与预算
     * @return 模型消息及其用量和裁减诊断
     */
    ContextBuildResult build(ContextBuildRequest request);
}
