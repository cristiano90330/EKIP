/* 按保留上限存储轨迹，并根据所有者范围提供查询。 */

package com.pony.knowledge.observability;

import com.pony.knowledge.utils.CurrentUserUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 进程内 Trace 存储。保存时按认证上下文补齐归属用户，读取只返回本人记录。
 * 归属在保存的唯一入口统一补充，避免每个记录点各自传递，也不会因为某条新增链路忘记打标而泄漏。
 * 没有认证上下文的记录（如非 HTTP 触发的调用）归属为空，普通用户不可见。
 */
@Component
public class InMemoryTraceStore implements TraceStore {
    private final int retention;
    private final CurrentUserUtil currentUserUtil;
    private final ConcurrentLinkedDeque<RagTrace> traces = new ConcurrentLinkedDeque<>();

    /**
     * 保存按保留上限存储轨迹所需的依赖和初始配置。
     * 上游为 Spring 组件装配。
     *
     * @param retention 初始化或更新同名字段的值
     * @param currentUserUtil 本次操作使用的 CurrentUserUtil 组件或记录
     */
    public InMemoryTraceStore(@Value("${observability.trace.retention:1000}") int retention,
                              CurrentUserUtil currentUserUtil) {
        this.retention = retention;
        this.currentUserUtil = currentUserUtil;
    }

    /**
     * 保存完成的轨迹并修剪超过保留上限的旧条目。
     * 上游为 AgentTracer.observeRoot、RagStageTracer.observeRoot。
     * 下游为 RagTrace.setEntrypoint、RagTrace.getUserId。
     *
     * @param trace 用于读取或更新的 RAG 请求轨迹
     * @param entrypoint 发起 RAG 请求的公开入口标识
     */
    @Override
    public void save(RagTrace trace, String entrypoint) {
        trace.setEntrypoint(entrypoint);
        if (trace.getUserId() == null) {
            trace.setUserId(currentUserUtil.getUserIdOrNull());
        }
        traces.addLast(trace);

        while (traces.size() > retention) {
            traces.pollFirst();
        }
    }

    /**
     * 依照账号及轨迹 ID 查找有权读取的记录。
     * 上游为 ObservabilityController.getTrace。
     *
     * @param traceId 用于定位观测记录的轨迹标识
     * @param userId 用户 ID
     * @return 一次 RAG 请求的所有者、执行策略、结果和阶段轨迹，以 RagTrace 返回
     */
    @Override
    public RagTrace findByIdForOwner(String traceId, Long userId) {
        if (traceId == null || userId == null) {
            return null;
        }
        return traces.stream()
                .filter(t -> traceId.equals(t.getTraceId()))
                .filter(t -> userId.equals(t.getUserId()))
                .findFirst().orElse(null);
    }

    /**
     * 按所有者筛选并截取最近的轨迹记录。
     * 上游为 ObservabilityController.listTraces。
     *
     * @param userId 用户 ID
     * @return 与过滤条件匹配的 List&lt;RagTrace&gt; 数据集合
     */
    @Override
    public List<RagTrace> listRecentForOwner(Long userId) {
        if (userId == null) {
            return List.of();
        }
        return traces.stream()
                .filter(t -> userId.equals(t.getUserId()))
                .sorted((a, b) -> Long.compare(b.getStartTimeEpochMs(), a.getStartTimeEpochMs()))
                .toList();
    }
}
