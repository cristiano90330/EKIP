/* 处理文档上传幂等回执、对象存储、任务投递、删除和读取。 */

package com.pony.knowledge.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pony.knowledge.audit.AuditAction;
import com.pony.knowledge.audit.AuditLogService;
import com.pony.knowledge.dto.DocumentPageQueryDTO;
import com.pony.knowledge.dto.DocumentChunkDTO;
import com.pony.knowledge.dto.DocumentChunkPageQueryDTO;
import com.pony.knowledge.dto.DocumentUploadReceiptDTO;
import com.pony.knowledge.dto.DocumentUploadReceiptLookupDTO;
import com.pony.knowledge.entity.DocumentStatus;
import com.pony.knowledge.entity.Document;
import com.pony.knowledge.entity.DocumentTask;
import com.pony.knowledge.entity.DocumentTaskStatus;
import com.pony.knowledge.entity.DocumentUploadReceiptRecord;
import com.pony.knowledge.exception.BusinessException;
import com.pony.knowledge.exception.ForbiddenException;
import com.pony.knowledge.exception.UploadIdempotencyConflictException;
import com.pony.knowledge.mapper.DocumentMapper;
import com.pony.knowledge.mapper.DocumentTaskMapper;
import com.pony.knowledge.mapper.DocumentUploadReceiptMapper;
import com.pony.knowledge.service.task.TaskDispatchCoordinator;
import com.pony.knowledge.service.DocumentService;
import com.pony.knowledge.service.KnowledgeBaseAccessService;
import com.pony.knowledge.service.storage.MinioService;
import com.pony.knowledge.utils.CurrentUserUtil;
import com.pony.knowledge.utils.FileExtensionUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;

/**
 * 文档受理与管理主线：校验归属及格式，按请求键找回上传，保存文档、任务与可选回执后投递。
 * 上传成功返回受理结果，解析由 DocumentProcessServiceImpl 继续；列表允许成员读取，上传与删除要求创建者。
 * 删除先提交数据库及其级联清理，再尽力删除原始文件。
 */
@Service
public class DocumentServiceImpl implements DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentServiceImpl.class);

    /**
     * `.doc` / `.ppt` 是旧版二进制，需要部署侧提供 LibreOffice 才能转换；缺失时任务会以
     * `DOCUMENT_INVALID` 明确提示另存为新格式，不会伪装成文件损坏。
     */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "pdf", "md", "markdown", "docx", "doc", "ppt", "pptx",
            "xlsx", "xls", "epub", "xmind", "txt", "csv", "json");

    private final DocumentMapper documentMapper;

    private final MinioService minioService;

    private final CurrentUserUtil currentUserUtil;

    private final String bucketName;

    private final DocumentTaskMapper documentTaskMapper;

    private final DocumentUploadReceiptMapper uploadReceiptMapper;

    private final TaskDispatchCoordinator taskDispatchCoordinator;

    private final KnowledgeBaseAccessService knowledgeBaseAccessService;

    /** 限定文档、任务和可选回执的数据库写入范围，MinIO 上传位于该范围之外。 */
    private final TransactionTemplate uploadTransaction;

    /** 独立提交数据库删除后再清理 MinIO 对象。 */
    private final TransactionTemplate deleteTransaction;

    /** 操作审计：写失败不影响上传/删除结果（见 AuditLogService 的“绝不外抛”约定）。 */
    private final AuditLogService auditLogService;
    private com.pony.knowledge.modelmanagement.index.KnowledgeIndexService indexes;

    /**
     * 配置文档读写流程的索引屏障和当前可写性检查。
     *
     * @param indexes 本次操作所用的 com.pony.knowledge.modelmanagement.index.KnowledgeIndexService 数据
     */
    @org.springframework.beans.factory.annotation.Autowired
    public void setKnowledgeIndexService(com.pony.knowledge.modelmanagement.index.KnowledgeIndexService indexes) {
        this.indexes = indexes;
    }

    /**
     * 保存处理文档上传幂等回执、对象存储、任务投递、删除和读取所需的依赖和初始配置。
     * 上游为 Spring 组件装配。
     *
     * @param documentMapper DocumentMapper，负责提供文档查询、切片分页、写入锁定和对象引用查询
     * @param minioService 本次操作使用的 MinioService 组件或记录
     * @param currentUserUtil 本次操作使用的 CurrentUserUtil 组件或记录
     * @param documentTaskMapper 本次操作使用的 DocumentTaskMapper 组件或记录
     * @param uploadReceiptMapper 本次操作使用的 DocumentUploadReceiptMapper 组件或记录
     * @param taskDispatchCoordinator 本次操作使用的 TaskDispatchCoordinator 组件或记录
     * @param bucketName 对象存储桶名
     * @param knowledgeBaseAccessService KnowledgeBaseAccessService，负责约定知识库可读范围和创建者操作的权限判定
     * @param transactionManager 用于创建或参与数据库事务的事务管理器
     * @param auditLogService 本次操作使用的 AuditLogService 组件或记录
     */
    public DocumentServiceImpl(
            DocumentMapper documentMapper,
            MinioService minioService,
            CurrentUserUtil currentUserUtil,
            DocumentTaskMapper documentTaskMapper,
            DocumentUploadReceiptMapper uploadReceiptMapper,
            TaskDispatchCoordinator taskDispatchCoordinator,
            @Value("${minio.bucket-name}") String bucketName,
            KnowledgeBaseAccessService knowledgeBaseAccessService,
            PlatformTransactionManager transactionManager,
            AuditLogService auditLogService){

        this.documentMapper = documentMapper;
        this.minioService = minioService;
        this.currentUserUtil = currentUserUtil;
        this.documentTaskMapper = documentTaskMapper;
        this.uploadReceiptMapper = uploadReceiptMapper;
        this.taskDispatchCoordinator = taskDispatchCoordinator;
        this.bucketName = bucketName;
        this.knowledgeBaseAccessService = knowledgeBaseAccessService;
        this.uploadTransaction = new TransactionTemplate(transactionManager);
        this.deleteTransaction = new TransactionTemplate(transactionManager);
        this.deleteTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.auditLogService = auditLogService;
    }

    /**
     * 先核对文档的知识库读取权，再分页读取切片及来源。
     * 上游为 DocumentController.chunks。
     * 下游为 DocumentMapper.selectById、KnowledgeBaseAccessService.canRead。
     *
     * @param documentId 文档 ID
     * @param query 调用方的查询内容或查询条件
     * @return 当前范围内的分页记录和分页元数据
     */
    @Override
    public IPage<DocumentChunkDTO> pageChunks(Long documentId, DocumentChunkPageQueryDTO query) {
        Long userId = currentUserUtil.getUserId();
        Document document = documentMapper.selectById(documentId);
        if (document == null || !knowledgeBaseAccessService.canRead(userId, document.getKnowledgeBaseId())) {
            throw new ForbiddenException("无权查看该文档切片");
        }
        return documentMapper.selectChunkPage(new Page<>(query.getPage(), query.getSize()),
                documentId, document.getKnowledgeBaseId());
    }

    /**
     * 上传原文件并储存文档与任务，使用幂等键时优先复用同内容的回执。
     * 上游为 DocumentController.upload。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param file 上传的原文件
     */
    @Override
    public void upload(
            Long knowledgeBaseId,
            MultipartFile file) {
        upload(knowledgeBaseId, file, null);
    }

    /**
     * 上传原文件并储存文档与任务，使用幂等键时优先复用同内容的回执。
     * 上游为 DocumentController.upload。
     * 下游为 FileExtensionUtil.of、DocumentUploadReceiptMapper.find。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param file 上传的原文件
     * @param requestKey 本次上传使用的幂等请求键
     * @return 本次得到的 DocumentUploadReceiptDTO 实例
     */
    @Override
    public DocumentUploadReceiptDTO upload(Long knowledgeBaseId, MultipartFile file, String requestKey) {
        String objectName = null;
        boolean databaseCommitted = false;
        try {
            Long userId = currentUserUtil.getUserId();
            requireKnowledgeBaseOwner(knowledgeBaseId, userId);

            String extension = FileExtensionUtil.of(file.getOriginalFilename());
            if (!ALLOWED_EXTENSIONS.contains(extension)) {
                throw new BusinessException("不支持的文档类型，仅支持: "
                        + String.join(" / ", ALLOWED_EXTENSIONS));
            }

            // 命中同内容的原回执可以直接返回，省去重复上传对象。
            String normalizedKey = normalizeRequestKey(requestKey);
            String fingerprint = normalizedKey == null ? null : contentSha256(file);
            if (normalizedKey != null) {
                DocumentUploadReceiptRecord existing = uploadReceiptMapper.find(
                        userId, knowledgeBaseId, normalizedKey);
                if (existing != null) {
                    assertSameContent(existing, fingerprint);
                    databaseCommitted = true;
                    return toReceipt(existing, true);
                }
            }

            if (indexes == null) throw new IllegalStateException("KnowledgeIndexService is required");
            indexes.assertWritable(knowledgeBaseId);
            // 对象上传不占用数据库事务；后续竞争失败或数据库未提交时，尽力清理多余对象。
            objectName = minioService.upload(file);
            PersistedUpload persisted = persistUpload(userId, knowledgeBaseId, normalizedKey,
                    fingerprint, file, objectName);
            databaseCommitted = true;

            // 数据库请求键可能被并发请求先占用，复用胜出者记录并删除本次对象。
            if (persisted.reused()) {
                deleteOrphanObject(objectName);
                return toReceipt(persisted.receipt(), true);
            }

            auditLogService.record(AuditAction.DOCUMENT_UPLOADED, "document",
                    persisted.receipt().getDocumentId(),
                    AuditLogService.details("knowledge_base_id", knowledgeBaseId,
                            "file_name", file.getOriginalFilename()));

            dispatchAfterCommit(persisted.receipt().getTaskId(), persisted.receipt().getDocumentId());
            if (normalizedKey == null) {
                return initialReceipt(persisted.receipt());
            }
            return toReceipt(persisted.receipt(), false);

        } catch (BusinessException | UploadIdempotencyConflictException e) {
            if (!databaseCommitted) {
                deleteOrphanObject(objectName);
                auditLogService.recordFailure(AuditAction.DOCUMENT_UPLOADED, "document", null, e,
                        AuditLogService.details("knowledge_base_id", knowledgeBaseId));
            }
            throw e;
        } catch (Exception e) {
            if (!databaseCommitted) {
                deleteOrphanObject(objectName);
                auditLogService.recordFailure(AuditAction.DOCUMENT_UPLOADED, "document", null, e,
                        AuditLogService.details("knowledge_base_id", knowledgeBaseId));
            }
            throw new RuntimeException(
                    "文件上传失败",
                    e
            );
        }
    }

    /**
     * 按登录用户、知识库和幂等键读取回执并附带关联状态。
     * 上游为 DocumentController.uploadReceipt。
     * 下游为 DocumentUploadReceiptMapper.find。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param requestKey 本次上传使用的幂等请求键
     * @return 本次得到的 DocumentUploadReceiptLookupDTO 实例
     */
    @Override
    public DocumentUploadReceiptLookupDTO findUploadReceipt(Long knowledgeBaseId, String requestKey) {
        Long userId = currentUserUtil.getUserId();
        requireKnowledgeBaseOwner(knowledgeBaseId, userId);
        String normalizedKey = normalizeRequestKey(requestKey);
        if (normalizedKey == null) {
            throw new BusinessException("requestKey 不能为空");
        }
        DocumentUploadReceiptRecord receipt = uploadReceiptMapper.find(userId, knowledgeBaseId, normalizedKey);
        DocumentUploadReceiptLookupDTO result = new DocumentUploadReceiptLookupDTO();
        result.setFound(receipt != null);
        if (receipt != null) {
            result.setReceipt(toReceipt(receipt, true));
        }
        return result;
    }

    /**
     * 在数据库事务内竞争请求键、写入文档和任务并关联回执。
     * 上游为 DocumentServiceImpl.upload。
     * 下游为 KnowledgeIndexService.acquireWriteBarrier、DocumentUploadReceiptMapper.reserve。
     *
     * @param userId 用户 ID
     * @param knowledgeBaseId 知识库 ID
     * @param requestKey 本次上传使用的幂等请求键
     * @param fingerprint 用于确认一致性的数据指纹
     * @param file 上传的原文件
     * @param objectName 原文件在桶内的对象键
     * @return 已确认持久化的回执及其是否复用原记录
     */
    private PersistedUpload persistUpload(Long userId, Long knowledgeBaseId, String requestKey,
                                          String fingerprint, MultipartFile file, String objectName) {
        return uploadTransaction.execute(status -> {
            indexes.acquireWriteBarrier(knowledgeBaseId);

            // 在创建文档和任务前尝试占用请求键，让并发重发共享同一组已提交记录。
            if (requestKey != null) {
                int reserved = uploadReceiptMapper.reserve(userId, knowledgeBaseId, requestKey, fingerprint);
                if (reserved == 0) {
                    DocumentUploadReceiptRecord winner = uploadReceiptMapper.find(userId, knowledgeBaseId, requestKey);
                    if (winner == null) {
                        throw new IllegalStateException("Upload receipt reservation disappeared");
                    }
                    assertSameContent(winner, fingerprint);
                    return new PersistedUpload(winner, true);
                }
            }

            Document document = new Document();
            document.setKnowledgeBaseId(knowledgeBaseId);
            document.setFileName(file.getOriginalFilename());
            document.setFileType(file.getContentType());
            document.setFilePath(objectName);
            document.setFileSize(file.getSize());
            document.setUploaderId(userId);
            document.setStatus(DocumentStatus.UPLOADED);
            documentMapper.insert(document);

            DocumentTask task = new DocumentTask();
            task.setDocumentId(document.getId());
            task.setStatus(DocumentTaskStatus.WAITING);
            task.setAutoRetryCount(0);
            task.setTotalAttemptCount(0);
            task.setManualRetryCount(0);
            documentTaskMapper.insert(task);

            DocumentUploadReceiptRecord newReceipt = new DocumentUploadReceiptRecord();
            newReceipt.setUserId(userId);
            newReceipt.setKnowledgeBaseId(knowledgeBaseId);
            newReceipt.setRequestKey(requestKey);
            newReceipt.setContentSha256(fingerprint);
            newReceipt.setDocumentId(document.getId());
            newReceipt.setTaskId(task.getId());
            if (requestKey != null && uploadReceiptMapper.complete(userId, knowledgeBaseId, requestKey,
                    document.getId(), task.getId()) != 1) {
                throw new IllegalStateException("Upload receipt could not be linked to document/task");
            }
            return new PersistedUpload(newReceipt, false);
        });
    }

    /**
     * 在文档维护前要求登录账号是目标知识库的所有者。
     * 上游为 DocumentServiceImpl.upload、DocumentServiceImpl.findUploadReceipt。
     * 下游为 KnowledgeBaseAccessService.canMaintainDocuments。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param userId 用户 ID
     */
    private void requireKnowledgeBaseOwner(Long knowledgeBaseId, Long userId) {
        if (!knowledgeBaseAccessService.canMaintainDocuments(userId, knowledgeBaseId)) {
            throw new ForbiddenException("无权操作该知识库");
        }
    }

    /**
     * 清理上传请求键并检查可接受的键长度和格式。
     * 上游为 DocumentServiceImpl.upload、DocumentServiceImpl.findUploadReceipt。
     *
     * @param requestKey 本次上传使用的幂等请求键
     * @return 规范化的请求键，未提供有效非空键时为空
     */
    private static String normalizeRequestKey(String requestKey) {
        if (requestKey == null || requestKey.isBlank()) {
            return null;
        }
        String normalized = requestKey.trim();

        if (normalized.length() > 128 || !normalized.matches("[\\p{L}\\p{N}._:-]+")) {
            throw new BusinessException("Idempotency-Key 格式不正确");
        }
        return normalized;
    }

    /**
     * 分块读取上传内容并计算 SHA-256 指纹。
     * 上游为 DocumentServiceImpl.upload。
     *
     * @param file 上传的原文件
     * @return 上传内容的 SHA-256 十六进制指纹
     */
    private static String contentSha256(MultipartFile file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = file.getInputStream();
             java.security.DigestInputStream hashing = new java.security.DigestInputStream(input, digest)) {
            hashing.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * 检查已有回执与本次文件的指纹是否一致，冲突时拒绝复用。
     * 上游为 DocumentServiceImpl.upload、DocumentServiceImpl.persistUpload。
     * 下游为 DocumentUploadReceiptRecord.getContentSha256。
     *
     * @param existing 本次操作使用的 DocumentUploadReceiptRecord 组件或记录
     * @param fingerprint 用于确认一致性的数据指纹
     */
    private static void assertSameContent(DocumentUploadReceiptRecord existing, String fingerprint) {
        if (!fingerprint.equalsIgnoreCase(existing.getContentSha256())) {
            throw new UploadIdempotencyConflictException();
        }
    }

    /**
     * 读取记录关联的文档和任务状态，供上传和回执查询返回。
     * 上游为 DocumentServiceImpl.upload、DocumentServiceImpl.findUploadReceipt。
     * 下游为 DocumentMapper.selectById、DocumentTaskMapper.selectById。
     *
     * @param receipt 本次操作使用的 DocumentUploadReceiptRecord 组件或记录
     * @param reused 本方法计算或判断使用的 reused 值
     * @return 本次得到的 DocumentUploadReceiptDTO 实例
     */
    private DocumentUploadReceiptDTO toReceipt(DocumentUploadReceiptRecord receipt, boolean reused) {
        if (receipt == null || receipt.getDocumentId() == null || receipt.getTaskId() == null) {
            throw new IllegalStateException("Upload receipt is incomplete");
        }
        Document document = documentMapper.selectById(receipt.getDocumentId());
        DocumentTask task = documentTaskMapper.selectById(receipt.getTaskId());
        if (document == null || task == null) {
            throw new IllegalStateException("Upload receipt points to a missing document/task");
        }
        DocumentUploadReceiptDTO dto = new DocumentUploadReceiptDTO();
        dto.setRequestKey(receipt.getRequestKey());
        dto.setDocumentId(receipt.getDocumentId());
        dto.setTaskId(receipt.getTaskId());
        dto.setDocumentStatus(document.getStatus() == null ? "UNKNOWN" : document.getStatus().name());
        dto.setTaskStatus(task.getStatus() == null ? "UNKNOWN" : task.getStatus().name());
        dto.setReused(reused);
        return dto;
    }

    /**
     * 为数据库记录生成初始受理快照，标记文档已上传、任务待处理。
     * 上游为 DocumentServiceImpl.upload。
     * 下游为 DocumentUploadReceiptRecord.getRequestKey、DocumentUploadReceiptRecord.getDocumentId。
     *
     * @param receipt 本次操作使用的 DocumentUploadReceiptRecord 组件或记录
     * @return 本次得到的 DocumentUploadReceiptDTO 实例
     */
    private static DocumentUploadReceiptDTO initialReceipt(DocumentUploadReceiptRecord receipt) {
        DocumentUploadReceiptDTO dto = new DocumentUploadReceiptDTO();
        dto.setRequestKey(receipt.getRequestKey());
        dto.setDocumentId(receipt.getDocumentId());
        dto.setTaskId(receipt.getTaskId());
        dto.setDocumentStatus(DocumentStatus.UPLOADED.name());
        dto.setTaskStatus(DocumentTaskStatus.WAITING.name());
        dto.setReused(false);
        return dto;
    }

    /**
     * 尝试清理未被数据库记录接管的原文件，清理失败只记录日志。
     * 上游为 DocumentServiceImpl.upload。
     * 下游为 MinioService.delete。
     *
     * @param objectName 原文件在桶内的对象键
     */
    private void deleteOrphanObject(String objectName) {
        if (objectName == null) return;
        try {
            minioService.delete(objectName);
        } catch (Exception cleanupFailure) {
            log.error("上传幂等失败后的多余 MinIO 对象清理失败，等待孤儿对象回收 object={}", objectName,
                    cleanupFailure);
        }
    }

    private record PersistedUpload(DocumentUploadReceiptRecord receipt, boolean reused) {
    }

    /**
     * 在数据库记录提交后投递处理任务，投递失败交给恢复扫描。
     * 上游为 DocumentServiceImpl.upload。
     * 下游为 TaskDispatchCoordinator.dispatch。
     *
     * @param taskId 文档任务 ID
     * @param documentId 文档 ID
     */
    private void dispatchAfterCommit(Long taskId, Long documentId) {
        try {
            taskDispatchCoordinator.dispatch(taskId);
        } catch (Exception e) {
            log.error("任务投递失败（文档与任务已提交，不再回滚）：documentId={} taskId={}；"
                            + "该任务停留在 WAITING，等待运行期恢复扫描补投",
                    documentId, taskId, e);
        }
    }

    /**
     * 检查文档归属和可写性，提交数据库删除后清理对象存储。
     * 上游为 DocumentController.delete。
     * 下游为 DocumentMapper.selectOwnedDocument、KnowledgeIndexService.acquireWriteBarrier。
     *
     * @param documentId 文档 ID
     */
    @Override
    public void delete(Long documentId) {
        boolean databaseCommitted = false;
        try{
            Long userId = currentUserUtil.getUserId();

            // 数据库删除与 chunk 替换、任务完成都先作用于 document，保持一致的锁定顺序。
            Document document = deleteTransaction.execute(status -> {
                Document owned = documentMapper.selectOwnedDocument(documentId, userId);
                if (owned == null) {
                    throw new ForbiddenException("无权删除该文档");
                }
                if (indexes == null) throw new IllegalStateException("KnowledgeIndexService is required");
                indexes.acquireWriteBarrier(owned.getKnowledgeBaseId());
                if (documentMapper.deleteById(documentId) != 1) {
                    throw new BusinessException("文档删除失败，请刷新后重试");
                }
                return owned;
            });
            databaseCommitted = true;

            try {
                minioService.delete(document.getFilePath());
            } catch (Exception e) {
                log.error("文档记录已删除但 MinIO 对象删除失败，产生孤儿对象待清理: documentId={}, object={}",
                        documentId, document.getFilePath(), e);
            }

            // 数据库删除已提交（deleteTransaction 是独立事务），此时记录审计；
            // 对象存储清理失败不影响这条记录 —— 用户视角的「删除」已经发生。
            auditLogService.record(AuditAction.KNOWLEDGE_DELETED, "document", documentId,
                    AuditLogService.details("knowledge_base_id", document.getKnowledgeBaseId(),
                            "file_name", document.getFileName()));
        } catch (BusinessException e ){
            if (!databaseCommitted)
                auditLogService.recordFailure(AuditAction.KNOWLEDGE_DELETED, "document", documentId, e, null);
            throw e;

        } catch (Exception e ){
            if (!databaseCommitted)
                auditLogService.recordFailure(AuditAction.KNOWLEDGE_DELETED, "document", documentId, e, null);
            throw new RuntimeException("文档删除失败", e);
        }
    }

    /**
     * 验证知识库读取权后，分页查询其中的文档。
     * 上游为 DocumentController.page。
     * 下游为 KnowledgeBaseAccessService.canRead、DocumentMapper.selectPageByKnowledgeBaseId。
     *
     * @param dto 携带当前操作参数的 DocumentPageQueryDTO 数据
     * @return 当前范围内的分页记录和分页元数据
     */
    @Override
    public IPage<Document> page(DocumentPageQueryDTO dto){
        Long userId = currentUserUtil.getUserId();

        if (!knowledgeBaseAccessService.canRead(userId, dto.getKnowledgeBaseId())) {
            throw new ForbiddenException("无权操作该知识库");
        }

        Page<Document> page = new Page<>(dto.getPage(), dto.getSize());

        return documentMapper.selectPageByKnowledgeBaseId(page, dto.getKnowledgeBaseId());
    }

}
