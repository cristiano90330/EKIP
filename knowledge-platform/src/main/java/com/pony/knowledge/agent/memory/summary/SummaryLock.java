/* 为同一会话的摘要写入提供进程内互斥。 */

package com.pony.knowledge.agent.memory.summary;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 会话摘要级联锁：
 *   - 同一 session 的摘要串行：同一 sessionId 拿同一把 ReentrantLock，同时只允许一个摘要运行；
 *   - 不同 session 可并行：锁按 sessionId 分开，互不阻塞。
 *
 * <p>只在单进程内生效，没有跨节点保护。当前为单节点部署，因此够用；
 * 多实例部署时两个实例各持自己的 ReentrantLock，互不阻塞，可能同时对同一 session 摘要并互相覆盖。
 * RedisMemoryStore.saveSummary 是纯 SET 覆盖，没有 WATCH/MULTI 或版本比较，不能兜底。
 * 要做跨实例正确性，需在 Redis 层加原子操作。
 */
@Component
public class SummaryLock {

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    /**
     * 按会话标识获取当前进程内的摘要互斥锁。
     * 上游由 {@code SummaryCoordinator.summarizeGuarded} 调用。
     *
     * @param sessionId 受当前用户与策略约束的服务端会话 ID
     */
    public void lock(String sessionId) {
        locks.computeIfAbsent(sessionId, k -> new ReentrantLock()).lock();
    }

    /**
     * 释放对应会话的摘要锁，为后续折叠任务腾出执行权。
     * 上游由 {@code SummaryCoordinator.summarizeGuarded} 调用。
     * 下游调用 {@code lock.isHeldByCurrentThread}、{@code lock.unlock}。
     *
     * @param sessionId 受当前用户与策略约束的服务端会话 ID
     */
    public void unlock(String sessionId) {
        ReentrantLock lock = locks.get(sessionId);
        if (lock != null && lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}
