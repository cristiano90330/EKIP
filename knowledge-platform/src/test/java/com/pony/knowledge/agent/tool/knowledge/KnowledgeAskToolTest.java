/* 验证问答工具的可信权限、证据映射、缺证和依赖失败输出。 */

package com.pony.knowledge.agent.tool.knowledge;

import com.pony.knowledge.agent.model.AgentExecutionContext;
import com.pony.knowledge.agent.tool.input.KnowledgeAskInput;
import com.pony.knowledge.agent.tool.output.KnowledgeAskOutput;
import com.pony.knowledge.agent.tool.output.ToolResult;
import com.pony.knowledge.service.rag.PythonRagPipeline;
import com.pony.knowledge.service.rag.model.RagEvidence;
import com.pony.knowledge.service.rag.model.RagQueryOutcome;
import com.pony.knowledge.exception.RagQueryFailureException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * knowledge_ask 的关键约束：身份只来自 AgentExecutionContext、权限集合永不为 null、
 * 无证据不生成（返回固定文案）。
 */
class KnowledgeAskToolTest {

    private KnowledgeAskTool tool;

    private PythonRagPipeline pipeline;

    /**
     * 更新当前对象的相应属性。
     * @param pipeline 本次调用传入的 {@code PythonRagPipeline}
     */
    private void setUp(PythonRagPipeline pipeline) {
        this.pipeline = pipeline;
        this.tool = new KnowledgeAskTool(pipeline);
    }

    /** 在该类型内部准备后续运行所需的数据。 */
    private RagEvidence evidence() {
        return RagEvidence.builder()
                .documentId(10L)
                .chunkId(30L)
                .knowledgeBaseId(1L)
                .chunkIndex(3)
                .content("连接池上限设为 20")
                .headingPath("部署 / 参数")
                .pageNo(7)
                .score(0.82)
                .build();
    }

    /**
     * 提供带准入证据和诊断的问答管线结果。
     *
     * @param evidences 本次调用传入的 {@code List<RagEvidence>}
     * @param answer 本次调用传入的 {@code String}
     * @param tokenCost 本次调用传入的 {@code Integer}
     * @return 带准入证据和诊断的问答管线结果
     */
    private RagQueryOutcome outcome(List<RagEvidence> evidences, String answer, Integer tokenCost) {
        return RagQueryOutcome.builder()
                .originalQuery("连接池怎么配")
                .rewrittenQuery("Redis 连接池 上限 配置")
                .answer(answer)
                .tokenCost(tokenCost)
                .evidences(evidences)
                .outcome(evidences == null || evidences.isEmpty() ? "no_evidence" : "success")
                .build();
    }

    /**
     * 在该类型内部准备后续运行所需的数据。
     * @param ids 本次调用传入的 {@code Set<Long>}
     * @return 调用方使用的 {@code AgentExecutionContext.AgentExecutionContextBuilder}
     */
    private AgentExecutionContext.AgentExecutionContextBuilder contextWith(Set<Long> ids) {
        return AgentExecutionContext.builder()
                .userId(42L)
                .sessionId("s-1")
                .accessibleKnowledgeBaseIds(ids);
    }

    /** 读取知识问答工具声明，确认模型参数只有业务输入，身份和范围由服务端传递。 */
    @Test
    void definition_exposesKnowledgeAsk_withNoIdentityFieldsInSchema() {
        setUp(mock(PythonRagPipeline.class));
        assertEquals("knowledge_ask", tool.definition().getName());
        assertEquals(KnowledgeAskInput.class, tool.definition().getInputClass());

        Map<String, Object> schema = tool.definition().getParameters();
        assertEquals("object", schema.get("type"));
        assertEquals(Set.of("question", "topK"),
                ((Map<?, ?>) schema.get("properties")).keySet());
        // 身份/权限字段绝不能出现在 LLM 可控的 schema 里
        assertFalse(((Map<?, ?>) schema.get("properties")).containsKey("userId"));
        assertFalse(((Map<?, ?>) schema.get("properties")).containsKey("accessibleKnowledgeBaseIds"));
    }

    /** 通过 {@code tool.execute} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void retrievalRequest_carriesResolvedPermissionSet_fromContext() {
        PythonRagPipeline pipeline = mock(PythonRagPipeline.class);
        when(pipeline.query(any(), any(), any(), anyInt()))
                .thenReturn(outcome(List.of(evidence()), "上限是 20。", 120));
        setUp(pipeline);

        tool.execute(KnowledgeAskInput.builder().question("连接池怎么配").topK(5).build(),
                contextWith(Set.of(1L, 2L)).build());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(pipeline).query(captor.capture(), eq("连接池怎么配"), eq(List.of()), eq(5));

        // 3.1 P1-14：userId/sessionId 已从检索请求删除——身份只经可访问集合透传（default-deny）
        assertNotNull(captor.getValue());
        assertEquals(Set.of(1L, 2L), Set.copyOf(captor.getValue()));
    }

    /** 通过 {@code tool.execute} 驱动用例，布尔状态符合断言，返回的数据、边界或顺序符合断言。 */
    @Test
    void nullPermissionSet_becomesEmptyCollection_neverNull() {
        PythonRagPipeline pipeline = mock(PythonRagPipeline.class);
        when(pipeline.query(any(), any(), any(), anyInt()))
                .thenReturn(outcome(List.of(), null, null));
        setUp(pipeline);

        tool.execute(KnowledgeAskInput.builder().question("连接池怎么配").build(),
                contextWith(null).build());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(pipeline).query(captor.capture(), eq("连接池怎么配"), eq(List.of()), anyInt());

        // 规则一：null 会让下游回落到 CurrentUserUtil（池线程上必 NPE）
        assertNotNull(captor.getValue(), "权限集合不得为 null");
        assertTrue(captor.getValue().isEmpty());
    }

    /** 通过 {@code tool.execute} 驱动用例，布尔状态符合断言，返回的数据、边界或顺序符合断言。 */
    @Test
    void emptyEvidence_returnsNoEvidenceAnswerWithoutSources() {
        PythonRagPipeline pipeline = mock(PythonRagPipeline.class);
        RagQueryOutcome noEvidence = outcome(List.of(), null, null);
        noEvidence.setReasonCode("below_threshold");
        when(pipeline.query(any(), any(), any(), anyInt()))
                .thenReturn(noEvidence);
        setUp(pipeline);

        ToolResult result = tool.execute(
                KnowledgeAskInput.builder().question("无关问题").build(),
                contextWith(Set.of(1L)).build());

        assertTrue(result.isSuccess());
        assertEquals("no_evidence", result.getOutcome());
        assertEquals("below_threshold", result.getReasonCode());
        KnowledgeAskOutput output = (KnowledgeAskOutput) result.getData();
        assertEquals("知识库中没有找到足够相关的信息。", output.getAnswer());
        assertTrue(output.getSources().isEmpty());
        assertEquals("Redis 连接池 上限 配置", output.getRewrittenQuery());
        assertEquals(0, output.getTokenCost());
    }

    /** 通过 {@code tool.execute} 驱动用例，抛出 {@code RagQueryFailureException}，实际结果与固定预期一致。 */
    @Test
    void missingOutcome_failsClosedInsteadOfInferringNoEvidence() {
        PythonRagPipeline pipeline = mock(PythonRagPipeline.class);
        when(pipeline.query(any(), any(), any(), anyInt()))
                .thenReturn(RagQueryOutcome.builder().evidences(List.of()).build());
        setUp(pipeline);

        RagQueryFailureException failure = org.junit.jupiter.api.Assertions.assertThrows(
                RagQueryFailureException.class,
                () -> tool.execute(KnowledgeAskInput.builder().question("问题").build(),
                        contextWith(Set.of(1L)).build()));

        assertEquals("dependency_unavailable", failure.getOutcome());
        assertEquals("ai_contract_mismatch", failure.getReasonCode());
    }

    /** 通过 {@code tool.execute} 驱动用例，布尔状态符合断言，实际结果与固定预期一致。 */
    @Test
    void withEvidence_returnsAnswerAndSources() {
        PythonRagPipeline pipeline = mock(PythonRagPipeline.class);
        when(pipeline.query(any(), any(), any(), anyInt()))
                .thenReturn(outcome(List.of(evidence()), "连接池上限设为 20。", 88));
        setUp(pipeline);

        ToolResult result = tool.execute(
                KnowledgeAskInput.builder().question("连接池怎么配").build(),
                contextWith(Set.of(1L)).build());

        assertTrue(result.isSuccess());
        KnowledgeAskOutput output = (KnowledgeAskOutput) result.getData();
        assertEquals("连接池上限设为 20。", output.getAnswer());
        assertEquals(88, output.getTokenCost());
        KnowledgeAskOutput.Source source = output.getSources().get(0);
        assertEquals(10L, source.getDocumentId());
        assertEquals(30L, source.getChunkId());
        assertEquals(1L, source.getKnowledgeBaseId());
        assertEquals(3, source.getChunkIndex());
        assertEquals("部署 / 参数", source.getHeadingPath());
        assertEquals(7, source.getPageNo());
        assertEquals(0.82, source.getScore());
    }

    /** 通过 {@code tool.execute} 驱动用例，抛出 {@code RagQueryFailureException}，实际结果与固定预期一致。 */
    @Test
    void dependencyFailureWithoutQualifiedEvidenceDoesNotBecomeNoEvidenceSuccess() {
        PythonRagPipeline pipeline = mock(PythonRagPipeline.class);
        when(pipeline.query(any(), any(), any(), anyInt())).thenReturn(RagQueryOutcome.builder()
                .outcome("dependency_unavailable")
                .reasonCode("scorer_unavailable")
                .failedStage("evidence_gate")
                .evidences(List.of())
                .build());
        setUp(pipeline);

        RagQueryFailureException failure = org.junit.jupiter.api.Assertions.assertThrows(
                RagQueryFailureException.class,
                () -> tool.execute(KnowledgeAskInput.builder().question("问题").build(),
                        contextWith(Set.of(1L)).build()));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failure.getStatus());
        assertEquals("scorer_unavailable", failure.getReasonCode());
    }

    /** 通过 {@code tool.execute} 驱动用例，布尔状态符合断言，实际结果与固定预期一致。 */
    @Test
    void generationFailureWithQualifiedEvidencePreservesSourcesAndDiagnosticOutcome() {
        PythonRagPipeline pipeline = mock(PythonRagPipeline.class);
        when(pipeline.query(any(), any(), any(), anyInt())).thenReturn(RagQueryOutcome.builder()
                .answer("")
                .outcome("dependency_unavailable")
                .reasonCode("generation_degraded")
                .failedStage("generate")
                .requestId("req-ask-failed")
                .evidences(List.of(evidence()))
                .build());
        setUp(pipeline);

        ToolResult result = tool.execute(KnowledgeAskInput.builder().question("连接池怎么配").build(),
                contextWith(Set.of(1L)).build());

        assertFalse(result.isSuccess());
        KnowledgeAskOutput output = (KnowledgeAskOutput) result.getData();
        assertEquals("dependency_unavailable", output.getOutcome());
        assertEquals("generation_degraded", output.getReasonCode());
        assertEquals("generate", output.getFailedStage());
        assertEquals("req-ask-failed", output.getRequestId());
        assertEquals(1, output.getSources().size());
    }
}
