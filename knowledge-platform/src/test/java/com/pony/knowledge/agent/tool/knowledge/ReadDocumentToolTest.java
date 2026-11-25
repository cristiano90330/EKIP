/* 验证文档阅读工具的授权、页游标、获准证据与字符预算。 */

package com.pony.knowledge.agent.tool.knowledge;

import com.pony.knowledge.agent.model.AgentExecutionContext;
import com.pony.knowledge.agent.tool.ToolDefinition;
import com.pony.knowledge.agent.tool.input.ReadDocumentInput;
import com.pony.knowledge.agent.tool.input.ReadDocumentLimits;
import com.pony.knowledge.agent.tool.output.ReadDocumentOutput;
import com.pony.knowledge.agent.tool.output.ToolResult;
import com.pony.knowledge.entity.Document;
import com.pony.knowledge.entity.DocumentStatus;
import com.pony.knowledge.exception.RagQueryFailureException;
import com.pony.knowledge.mapper.DocumentMapper;
import com.pony.knowledge.service.DocumentReadAccessService;
import com.pony.knowledge.service.rag.PythonRagPipeline;
import com.pony.knowledge.service.rag.model.RagEvidence;
import com.pony.knowledge.service.rag.model.RagQueryOutcome;
import com.pony.knowledge.service.rag.model.DocumentReadWindow;
import com.pony.knowledge.service.rag.model.RagDocumentPage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * read_document 的聚焦验收：文档归属、默认拒绝、只消费准入证据、跨文档拒绝与输出上限。
 * 不另造完整检索夹具；Python 侧文档限定 SQL 与准入路径由 A 的测试覆盖。
 */
class ReadDocumentToolTest {

    private static final long DOCUMENT_ID = 10L;

    private static final long KB_ID = 1L;

    private static final long USER_ID = 7L;

    private static final String QUERY = "报销标准";

    private final DocumentMapper documentMapper = mock(DocumentMapper.class);

    private final DocumentScopedEvidenceReader reader = mock(DocumentScopedEvidenceReader.class);

    private final ReadDocumentTool tool = new ReadDocumentTool(
            new DocumentReadAccessService(documentMapper), reader);

    /** 通过 {@code pipeline.retrieveAdmitted}、{@code outcome} 驱动该场景，核对输出和依赖调用。 */
    @Test
    void productionReader_passesAuthorizedScopeAndDocumentToAdmittedRetrieval() {
        PythonRagPipeline pipeline = mock(PythonRagPipeline.class);
        RagQueryOutcome expected = outcome("no_evidence", List.of(), List.of());
        when(pipeline.retrieveAdmitted(List.of(KB_ID), QUERY, List.of(), 3, DOCUMENT_ID))
                .thenReturn(expected);

        RagQueryOutcome actual = new PythonDocumentScopedEvidenceReader(pipeline)
                .readAdmitted(List.of(KB_ID), DOCUMENT_ID, QUERY, 3);

        assertSame(expected, actual);
        verify(pipeline).retrieveAdmitted(List.of(KB_ID), QUERY, List.of(), 3, DOCUMENT_ID);
    }

    /** 通过 {@code tool.definition}、{@code properties.get} 驱动用例，布尔状态符合断言，实际结果与固定预期一致。 */
    @Test
    void definition_exposesOnlyLlmControlledParameters() {
        ToolDefinition definition = tool.definition();
        assertEquals("read_document", definition.getName());
        assertFalse(definition.isRequiresApproval());
        assertEquals("object", definition.getParameters().get("type"));

        @SuppressWarnings("unchecked")
        Map<String, Object> properties =
                (Map<String, Object>) definition.getParameters().get("properties");
        assertEquals(Set.of("documentId", "query", "limit", "mode", "offset", "contentOffset", "endOffset", "aroundChunkId", "contextRadius"), properties.keySet());
        assertFalse(properties.containsKey("userId"));
        assertFalse(properties.containsKey("knowledgeBaseIds"));

        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) definition.getParameters().get("required");
        assertEquals(Set.of("documentId", "query"), Set.copyOf(required));

        @SuppressWarnings("unchecked")
        Map<String, Object> limit = (Map<String, Object>) properties.get("limit");
        assertEquals(1L, limit.get("minimum"));
        assertEquals((long) ReadDocumentLimits.MAX_LIMIT, limit.get("maximum"));
    }

    /** 通过 {@code tool.definition}、{@code properties.get} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void definition_allowsCopyingNullPageBoundsWithoutInventingZero() throws Exception {
        Map<?, ?> properties = (Map<?, ?>) tool.definition().getParameters().get("properties");
        for (String name : List.of("endOffset", "aroundChunkId", "contextRadius")) {
            Map<?, ?> property = (Map<?, ?>) properties.get(name);
            assertEquals(List.of("integer", "null"), property.get("type"));
        }

        Map<?, ?> end = (Map<?, ?>) properties.get("endOffset");
        assertEquals(0L, end.get("minimum"));
        assertEquals(1000000L, end.get("maximum"));
        ReadDocumentInput replay = new ObjectMapper().readValue(
                "{\"documentId\":10,\"query\":\"核验代号\",\"mode\":\"page\","
                        + "\"offset\":0,\"contentOffset\":400,\"endOffset\":null,"
                        + "\"aroundChunkId\":null,\"contextRadius\":null}", ReadDocumentInput.class);
        assertNull(replay.getEndOffset());
        assertNull(replay.getAroundChunkId());
        assertNull(replay.getContextRadius());
        assertEquals(400, replay.getContentOffset());
    }

    /** 请求当前范围外的文档，验证在执行证据读取前拒绝该请求。 */
    @Test
    void documentOutsideEffectiveScope_isRejectedBeforeRetrieval() {
        when(documentMapper.selectReadableInScope(DOCUMENT_ID, List.of(KB_ID))).thenReturn(document(DocumentStatus.COMPLETED));

        ToolResult result = tool.execute(input(5), context(Set.of(2L)));

        assertFalse(result.isSuccess());
        assertEquals(ReadDocumentTool.NOT_READABLE_MESSAGE, result.getErrorMessage());
        assertFalse(result.isRetryable());
        verifyNoInteractions(reader);
    }

    /** 通过 {@code tool.execute} 驱动用例，跳过受保护的调用，布尔状态符合断言。 */
    @Test
    void missingOrDeletedDocument_isRejected() {
        when(documentMapper.selectReadableInScope(DOCUMENT_ID, List.of(KB_ID))).thenReturn(null);

        ToolResult result = tool.execute(input(null), context(Set.of(KB_ID)));

        assertFalse(result.isSuccess());
        assertEquals(ReadDocumentTool.NOT_READABLE_MESSAGE, result.getErrorMessage());
        verifyNoInteractions(reader);
    }

    /** 通过 {@code tool.execute} 驱动用例，跳过受保护的调用，布尔状态符合断言。 */
    @Test
    void documentNotCompleted_isRejected() {
        for (DocumentStatus status : List.of(
                DocumentStatus.UPLOADED, DocumentStatus.PROCESSING, DocumentStatus.FAILED)) {
            reset(documentMapper);
            when(documentMapper.selectReadableInScope(DOCUMENT_ID, List.of(KB_ID))).thenReturn(document(status));

            ToolResult result = tool.execute(input(null), context(Set.of(KB_ID)));

            assertFalse(result.isSuccess(), status + " 不应可读");
            assertEquals(ReadDocumentTool.NOT_READABLE_MESSAGE, result.getErrorMessage());
        }
        verifyNoInteractions(reader);
    }

    /** 通过 {@code tool.execute} 驱动用例，跳过受保护的调用，布尔状态符合断言。 */
    @Test
    void emptyScopeOrMissingIdentity_isDefaultDenyBeforeDatabaseAndRetrieval() {
        ToolResult emptyScope = tool.execute(input(null), context(Set.of()));
        ToolResult nullScope = tool.execute(input(null), context(null));
        ToolResult noUser = tool.execute(input(null), AgentExecutionContext.builder()
                .userId(null)
                .accessibleKnowledgeBaseIds(Set.of(KB_ID))
                .build());

        for (ToolResult result : List.of(emptyScope, nullScope, noUser)) {
            assertFalse(result.isSuccess());
            assertEquals(ReadDocumentTool.NOT_READABLE_MESSAGE, result.getErrorMessage());
        }
        verifyNoInteractions(documentMapper, reader);
    }

    /** 通过 {@code reader.readAdmitted}、{@code tool.execute} 驱动用例，布尔状态符合断言，实际结果与固定预期一致。 */
    @Test
    void returnsOnlyAdmittedEvidence_neverRawSources() {
        when(documentMapper.selectReadableInScope(DOCUMENT_ID, List.of(KB_ID))).thenReturn(document(DocumentStatus.COMPLETED));
        RagEvidence raw = evidence(99L, DOCUMENT_ID, "原始候选不应出现");
        RagEvidence admitted = evidence(7L, DOCUMENT_ID, "获准片段");
        when(reader.readAdmitted(List.of(KB_ID), DOCUMENT_ID, QUERY,
                ReadDocumentLimits.DEFAULT_LIMIT))
                .thenReturn(outcome("success", List.of(raw), List.of(admitted, admitted)));

        ToolResult result = tool.execute(input(null), context(Set.of(KB_ID)));

        assertTrue(result.isSuccess());
        ReadDocumentOutput output = (ReadDocumentOutput) result.getData();
        assertEquals(DOCUMENT_ID, output.getDocumentId());
        assertEquals("制度.pdf", output.getFileName());
        assertEquals("success", output.getOutcome());
        assertFalse(output.isTruncated());
        assertEquals(1, output.getSnippets().size());

        ReadDocumentOutput.Snippet snippet = output.getSnippets().get(0);
        assertEquals(DOCUMENT_ID, snippet.getDocumentId());
        assertEquals(7L, snippet.getChunkId());
        assertEquals("获准片段", snippet.getContent());
        assertEquals("1.1", snippet.getHeadingPath());
        assertEquals(2, snippet.getPageNo());
        assertTrue(output.getSnippets().stream()
                .noneMatch(item -> item.getContent().contains("原始候选")));
    }

    /** 让下游返回另一文档的获准证据，确认 Java 将不一致结果视为契约失败。 */
    @Test
    void admittedEvidenceFromAnotherDocument_isContractFailure() {
        when(documentMapper.selectReadableInScope(DOCUMENT_ID, List.of(KB_ID))).thenReturn(document(DocumentStatus.COMPLETED));
        when(reader.readAdmitted(List.of(KB_ID), DOCUMENT_ID, QUERY, ReadDocumentLimits.DEFAULT_LIMIT))
                .thenReturn(outcome("success", List.of(), List.of(evidence(7L, 999L, "越权片段"))));

        RagQueryFailureException failure = assertThrows(RagQueryFailureException.class,
                () -> tool.execute(input(null), context(Set.of(KB_ID))));

        assertEquals("dependency_unavailable", failure.getOutcome());
        assertEquals("ai_contract_mismatch", failure.getReasonCode());
    }

    /** 让下游返回其他知识库的证据，确认读取工具未将片段交付给本轮。 */
    @Test
    void admittedEvidenceFromAnotherKnowledgeBase_isContractFailure() {
        when(documentMapper.selectReadableInScope(DOCUMENT_ID, List.of(KB_ID))).thenReturn(document(DocumentStatus.COMPLETED));
        RagEvidence mismatched = evidence(7L, DOCUMENT_ID, "越权片段");
        mismatched.setKnowledgeBaseId(2L);
        when(reader.readAdmitted(List.of(KB_ID), DOCUMENT_ID, QUERY, ReadDocumentLimits.DEFAULT_LIMIT))
                .thenReturn(outcome("success", List.of(), List.of(mismatched)));

        RagQueryFailureException failure = assertThrows(RagQueryFailureException.class,
                () -> tool.execute(input(null), context(Set.of(KB_ID))));

        assertEquals("ai_contract_mismatch", failure.getReasonCode());
    }

    /** 通过 {@code reader.readAdmitted}、{@code tool.execute} 驱动用例，布尔状态符合断言，实际结果与固定预期一致。 */
    @Test
    void appliesSnippetLimitAndTruncatesContent() {
        when(documentMapper.selectReadableInScope(DOCUMENT_ID, List.of(KB_ID))).thenReturn(document(DocumentStatus.COMPLETED));
        List<RagEvidence> admitted = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            admitted.add(evidence(100L + i, DOCUMENT_ID, "x".repeat(2000)));
        }

        when(reader.readAdmitted(List.of(KB_ID), DOCUMENT_ID, QUERY, 3))
                .thenReturn(outcome("success", List.of(), admitted));

        ToolResult result = tool.execute(input(3), context(Set.of(KB_ID)));

        assertTrue(result.isSuccess());
        ReadDocumentOutput output = (ReadDocumentOutput) result.getData();
        assertTrue(output.isTruncated());
        assertEquals(3, output.getSnippets().size());
        for (ReadDocumentOutput.Snippet snippet : output.getSnippets()) {
            assertEquals(ReadDocumentTool.MAX_SNIPPET_CHARS, snippet.getContent().length());
            assertTrue(snippet.getContent().endsWith(ReadDocumentTool.TRUNCATION_MARKER));
        }

        verify(reader).readAdmitted(List.of(KB_ID), DOCUMENT_ID, QUERY, 3);
    }

    /** 通过 {@code reader.readAdmitted}、{@code tool.execute} 驱动用例，布尔状态符合断言。 */
    @Test
    void limitAboveMaximum_isClampedToSchemaBound() {
        when(documentMapper.selectReadableInScope(DOCUMENT_ID, List.of(KB_ID))).thenReturn(document(DocumentStatus.COMPLETED));
        when(reader.readAdmitted(List.of(KB_ID), DOCUMENT_ID, QUERY, ReadDocumentLimits.MAX_LIMIT))
                .thenReturn(outcome("success", List.of(),
                        List.of(evidence(7L, DOCUMENT_ID, "获准片段"))));

        ToolResult result = tool.execute(input(99), context(Set.of(KB_ID)));

        assertTrue(result.isSuccess());
        verify(reader).readAdmitted(
                List.of(KB_ID), DOCUMENT_ID, QUERY, ReadDocumentLimits.MAX_LIMIT);
    }

    /** 通过 {@code reader.readAdmitted}、{@code tool.execute} 驱动用例，布尔状态符合断言，实际结果与固定预期一致。 */
    @Test
    void defaultLimitAndTotalCharacterBudget_areEnforced() {
        when(documentMapper.selectReadableInScope(DOCUMENT_ID, List.of(KB_ID))).thenReturn(document(DocumentStatus.COMPLETED));
        List<RagEvidence> admitted = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            admitted.add(evidence(200L + i, DOCUMENT_ID, "y".repeat(2000)));
        }

        when(reader.readAdmitted(List.of(KB_ID), DOCUMENT_ID, QUERY,
                ReadDocumentLimits.DEFAULT_LIMIT))
                .thenReturn(outcome("success", List.of(), admitted));

        ToolResult result = tool.execute(input(null), context(Set.of(KB_ID)));

        ReadDocumentOutput output = (ReadDocumentOutput) result.getData();
        assertTrue(output.isTruncated());
        assertEquals(ReadDocumentLimits.DEFAULT_LIMIT, output.getSnippets().size());
        int totalChars = output.getSnippets().stream()
                .mapToInt(snippet -> snippet.getContent().length())
                .sum();
        assertEquals(ReadDocumentTool.MAX_TOTAL_CHARS, totalChars);
        verify(reader).readAdmitted(
                List.of(KB_ID), DOCUMENT_ID, QUERY, ReadDocumentLimits.DEFAULT_LIMIT);
    }

    /** 通过 {@code reader.readAdmitted}、{@code tool.execute} 驱动用例，布尔状态符合断言，返回的数据、边界或顺序符合断言。 */
    @Test
    void noEvidence_isSuccessWithEmptySnippets() {
        when(documentMapper.selectReadableInScope(DOCUMENT_ID, List.of(KB_ID))).thenReturn(document(DocumentStatus.COMPLETED));
        RagQueryOutcome noEvidence = outcome("no_evidence", List.of(), null);
        noEvidence.setReasonCode("below_threshold");
        when(reader.readAdmitted(List.of(KB_ID), DOCUMENT_ID, QUERY, ReadDocumentLimits.DEFAULT_LIMIT))
                .thenReturn(noEvidence);

        ToolResult result = tool.execute(input(null), context(Set.of(KB_ID)));

        assertTrue(result.isSuccess());
        assertEquals("no_evidence", result.getOutcome());
        assertEquals("below_threshold", result.getReasonCode());
        ReadDocumentOutput output = (ReadDocumentOutput) result.getData();
        assertEquals("no_evidence", output.getOutcome());
        assertTrue(output.getSnippets().isEmpty());
        assertFalse(output.isTruncated());
    }

    /** 通过 {@code reader.readAdmitted}、{@code tool.execute} 驱动用例，抛出 {@code RagQueryFailureException}，实际结果与固定预期一致。 */
    @Test
    void dependencyUnavailableAndTimeout_areFailuresNotNoEvidence() {
        when(documentMapper.selectReadableInScope(DOCUMENT_ID, List.of(KB_ID))).thenReturn(document(DocumentStatus.COMPLETED));
        when(reader.readAdmitted(List.of(KB_ID), DOCUMENT_ID, QUERY, ReadDocumentLimits.DEFAULT_LIMIT))
                .thenReturn(outcome("dependency_unavailable", List.of(), List.of()))
                .thenReturn(outcome("timeout", List.of(), List.of()));

        RagQueryFailureException unavailable = assertThrows(RagQueryFailureException.class,
                () -> tool.execute(input(null), context(Set.of(KB_ID))));
        assertEquals("dependency_unavailable", unavailable.getOutcome());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, unavailable.getStatus());

        RagQueryFailureException timeout = assertThrows(RagQueryFailureException.class,
                () -> tool.execute(input(null), context(Set.of(KB_ID))));
        assertEquals("timeout", timeout.getOutcome());
        assertEquals(HttpStatus.GATEWAY_TIMEOUT, timeout.getStatus());
    }

    /** 通过 {@code reader.readAdmitted}、{@code tool.execute} 驱动用例，抛出 {@code RagQueryFailureException}，实际结果与固定预期一致。 */
    @Test
    void successWithoutAdmittedEvidence_isContractFailure() {
        when(documentMapper.selectReadableInScope(DOCUMENT_ID, List.of(KB_ID))).thenReturn(document(DocumentStatus.COMPLETED));
        when(reader.readAdmitted(List.of(KB_ID), DOCUMENT_ID, QUERY, ReadDocumentLimits.DEFAULT_LIMIT))
                .thenReturn(outcome("success",
                        List.of(evidence(99L, DOCUMENT_ID, "raw")), List.of()));

        RagQueryFailureException failure = assertThrows(RagQueryFailureException.class,
                () -> tool.execute(input(null), context(Set.of(KB_ID))));

        assertEquals("ai_contract_mismatch", failure.getReasonCode());
    }

    /** 构造有后续内容的正文窗口，核对当前页完整输出且下一位置正确。 */
    @Test
    void continuousWindow_preservesCompleteTextAndContinuationWithinDefaultOutputBudget() throws Exception {
        Document escapedMetadata = document(DocumentStatus.COMPLETED);
        escapedMetadata.setFileName(String.valueOf((char) 0).repeat(40));
        when(documentMapper.selectReadableInScope(DOCUMENT_ID, List.of(KB_ID)))
                .thenReturn(escapedMetadata);
        when(reader.readPage(eq(List.of(KB_ID)), eq(DOCUMENT_ID), eq(QUERY), any()))
                .thenAnswer(invocation -> {
                    DocumentReadWindow window = invocation.getArgument(3);
                    // JSON 最坏转义仍须和游标完整交付，不能像 query 模式那样先截正文再跳块。
                    RagEvidence admitted = evidence(7L, DOCUMENT_ID, String.valueOf((char) 0).repeat(window.windowChars()));
                    admitted.setHeadingPath(String.valueOf((char) 0).repeat(40));
                    admitted.setSourceType(String.valueOf((char) 0).repeat(24));
                    admitted.setContentOffset(0);
                    RagQueryOutcome result = outcome("success", List.of(), List.of(admitted));
                    result.setDocumentPage(RagDocumentPage.builder().offset(0).contentOffset(0)
                            .nextOffset(0).nextContentOffset(window.windowChars()).hasMore(true)
                            .scannedChars(window.windowChars()).build());
                    return result;
                });
        ToolResult result = tool.execute(ReadDocumentInput.builder().documentId(DOCUMENT_ID)
                .query(QUERY).mode("page").build(), context(Set.of(KB_ID)));
        assertTrue(result.isSuccess());
        ReadDocumentOutput output = (ReadDocumentOutput) result.getData();
        assertEquals(0, output.getNextOffset());
        assertEquals(output.getScannedChars(), output.getNextContentOffset());
        assertEquals(output.getScannedChars().intValue(), output.getSnippets().get(0).getContent().length());
        assertFalse(output.isTruncated());
        assertTrue((800 + output.getScannedChars() - 1) / output.getScannedChars() <= 2,
                "默认输出预算应允许一个800字块在两页内读完");
        String json = new ObjectMapper().writeValueAsString(output);
        String actualToolMessage = "\nsource_reference=[[cite:S10_7_O0]] documentId=10 chunkId=7 contentOffset=0"
                + "\ntool=read_document success=true outcome=success data=" + json;
        assertTrue(actualToolMessage.codePointCount(0, actualToolMessage.length()) <= 4000);
        assertTrue(json.indexOf("nextOffset") < json.indexOf("snippets"));
    }

    /** 分别模拟无获准证据的阅读页和极小字数预算，核对续读游标只在实际扫描后推进。 */
    @Test
    void rejectedWindow_stillReturnsScanCursor_butTinyBudgetDoesNotReadOrAdvance() {
        when(documentMapper.selectReadableInScope(DOCUMENT_ID, List.of(KB_ID)))
                .thenReturn(document(DocumentStatus.COMPLETED));
        RagQueryOutcome rejected = outcome("no_evidence", List.of(), List.of());
        rejected.setDocumentPage(RagDocumentPage.builder().offset(2).contentOffset(0)
                .nextOffset(3).nextContentOffset(0).hasMore(true).scannedChars(10).build());
        when(reader.readPage(eq(List.of(KB_ID)), eq(DOCUMENT_ID), eq(QUERY), any())).thenReturn(rejected);
        ReadDocumentInput input = ReadDocumentInput.builder().documentId(DOCUMENT_ID).query(QUERY)
                .mode("page").offset(2).build();
        ReadDocumentOutput output = (ReadDocumentOutput) tool.execute(input, context(Set.of(KB_ID))).getData();
        assertEquals(3, output.getNextOffset());
        assertTrue(output.getSnippets().isEmpty());

        reset(reader);
        ReflectionTestUtils.setField(tool, "toolOutputMaxChars", 1000);
        ToolResult failed = tool.execute(input, context(Set.of(KB_ID)));
        assertFalse(failed.isSuccess());
        assertEquals("tool_output_budget_exceeded", failed.getReasonCode());
        assertNull(failed.getData(), "预算失败不得交付新游标");
        verifyNoInteractions(reader);
    }

    /**
     * 返回当前计算采用的 {@code document}。
     *
     * @param status 本次调用传入的 {@code DocumentStatus}
     * @return 当前记录中的 {@code document}
     */
    private Document document(DocumentStatus status) {
        Document document = new Document();
        document.setId(DOCUMENT_ID);
        document.setKnowledgeBaseId(KB_ID);
        document.setFileName("制度.pdf");
        document.setStatus(status);
        return document;
    }

    /**
     * 在该类型内部准备后续运行所需的数据。
     * @param knowledgeBaseIds 本次调用传入的 {@code Set<Long>}
     * @return 调用方使用的 {@code AgentExecutionContext}
     */
    private AgentExecutionContext context(Set<Long> knowledgeBaseIds) {
        return AgentExecutionContext.builder()
                .userId(USER_ID)
                .sessionId(USER_ID + ":s1")
                .traceId("trace-1")
                .accessibleKnowledgeBaseIds(knowledgeBaseIds)
                .build();
    }

    /**
     * 在该类型内部准备后续运行所需的数据。
     *
     * @param chunkId 本次调用传入的 {@code long}
     * @param documentId 目标文档 ID
     * @param content 待处理的正文内容
     * @return 调用方使用的 {@code RagEvidence}
     */
    private RagEvidence evidence(long chunkId, long documentId, String content) {
        return RagEvidence.builder()
                .chunkId(chunkId)
                .documentId(documentId)
                .knowledgeBaseId(KB_ID)
                .chunkIndex((int) chunkId)
                .content(content)
                .headingPath("1.1")
                .pageNo(2)
                .sourceType("paragraph")
                .build();
    }

    /**
     * 提供带准入证据和诊断的问答管线结果。
     *
     * @param outcome 问答或检索的业务终态
     * @param sources 本次调用传入的 {@code List<RagEvidence>}
     * @param evidences 本次调用传入的 {@code List<RagEvidence>}
     * @return 带准入证据和诊断的问答管线结果
     */
    private RagQueryOutcome outcome(
            String outcome, List<RagEvidence> sources, List<RagEvidence> evidences) {
        return RagQueryOutcome.builder()
                .outcome(outcome)
                .sources(sources)
                .evidences(evidences)
                .requestId("req-1")
                .build();
    }

    /**
     * 在该类型内部准备后续运行所需的数据。
     * @param limit 最多返回或保留的条数
     * @return 调用方使用的 {@code ReadDocumentInput}
     */
    private ReadDocumentInput input(Integer limit) {
        return ReadDocumentInput.builder()
                .documentId(DOCUMENT_ID)
                .query(QUERY)
                .limit(limit)
                .build();
    }
}
