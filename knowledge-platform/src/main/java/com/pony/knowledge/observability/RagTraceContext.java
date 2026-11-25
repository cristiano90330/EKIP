/* 管理执行线程上当前关联的 RAG 轨迹。 */

package com.pony.knowledge.observability;

public class RagTraceContext {
    private static final ThreadLocal<RagTrace>
            CURRENT = new ThreadLocal<>();

    /**
     * 将已有轨迹绑定到当前执行线程。
     * 上游为 AgentTracer.observeRoot、ToolExecutor.runTool。
     *
     * @param trace 用于读取或更新的 RAG 请求轨迹
     */
    public static void set(RagTrace trace) {
        CURRENT.set(trace);
    }

    /**
     * 读取当前线程关联的轨迹。
     * 上游为 AgentTracer.observeRoot、ToolExecutor.runWithTimeout。
     *
     * @return 一次 RAG 请求的所有者、执行策略、结果和阶段轨迹，以 RagTrace 返回
     */
    public static RagTrace get() {
        return CURRENT.get();
    }

    /**
     * 移除当前线程的轨迹，避免线程池复用时继承旧请求。
     * 上游为 AgentTracer.observeRoot、ToolExecutor.runTool。
     */
    public static void clear() {
        CURRENT.remove();
    }
}
