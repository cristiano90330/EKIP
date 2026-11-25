/* 在任务线程建立并恢复调用方的安全上下文。 */

package com.pony.knowledge.security;

import org.springframework.core.task.TaskDecorator;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 把提交线程的 {@link SecurityContext} 带到工作线程。
 *
 * <p><b>为什么必须有</b>：SSE 入口（{@code /agent/chat/stream}、{@code /knowledge/ask/stream}）
 * 不能「先算完再建流」——必须先返回 emitter，再把回合提交到 {@code agentTurnExecutor}。线程一换，
 * {@code SecurityContextHolder} 就是空的，回合里 {@code CurrentUserUtil.getUserId()} 直接抛
 * 「未认证或登录已过期」，客户端只看到一帧 error。这与令牌是否有效无关，刷新令牌也不会好。
 *
 * <p><b>为什么不能挂到所有线程池</b>：文档解析、摘要等后台池本不需要用户身份；把身份传进去
 * 会让「系统后台任务」变成「以某个用户身份运行」，是权限面的扩大。因此只给 SSE 回合池组合本装饰器。
 *
 * <p>异常安全：无论任务如何结束都会恢复调用线程原有的上下文，避免线程复用时把上一个请求的
 * 身份泄漏给下一个任务。
 */
public class SecurityContextTaskDecorator implements TaskDecorator {

    /**
     * 捕获提交线程的认证身份，包装成可在执行线程恢复原状态的任务。
     * 下游为 SecurityContextHolder.getContext、SecurityContextHolder.setContext。
     *
     * @param task 任务记录
     * @return 本次计算或查询得到的 Runnable 结果
     */
    @Override
    public Runnable decorate(Runnable task) {
        // 提交线程捕获（后续 filter 会 clearContext，但这里持有的是对象引用，认证信息不会因此丢失）
        SecurityContext captured = SecurityContextHolder.getContext();
        return () -> {
            SecurityContext previous = SecurityContextHolder.getContext();
            SecurityContextHolder.setContext(captured);
            try {
                task.run();
            } finally {
                SecurityContextHolder.setContext(previous);
            }
        };
    }
}
