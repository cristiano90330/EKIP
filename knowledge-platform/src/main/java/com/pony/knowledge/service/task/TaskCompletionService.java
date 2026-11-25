/* 在数据库锁和租约约束下提交文档任务状态并发布快照。 */

package com.pony.knowledge.service.task;

import com.pony.knowledge.config.TaskReliabilityProperties;
import com.pony.knowledge.dto.DocumentTaskDTO;
import com.pony.knowledge.entity.Document;
import com.pony.knowledge.entity.DocumentStatus;
import com.pony.knowledge.entity.DocumentTask;
import com.pony.knowledge.entity.DocumentTaskStatus;
import com.pony.knowledge.exception.ForbiddenException;
import com.pony.knowledge.mapper.DocumentMapper;
import com.pony.knowledge.mapper.DocumentTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;

/**
 * 统一提交任务与文档的关联状态。解析和模型调用在此之前完成。
 * 先锁定 document，再变更状态和必要的切片数据；事务提交后才向进程内 SSE 总线通知。
 * 通知异常只记日志，不改变已经持久化的结果。
 */
@Service
public class TaskCompletionService {
    private static final Logger log = LoggerFactory.getLogger(TaskCompletionService.class);
    private static final String DISPATCH_REJECTED_CODE = "TASK_DISPATCH_REJECTED";
    private static final String DISPATCH_REJECTED_MESSAGE = "后台任务队列暂时繁忙，等待恢复扫描后自动继续";
    private final DocumentMapper documents;
    private final DocumentTaskMapper tasks;
    private final TaskStatusEventBus events;
    private final TransactionTemplate transaction;
    private final TaskReliabilityProperties properties;

    /**
     * 保存在数据库锁和租约约束下提交文档任务状态并发布快照所需的依赖和初始配置。
     * 上游为 Spring 组件装配。
     *
     * @param documents DocumentMapper，负责提供文档查询、切片分页、写入锁定和对象引用查询
     * @param tasks 本次操作使用的 DocumentTaskMapper 组件或记录
     * @param events 本次操作使用的 TaskStatusEventBus 组件或记录
     * @param transactionManager 用于创建或参与数据库事务的事务管理器
     * @param properties 供本组件执行时读取的配置约束
     */
    public TaskCompletionService(DocumentMapper documents, DocumentTaskMapper tasks,
                                 TaskStatusEventBus events, PlatformTransactionManager transactionManager,
                                 TaskReliabilityProperties properties) {
        this.documents = documents;
        this.tasks = tasks;
        this.events = events;
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactionManager);
        // 每次状态提交使用独立事务，使 execute 返回后再发布的通知对应已提交记录。
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 锁定文档并尝试更新任务处理租约，成功时返回持有租约的任务快照。
     * 上游为 DocumentProcessServiceImpl.process。
     * 下游为 DocumentMapper.selectByIdForUpdate、DocumentTaskMapper.selectById。
     *
     * @param taskId 文档任务 ID
     * @param documentId 文档 ID
     * @return 取得执行权后的任务快照，认领失败时为空
     */
    public DocumentTask claim(Long taskId, Long documentId) {
        DocumentTask claimed = transaction.execute(status -> {
            if (documents.selectByIdForUpdate(documentId) == null) return null;
            DocumentTask current = tasks.selectById(taskId);
            if (current == null || !documentId.equals(current.getDocumentId())
                    || tasks.claimProcessing(taskId, properties.getLeaseDurationMs(), properties.getStuckTimeoutMs()) == 0) {
                return null;
            }
            updateDocument(documentId, DocumentStatus.PROCESSING);
            return requireSnapshot(taskId);
        });
        if (claimed != null) publish(claimed);
        return claimed;
    }

    /**
     * 按任务 ID、尝试次数和处理状态判断快照是否对应当前尝试。
     * 上游为 DocumentProcessServiceImpl.processAttempt、TaskCompletionService.finishLocked。
     * 下游为 DocumentTask.getStatus、DocumentTask.getId。
     *
     * @param attempt 处理尝试对应的任务快照
     * @param current 这次运行的当前结果或状态
     * @return ID、尝试号及处理状态仍对应当前执行时为 true
     */
    public static boolean isCurrentAttempt(DocumentTask attempt, DocumentTask current) {
        return current != null && current.getStatus() == DocumentTaskStatus.PROCESSING
                && Objects.equals(attempt.getId(), current.getId())
                && Objects.equals(attempt.getTotalAttemptCount(), current.getTotalAttemptCount());
    }

    /**
     * 在完成事务内执行切片写入并更新本次尝试的成功终态。
     * 上游为 DocumentChunkServiceImpl.completeTask。
     * 下游为 TaskCompletionService.finishLocked、TaskCompletionService.publishAfterCommit。
     *
     * @param attempt 处理尝试对应的任务快照
     * @param writeChunks 由此处在对应上下文或作用域内执行的 Runnable 回调
     * @return 本次写入仍符合任务状态及尝试约束且成功完成时为 true
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean completeAttempt(DocumentTask attempt, Runnable writeChunks) {
        DocumentTask completed = finishLocked(attempt, DocumentStatus.COMPLETED, null, null, writeChunks);
        if (completed == null) return false;
        publishAfterCommit(completed);
        return true;
    }

    /**
     * 锁定文档，清除任务租约并提交取消结果。
     * 上游为 DocumentTaskServiceImpl.cancel。
     * 下游为 DocumentMapper.selectByIdForUpdate、DocumentTaskMapper.selectById。
     *
     * @param taskId 文档任务 ID
     * @param documentId 文档 ID
     * @return 本次得到的 DocumentTaskDTO 实例
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public DocumentTaskDTO cancel(Long taskId, Long documentId) {
        if (documents.selectByIdForUpdate(documentId) == null) {
            throw new ForbiddenException("无权操作该文档任务");
        }
        DocumentTask current = tasks.selectById(taskId);
        if (current == null || !documentId.equals(current.getDocumentId())) {
            throw new ForbiddenException("无权操作该文档任务");
        }
        if (tasks.markCancelled(taskId) == 0) return TaskDtoConverter.toDto(current);
        // Older deployments could have committed derived chunks before the terminal update.
        documents.deleteChunks(documentId);
        updateDocument(documentId, DocumentStatus.CANCELLED);
        DocumentTask cancelled = requireSnapshot(taskId);
        publishAfterCommit(cancelled);
        return TaskDtoConverter.toDto(cancelled);
    }

    /**
     * 为仍持有执行权的尝试提交失败终态。
     * 上游为 DocumentProcessServiceImpl.processAttempt。
     * 下游为 TaskCompletionService.finish。
     *
     * @param attempt 处理尝试对应的任务快照
     * @param errorMessage 可记录或返回的错误详情
     * @param errorCode 稳定的错误分类码
     * @return 符合本方法描述的状态或完成条件时为 true
     */
    public boolean markFailed(DocumentTask attempt, String errorMessage, String errorCode) {
        return finish(attempt, DocumentStatus.FAILED, errorMessage, errorCode);
    }

    /**
     * 把重试安排失败的尝试交回等待扫描，保留数据库恢复条件。
     * 上游为 DocumentProcessServiceImpl.processAttempt、TaskDispatchCoordinator.scheduleRetry。
     * 下游为 TaskCompletionService.finish、DocumentTask.getId。
     *
     * @param attempt 处理尝试对应的任务快照
     * @param rejection 当前流程捕获的 RuntimeException 异常
     */
    public void recoverRejectedRetry(DocumentTask attempt, RuntimeException rejection) {
        if (finish(attempt, DocumentStatus.UPLOADED, DISPATCH_REJECTED_MESSAGE, DISPATCH_REJECTED_CODE)) {
            log.warn("自动重试投递被拒，任务转为 WAITING 且等待恢复扫描 taskId={} reasonCode={}",
                    attempt.getId(), DISPATCH_REJECTED_CODE, rejection);
        } else {
            log.warn("自动重试投递被拒，原尝试已失效 taskId={} attempt={}",
                    attempt.getId(), attempt.getTotalAttemptCount(), rejection);
        }
    }

    /**
     * 开启事务提交尝试终态，并在提交成功后发布任务快照。
     * 上游为 TaskCompletionService.markFailed、TaskCompletionService.recoverRejectedRetry。
     * 下游为 TaskCompletionService.finishLocked、TaskCompletionService.publish。
     *
     * @param attempt 处理尝试对应的任务快照
     * @param documentStatus 需要写入或展示的文档状态
     * @param errorMessage 可记录或返回的错误详情
     * @param errorCode 稳定的错误分类码
     * @return 符合本方法描述的状态或完成条件时为 true
     */
    private boolean finish(DocumentTask attempt, DocumentStatus documentStatus,
                           String errorMessage, String errorCode) {
        DocumentTask completed = transaction.execute(status -> finishLocked(attempt,
                documentStatus, errorMessage, errorCode, null));
        if (completed == null) return false;
        publish(completed);
        return true;
    }

    /**
     * 锁定文档后再次核对尝试和任务状态，再执行写入并更新文档终态。
     * 上游为 TaskCompletionService.completeAttempt、TaskCompletionService.finish。
     * 下游为 DocumentMapper.selectByIdForUpdate、DocumentTaskMapper.selectById。
     *
     * @param attempt 处理尝试对应的任务快照
     * @param documentStatus 需要写入或展示的文档状态
     * @param errorMessage 可记录或返回的错误详情
     * @param errorCode 稳定的错误分类码
     * @param writeChunks 由此处在对应上下文或作用域内执行的 Runnable 回调
     * @return 后台文档任务、重试次数、处理时间与错误状态，以 DocumentTask 返回
     */
    private DocumentTask finishLocked(DocumentTask attempt, DocumentStatus documentStatus,
                                      String errorMessage, String errorCode, Runnable writeChunks) {
        Long taskId = attempt.getId();
        Long documentId = attempt.getDocumentId();
        if (documents.selectByIdForUpdate(documentId) == null) return null;

        // 持有文档行锁后才读取任务，防止取消、重试和旧工作线程的完成操作交错。
        DocumentTask current = tasks.selectById(taskId);
        if (!isCurrentAttempt(attempt, current) || !documentId.equals(current.getDocumentId())) return null;
        int attemptNumber = attempt.getTotalAttemptCount();
        int changed = switch (documentStatus) {
            case COMPLETED -> tasks.markSuccess(taskId, attemptNumber);
            case FAILED -> tasks.markFailed(taskId, attemptNumber, errorMessage, errorCode);
            case UPLOADED -> tasks.markWaitingAfterRetryScheduleFailure(taskId, attemptNumber, errorMessage, errorCode);
            default -> throw new IllegalArgumentException("Unsupported completion status: " + documentStatus);
        };
        if (changed == 0) return null;
        if (writeChunks != null) writeChunks.run();
        updateDocument(documentId, documentStatus);
        return requireSnapshot(taskId);
    }

    /**
     * 在完成事务中更新对应文档的状态，数据库写入失败时抛出异常。
     * 上游为 TaskCompletionService.claim、TaskCompletionService.cancel。
     * 下游为 DocumentMapper.updateById。
     *
     * @param documentId 文档 ID
     * @param documentStatus 需要写入或展示的文档状态
     */
    private void updateDocument(Long documentId, DocumentStatus documentStatus) {
        Document patch = new Document();
        patch.setId(documentId);
        patch.setStatus(documentStatus);

        if (documents.updateById(patch) != 1) {
            throw new IllegalStateException("Document status update affected no row: " + documentId);
        }
    }

    /**
     * 重新读取事务内的任务状态，记录缺失时阻止返回空快照。
     * 上游为 TaskCompletionService.claim、TaskCompletionService.cancel。
     * 下游为 DocumentTaskMapper.selectById。
     *
     * @param taskId 文档任务 ID
     * @return 后台文档任务、重试次数、处理时间与错误状态，以 DocumentTask 返回
     */
    private DocumentTask requireSnapshot(Long taskId) {
        DocumentTask snapshot = tasks.selectById(taskId);
        if (snapshot == null) throw new IllegalStateException("Task disappeared during completion: " + taskId);
        return snapshot;
    }

    /**
     * 将状态发布注册为事务提交后的回调。
     * 上游为 TaskCompletionService.completeAttempt、TaskCompletionService.cancel。
     * 下游为 TransactionSynchronizationManager.registerSynchronization。
     *
     * @param task 任务记录
     */
    private void publishAfterCommit(DocumentTask task) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            /**
             * 事务提交后执行本次登记的状态通知或任务投递。
             * 上游为 事务同步管理器的提交后回调。
             */
            @Override public void afterCommit() { publish(task); }
        });
    }

    /**
     * 向任务事件总线分发已提交的任务状态，发送失败只记录告警。
     * 上游为 TaskCompletionService.claim、TaskCompletionService.finish。
     * 下游为 TaskStatusEventBus.publish。
     *
     * @param task 任务记录
     */
    private void publish(DocumentTask task) {
        try {
            events.publish(task);
        } catch (RuntimeException publicationFailure) {
            log.warn("任务状态已提交，事件发布失败 taskId={}", task.getId(), publicationFailure);
        }
    }
}
