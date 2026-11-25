/* 维护文档 SSE 订阅并有序分发初始和最新任务快照。 */

package com.pony.knowledge.service.task;

import com.pony.knowledge.dto.DocumentTaskDTO;
import com.pony.knowledge.entity.DocumentTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 按文档 ID 维护当前 JVM 的 SSE 订阅。订阅注册、初始快照发送和事件发布共用文档级锁
 * 及事件序号，避免新事件发送后又补发旧快照；断流后的状态恢复依赖数据库查询。
 * 启用跨实例通知时，通过 Redis 通知其他实例补读最新快照。
 */
@Component
public class TaskStatusEventBus {

    private static final Logger log = LoggerFactory.getLogger(TaskStatusEventBus.class);

    private final Map<Long, Bucket> subscribers = new ConcurrentHashMap<>();
    private TaskStatusBroadcast broadcast;

    /**
     * 关联可选的跨节点任务状态广播组件。
     *
     * @param broadcast 本次操作使用的 TaskStatusBroadcast 组件或记录
     */
    @Autowired(required = false)
    void setBroadcast(TaskStatusBroadcast broadcast) { this.broadcast = broadcast; }

    /**
     * 建立文档 SSE 订阅并记录登记时的发布版本。
     * 上游为 DocumentController.streamTask。
     *
     * @param documentId 文档 ID
     * @param emitter 向当前浏览器连接写入 SSE 的发送器
     * @return 用于核对发布版本和后续取消订阅的句柄
     */
    public Subscription subscribe(Long documentId, SseEmitter emitter) {
        AtomicReference<Subscription> created = new AtomicReference<>();
        subscribers.compute(documentId, (id, current) -> {
            Bucket bucket = current == null ? new Bucket() : current;
            synchronized (bucket) {
                Subscription subscription = new Subscription(id, emitter, bucket, bucket.revision);
                bucket.entries.add(subscription);
                created.set(subscription);
            }
            return bucket;
        });
        return created.get();
    }

    /**
     * 移除匹配的订阅并回收没有监听者的文档订阅组。
     * 上游为 DocumentController.streamTask、TaskStatusEventBus.sendInitialSnapshotIfUnchanged。
     *
     * @param documentId 文档 ID
     * @param emitter 向当前浏览器连接写入 SSE 的发送器
     */
    public void unsubscribe(Long documentId, SseEmitter emitter) {
        Bucket bucket = subscribers.get(documentId);
        if (bucket == null) {
            return;
        }
        Subscription match = null;

        synchronized (bucket) {
            for (Subscription candidate : bucket.entries) {
                if (candidate.emitter == emitter) {
                    match = candidate;
                    break;
                }
            }
        }
        if (match != null) {
            unsubscribe(match);
        }
    }

    /**
     * 移除匹配的订阅并回收没有监听者的文档订阅组。
     * 上游为 DocumentController.streamTask、TaskStatusEventBus.sendInitialSnapshotIfUnchanged。
     *
     * @param subscription 记录订阅关系、发送器和发布版本的句柄
     */
    public void unsubscribe(Subscription subscription) {
        if (subscription == null) {
            return;
        }
        subscribers.computeIfPresent(subscription.documentId, (id, current) -> {
            if (current != subscription.bucket) {
                return current;
            }
            synchronized (current) {
                subscription.active = false;
                current.entries.remove(subscription);
                return current.entries.isEmpty() ? null : current;
            }
        });
    }

    /**
     * 仅在订阅仍有效且发布版本未变时发送初始快照。
     * 上游为 DocumentController.streamTask。
     * 下游为 TaskStatusEventBus.unsubscribe。
     *
     * @param subscription 记录订阅关系、发送器和发布版本的句柄
     * @param snapshot 本次操作使用的 DocumentTaskDTO 组件或记录
     * @return 初始快照成功送达且期间没有更新时为 true
     */
    public boolean sendInitialSnapshotIfUnchanged(Subscription subscription, DocumentTaskDTO snapshot) {
        // 初始快照只能在发布版本与订阅登记时一致的情况下下发。
        if (subscription == null) {
            return false;
        }
        Bucket bucket = subscription.bucket;
        Exception sendFailure = null;

        synchronized (bucket) {
            if (!subscription.active || subscribers.get(subscription.documentId) != bucket
                    || bucket.revision != subscription.registeredRevision) {
                return false;
            }
            try {
                subscription.emitter.send(SseEmitter.event().name("task").data(snapshot));
                return true;
            } catch (IOException | RuntimeException e) {
                sendFailure = e;
            }
        }
        log.warn("SSE 初始快照发送失败，移除订阅 documentId={}", subscription.documentId, sendFailure);

        try {
            subscription.emitter.completeWithError(sendFailure);
        } catch (RuntimeException completionFailure) {
            log.debug("SSE 初始快照失败后的完成回调也失败，移除订阅 documentId={}",
                    subscription.documentId, completionFailure);
        } finally {
            unsubscribe(subscription);
        }
        return false;
    }

    /**
     * 发布已经持久化的文档任务状态，并让其他节点补读最新快照。
     * 上游为 DocumentProcessServiceImpl.processAttempt、TaskCompletionService.publish。
     * 下游为 TaskStatusBroadcast.latestTask、TaskStatusBroadcast.notifyDocument。
     *
     * @param task 任务记录
     */
    public void publish(DocumentTask task) {
        Long documentId = task.getDocumentId();
        if (broadcast == null) {
            deliver(documentId, () -> task, false);
            return;
        }
        try {
            refresh(documentId, () -> broadcast.latestTask(documentId));
        } finally {
            broadcast.notifyDocument(documentId);
        }
    }

    /**
     * 使用订阅侧提供的数据库快照来源刷新文档监听者。
     * 上游为 TaskStatusBroadcast.taskStatusListener、TaskStatusEventBus.publish。
     * 下游为 TaskStatusEventBus.deliver。
     *
     * @param documentId 文档 ID
     * @param latestTask 由此处在对应上下文或作用域内执行的 Supplier&lt;DocumentTask&gt; 回调
     */
    void refresh(Long documentId, Supplier<DocumentTask> latestTask) {
        deliver(documentId, latestTask, true);
    }

    /**
     * 串行读取并向当前文档订阅者发送快照，只清理失败的连接。
     * 上游为 TaskStatusEventBus.publish、TaskStatusEventBus.refresh。
     * 下游为 TaskDtoConverter.toDto。
     *
     * @param documentId 文档 ID
     * @param snapshot 由此处在对应上下文或作用域内执行的 Supplier&lt;DocumentTask&gt; 回调
     * @param deduplicate 本方法计算或判断使用的 deduplicate 值
     */
    private void deliver(Long documentId, Supplier<DocumentTask> snapshot, boolean deduplicate) {
        Bucket bucket = subscribers.get(documentId);
        if (bucket == null) {
            return;
        }
        ArrayList<Subscription> failed = new ArrayList<>();

        synchronized (bucket) {
            if (subscribers.get(documentId) != bucket) {
                return;
            }
            // Read inside the per-document lock so overlapping notifications cannot send an older DB view last.
            DocumentTask task = snapshot.get();
            if (task == null) return;
            DocumentTaskDTO dto = TaskDtoConverter.toDto(task);
            if (deduplicate && dto.equals(bucket.lastPublished)) return;
            bucket.lastPublished = dto;
            bucket.revision++;
            for (Subscription subscription : new ArrayList<>(bucket.entries)) {
                if (!subscription.active) {
                    continue;
                }
                try {
                    subscription.emitter.send(SseEmitter.event().name("task").data(dto));
                } catch (Exception e) {
                    log.warn("SSE 发送失败，移除订阅 documentId={}", documentId, e);
                    failed.add(subscription);
                }
            }
        }

        for (Subscription subscription : failed) {
            try {
                subscription.emitter.complete();
            } catch (RuntimeException completionFailure) {
                log.debug("SSE 发送失败后的完成回调也失败，移除订阅 documentId={}",
                        documentId, completionFailure);
            } finally {
                unsubscribe(subscription);
            }
        }
    }

    /** 记录连接、所属文档桶及注册时的事件序号，用于快照排序和注销。 */
    public static final class Subscription {
        private final Long documentId;
        private final SseEmitter emitter;
        private final Bucket bucket;
        private final long registeredRevision;
        private boolean active = true;

        /**
         * 建立 Subscription，将调用方提供的初始值写入其状态。
         *
         * @param documentId 文档 ID
         * @param emitter 向当前浏览器连接写入 SSE 的发送器
         * @param bucket 对象存储桶
         * @param registeredRevision 初始化或更新同名字段的值
         */
        private Subscription(Long documentId, SseEmitter emitter, Bucket bucket, long registeredRevision) {
            this.documentId = documentId;
            this.emitter = emitter;
            this.bucket = bucket;
            this.registeredRevision = registeredRevision;
        }
    }

    /** 同一文档的订阅集合及事件序号，也是文档级同步锁。 */
    private static final class Bucket {
        private final Set<Subscription> entries = new LinkedHashSet<>();
        private long revision;
        private DocumentTaskDTO lastPublished;
    }
}
