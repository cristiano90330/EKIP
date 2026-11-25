/* 定义在获准知识库及指定文档内读取准入证据和指定窗口的接口。 */

package com.pony.knowledge.agent.tool.knowledge;

import com.pony.knowledge.service.rag.model.RagQueryOutcome;
import com.pony.knowledge.service.rag.model.DocumentReadWindow;

import java.util.List;

/**
 * read_document 的检索端口：由 {@link PythonDocumentScopedEvidenceReader} 接到
 * Python 的“文档限定 + 证据准入但不生成”路径。
 *
 * <p>实现必须在向量与关键词查询中同时绑定 knowledgeBaseIds 与 documentId，
 * 不能在 Java 侧读取原文后再过滤；返回值中只有 evidences 可进入工具输出，sources 是原始候选。</p>
 */
@FunctionalInterface
public interface DocumentScopedEvidenceReader {

    /**
     * 按已授权库、目标文档和查询读取通过证据准入的片段。
     * 上游由 {@code ReadDocumentTool.execute} 调用。
     *
     * @param knowledgeBaseIds 本次调用传入的 {@code List<Long>}
     * @param documentId 目标文档 ID
     * @param query 待检索或组织上下文的查询文本
     * @param limit 最多返回或保留的条数
     * @return 带准入证据和诊断的问答管线结果
     */
    RagQueryOutcome readAdmitted(
            List<Long> knowledgeBaseIds, Long documentId, String query, int limit);

    /**
     * 对指定授权文档的窗口执行读取和证据准入，返回正文或下页位置。
     * 上游由 {@code ReadDocumentTool.readPage} 调用。
     *
     * @param knowledgeBaseIds 本次调用传入的 {@code List<Long>}
     * @param documentId 目标文档 ID
     * @param query 待检索或组织上下文的查询文本
     * @param window 允许读取的窗口范围和字符预算
     * @return 带准入证据和诊断的问答管线结果
     */
    default RagQueryOutcome readPage(List<Long> knowledgeBaseIds, Long documentId,
                                     String query, DocumentReadWindow window) {
        throw new UnsupportedOperationException("Sequential document reading is unavailable");
    }
}
