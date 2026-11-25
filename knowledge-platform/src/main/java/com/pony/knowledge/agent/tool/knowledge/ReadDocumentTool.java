/* 校验指定文档的访问、处理状态和预算，再返回获准摘录或正文窗口。 */

package com.pony.knowledge.agent.tool.knowledge;

import com.pony.knowledge.agent.model.AgentExecutionContext;
import com.pony.knowledge.agent.tool.AgentTool;
import com.pony.knowledge.agent.tool.ToolDefinition;
import com.pony.knowledge.agent.tool.ToolSchemaGenerator;
import com.pony.knowledge.agent.tool.input.ReadDocumentInput;
import com.pony.knowledge.agent.tool.input.ReadDocumentLimits;
import com.pony.knowledge.agent.tool.input.ToolInput;
import com.pony.knowledge.agent.tool.output.ReadDocumentOutput;
import com.pony.knowledge.agent.tool.output.ToolResult;
import com.pony.knowledge.entity.Document;
import com.pony.knowledge.exception.RagQueryFailureException;
import com.pony.knowledge.service.DocumentReadAccessService;
import com.pony.knowledge.service.rag.model.RagEvidence;
import com.pony.knowledge.service.rag.model.RagQueryOutcome;
import com.pony.knowledge.service.rag.model.DocumentReadWindow;
import com.pony.knowledge.service.rag.model.RagDocumentPage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * read_document：在已授权知识库范围内，按 documentId 限定读取与问题相关、且经证据准入的原文片段。
 *
 * <p>文档归属先由 Java 文档记录核验；片段查询由 Python 的文档限定准入检索完成。本类不读取原文、
 * 不做应用层过滤，也不消费原始排序候选 sources。</p>
 *
 * <p>由 ToolRegistry 自动发现；{@link DocumentScopedEvidenceReader} 将已核验的范围与
 * documentId 传给 Python 的文档限定准入检索。</p>
 */
@Component
@RequiredArgsConstructor
public class ReadDocumentTool implements AgentTool {

    static final String NOT_READABLE_MESSAGE = "文档不存在或不可读";

    static final int MAX_SNIPPET_CHARS = 1200;

    static final int MAX_TOTAL_CHARS = 6000;

    static final int PAGE_METADATA_RESERVE_CHARS = 1600;

    static final String TRUNCATION_MARKER = "…";

    private final DocumentReadAccessService documentReadAccessService;

    private final DocumentScopedEvidenceReader documentScopedEvidenceReader;

    @Value("${agent.react.tool-output-max-chars:4000}")
    private int toolOutputMaxChars = 4000;

    /**
     * 提供文档阅读工具的参数 Schema、用途说明与可用模式。
     * 上游由 {@code ReadDocumentTool.notReadable}、{@code ReadDocumentTool.success} 调用。
     * 下游调用 {@code ToolSchemaGenerator.schemaOf}。
     *
     * @return 公开给模型或页面目录的工具定义
     */
    @Override
    public ToolDefinition definition() {
        Map<String, Object> parameters = ToolSchemaGenerator.schemaOf(ReadDocumentInput.class);
        Map<?, ?> properties = (Map<?, ?>) parameters.get("properties");
        for (String field : List.of("endOffset", "aroundChunkId", "contextRadius")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> property = (Map<String, Object>) properties.get(field);
            property.put("type", List.of("integer", "null"));
            property.put("description", switch (field) {
                case "endOffset" -> "Exclusive chunk boundary, not a character offset. Copy the previous endOffset exactly. "
                        + "For an unbounded/full read it is null: pass null or omit, never use nextContentOffset here.";
                case "aroundChunkId" -> "Only for initial neighborhood reading. Leave null for a full read or continuation. Zero is invalid.";
                default -> "Only used with aroundChunkId. Leave null or omit for sequential reading.";
            });
        }
        return ToolDefinition.builder()
                .name("read_document")
                .description("Read relevant, evidence-admitted excerpts from one specific document inside the current "
                        + "knowledge scope. Use a known documentId, obtained from list_documents if needed. "
                        + "mode=query (default) keeps relevant excerpt retrieval; limit applies only to query. "
                        + "mode=page reads ONE bounded original-text window in stable chunkIndex/id order. Start with"
                        + " offset=0,contentOffset=0; continue by copying nextOffset,nextContentOffset and endOffset "
                        + "until hasMore=false, including after no_evidence. For limited context around a known hit, "
                        + "supply aroundChunkId and contextRadius=0..2 on the initial page (no offsets); continue "
                        + "with the returned offsets and endOffset without aroundChunkId. query is the evidence-"
                        + "review goal in both modes. Only admitted windows are returned; a failed page never "
                        + "advances its cursor. query must be non-empty in page mode too. When endOffset is null omit"
                        + " it, never substitute 0. query must preserve the user's specific requested fact, scope and constraints; "
                        + "do not reduce it to only a document or project title.")
                .inputClass(ReadDocumentInput.class)
                .parameters(parameters)
                .build();
    }

    /**
     * 解析可信身份和知识库范围，校验目标文档，再读取获准摘录或正文窗口。
     * 上游由 {@code ToolExecutor（执行 read_document）} 调用。
     * 下游调用 {@code DocumentReadAccessService.resolveReadableDocument}、{@code DocumentScopedEvidenceReader.readAdmitted}、{@code ReadDocumentTool.readPage}。
     *
     * @param input 本次操作的结构化输入
     * @param context 可信执行上下文，包含身份、范围和本轮执行状态
     * @return 带载荷或可识别错误状态的工具结果
     */
    @Override
    public ToolResult execute(ToolInput input, AgentExecutionContext context) {
        ReadDocumentInput readInput = (ReadDocumentInput) input;
        if (context == null || context.getUserId() == null || context.getUserId() <= 0) {
            return notReadable();
        }

        Set<Long> scope = context.getAccessibleKnowledgeBaseIds();
        Document document = documentReadAccessService.resolveReadableDocument(
                readInput.getDocumentId(), scope);
        if (document == null) {
            return notReadable();
        }

        // 先定位可信范围中的目标文档，再让读取模式决定检索计划和分页限制。
        String mode = readInput.getMode() == null ? "query" : readInput.getMode();
        if ("page".equals(mode)) {
            return readPage(readInput, scope, document);
        }
        if (!"query".equals(mode) || readInput.getOffset() != null
                || readInput.getContentOffset() != null || readInput.getEndOffset() != null
                || readInput.getAroundChunkId() != null || readInput.getContextRadius() != null) {
            return invalid("分页参数只用于 mode=page");
        }

        int limit = readInput.getLimit() == null
                ? ReadDocumentLimits.DEFAULT_LIMIT
                : Math.max(1, Math.min(readInput.getLimit(), ReadDocumentLimits.MAX_LIMIT));
        RagQueryOutcome outcome = documentScopedEvidenceReader.readAdmitted(
                new ArrayList<>(scope), document.getId(), readInput.getQuery(), limit);
        if (outcome == null) {
            throw contractMismatch(null);
        }

        String outcomeCode = outcome.getOutcome();
        if (isUnavailable(outcomeCode)) {
            throw unavailableFailure(outcome);
        }
        if ("no_evidence".equals(outcomeCode)) {
            if (outcome.getEvidences() != null && !outcome.getEvidences().isEmpty()) {
                throw contractMismatch(outcome);
            }
            return success(document, readInput.getQuery(), outcome, List.of(), false);
        }
        if (!"success".equals(outcomeCode)) {
            throw contractMismatch(outcome);
        }

        // 按目标文档复核准入证据，再统一应用返回限额。
        validateAdmitted(outcome, document);
        BoundedSnippets bounded = bound(outcome.getEvidences(), document.getId(), limit);
        if (bounded.snippets().isEmpty()) {
            throw contractMismatch(outcome);
        }

        return success(document, readInput.getQuery(), outcome,
                bounded.snippets(), bounded.truncated());
    }

    /**
     * 按文档内连续窗口调用准入读取，返回带字符约束的片段与安全续读游标。
     * 上游由 {@code ReadDocumentTool.execute} 调用。
     * 下游调用 {@code documentScopedEvidenceReader.readPage}、{@code page.isHasMore}、{@code json.codePointCount}。
     *
     * @param input 本次操作的结构化输入
     * @param scope 当前已授权的知识库 ID 集合
     * @param document 数据库中已定位的目标文档
     * @return 带载荷或可识别错误状态的工具结果
     */
    private ToolResult readPage(ReadDocumentInput input, Set<Long> scope, Document document) {
        int offset = input.getOffset() == null ? 0 : input.getOffset();
        int contentOffset = input.getContentOffset() == null ? 0 : input.getContentOffset();
        int radius = input.getContextRadius() == null ? 1 : input.getContextRadius();
        if (offset < 0 || offset > 1000000 || contentOffset < 0 || contentOffset > 100000000
                || radius < 0 || radius > 2 || input.getLimit() != null && input.getLimit() != 1
                || input.getEndOffset() != null && (input.getEndOffset() < offset || input.getEndOffset() > 1000000)
                || input.getAroundChunkId() != null && (input.getAroundChunkId() <= 0
                || offset != 0 || contentOffset != 0 || input.getEndOffset() != null)) {
            return invalid("连续阅读参数无效；每次只返回一个窗口，aroundChunkId 只用于起始页");
        }
        // 为 JSON 转义、来源标识及有界位置元数据预留空间。极小预算在读库/调用模型前拒绝。
        int windowChars = toolOutputMaxChars <= 0 ? MAX_SNIPPET_CHARS
                : Math.min(MAX_SNIPPET_CHARS, (toolOutputMaxChars - PAGE_METADATA_RESERVE_CHARS) / 6);
        if (windowChars < 16) {
            return budgetFailure();
        }

        DocumentReadWindow window = new DocumentReadWindow(offset, contentOffset,
                input.getEndOffset(), input.getAroundChunkId(), radius, windowChars);
        RagQueryOutcome outcome = documentScopedEvidenceReader.readPage(
                new ArrayList<>(scope), document.getId(), input.getQuery(), window);
        if (outcome == null) {
            throw contractMismatch(null);
        }
        if (isUnavailable(outcome.getOutcome())) {
            throw unavailableFailure(outcome);
        }
        if (!"success".equals(outcome.getOutcome()) && !"no_evidence".equals(outcome.getOutcome())) {
            throw contractMismatch(outcome);
        }


        // 先核对页面及游标，再允许把片段和续读位置公开给调用方。
        RagDocumentPage page = outcome.getDocumentPage();
        validatePage(page, window, outcome);
        List<ReadDocumentOutput.Snippet> snippets = new ArrayList<>();
        if ("success".equals(outcome.getOutcome())) {
            validateAdmitted(outcome, document);
            if (outcome.getEvidences().size() != 1) {
                throw contractMismatch(outcome);
            }
            RagEvidence evidence = outcome.getEvidences().get(0);
            if (!Integer.valueOf(page.getContentOffset()).equals(evidence.getContentOffset())
                    || evidence.getContent().codePointCount(0, evidence.getContent().length()) > windowChars) {
                throw contractMismatch(outcome);
            }
            snippets.add(ReadDocumentOutput.Snippet.builder().documentId(document.getId())
                    .chunkId(evidence.getChunkId()).knowledgeBaseId(evidence.getKnowledgeBaseId())
                    .chunkIndex(evidence.getChunkIndex()).contentOffset(evidence.getContentOffset())
                    .content(evidence.getContent()).headingPath(abbreviate(evidence.getHeadingPath(), 40))
                    .pageNo(evidence.getPageNo()).sourceType(abbreviate(evidence.getSourceType(), 24)).build());
        } else if (outcome.getEvidences() != null && !outcome.getEvidences().isEmpty()) {
            throw contractMismatch(outcome);
        }

        ReadDocumentOutput data = ReadDocumentOutput.builder().mode("page")
                .offset(page.getOffset()).contentOffset(page.getContentOffset()).endOffset(page.getEndOffset())
                .nextOffset(page.getNextOffset()).nextContentOffset(page.getNextContentOffset())
                .hasMore(page.isHasMore()).scannedChars(page.getScannedChars())
                .documentId(document.getId()).fileName(abbreviate(document.getFileName(), 40))
                .outcome(outcome.getOutcome()).snippets(List.copyOf(snippets)).build();
        // 绝不把被运行时预算裁掉的窗口当成已交付页；失败结果不含新游标。
        try {
            String json = new ObjectMapper().writeValueAsString(data);
            int reserve = 240 + (outcome.getReasonCode() == null ? 0 : outcome.getReasonCode().length());
            if (toolOutputMaxChars > 0 && json.codePointCount(0, json.length()) + reserve > toolOutputMaxChars) {
                return budgetFailure();
            }
        } catch (Exception exception) {
            throw contractMismatch(outcome);
        }

        return ToolResult.builder().toolName("read_document").success(true).outcome(outcome.getOutcome())
                .reasonCode(outcome.getReasonCode()).data(data).build();
    }

    /**
     * 检查 Python 返回的页码、偏移和文档窗口是否符合本次读取请求。
     * 上游由 {@code ReadDocumentTool.readPage} 调用。
     * 下游调用 {@code window.contentOffset}、{@code window.windowChars}、{@code window.aroundChunkId}。
     *
     * @param page 本次调用传入的 {@code RagDocumentPage}
     * @param window 允许读取的窗口范围和字符预算
     * @param outcome 问答或检索的业务终态
     */
    private static void validatePage(RagDocumentPage page, DocumentReadWindow window, RagQueryOutcome outcome) {
        if (page == null || page.getOffset() < 0 || page.getContentOffset() != window.contentOffset()
                || page.getScannedChars() < 0 || page.getScannedChars() > window.windowChars()
                || window.aroundChunkId() == null && page.getOffset() != window.offset()
                || window.aroundChunkId() == null && !java.util.Objects.equals(page.getEndOffset(), window.endOffset())
                || page.isHasMore() && (page.getNextOffset() == null || page.getNextContentOffset() == null)
                || !page.isHasMore() && (page.getNextOffset() != null || page.getNextContentOffset() != null)) {
            throw contractMismatch(outcome);
        }
        if (page.isHasMore() && !(
                page.getNextOffset() == page.getOffset()
                        && page.getNextContentOffset() == page.getContentOffset() + page.getScannedChars()
                        && page.getScannedChars() > 0
                || page.getNextOffset() == page.getOffset() + 1 && page.getNextContentOffset() == 0)) {
            throw contractMismatch(outcome);
        }
        if (page.getEndOffset() != null && (page.getEndOffset() < page.getOffset()
                || page.isHasMore() && page.getNextOffset() >= page.getEndOffset())) {
            throw contractMismatch(outcome);
        }
    }

    /**
     * 构造页码、模式或其他入参约束失败的不可重试工具返回。
     * 上游由 {@code ReadDocumentTool.execute}、{@code ReadDocumentTool.readPage} 调用。
     *
     * @param message 待处理的消息或对外提示
     * @return 带载荷或可识别错误状态的工具结果
     */
    private ToolResult invalid(String message) {
        return ToolResult.builder().toolName("read_document").success(false)
                .errorMessage(message).retryable(false).build();
    }

    /**
     * 返回工具输出预算不足的结果，避免调用方把失败页当成已读取。
     * 上游由 {@code ReadDocumentTool.readPage} 调用。
     *
     * @return 带载荷或可识别错误状态的工具结果
     */
    private ToolResult budgetFailure() {
        return ToolResult.builder().toolName("read_document").success(false)
                .outcome("dependency_unavailable").reasonCode("tool_output_budget_exceeded")
                .errorMessage("工具输出预算不足；本页未交付，请保留原游标并提高输出预算")
                .retryable(false).build();
    }

    /**
     * 控制来源路径或文件名等元数据字段的最大长度。
     * 上游由 {@code ReadDocumentTool.readPage} 调用。
     * 下游调用 {@code value.codePointCount}、{@code value.length}、{@code value.offsetByCodePoints}。
     *
     * @param value 本次调用传入的 {@code String}
     * @param max 本次调用传入的 {@code int}
     * @return 供后续处理使用的文本或标识
     */
    private static String abbreviate(String value, int max) {
        if (value == null || value.codePointCount(0, value.length()) <= max) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, max));
    }

    /**
     * 复核获准片段中的文档和知识库身份是否与已授权目标一致。
     * 上游由 {@code ReadDocumentTool.execute}、{@code ReadDocumentTool.readPage} 调用。
     * 下游调用 {@code contractMismatch}。
     *
     * @param outcome 问答或检索的业务终态
     * @param document 数据库中已定位的目标文档
     */
    private static void validateAdmitted(RagQueryOutcome outcome, Document document) {
        List<RagEvidence> admitted = outcome.getEvidences();
        if (admitted == null || admitted.isEmpty()) {
            throw contractMismatch(outcome);
        }
        for (RagEvidence evidence : admitted) {
            if (evidence == null
                    || evidence.getDocumentId() == null
                    || !evidence.getDocumentId().equals(document.getId())
                    || !document.getKnowledgeBaseId().equals(evidence.getKnowledgeBaseId())
                    || evidence.getChunkId() == null
                    || evidence.getContent() == null
                    || evidence.getContent().isBlank()) {
                throw contractMismatch(outcome);
            }
        }
    }

    /**
     * 按片段数量与总字符预算筛选可交付正文，避免为足够短而截断获准片段。
     * 上游由 {@code ReadDocumentTool.execute} 调用。
     * 下游调用 {@code TRUNCATION_MARKER.length}、{@code content.length}、{@code truncate}。
     *
     * @param admitted 本次调用传入的 {@code List<RagEvidence>}
     * @param documentId 目标文档 ID
     * @param limit 最多返回或保留的条数
     * @return 调用方使用的 {@code BoundedSnippets}
     */
    private static BoundedSnippets bound(
            List<RagEvidence> admitted, Long documentId, int limit) {
        List<ReadDocumentOutput.Snippet> snippets = new ArrayList<>();
        Set<Long> seenChunkIds = new HashSet<>();
        boolean truncated = false;
        int totalChars = 0;

        for (RagEvidence evidence : admitted) {
            if (!seenChunkIds.add(evidence.getChunkId())) {
                continue;
            }
            if (snippets.size() >= limit) {
                truncated = true;
                break;
            }
            int remaining = MAX_TOTAL_CHARS - totalChars;
            if (remaining <= TRUNCATION_MARKER.length()) {
                truncated = true;
                break;
            }
            String content = evidence.getContent();
            if (content.length() > MAX_SNIPPET_CHARS) {
                content = truncate(content, MAX_SNIPPET_CHARS);
                truncated = true;
            }
            if (content.length() > remaining) {
                content = truncate(content, remaining);
                truncated = true;
            }
            totalChars += content.length();
            snippets.add(ReadDocumentOutput.Snippet.builder()
                    .documentId(documentId)
                    .chunkId(evidence.getChunkId())
                    .knowledgeBaseId(evidence.getKnowledgeBaseId())
                    .chunkIndex(evidence.getChunkIndex())
                    .content(content)
                    .headingPath(evidence.getHeadingPath())
                    .pageNo(evidence.getPageNo())
                    .sourceType(evidence.getSourceType())
                    .build());
        }
        return new BoundedSnippets(List.copyOf(snippets), truncated);
    }

    /**
     * 按正文长度边界保留用于当前读文档结果的子串。
     * 上游由 {@code ReadDocumentTool.bound} 调用。
     * 下游调用 {@code TRUNCATION_MARKER.length}。
     *
     * @param content 待处理的正文内容
     * @param maxChars 允许使用的最大正文字符数
     * @return 供后续处理使用的文本或标识
     */
    private static String truncate(String content, int maxChars) {
        return content.substring(0, maxChars - TRUNCATION_MARKER.length()) + TRUNCATION_MARKER;
    }

    /**
     * 将文档标识、问题、准入片段与可选页信息装入模型工具结果。
     * 上游由 {@code ReadDocumentTool.execute} 调用。
     * 下游调用 {@code definition}。
     *
     * @param document 数据库中已定位的目标文档
     * @param query 待检索或组织上下文的查询文本
     * @param outcome 问答或检索的业务终态
     * @param snippets 本次调用传入的 {@code List<ReadDocumentOutput.Snippet>}
     * @param truncated 本次调用传入的 {@code boolean}
     * @return 带载荷或可识别错误状态的工具结果
     */
    private ToolResult success(Document document, String query, RagQueryOutcome outcome,
                               List<ReadDocumentOutput.Snippet> snippets, boolean truncated) {
        return ToolResult.builder()
                .toolName(definition().getName())
                .success(true)
                .outcome(outcome.getOutcome())
                .reasonCode(outcome.getReasonCode())
                .data(ReadDocumentOutput.builder()
                        .documentId(document.getId())
                        .fileName(document.getFileName())
                        .query(query)
                        .outcome(outcome.getOutcome())
                        .truncated(truncated)
                        .snippets(snippets)
                        .build())
                .build();
    }

    /**
     * 返回文档在当前可信范围内无法读取的统一结果。
     * 上游由 {@code ReadDocumentTool.execute} 调用。
     * 下游调用 {@code definition}。
     *
     * @return 带载荷或可识别错误状态的工具结果
     */
    private ToolResult notReadable() {
        return ToolResult.builder()
                .toolName(definition().getName())
                .success(false)
                .errorMessage(NOT_READABLE_MESSAGE)
                .retryable(false)
                .build();
    }

    /**
     * 区分问答管线返回的依赖故障或超时终态。
     * 上游由 {@code ReadDocumentTool.execute}、{@code ReadDocumentTool.readPage} 调用。
     *
     * @param outcome 问答或检索的业务终态
     * @return 条件成立时为 true，其他状态按 false 处理
     */
    private static boolean isUnavailable(String outcome) {
        return "dependency_unavailable".equals(outcome) || "timeout".equals(outcome);
    }

    /**
     * 将依赖错误或超时连同原诊断传给工具执行层。
     * 上游由 {@code ReadDocumentTool.execute}、{@code ReadDocumentTool.readPage} 调用。
     *
     * @param outcome 问答或检索的业务终态
     * @return 调用方使用的 {@code RagQueryFailureException}
     */
    private static RagQueryFailureException unavailableFailure(RagQueryOutcome outcome) {
        boolean timeout = "timeout".equals(outcome.getOutcome());
        return new RagQueryFailureException(
                timeout ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.SERVICE_UNAVAILABLE,
                outcome.getOutcome(),
                outcome.getReasonCode() == null ? outcome.getOutcome() : outcome.getReasonCode(),
                outcome.getFailedStage() == null ? "rag.query" : outcome.getFailedStage(),
                outcome.getRequestId(),
                timeout ? "文档阅读超时，请稍后重试" : "文档阅读服务暂时不可用，请稍后重试",
                null);
    }

    /**
     * 为目标文档、定位信息或返回正文不一致的结果生成契约错误。
     * 上游由 {@code ReadDocumentTool.execute}、{@code ReadDocumentTool.readPage}、{@code ReadDocumentTool.validateAdmitted} 调用。
     *
     * @param outcome 问答或检索的业务终态
     * @return 调用方使用的 {@code RagQueryFailureException}
     */
    private static RagQueryFailureException contractMismatch(RagQueryOutcome outcome) {
        return new RagQueryFailureException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "dependency_unavailable",
                "ai_contract_mismatch",
                "ai.response_contract",
                outcome == null ? null : outcome.getRequestId(),
                "文档阅读结果不符合契约",
                null);
    }

    private record BoundedSnippets(
            List<ReadDocumentOutput.Snippet> snippets, boolean truncated) {
    }
}
