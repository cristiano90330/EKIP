/* 解析 Agent 文档工具可读取的文档、目录和知识库范围。 */

package com.pony.knowledge.service;

import com.pony.knowledge.entity.Document;
import com.pony.knowledge.entity.DocumentStatus;
import com.pony.knowledge.mapper.DocumentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.ArrayList;
import java.util.List;

/**
 * read_document 的文档归属核验：文档必须存在、处理状态为 COMPLETED，且所属知识库在当前请求的
 * 有效范围内（该范围已按当前用户与 Agent 配置解析）。
 *
 * <p>文档不存在、越权、删除、尚未处理完成或范围为空都返回 null；调用方统一按“不存在或不可读”
 * 处理，不区分原因，避免用 documentId 探测他人文档。</p>
 */
@Service
@RequiredArgsConstructor
public class DocumentReadAccessService {

    private final DocumentMapper documentMapper;

    /**
     * 根据文档 ID 和已授权库范围解析可供文档 Agent 使用的文件。
     * 上游为 ReadDocumentTool.execute。
     * 下游为 DocumentMapper.selectReadableInScope。
     *
     * @param documentId 文档 ID
     * @param effectiveKnowledgeBaseIds 相关记录的 ID 列表
     * @return 知识库文档的对象位置、元数据和处理状态，以 Document 返回
     */
    public Document resolveReadableDocument(Long documentId, Set<Long> effectiveKnowledgeBaseIds) {
        if (documentId == null || documentId <= 0
                || effectiveKnowledgeBaseIds == null || effectiveKnowledgeBaseIds.isEmpty()) {
            return null;
        }
        Document document = documentMapper.selectReadableInScope(
                documentId, new ArrayList<>(effectiveKnowledgeBaseIds));
        if (document == null
                || document.getKnowledgeBaseId() == null
                || !effectiveKnowledgeBaseIds.contains(document.getKnowledgeBaseId())
                || document.getStatus() != DocumentStatus.COMPLETED) {
            return null;
        }
        return document;
    }

    /**
     * 按已获准知识库范围查询文档目录。
     * 上游为 ListDocumentsTool.execute。
     * 下游为 DocumentMapper.selectReadableDirectory。
     *
     * @param scope 需要本方法按序读取或筛选的 Set&lt;Long&gt; 数据
     * @param offset 从窗口起点计算的偏移
     * @param limit 本次最多处理或返回的项数
     * @return 与过滤条件匹配的 List&lt;Document&gt; 数据集合
     */
    public List<Document> listReadableDocuments(Set<Long> scope, int offset, int limit) {
        if (scope == null || scope.isEmpty()) {
            return List.of();
        }
        return documentMapper.selectReadableDirectory(new ArrayList<>(scope), offset, limit);
    }
}
