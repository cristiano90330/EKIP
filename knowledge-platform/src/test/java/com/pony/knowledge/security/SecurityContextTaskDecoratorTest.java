/* 检查任务执行前后的安全上下文传递和恢复。 */

package com.pony.knowledge.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SSE 回合线程取不到当前用户的口径门禁。
 *
 * <p>缺陷现场：{@code /agent/chat/stream} 先返回 emitter，回合在 {@code agent-turn-N} 线程跑，
 * 那里 {@code SecurityContextHolder} 为空 → {@code CurrentUserUtil.getUserId()} 抛「未认证」，
 * 客户端只收到一帧 error。与令牌有效性无关，刷新令牌也不会好。
 */
class SecurityContextTaskDecoratorTest {

    /** 回收当前用例的资源和上下文，调用 {@code SecurityContextHolder.clearContext}。 */
    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    /** 核对 {@code SecurityContextHolder.getContext().getAuthentication().getPrincipal()} 与预期 {@code 1016L} 一致。 */
    @Test
    void carriesAuthenticationIntoTheWorkerThreadAndRestoresAfterwards() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(1016L, null, java.util.List.of()));

        Runnable decorated = new SecurityContextTaskDecorator().decorate(
                () -> assertEquals(1016L, SecurityContextHolder.getContext()
                        .getAuthentication().getPrincipal()));

        // 模拟请求结束：filter 会清空当前线程的上下文
        SecurityContextHolder.clearContext();

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                decorated.run();
                // 跑完后必须恢复成工作线程原来的样子，不能把身份留给下一个任务
                assertNull(SecurityContextHolder.getContext().getAuthentication());
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "agent-turn-test");
        worker.start();
        worker.join();

        assertNull(failure.get(), () -> "工作线程断言失败: " + failure.get());
        assertTrue(true);
    }

    /** 核对 {@code seen.get()} 为空。调用路径经过 {@code SecurityContextHolder.clearContext}。 */
    @Test
    void doesNotInventAuthenticationWhenSubmitterWasAnonymous() throws Exception {
        SecurityContextHolder.clearContext();
        AtomicReference<Object> seen = new AtomicReference<>("unset");

        Runnable decorated = new SecurityContextTaskDecorator().decorate(
                () -> seen.set(SecurityContextHolder.getContext().getAuthentication()));

        Thread worker = new Thread(decorated);
        worker.start();
        worker.join();

        assertNull(seen.get());
    }
}
