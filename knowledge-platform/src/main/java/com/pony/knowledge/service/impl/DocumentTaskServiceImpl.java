/* 依据文档读取与维护权限提供任务状态、重试和取消。 */

package com.pony.knowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.pony.knowledge.dto.DocumentTaskDTO;
import com.pony.knowledge.entity.Document;
import com.pony.knowledge.entity.DocumentTask;
import com.pony.knowledge.entity.DocumentStatus;
import com.pony.knowledge.entity.DocumentTaskStatus;
import com.pony.knowledge.exception.BusinessException;
import com.pony.knowledge.exception.ForbiddenException;
import com.pony.knowledge.mapper.DocumentMapper;
import com.pony.knowledge.mapper.DocumentTaskMapper;
import com.pony.knowledge.mapper.DocumentUploadReceiptMapper;
import com.pony.knowledge.service.KnowledgeBaseAccessService;
import com.pony.knowledge.service.task.TaskDispatchCoordinator;
import com.pony.knowledge.service.task.TaskCompletionService;
import com.pony.knowledge.service.task.TaskDtoConverter;
import com.pony.knowledge.service.DocumentTaskService;
import com.pony.knowledge.utils.CurrentUserUtil;
import org.springframework.stereotype.Service;

/**
 * 创建者和成员可读最新任务；取消与手动重试由创建者执行。
 */
@Service
public class DocumentTaskServiceImpl implements DocumentTaskService {
    private final DocumentTaskMapper taskMapper;

    private final TaskDispatchCoordinator taskDispatchCoordinator;

    private final DocumentMapper documentMapper;

    private final CurrentUserUtil currentUserUtil;

    private final KnowledgeBaseAccessService knowledgeBaseAccessService;
    private final TaskCompletionService completion;
    private final DocumentUploadReceiptMapper receipts;
    private com.pony.knowledge.modelmanagement.index.KnowledgeIndexService indexes;

    /**
     * 配置重试和取消时使用的知识库写入屏障。
     *
     * @param indexes 本次操作所用的 com.pony.knowledge.modelmanagement.index.KnowledgeIndexService 数据
     */
    @org.springframework.beans.factory.annotation.Autowired
    public void setKnowledgeIndexService(com.pony.knowledge.modelmanagement.index.KnowledgeIndexService indexes) {
        this.indexes = indexes;
    }

    /**
     * 保存依据文档读取与维护权限提供任务状态、重试和取消所需的依赖和初始配置。
     * 上游为 Spring 组件装配。
     *
     * @param taskMapper 本次操作使用的 DocumentTaskMapper 组件或记录
     * @param taskDispatchCoordinator 本次操作使用的 TaskDispatchCoordinator 组件或记录
     * @param documentMapper DocumentMapper，负责提供文档查询、切片分页、写入锁定和对象引用查询
     * @param currentUserUtil 本次操作使用的 CurrentUserUtil 组件或记录
     * @param knowledgeBaseAccessService KnowledgeBaseAccessService，负责约定知识库可读范围和创建者操作的权限判定
     * @param completion 本次操作使用的 TaskCompletionService 组件或记录
     * @param receipts 本次操作使用的 DocumentUploadReceiptMapper 组件或记录
     */
    public DocumentTaskServiceImpl(
            DocumentTaskMapper taskMapper,
            TaskDispatchCoordinator taskDispatchCoordinator,
            DocumentMapper documentMapper,
            CurrentUserUtil currentUserUtil,
            KnowledgeBaseAccessService knowledgeBaseAccessService,
            TaskCompletionService completion,
            DocumentUploadReceiptMapper receipts
    ){
        this.taskMapper = taskMapper;
        this.taskDispatchCoordinator = taskDispatchCoordinator;
        this.documentMapper = documentMapper;
        this.currentUserUtil = currentUserUtil;
        this.knowledgeBaseAccessService = knowledgeBaseAccessService;
        this.completion = completion;
        this.receipts = receipts;
    }

    /**
     * 按文档所属知识库检查请求者的读取权限。
     * 上游为 DocumentTaskServiceImpl.getTask。
     * 下游为 DocumentMapper.selectById、KnowledgeBaseAccessService.canRead。
     *
     * @param documentId 文档 ID
     */
    private void checkDocumentReadAccess(Long documentId) {
        Long userId = currentUserUtil.getUserId();
        Document document = documentMapper.selectById(documentId);
        if (document == null || !knowledgeBaseAccessService.canRead(userId, document.getKnowledgeBaseId())) {
            throw new ForbiddenException("无权查看该文档任务");
        }
    }

    /**
     * 按登录用户和文档 ID 查找本人可维护的文档，缺失或越权时拒绝。
     * 上游为 DocumentTaskServiceImpl.retry、DocumentTaskServiceImpl.cancel。
     * 下游为 DocumentMapper.selectOwnedDocument。
     *
     * @param documentId 文档 ID
     * @return 知识库文档的对象位置、元数据和处理状态，以 Document 返回
     */
    private Document requireDocumentOwnership(Long documentId) {
        Long userId = currentUserUtil.getUserId();
        Document document = documentMapper.selectOwnedDocument(documentId, userId);
        if (document == null) {
            throw new ForbiddenException("无权操作该文档");
        }
        return document;
    }

    /**
     * 检查文档读取权并将最新任务映射为前端状态快照。
     * 上游为 DocumentController.streamTask、DocumentController.retry。
     * 下游为 TaskDtoConverter.toDto。
     *
     * @param documentId 文档 ID
     * @return 本次得到的 DocumentTaskDTO 实例
     */
    @Override
    public DocumentTaskDTO getTask(Long documentId){

        checkDocumentReadAccess(documentId);

        return TaskDtoConverter.toDto(latestTask(documentId));
    }

    /**
     * 在写入屏障内重置或重建任务，在事务提交后安排执行。
     * 上游为 DocumentController.retry。
     * 下游为 KnowledgeIndexService.acquireWriteBarrier、DocumentMapper.selectByIdForUpdate。
     *
     * @param documentId 文档 ID
     */
    @Override
    @org.springframework.transaction.annotation.Transactional
    public void retry(Long documentId){

        Document document = requireDocumentOwnership(documentId);
        if (indexes == null) throw new IllegalStateException("KnowledgeIndexService is required");

        // 先取得知识库写入屏障，再读取并修改任务，避免与索引代际切换交错。
        indexes.acquireWriteBarrier(document.getKnowledgeBaseId());
        if (documentMapper.selectByIdForUpdate(documentId) == null) throw new ForbiddenException("无权操作该文档");

        DocumentTask task = latestTask(documentId);
        Long taskId = task.getId();

        if (task.getStatus() == DocumentTaskStatus.CANCELLED) {
            taskId = createReplacement(task);
        } else if (taskMapper.retryTask(taskId) == 0) {
            throw new BusinessException("任务当前不可重试");
        }
        Document patch = new Document();
        patch.setId(documentId);
        patch.setStatus(DocumentStatus.UPLOADED);
        if (documentMapper.updateById(patch) != 1) throw new IllegalStateException("无法更新文档等待状态");

        Long nextTaskId = taskId;

        // 处理线程只能读取已提交的数据，重试投递登记在事务完成后。
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    /**
                     * 事务提交后执行本次登记的状态通知或任务投递。
                     * 上游为 事务同步管理器的提交后回调。
                     */
                    @Override public void afterCommit() { dispatchRetriedTask(nextTaskId); }
                });
    }

    /**
     * 检查文档维护权与索引写入屏障，再提交指定处理任务的取消状态。
     * 上游为 DocumentController.cancelTask。
     * 下游为 KnowledgeIndexService.acquireWriteBarrier、TaskCompletionService.cancel。
     *
     * @param documentId 文档 ID
     * @param taskId 文档任务 ID
     * @return 本次得到的 DocumentTaskDTO 实例
     */
    @Override
    @org.springframework.transaction.annotation.Transactional
    public DocumentTaskDTO cancel(Long documentId, Long taskId) {
        Document document = requireDocumentOwnership(documentId);
        indexes.acquireWriteBarrier(document.getKnowledgeBaseId());
        return completion.cancel(taskId, documentId);
    }

    /**
     * 按任务 ID 倒序查找该文档最近创建的任务。
     * 上游为 DocumentTaskServiceImpl.retry。
     * 下游为 DocumentTaskMapper.selectOne。
     *
     * @param documentId 文档 ID
     * @return 后台文档任务、重试次数、处理时间与错误状态，以 DocumentTask 返回
     */
    private DocumentTask latestTask(Long documentId) {
        DocumentTask task = taskMapper.selectOne(new QueryWrapper<DocumentTask>()
                .eq("document_id", documentId).orderByDesc("id").last("LIMIT 1"));
        if (task == null) throw new BusinessException("任务不存在");
        return task;
    }

    /**
     * 创建取消后的新尝试，并将上传回执重新关联到新任务。
     * 上游为 DocumentTaskServiceImpl.retry。
     * 下游为 DocumentTaskMapper.insert、DocumentUploadReceiptMapper.followRetriedTask。
     *
     * @param cancelled 本次操作使用的 DocumentTask 组件或记录
     * @return 取消后的替换任务 ID
     */
    private Long createReplacement(DocumentTask cancelled) {
        DocumentTask task = new DocumentTask();
        task.setDocumentId(cancelled.getDocumentId());
        task.setStatus(DocumentTaskStatus.WAITING);
        task.setAutoRetryCount(0);
        task.setTotalAttemptCount(cancelled.getTotalAttemptCount());
        task.setManualRetryCount(cancelled.getManualRetryCount() + 1);
        task.setLastErrorMessage(cancelled.getLastErrorMessage());
        task.setLastErrorCode(cancelled.getLastErrorCode());
        if (taskMapper.insert(task) != 1 || task.getId() == null) {
            throw new IllegalStateException("无法创建新的文档任务");
        }
        receipts.followRetriedTask(task.getDocumentId(), cancelled.getId(), task.getId());
        return task.getId();
    }

    /**
     * 尝试投递已提交的重试任务，队列拒绝时由恢复扫描接管。
     * 下游为 TaskDispatchCoordinator.dispatch。
     *
     * @param taskId 文档任务 ID
     */
    private void dispatchRetriedTask(Long taskId) {
        try {
            taskDispatchCoordinator.dispatch(taskId);
        } catch (RuntimeException dispatchFailure) {
            // Retry already committed as WAITING. The bounded WAITING scanner will hand it off;
            // returning success here avoids prompting the user to create another retry action.
            org.slf4j.LoggerFactory.getLogger(DocumentTaskServiceImpl.class)
                    .warn("手动重试已进入 WAITING，立即投递失败，等待恢复扫描 taskId={}", taskId, dispatchFailure);
        }
    }
}
