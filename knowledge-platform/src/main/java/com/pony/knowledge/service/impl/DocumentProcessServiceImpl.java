/* 执行后台文档任务，协调任务认领、AI 解析、切片入库和失败重试。 */

package com.pony.knowledge.service.impl;

import com.pony.knowledge.config.TaskReliabilityProperties;
import com.pony.knowledge.entity.Document;
import com.pony.knowledge.entity.DocumentTask;
import com.pony.knowledge.mapper.DocumentMapper;
import com.pony.knowledge.mapper.DocumentTaskMapper;
import com.pony.knowledge.service.DocumentChunkService;
import com.pony.knowledge.service.DocumentProcessService;
import com.pony.knowledge.service.ai.AiServiceClient;
import com.pony.knowledge.service.ai.DocumentParseCancellation;
import com.pony.knowledge.service.task.TaskFailureClassifier;
import com.pony.knowledge.service.task.TaskFailureKind;
import com.pony.knowledge.service.task.TaskDispatchCoordinator;
import com.pony.knowledge.service.task.TaskLeaseHeartbeat;
import com.pony.knowledge.service.task.TaskStatusEventBus;
import com.pony.knowledge.service.task.TaskCompletionService;
import com.pony.knowledge.utils.FileExtensionUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class DocumentProcessServiceImpl implements DocumentProcessService {

    private static final Logger log = LoggerFactory.getLogger(DocumentProcessServiceImpl.class);

    private final DocumentMapper documentMapper;

    private final DocumentTaskMapper documentTaskMapper;

    private final AiServiceClient aiServiceClient;

    private final String bucketName;

    private final DocumentChunkService documentChunkService;

    private final TaskFailureClassifier classifier;

    private final TaskDispatchCoordinator dispatchCoordinator;

    private final TaskStatusEventBus eventBus;

    private final TaskReliabilityProperties properties;
    private final TaskCompletionService completionService;
    private final TaskLeaseHeartbeat heartbeat;

    /**
     * 保存执行后台文档任务所需的依赖和初始配置。
     * 上游为 Spring 组件装配。
     *
     * @param documentMapper DocumentMapper，负责提供文档查询、切片分页、写入锁定和对象引用查询
     * @param documentTaskMapper 本次操作使用的 DocumentTaskMapper 组件或记录
     * @param aiServiceClient AiServiceClient，负责装配 Java 到 AI 服务的内部 HTTP 请求，承接解析、嵌入、问答与能力检查
     * @param documentChunkService DocumentChunkService，负责约定文档切片和向量的替换以及任务终态的原子提交
     * @param bucketName 对象存储桶名
     * @param classifier 本次操作使用的 TaskFailureClassifier 组件或记录
     * @param dispatchCoordinator 本次操作使用的 TaskDispatchCoordinator 组件或记录
     * @param eventBus 本次操作使用的 TaskStatusEventBus 组件或记录
     * @param properties 供本组件执行时读取的配置约束
     * @param completionService 本次操作使用的 TaskCompletionService 组件或记录
     * @param heartbeat 本次操作使用的 TaskLeaseHeartbeat 组件或记录
     */
    public DocumentProcessServiceImpl(
            DocumentMapper documentMapper,
            DocumentTaskMapper documentTaskMapper,
            AiServiceClient aiServiceClient,
            DocumentChunkService documentChunkService,
            @Value("${minio.bucket-name}") String bucketName,
            TaskFailureClassifier classifier,
            TaskDispatchCoordinator dispatchCoordinator,
            TaskStatusEventBus eventBus,
            TaskReliabilityProperties properties,
            TaskCompletionService completionService,
            TaskLeaseHeartbeat heartbeat) {
        this.documentMapper = documentMapper;
        this.documentTaskMapper = documentTaskMapper;
        this.aiServiceClient = aiServiceClient;
        this.bucketName = bucketName;
        this.documentChunkService = documentChunkService;
        this.classifier = classifier;
        this.dispatchCoordinator = dispatchCoordinator;
        this.eventBus = eventBus;
        this.properties = properties;
        this.completionService = completionService;
        this.heartbeat = heartbeat;
    }

    /**
     * 读取任务和文档，取得数据库租约后委派本次解析并关闭续租登记。
     * 上游为 TaskDispatchCoordinator.submitReserved 提交的文档工作任务。
     * 下游为 TaskCompletionService.claim 和本类的 processAttempt。
     *
     * @param taskId 文档任务 ID
     */
    @Override
    public void process(Long taskId) {
        DocumentTask task = documentTaskMapper.selectById(taskId);
        if (task == null) {
            log.warn("任务不存在，忽略 taskId={}", taskId);
            return;
        }
        Document document = documentMapper.selectById(task.getDocumentId());
        if (document == null) {
            log.warn("文档不存在，忽略 taskId={} documentId={}", taskId, task.getDocumentId());
            return;
        }

        // 多个工作节点可能收到同一任务，只有取得数据库租约的节点才开始解析。
        DocumentTask attempt = completionService.claim(taskId, document.getId());
        if (attempt == null) return;

        // 续租覆盖本次解析和切片写入，在工作返回后停止。
        var cancellation = new DocumentParseCancellation();
        try (var lease = heartbeat.maintain(attempt, cancellation::cancel)) {
            processAttempt(taskId, document, attempt, cancellation);
        }
    }

    /**
     * 调用 AI 解析并提交切片，按失败种类和次数决定重试或终止。
     * 上游为 DocumentProcessServiceImpl.process。
     * 下游为 AiServiceClient.parseDocument、DocumentChunkService.completeTask 和 TaskDispatchCoordinator.scheduleRetry。
     *
     * @param taskId 文档任务 ID
     * @param document 文档记录
     * @param attempt 处理尝试对应的任务快照
     * @param cancellation 租约失效时用于停止本次 Python 调用的信号
     */
    private void processAttempt(Long taskId, Document document, DocumentTask attempt,
                                DocumentParseCancellation cancellation) {
        try {
            String fileExtension = FileExtensionUtil.of(document.getFileName());
            AiServiceClient.ParseResult result = aiServiceClient.parseDocument(
                    document.getId(), document.getFilePath(), bucketName, fileExtension, cancellation);

            // 模型调用期间任务可能已取消或被接管，提前拦截迟到结果，最终写入仍以提交事务内的条件为准。
            if (cancellation.isCancelled()
                    || !TaskCompletionService.isCurrentAttempt(attempt, documentTaskMapper.selectById(taskId))) return;
            if (documentChunkService.completeTask(attempt, document.getKnowledgeBaseId(), result.getChunks())) {
                log.info("文档解析成功, documentId={}, taskId={}", document.getId(), taskId);
            }
        } catch (Exception e) {
            if (cancellation.isCancelled()) return;
            DocumentTask fresh = documentTaskMapper.selectById(taskId);
            if (!TaskCompletionService.isCurrentAttempt(attempt, fresh)) return;
            int retryCount = fresh.getAutoRetryCount();
            String errorMsg = boundedErrorMessage(e);
            String errorCode = classifier.reasonCode(e);
            if (classifier.classify(e) == TaskFailureKind.TRANSIENT
                    && retryCount < properties.getMaxAttempts()) {
                long delayMs = properties.backoffMs(retryCount);

                // 先把失败和重试时间写进数据库，再交给本地调度器。调度器不可用时恢复扫描仍可发现任务。
                if (documentTaskMapper.recordRetryFailure(taskId, attempt.getTotalAttemptCount(), delayMs, errorMsg, errorCode) == 0) {
                    return;
                }
                fresh.setErrorMessage(errorMsg);
                fresh.setErrorCode(errorCode);
                fresh.setLastErrorMessage(errorMsg);
                fresh.setLastErrorCode(errorCode);
                try {
                    dispatchCoordinator.scheduleRetry(attempt, delayMs);
                } catch (RuntimeException dispatchFailure) {
                    completionService.recoverRejectedRetry(attempt, dispatchFailure);
                    return;
                }
                try {
                    eventBus.publish(fresh);
                } catch (RuntimeException publicationFailure) {
                    log.warn("自动重试已登记，任务状态通知失败 taskId={}", taskId, publicationFailure);
                }
                log.warn("文档解析 transient 失败，计划第 {} 次重试（最多 {} 次），taskId={}, delayMs={}, reasonCode={}",
                        retryCount + 1, properties.getMaxAttempts(), taskId, delayMs, errorCode, e);
            } else {
                if (completionService.markFailed(attempt, errorMsg, errorCode)) {
                    log.error("文档解析失败, documentId={}, taskId={}, attempt={}", document.getId(), taskId, retryCount, e);
                }
            }
        }
    }

    /**
     * 提取异常信息并限制对外任务错误文本的长度。
     * 上游为 DocumentProcessServiceImpl.processAttempt。
     *
     * @param e 本次执行捕获的异常
     * @return 最多 1000 个字符的任务错误说明
     */
    private String boundedErrorMessage(Exception e) {
        String errorMsg = e.getMessage() == null ? e.toString() : e.getMessage();
        return errorMsg.length() > 1000 ? errorMsg.substring(0, 1000) : errorMsg;
    }
}
