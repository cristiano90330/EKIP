/* 约定轨迹保存和按所有者读取的存储接口。 */

package com.pony.knowledge.observability;

import java.util.List;

/**
 * Trace 的保存与读取边界。读取只提供按归属用户定界的入口：
 * Trace 含原始问题与阶段信息，按实例共享的无过滤读取会把他人请求内容暴露给任意登录用户。
 */
public interface TraceStore {

    /**
     * 将完整问答轨迹写入实现所管理的轨迹存储。
     * 上游为 AgentTracer.observeRoot、RagStageTracer.observeRoot。
     *
     * @param trace 用于读取或更新的 RAG 请求轨迹
     * @param entrypoint 发起 RAG 请求的公开入口标识
     */
    void save(RagTrace trace, String entrypoint);

    /**
     * 声明按账号所有权和轨迹 ID 读取单条轨迹的入口。
     * 上游为 ObservabilityController.getTrace。
     *
     * @param traceId 用于定位观测记录的轨迹标识
     * @param userId 用户 ID
     * @return 一次 RAG 请求的所有者、执行策略、结果和阶段轨迹，以 RagTrace 返回
     */
    RagTrace findByIdForOwner(String traceId, Long userId);

    /**
     * 声明按所有者和数量限制读取最近轨迹的入口。
     * 上游为 ObservabilityController.listTraces。
     *
     * @param userId 用户 ID
     * @return 与过滤条件匹配的 List&lt;RagTrace&gt; 数据集合
     */
    List<RagTrace> listRecentForOwner(Long userId);
}
