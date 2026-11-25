/* 通过 Redis 传递文档变更提示，订阅节点回查任务快照。 */

package com.pony.knowledge.service.task;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.pony.knowledge.entity.DocumentTask;
import com.pony.knowledge.mapper.DocumentTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Redis carries only document IDs. Each JVM reads its latest committed snapshot from PostgreSQL. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "task.broadcast.enabled", havingValue = "true")
public class TaskStatusBroadcast {
    private static final Logger log = LoggerFactory.getLogger(TaskStatusBroadcast.class);
    private final StringRedisTemplate redis;
    private final DocumentTaskMapper tasks;
    private final String channel;

    /**
     * 保存通过 Redis 传递文档变更提示所需的依赖和初始配置。
     *
     * @param redis 访问 Redis 保存和读取状态的客户端
     * @param tasks 本次操作使用的 DocumentTaskMapper 组件或记录
     * @param channel 初始化或更新同名字段的值
     */
    public TaskStatusBroadcast(StringRedisTemplate redis, DocumentTaskMapper tasks,
                               @Value("${task.broadcast.channel}") String channel) {
        this.redis = redis;
        this.tasks = tasks;
        this.channel = channel;
    }

    /**
     * 向 Redis 频道发布文档 ID，提示各订阅节点读取任务快照。
     * 上游为 TaskStatusEventBus.publish。
     *
     * @param documentId 文档 ID
     */
    void notifyDocument(Long documentId) {
        try {
            redis.convertAndSend(channel, documentId.toString());
        } catch (RuntimeException failure) {
            log.warn("跨实例任务通知未送达，可通过任务查询恢复 documentId={}", documentId, failure);
        }
    }

    /**
     * 查询文档最新任务，给跨实例的状态通知补充数据库快照。
     * 上游为 TaskStatusBroadcast.taskStatusListener、TaskStatusEventBus.publish。
     * 下游为 DocumentTaskMapper.selectOne。
     *
     * @param documentId 文档 ID
     * @return 后台文档任务、重试次数、处理时间与错误状态，以 DocumentTask 返回
     */
    DocumentTask latestTask(Long documentId) {
        return tasks.selectOne(new QueryWrapper<DocumentTask>().eq("document_id", documentId)
                .orderByDesc("id").last("LIMIT 1"));
    }

    /**
     * 创建处理 Redis 任务事件的有界执行器。
     * 上游为 Spring 容器组件装配。
     *
     * @return 已配置队列、线程或调度条件的 ExecutorService，由容器管理
     */
    @Bean(destroyMethod = "shutdown")
    ExecutorService taskStatusBroadcastExecutor() {
        return new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(128),
                task -> new Thread(task, "task-status-broadcast"), new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 建立任务状态频道订阅，并将有效文档 ID 交给事件总线刷新。
     * 上游为 Spring 容器组件装配。
     * 下游为 TaskStatusEventBus.refresh。
     *
     * @param connections 本次操作所用的 RedisConnectionFactory 数据
     * @param bus 本次操作使用的 TaskStatusEventBus 组件或记录
     * @param executor 本次操作所用的 ExecutorService 数据
     * @return 本次计算或查询得到的 RedisMessageListenerContainer 结果
     */
    @Bean
    RedisMessageListenerContainer taskStatusListener(RedisConnectionFactory connections, TaskStatusEventBus bus,
            @Qualifier("taskStatusBroadcastExecutor") ExecutorService executor) {
        RedisMessageListenerContainer listener = new RedisMessageListenerContainer();
        listener.setConnectionFactory(connections);
        listener.setTaskExecutor(executor);
        listener.addMessageListener((message, pattern) -> {
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            if (body.length() > 19 || body.isEmpty()) return;
            long documentId;
            try { documentId = Long.parseLong(body); }
            catch (NumberFormatException invalid) { return; }
            if (documentId > 0) bus.refresh(documentId, () -> latestTask(documentId));
        }, ChannelTopic.of(channel));
        return listener;
    }
}
