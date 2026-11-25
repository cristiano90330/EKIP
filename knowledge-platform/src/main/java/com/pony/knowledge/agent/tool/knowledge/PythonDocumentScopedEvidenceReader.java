/* 通过 Java RAG 管线把指定文档的阅读请求交给 Python。 */

package com.pony.knowledge.agent.tool.knowledge;

import com.pony.knowledge.service.rag.PythonRagPipeline;
import com.pony.knowledge.service.rag.model.RagQueryOutcome;
import com.pony.knowledge.service.rag.model.DocumentReadWindow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** 将 read_document 的文档限定请求接到现有准入检索路径，不在 Java 侧过滤原始候选。 */
@Component
@RequiredArgsConstructor
public class PythonDocumentScopedEvidenceReader implements DocumentScopedEvidenceReader {

    private final PythonRagPipeline pythonRagPipeline;

    /**
     * 将目标文档和可信范围传给 Python RAG 管线的文档内证据入口。
     * 下游调用 {@code pythonRagPipeline.retrieveAdmitted}。
     *
     * @param knowledgeBaseIds 本次调用传入的 {@code List<Long>}
     * @param documentId 目标文档 ID
     * @param query 待检索或组织上下文的查询文本
     * @param limit 最多返回或保留的条数
     * @return 带准入证据和诊断的问答管线结果
     */
    @Override
    public RagQueryOutcome readAdmitted(
            List<Long> knowledgeBaseIds, Long documentId, String query, int limit) {
        return pythonRagPipeline.retrieveAdmitted(
                knowledgeBaseIds, query, List.of(), limit, documentId);
    }

    /**
     * 调用 Python 管线的连续窗口读取，并原样保留该窗口的问答状态及续读位置。
     * 下游调用 {@code pythonRagPipeline.retrieveDocumentPage}。
     *
     * @param knowledgeBaseIds 本次调用传入的 {@code List<Long>}
     * @param documentId 目标文档 ID
     * @param query 待检索或组织上下文的查询文本
     * @param window 允许读取的窗口范围和字符预算
     * @return 带准入证据和诊断的问答管线结果
     */
    @Override
    public RagQueryOutcome readPage(List<Long> knowledgeBaseIds, Long documentId,
                                    String query, DocumentReadWindow window) {
        return pythonRagPipeline.retrieveDocumentPage(knowledgeBaseIds, documentId, query, window);
    }
}
