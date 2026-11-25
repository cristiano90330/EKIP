/* 验证检索工具的用户提问回填、授权范围和结果状态映射。 */

package com.pony.knowledge.agent.tool.knowledge;

import com.pony.knowledge.agent.model.AgentExecutionContext;
import com.pony.knowledge.agent.tool.input.KnowledgeSearchInput;
import com.pony.knowledge.agent.tool.output.KnowledgeSearchOutput;
import com.pony.knowledge.agent.tool.output.ToolResult;
import com.pony.knowledge.service.rag.PythonRagPipeline;
import com.pony.knowledge.service.rag.model.RagEvidence;
import com.pony.knowledge.service.rag.model.RagQueryOutcome;
import com.pony.knowledge.exception.RagQueryFailureException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 3.7b 验收：KnowledgeSearchTool 纯逻辑——一次调用 PythonRagPipeline（Agent 准入检索）、
 * 权限 Context 传递、只返回获准证据。（Trace / Metrics 由 3.7c 的 ToolExecutor 统一负责，见 ToolExecutorTest。）
 */
class KnowledgeSearchToolTest {

    /** 通过 {@code pipeline.retrieveAdmitted}、{@code outcome} 驱动该场景，核对输出和依赖调用。 */
    @Test
    void currentUserQuestionIsKeptWhenTheModelSuppliesOnlyATopic() {
        PythonRagPipeline pipeline = mock(PythonRagPipeline.class);
        String question = "星河项目材料缺失时应在几个工作日补齐？";
        String hint = "星河项目报销制度";
        when(pipeline.retrieveAdmitted(List.of(1L), question, List.of(), 5, null, hint))
                .thenReturn(outcome(question, hint, List.of(), List.of()));
        AgentExecutionContext context = AgentExecutionContext.builder().userId(1L)
                .accessibleKnowledgeBaseIds(Set.of(1L)).currentQuery(question).build();
        new KnowledgeSearchTool(pipeline).execute(
                KnowledgeSearchInput.builder().query(hint).build(), context);
        verify(pipeline).retrieveAdmitted(List.of(1L), question, List.of(), 5, null, hint);
    }

    /**
     * 在该类型内部准备后续运行所需的数据。
     * @param content 待处理的正文内容
     * @return 调用方使用的 {@code RagEvidence}
     */
    private RagEvidence evidence(String content) {
        return RagEvidence.builder()
                .documentId(10L)
                .chunkId(1L)
                .knowledgeBaseId(1L)
                .chunkIndex(0)
                .content(content)
                .score(0.9)
                .build();
    }

    /**
     * 提供带准入证据和诊断的问答管线结果。
     *
     * @param originalQuery 本次调用传入的 {@code String}
     * @param rewrittenQuery 本次调用传入的 {@code String}
     * @param rawSources 本次调用传入的 {@code List<RagEvidence>}
     * @param admitted 本次调用传入的 {@code List<RagEvidence>}
     * @return 带准入证据和诊断的问答管线结果
     */
    private RagQueryOutcome outcome(String originalQuery, String rewrittenQuery,
                                    List<RagEvidence> rawSources, List<RagEvidence> admitted) {
        return RagQueryOutcome.builder()
                .originalQuery(originalQuery)
                .rewrittenQuery(rewrittenQuery)
                .sources(rawSources)
                .evidences(admitted)
                .outcome(admitted == null || admitted.isEmpty() ? "no_evidence" : "success")
                .build();
    }

    /** 通过 {@code pipeline.retrieveAdmitted}、{@code anyInt} 驱动该场景，抛出 {@code RagQueryFailureException}，实际结果与固定预期一致。 */
    @Test
    void missingOutcome_failsClosedInsteadOfInferringNoEvidence() {
        PythonRagPipeline pipeline = mock(PythonRagPipeline.class);
        when(pipeline.retrieveAdmitted(any(), any(), any(), anyInt()))
                .thenReturn(RagQueryOutcome.builder().evidences(List.of()).build());

        RagQueryFailureException failure = assertThrows(RagQueryFailureException.class,
                () -> new KnowledgeSearchTool(pipeline).execute(
                        KnowledgeSearchInput.builder().query("q").build(),
                        AgentExecutionContext.builder().userId(1L)
                                .accessibleKnowledgeBaseIds(Set.of(1L)).build()));

        assertEquals("dependency_unavailable", failure.getOutcome());
        assertEquals("ai_contract_mismatch", failure.getReasonCode());
    }

    /** 通过 {@code tool.execute} 驱动用例，布尔状态符合断言，实际结果与固定预期一致。 */
    @Test
    void knowledgeSearch_returnsOnlyAdmittedEvidence_andPassesContextPermission() {
        PythonRagPipeline pipeline = mock(PythonRagPipeline.class);
        when(pipeline.retrieveAdmitted(eq(List.of(1L)), eq("Redis连接池怎么配置"), eq(List.of()), eq(5)))
                .thenReturn(outcome("Redis连接池怎么配置", "Redis 连接池 配置",
                        List.of(evidence("原始候选，不得交给模型")),
                        List.of(evidence("Redis 连接池配置说明"))));
        KnowledgeSearchTool tool = new KnowledgeSearchTool(pipeline);

        ToolResult result = tool.execute(
                KnowledgeSearchInput.builder()
                        .query("Redis连接池怎么配置")
                        .topK(5)
                        .build(),
                AgentExecutionContext.builder()
                        .userId(1L)
                        .sessionId("test-session")
                        .traceId("test-trace")
                        .accessibleKnowledgeBaseIds(Set.of(1L))
                        .build());

        assertTrue(result.isSuccess());
        assertEquals("success", result.getOutcome());
        KnowledgeSearchOutput output = (KnowledgeSearchOutput) result.getData();
        assertNotNull(output);
        assertEquals("Redis连接池怎么配置", output.getOriginalQuery());
        assertEquals(1, output.getEvidences().size());
        assertEquals("Redis 连接池配置说明", output.getEvidences().get(0).getContent());

        // 权限 Context 传递：accessibleKnowledgeBaseIds=[1] 到达 Python 管线的库过滤
        verify(pipeline).retrieveAdmitted(eq(List.of(1L)), eq("Redis连接池怎么配置"), eq(List.of()), eq(5));
    }

    /** 通过 {@code tool.execute} 驱动用例，布尔状态符合断言，返回的数据、边界或顺序符合断言。 */
    @Test
    void knowledgeSearch_withoutEvidence_returnsExplicitNoEvidence() {
        PythonRagPipeline pipeline = mock(PythonRagPipeline.class);
        RagQueryOutcome noEvidence = outcome("无关问题", "无关问题", List.of(), List.of());
        noEvidence.setReasonCode("below_threshold");
        when(pipeline.retrieveAdmitted(any(), any(), any(), anyInt()))
                .thenReturn(noEvidence);
        KnowledgeSearchTool tool = new KnowledgeSearchTool(pipeline);

        ToolResult result = tool.execute(
                KnowledgeSearchInput.builder().query("无关问题").topK(5).build(),
                AgentExecutionContext.builder()
                        .userId(1L)
                        .accessibleKnowledgeBaseIds(Set.of(1L))
                        .build());

        assertTrue(result.isSuccess());
        assertEquals("no_evidence", result.getOutcome());
        assertEquals("below_threshold", result.getReasonCode());
        KnowledgeSearchOutput output = (KnowledgeSearchOutput) result.getData();
        assertNotNull(output);
        assertTrue(output.getEvidences().isEmpty());
        assertEquals("no_evidence", output.getOutcome());
        assertEquals("below_threshold", output.getReasonCode());
    }

    /** 通过 {@code pipeline.retrieveAdmitted}、{@code anyInt} 驱动该场景，抛出 {@code RagQueryFailureException}，实际结果与固定预期一致。 */
    @Test
    void dependencyFailure_preservesOutcomeAndReasonInsteadOfBecomingNoEvidence() {
        PythonRagPipeline pipeline = mock(PythonRagPipeline.class);
        when(pipeline.retrieveAdmitted(any(), any(), any(), anyInt()))
                .thenReturn(RagQueryOutcome.builder()
                        .outcome("dependency_unavailable")
                        .reasonCode("scorer_unavailable")
                        .failedStage("evidence_gate")
                        .evidences(List.of())
                        .build());

        RagQueryFailureException failure = assertThrows(RagQueryFailureException.class,
                () -> new KnowledgeSearchTool(pipeline).execute(
                        KnowledgeSearchInput.builder().query("q").build(),
                        AgentExecutionContext.builder().userId(1L)
                                .accessibleKnowledgeBaseIds(Set.of(1L)).build()));

        assertEquals("dependency_unavailable", failure.getOutcome());
        assertEquals("scorer_unavailable", failure.getReasonCode());
    }

    /** 通过 {@code pipeline.retrieveAdmitted}、{@code ArgumentCaptor.forClass} 驱动该场景，布尔状态符合断言，返回的数据、边界或顺序符合断言。 */
    @Test
    void nullPermissionSet_isPassedAsEmptyCollection_neverNull() {
        PythonRagPipeline pipeline = mock(PythonRagPipeline.class);
        when(pipeline.retrieveAdmitted(any(), any(), any(), anyInt()))
                .thenReturn(outcome("q", "q", List.of(), List.of()));

        new KnowledgeSearchTool(pipeline).execute(
                KnowledgeSearchInput.builder().query("q").topK(5).build(),
                AgentExecutionContext.builder().userId(1L).accessibleKnowledgeBaseIds(null).build());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(pipeline).retrieveAdmitted(captor.capture(), eq("q"), eq(List.of()), eq(5));
        assertNotNull(captor.getValue(), "权限集合不得为 null");
        assertTrue(captor.getValue().isEmpty());
    }
}
