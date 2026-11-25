/* 在索引写入约束下删除知识库和数据库关联，再清理原文件。 */

package com.pony.knowledge.service;

import com.pony.knowledge.audit.AuditAction;
import com.pony.knowledge.audit.AuditLogService;
import com.pony.knowledge.exception.ForbiddenException;
import com.pony.knowledge.mapper.DocumentMapper;
import com.pony.knowledge.mapper.KnowledgeBaseMapper;
import com.pony.knowledge.modelmanagement.index.KnowledgeIndexService;
import com.pony.knowledge.service.storage.MinioService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

@Service
public class KnowledgeBaseRemovalService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseRemovalService.class);

    private final KnowledgeBaseService knowledgeBases;
    private final KnowledgeBaseMapper libraries;
    private final DocumentMapper documents;
    private final KnowledgeIndexService indexes;
    private final MinioService minio;
    private final AuditLogService audit;
    private final TransactionTemplate deleteTransaction;

    /**
     * 保存在索引写入约束下删除知识库和数据库关联所需的依赖和初始配置。
     * 上游为 Spring 组件装配。
     *
     * @param knowledgeBases KnowledgeBaseService，负责约定知识库创建、更新、查询与所有权检查
     * @param libraries 本次操作使用的 KnowledgeBaseMapper 组件或记录
     * @param documents DocumentMapper，负责提供文档查询、切片分页、写入锁定和对象引用查询
     * @param indexes KnowledgeIndexService，负责管理活动索引代际、向量空间和可恢复的影子索引重建任务
     * @param minio 本次操作使用的 MinioService 组件或记录
     * @param audit 本次操作使用的 AuditLogService 组件或记录
     * @param transactions 本次操作所用的 PlatformTransactionManager 数据
     */
    public KnowledgeBaseRemovalService(KnowledgeBaseService knowledgeBases, KnowledgeBaseMapper libraries,
                                       DocumentMapper documents, KnowledgeIndexService indexes, MinioService minio,
                                       AuditLogService audit, PlatformTransactionManager transactions) {
        this.knowledgeBases = knowledgeBases;
        this.libraries = libraries;
        this.documents = documents;
        this.indexes = indexes;
        this.minio = minio;
        this.audit = audit;
        this.deleteTransaction = new TransactionTemplate(transactions);
        this.deleteTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 检查所有权和索引屏障，删除数据库中的知识库后尽力清理原文件。
     * 上游为 KnowledgeBaseController.delete。
     * 下游为 KnowledgeBaseService.checkOwnership、KnowledgeIndexService.acquireWriteBarrier。
     *
     * @param knowledgeBaseId 知识库 ID
     * @return 本次计算或查询得到的 Removal 结果
     */
    public Removal remove(Long knowledgeBaseId) {
        List<String> objectNames;

        try {
            objectNames = deleteTransaction.execute(status -> {
                knowledgeBases.checkOwnership(knowledgeBaseId);
                indexes.acquireWriteBarrier(knowledgeBaseId);
                List<String> paths = documents.selectFilePathsByKnowledgeBase(knowledgeBaseId);
                if (libraries.deleteById(knowledgeBaseId) != 1) {
                    throw new ForbiddenException("无权操作该知识库");
                }
                return paths;
            });
        } catch (RuntimeException failure) {
            audit.recordFailure(AuditAction.KB_DELETED, "knowledge_base", knowledgeBaseId, failure, null);
            throw failure;
        }

        // PostgreSQL has committed the cascade. A failed object cleanup cannot restore the library.
        int filesPendingCleanup = 0;

        for (String objectName : objectNames) {
            try {
                minio.delete(objectName);
            } catch (Exception failure) {
                filesPendingCleanup++;
                log.warn("知识库已删除，原文件清理失败 knowledgeBaseId={}, object={}", knowledgeBaseId,
                        objectName, failure);
            }
        }
        audit.record(AuditAction.KB_DELETED, "knowledge_base", knowledgeBaseId,
                AuditLogService.details("files_pending_cleanup", filesPendingCleanup));
        return new Removal(filesPendingCleanup);
    }

    public record Removal(int filesPendingCleanup) { }
}
