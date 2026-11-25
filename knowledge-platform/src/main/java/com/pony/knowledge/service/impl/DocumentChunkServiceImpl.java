/* 为切片计算向量，并事务性替换正文、父子关联和索引记录。 */

package com.pony.knowledge.service.impl;

import com.pony.knowledge.entity.DocumentTask;
import com.pony.knowledge.service.DocumentChunkService;
import com.pony.knowledge.service.ai.AiServiceClient;
import com.pony.knowledge.service.task.TaskCompletionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.pony.knowledge.modelmanagement.index.KnowledgeIndexService;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 文档内容入库：先取得子块向量，再在短事务内锁定文档并替换整份文档的 chunk。
 * 文档已删除时停止写入；写入失败回滚替换，避免只保留部分新内容。开启父块功能时同时建立父子关联。
 */
@Service
public class DocumentChunkServiceImpl implements DocumentChunkService {

    private static final Logger log = LoggerFactory.getLogger(DocumentChunkServiceImpl.class);

    private final JdbcTemplate jdbcTemplate;

    private final AiServiceClient aiServiceClient;

    /** 是否在写入时建立供检索后扩展使用的父子块关系。 */
    private final boolean smallToBigEnabled;

    /** 聚合相邻子块时使用的父块字符阈值，单个子块内容不截断。 */
    private final int parentMaxChars;

    /** 控制文档锁定、旧块删除、新块插入及父子关系更新的原子写入范围。 */
    private final TransactionTemplate pgTransaction;
    private KnowledgeIndexService indexes;
    private TaskCompletionService completion;

    /**
     * 配置切片向量写入所用的索引服务。
     *
     * @param indexes KnowledgeIndexService，负责管理活动索引代际、向量空间和可恢复的影子索引重建任务
     */
    @Autowired
    public void setKnowledgeIndexService(KnowledgeIndexService indexes) { this.indexes = indexes; }

    /**
     * 配置切片写入和任务终态共同提交时使用的服务。
     *
     * @param completion 本次操作使用的 TaskCompletionService 组件或记录
     */
    @Autowired
    public void setTaskCompletionService(TaskCompletionService completion) { this.completion = completion; }

    /**
     * 保存为切片计算向量所需的依赖和初始配置。
     * 上游为 Spring 组件装配。
     *
     * @param jdbcTemplate 负责数据库 SQL 的 JDBC 组件
     * @param aiServiceClient AiServiceClient，负责装配 Java 到 AI 服务的内部 HTTP 请求，承接解析、嵌入、问答与能力检查
     * @param transactionManager 用于创建或参与数据库事务的事务管理器
     * @param smallToBigEnabled 是否启用相关处理
     * @param parentMaxChars 初始化或更新同名字段的值
     */
    public DocumentChunkServiceImpl(
            JdbcTemplate jdbcTemplate,
            AiServiceClient aiServiceClient,
            PlatformTransactionManager transactionManager,
            @Value("${rag.small-to-big.enabled:false}") boolean smallToBigEnabled,
            @Value("${rag.small-to-big.parent-max-chars:4096}") int parentMaxChars) {
        this.jdbcTemplate = jdbcTemplate;
        this.aiServiceClient = aiServiceClient;
        this.smallToBigEnabled = smallToBigEnabled;
        this.parentMaxChars = parentMaxChars;
        this.pgTransaction = new TransactionTemplate(transactionManager);
    }

    /**
     * 计算新切片向量，在写入屏障和文档锁下替换索引及正文。
     * 下游为 KnowledgeIndexService.embeddingExecution、KnowledgeIndexService.acquireWriteBarrier。
     *
     * @param documentId 文档 ID
     * @param knowledgeBaseId 知识库 ID
     * @param chunks 待处理的切片列表
     */
    @Override
    public void replaceChunks(
            Long documentId,
            Long knowledgeBaseId,
            List<AiServiceClient.ChunkResult> chunks) {

        if (indexes == null) throw new IllegalStateException("KnowledgeIndexService is required");
        Map<String, Object> execution = indexes.embeddingExecution(knowledgeBaseId, 120000);
        List<List<Double>> vectors = embedChunkContents(chunks, execution);

        pgTransaction.execute(status -> {
            indexes.acquireWriteBarrier(knowledgeBaseId);
            List<Long> existing = jdbcTemplate.queryForList(
                    "SELECT id FROM document WHERE id = ? FOR UPDATE", Long.class, documentId);
            if (existing.isEmpty()) {
                log.info("文档在 chunk 写入期间已删除，跳过 documentId={}", documentId);
                return null;
            }
            writeChunks(documentId, knowledgeBaseId, chunks, execution, vectors);
            return null;
        });
    }

    /**
     * 先准备嵌入向量，再通过任务完成事务共同提交切片和成功状态。
     * 上游为 DocumentProcessServiceImpl.processAttempt。
     * 下游为 KnowledgeIndexService.embeddingExecution、KnowledgeIndexService.acquireWriteBarrier。
     *
     * @param attempt 处理尝试对应的任务快照
     * @param knowledgeBaseId 知识库 ID
     * @param chunks 待处理的切片列表
     * @return 任务仍有效且切片及终态成功提交时为 true
     */
    @Override
    public boolean completeTask(DocumentTask attempt, Long knowledgeBaseId, List<AiServiceClient.ChunkResult> chunks) {
        Map<String, Object> execution = indexes.embeddingExecution(knowledgeBaseId, 120000);
        List<List<Double>> vectors = embedChunkContents(chunks, execution);

        // 模型调用在事务外完成，切片、活动向量和成功终态在同一次事务校验中提交。
        return Boolean.TRUE.equals(pgTransaction.execute(status -> {
            indexes.acquireWriteBarrier(knowledgeBaseId);
            return completion.completeAttempt(attempt,
                    () -> writeChunks(attempt.getDocumentId(), knowledgeBaseId, chunks, execution, vectors));
        }));
    }

    /**
     * 写入切片数据库行并更新与切片一一对应的活动索引向量。
     * 上游为 DocumentChunkServiceImpl.replaceChunks、DocumentChunkServiceImpl.completeTask。
     * 下游为 KnowledgeIndexService.writeDocumentVectors。
     *
     * @param documentId 文档 ID
     * @param knowledgeBaseId 知识库 ID
     * @param chunks 待处理的切片列表
     * @param execution 含当前操作所需属性的映射
     * @param vectors 与切片次序对齐的嵌入向量
     */
    private void writeChunks(Long documentId, Long knowledgeBaseId, List<AiServiceClient.ChunkResult> chunks,
                             Map<String, Object> execution, List<List<Double>> vectors) {
        List<Long> ids = replacePgRows(documentId, knowledgeBaseId, chunks);
        indexes.writeDocumentVectors(knowledgeBaseId, execution, ids, vectors);
    }

    /**
     * 批量嵌入切片正文，并验证返回向量数量。
     * 上游为 DocumentChunkServiceImpl.replaceChunks、DocumentChunkServiceImpl.completeTask。
     * 下游为 AiServiceClient.embed。
     *
     * @param chunks 待处理的切片列表
     * @param execution 含当前操作所需属性的映射
     * @return 与正文次序一致的嵌入向量
     */
    private List<List<Double>> embedChunkContents(List<AiServiceClient.ChunkResult> chunks, Map<String, Object> execution) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        List<String> contents = chunks.stream()
                .map(AiServiceClient.ChunkResult::getContent)
                .toList();

        List<List<Double>> vectors = aiServiceClient.embed(contents, execution).getEmbeddings();

        if (vectors == null || vectors.size() != chunks.size()) {
            throw new IllegalStateException(
                    "嵌入返回向量数(" + (vectors == null ? 0 : vectors.size()) + ") 与 chunk 数(" + chunks.size() + ") 不符");
        }
        return vectors;
    }

    /**
     * 删除旧切片并插入新切片及父子关系，返回用于写入向量的切片标识。
     * 上游为 DocumentChunkServiceImpl.writeChunks。
     * 下游为 DocumentChunkServiceImpl.linkParents。
     *
     * @param documentId 文档 ID
     * @param knowledgeBaseId 知识库 ID
     * @param chunks 待处理的切片列表
     * @return 新写入切片行的 ID 列表
     */
    private List<Long> replacePgRows(
            Long documentId,
            Long knowledgeBaseId,
            List<AiServiceClient.ChunkResult> chunks) {

        jdbcTemplate.update("DELETE FROM document_chunk WHERE document_id = ?", documentId);

        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }

        List<ChildRow> children = new ArrayList<>(chunks.size());
        int maxChildChunkIndex = -1;

        for (int i = 0; i < chunks.size(); i++) {
            AiServiceClient.ChunkResult chunk = chunks.get(i);

            // 优先使用解析阶段的 jieba 分词文本；缺失时回退正文并告警，中文关键词召回可能受影响。
            String searchText;
            if (chunk.getSearchText() == null || chunk.getSearchText().isBlank()) {
                searchText = chunk.getContent();
                log.warn("documentId={} chunkIndex={} 的 searchText 为空，回退用 content 生成 search_vector——"
                        + "关键词检索将静默失效（PG 'simple' 不切中文）",
                        documentId, chunk.getChunkIndex());
            } else {
                searchText = chunk.getSearchText();
            }

            Long childId = jdbcTemplate.queryForObject(
                    "INSERT INTO document_chunk " +
                    "(document_id, knowledge_base_id, chunk_index, content, char_count, embedding, search_vector, " +
                    "source_type, page_no, heading_path, table_id, row_index, create_time, update_time) " +
                    "VALUES (?, ?, ?, ?, ?, NULL, to_tsvector('simple', ?), ?, ?, ?, ?, ?, now(), now()) " +
                    "RETURNING id",
                    Long.class,
                    documentId,
                    knowledgeBaseId,
                    chunk.getChunkIndex(),
                    chunk.getContent(),
                    chunk.getContent().length(),
                    searchText,
                    chunk.getSourceType(),
                    chunk.getPageNo(),
                    chunk.getHeadingPath(),
                    chunk.getTableId(),
                    chunk.getRowIndex());

            children.add(new ChildRow(childId, chunk.getChunkIndex(), chunk.getContent(),
                    chunk.getHeadingPath(), chunk.getPageNo()));
            maxChildChunkIndex = Math.max(maxChildChunkIndex, chunk.getChunkIndex() == null ? -1 : chunk.getChunkIndex());
        }

        if (smallToBigEnabled) {
            linkParents(documentId, knowledgeBaseId, children, maxChildChunkIndex + 1);
        }
        return children.stream().map(child -> child.id).toList();
    }

    /**
     * 按来源分组组织父切片并写回子切片的父级引用。
     * 上游为 DocumentChunkServiceImpl.replacePgRows。
     * 下游为 DocumentChunkServiceImpl.parentKey。
     *
     * @param documentId 文档 ID
     * @param knowledgeBaseId 知识库 ID
     * @param children 需要本方法按序读取或筛选的 List&lt;ChildRow&gt; 数据
     * @param firstParentIndex 处理位置索引
     */
    private void linkParents(Long documentId, Long knowledgeBaseId, List<ChildRow> children, int firstParentIndex) {
        int parentChunkIndex = firstParentIndex;
        int i = 0;
        while (i < children.size()) {
            ChildRow first = children.get(i);
            String key = parentKey(first);
            StringBuilder sb = new StringBuilder();
            List<ChildRow> members = new ArrayList<>();
            while (i < children.size()) {
                ChildRow c = children.get(i);
                if (!parentKey(c).equals(key)
                        || (sb.length() > 0 && (long) sb.length() + 2 + c.content.length() > parentMaxChars)) {
                    break;
                }
                if (sb.length() > 0) {
                    sb.append("\n\n");
                }
                sb.append(c.content);
                members.add(c);
                i++;
            }
            if (members.isEmpty()) {
                ChildRow c = children.get(i);
                sb = new StringBuilder(c.content);
                members = List.of(c);
                i++;
            }
            long parentId = jdbcTemplate.queryForObject(
                    "INSERT INTO document_chunk " +
                    "(document_id, knowledge_base_id, chunk_index, content, char_count, embedding, search_vector, " +
                    "source_type, page_no, heading_path, table_id, row_index, create_time, update_time) " +
                    "VALUES (?, ?, ?, ?, ?, NULL, NULL, 'parent', ?, ?, NULL, NULL, now(), now()) RETURNING id",
                    Long.class,
                    documentId, knowledgeBaseId, parentChunkIndex++, sb.toString(), sb.length(),
                    first.pageNo, first.headingPath);
            for (ChildRow m : members) {
                jdbcTemplate.update("UPDATE document_chunk SET parent_chunk_id = ? WHERE id = ?", parentId, m.id);
            }
        }
    }

    /**
     * 根据切片标题路径生成父切片分组键。
     * 上游为 DocumentChunkServiceImpl.linkParents。
     *
     * @param c 本次操作使用的 ChildRow 组件或记录
     * @return 用于聚合相邻子切片的标题路径键
     */
    private static String parentKey(ChildRow c) {
        return c.headingPath == null ? "" : c.headingPath;
    }

    /** 保存父块聚合需要的已入库子块信息。 */
    private static final class ChildRow {
        final long id;
        final int chunkIndex;
        final String content;
        final String headingPath;
        final Integer pageNo;

        /**
         * 建立 ChildRow，将调用方提供的初始值写入其状态。
         *
         * @param id 要处理的记录 ID
         * @param chunkIndex 处理位置索引
         * @param content 待处理的正文内容
         * @param headingPath 初始化或更新同名字段的值
         * @param pageNo 初始化或更新同名字段的值
         */
        ChildRow(long id, Integer chunkIndex, String content, String headingPath, Integer pageNo) {
            this.id = id;
            this.chunkIndex = chunkIndex == null ? -1 : chunkIndex;
            this.content = content == null ? "" : content;
            this.headingPath = headingPath;
            this.pageNo = pageNo;
        }
    }

}
