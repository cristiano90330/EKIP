/* 验证摘要锁串行化同一会话并允许不同会话并行。 */

package com.pony.knowledge.agent.memory.summary;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 3.8e 并发验收：
 *   - 同一个 session 同一把锁：后请求的摘要被阻塞，直到先请求释放（串行）；
 *   - 不同 session 锁不同：可同时获得（并行）。
 */
class SummaryLockTest {

    /** 让两个任务同时申请同一会话的摘要锁，核对后到者在首个锁释放后继续。 */
    @Test
    void sameSession_isSerialized_untilUnlocked() throws Exception {
        SummaryLock lock = new SummaryLock();
        lock.lock("A");   // 主线程先占住 A

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            CountDownLatch acquired = new CountDownLatch(1);
            Future<?> f = pool.submit(() -> {
                lock.lock("A");
                acquired.countDown();
                lock.unlock("A");
            });

            // A 被占住 → 先请求的线程拿不到锁（等待）
            assertFalse(acquired.await(200, TimeUnit.MILLISECONDS),
                    "同一 session 的第二次摘要应被锁阻塞（串行）");
            lock.unlock("A");          // 释放后，等着的线程拿到并可释放
            assertTrue(acquired.await(2, TimeUnit.SECONDS), "释放后等待者应拿到锁");
            f.get(2, TimeUnit.SECONDS);
        } finally {
            lock.unlock("A");
            pool.shutdownNow();
        }
    }

    /** 分别对两个会话申请摘要锁，验证每个会话独立取得锁。 */
    @Test
    void differentSessions_areNotBlocked() throws Exception {
        SummaryLock lock = new SummaryLock();
        lock.lock("A");   // 只锁 A

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            CountDownLatch acquired = new CountDownLatch(1);
            Future<?> f = pool.submit(() -> {
                lock.lock("B");
                acquired.countDown();
                lock.unlock("B");
            });

            assertTrue(acquired.await(1, TimeUnit.SECONDS), "不同 session 的锁不互相阻塞（可并行）");
            f.get(1, TimeUnit.SECONDS);
        } finally {
            lock.unlock("A");
            pool.shutdownNow();
        }
    }
}
