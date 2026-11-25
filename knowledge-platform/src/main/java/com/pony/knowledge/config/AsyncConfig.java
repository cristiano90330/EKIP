/* 装配文档处理、检索、Agent 回合和摘要使用的线程池与调度器。 */

package com.pony.knowledge.config;

import com.pony.knowledge.security.SecurityContextTaskDecorator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 启用定时任务和异步方法，并按用途注册独立的调度器与执行器。文档任务、Agent 工具分派、
 * 工具执行和摘要各用命名 Bean 隔离，消费者通过限定名称取得对应线程池；执行器上的
 * TaskDecorator 负责传播观测上下文。资源随 Spring 容器关闭，本配置未设置等待全部排队任务完成。
 */
@EnableScheduling
@Configuration
@EnableAsync
public class AsyncConfig {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    private final TaskDecorator observationTaskDecorator;

    /**
     * SSE 回合池专用：先恢复 SecurityContext，再恢复 requestId / observation。
     *
     * <p>SSE 入口必须先返回 emitter 再把回合交给工作线程，线程一换 {@code SecurityContextHolder} 就是空的，
     * 回合里的 {@code CurrentUserUtil.getUserId()} 会直接抛「未认证」——这是 2026-10-23 实测到的
     * 100% 必现故障。只给 SSE 池组合该装饰器：文档解析、摘要等后台池不该带上用户身份。
     */
    private final TaskDecorator sseTaskDecorator;

    /**
     * 保存装配文档处理、检索、Agent 回合和摘要使用的线程池与调度器所需的依赖和初始配置。
     * 上游为 Spring 组件装配。
     *
     * @param observationTaskDecorator 本次操作所用的 TaskDecorator 数据
     */
    public AsyncConfig(TaskDecorator observationTaskDecorator) {
        this.observationTaskDecorator = observationTaskDecorator;
        SecurityContextTaskDecorator security = new SecurityContextTaskDecorator();
        this.sseTaskDecorator = task ->
                security.decorate(observationTaskDecorator.decorate(task));
    }

    /**
     * 装配执行文档延迟重试和维护任务的调度线程池。
     * 上游为 Spring 容器组件装配。
     *
     * @return 已配置队列、线程或调度条件的 TaskScheduler，由容器管理
     */
    @Bean("taskScheduler")
    public TaskScheduler taskScheduler(){
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();

        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("task-sched-");

        return scheduler;
    }

    /**
     * 装配独立的续租调度器，使任务负载与续租定时分离。
     * 上游为 Spring 容器组件装配。
     *
     * @return 已配置队列、线程或调度条件的 ThreadPoolTaskScheduler，由容器管理
     */
    @Bean("documentTaskLeaseScheduler")
    public ThreadPoolTaskScheduler documentTaskLeaseScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("document-lease-");
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    /**
     * 装配文档后台任务线程池和队列拒绝处理。
     * 上游为 Spring 容器组件装配。
     *
     * @return 已配置队列、线程或调度条件的 ThreadPoolTaskExecutor，由容器管理
     */
    @Bean("documentTaskExecutor")
    public ThreadPoolTaskExecutor documentTaskExecutor(){
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("document-task-");
        executor.setTaskDecorator(observationTaskDecorator);
        // 拒绝后由投递方处理，避免在上传或恢复扫描线程中同步执行文档处理。
        executor.setRejectedExecutionHandler(new RejectWithWarningPolicy());
        executor.initialize();

        return executor;
    }

    /**
     * 文档池无法接收任务时记录池状态，并沿用 AbortPolicy 抛出拒绝异常。
     * 本策略不修改数据库状态；普通投递和延迟重试的补偿分别由协调器及其失败处理器负责。
     */
    public static class RejectWithWarningPolicy extends ThreadPoolExecutor.AbortPolicy {
        /**
         * 在执行器拒绝文档任务时记录队列状态，并交由拒绝策略抛出异常。
         *
         * @param task 任务记录
         * @param executor 本次操作所用的 ThreadPoolExecutor 数据
         */
        @Override
        public void rejectedExecution(Runnable task, ThreadPoolExecutor executor) {
            log.warn("documentTaskExecutor 已满载（pool={}/{}，队列 {} 已满），拒绝任务并交由 WAITING 恢复扫描重投。"
                            + "持续出现说明需要限流或扩容，而不是调大 queueCapacity",
                    executor.getPoolSize(), executor.getMaximumPoolSize(),
                    executor.getQueue().size());
            super.rejectedExecution(task, executor);
        }
    }

    /**
     * 装配 RAG 检索线程池，并传播观测及身份上下文。
     * 上游为 Spring 容器组件装配。
     *
     * @return 已配置队列、线程或调度条件的 Executor，由容器管理
     */
    @Bean("ragRetrievalExecutor")
    public Executor ragRetrievalExecutor(){
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("rag-retrieval-");
        executor.setTaskDecorator(observationTaskDecorator);
        executor.initialize();

        return executor;
    }

    /**
     * 装配执行 Agent 工具逻辑的有界线程池。
     * 上游为 Spring 容器组件装配。
     *
     * @return 已配置队列、线程或调度条件的 Executor，由容器管理
     */
    @Bean("agentToolExecutor")
    public Executor agentToolExecutor(){
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("agent-tool-");
        executor.setTaskDecorator(observationTaskDecorator);
        executor.initialize();

        return executor;
    }

    /**
     * 装配 Agent 工具任务的分发线程池。
     * 上游为 Spring 容器组件装配。
     *
     * @return 已配置队列、线程或调度条件的 Executor，由容器管理
     */
    @Bean("agentToolDispatchExecutor")
    public Executor agentToolDispatchExecutor(){
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("agent-dispatch-");
        executor.setTaskDecorator(observationTaskDecorator);
        executor.initialize();

        return executor;
    }

    /**
     * 装配运行 Agent 与问答流式回合的执行器。
     * 上游为 Spring 容器组件装配。
     *
     * @return 已配置队列、线程或调度条件的 Executor，由容器管理
     */
    @Bean("agentTurnExecutor")
    public Executor agentTurnExecutor(){
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(8);
        executor.setThreadNamePrefix("agent-turn-");
        // 与其它池不同：这里必须把 Spring Security 上下文一起带过去，否则回合线程取不到当前用户
        executor.setTaskDecorator(sseTaskDecorator);
        // 队列满即拒绝：控制器据此立刻发一帧 error，而不是让客户端挂着等一个不会开始的回合。
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();

        return executor;
    }

    /**
     * 装配后台摘要专用的有界执行器。
     * 上游为 Spring 容器组件装配。
     *
     * @return 已配置队列、线程或调度条件的 Executor，由容器管理
     */
    @Bean("summaryExecutor")
    public Executor summaryExecutor(){
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("agent-summary-");
        executor.setTaskDecorator(observationTaskDecorator);
        executor.initialize();

        return executor;
    }
}
