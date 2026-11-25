/* 列举 Agent 有权读取的文档元数据，返回有界目录和续页信息。 */

package com.pony.knowledge.agent.tool.knowledge;

import com.pony.knowledge.agent.model.AgentExecutionContext;
import com.pony.knowledge.agent.tool.AgentTool;
import com.pony.knowledge.agent.tool.ToolDefinition;
import com.pony.knowledge.agent.tool.ToolSchemaGenerator;
import com.pony.knowledge.agent.tool.input.ListDocumentsInput;
import com.pony.knowledge.agent.tool.input.ToolInput;
import com.pony.knowledge.agent.tool.output.ListDocumentsOutput;
import com.pony.knowledge.agent.tool.output.ToolResult;
import com.pony.knowledge.entity.Document;
import com.pony.knowledge.service.DocumentReadAccessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/** 在当前有效范围内发现可读文档；目录元数据不计作知识证据。 */
@Component
@RequiredArgsConstructor
public class ListDocumentsTool implements AgentTool {
    private final DocumentReadAccessService access;

    @Value("${agent.react.tool-output-max-chars:4000}")
    private int toolOutputMaxChars = 4000;

    /**
     * 声明文档目录工具的检索关键词、游标和结果数量约束。
     * 下游调用 {@code ToolSchemaGenerator.schemaOf}。
     *
     * @return 公开给模型或页面目录的工具定义
     */
    @Override
    public ToolDefinition definition() {
        return ToolDefinition.builder().name("list_documents")
                .description("List completed, readable documents in the current knowledge scope. Returns metadata and "
                        + "documentId for read_document; no document text. Use offset=0 initially, then nextOffset. "
                        + "limit defaults to 5 (maximum 10).")
                .inputClass(ListDocumentsInput.class)
                .parameters(ToolSchemaGenerator.schemaOf(ListDocumentsInput.class)).build();
    }

    /**
     * 在有效知识库范围内分页读取文档目录并转换为安全可见的元数据。
     * 上游由 {@code ToolExecutor（执行 list_documents）} 调用。
     * 下游调用 {@code access.listReadableDocuments}、{@code items.subList}、{@code json.codePointCount}。
     *
     * @param input 本次操作的结构化输入
     * @param context 可信执行上下文，包含身份、范围和本轮执行状态
     * @return 带载荷或可识别错误状态的工具结果
     */
    @Override
    public ToolResult execute(ToolInput input, AgentExecutionContext context) {
        if (context == null || context.getUserId() == null || context.getUserId() <= 0) {
            return ToolResult.builder().toolName("list_documents").success(false)
                    .errorMessage("当前身份不可读取文档目录").retryable(false).build();
        }

        ListDocumentsInput request = (ListDocumentsInput) input;
        int offset = request.getOffset() == null ? 0 : request.getOffset();
        int limit = request.getLimit() == null ? 5 : request.getLimit();
        if (offset < 0 || offset > 1000000 || limit < 1 || limit > 10) {
            return ToolResult.builder().toolName("list_documents").success(false)
                    .errorMessage("目录分页参数超出范围").retryable(false).build();
        }

        List<Document> rows = access.listReadableDocuments(
                context.getAccessibleKnowledgeBaseIds(), offset, limit + 1);
        List<ListDocumentsOutput.Item> items = rows.stream().limit(limit)
                .map(document -> ListDocumentsOutput.Item.builder()
                                .documentId(document.getId()).knowledgeBaseId(document.getKnowledgeBaseId())
                                .fileName(abbreviate(document.getFileName(), 80))
                                .fileType(abbreviate(document.getFileType(), 24))
                                .fileSize(document.getFileSize()).build()).toList();
        // 目录也按实际交付条数推进，不能在运行时截断了元数据后跳过未显示的文档。
        for (int count = items.size(); count >= 0; count--) {
            boolean more = rows.size() > count;
            ListDocumentsOutput data = ListDocumentsOutput.builder().offset(offset).hasMore(more)
                    .nextOffset(more ? offset + count : null).documents(items.subList(0, count)).build();
            try {
                String json = new ObjectMapper().writeValueAsString(data);
                if ((toolOutputMaxChars <= 0
                        || json.codePointCount(0, json.length()) + 100 <= toolOutputMaxChars)
                        && (count > 0 || rows.isEmpty())) {
                    return ToolResult.builder().toolName("list_documents").success(true).data(data).build();
                }
            } catch (Exception exception) {
                break;
            }
        }

        return ToolResult.builder().toolName("list_documents").success(false)
                .reasonCode("tool_output_budget_exceeded").errorMessage("目录输出预算不足；请保留原 offset")
                .retryable(false).build();
    }

    /**
     * 控制目录字段的最大展示长度，限制工具输出占用。
     * 上游由 {@code ListDocumentsTool.execute} 调用。
     * 下游调用 {@code value.codePointCount}、{@code value.length}、{@code value.offsetByCodePoints}。
     *
     * @param value 本次调用传入的 {@code String}
     * @param max 本次调用传入的 {@code int}
     * @return 供后续处理使用的文本或标识
     */
    private static String abbreviate(String value, int max) {
        return value == null || value.codePointCount(0, value.length()) <= max
                ? value : value.substring(0, value.offsetByCodePoints(0, max)) + "…";
    }
}
