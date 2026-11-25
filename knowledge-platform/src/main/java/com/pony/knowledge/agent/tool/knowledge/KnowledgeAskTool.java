/* 在已授权知识库内运行证据问答，并映射可交给 Agent 的答案与诊断。 */

package com.pony.knowledge.agent.tool.knowledge;

import com.pony.knowledge.agent.model.AgentExecutionContext;
import com.pony.knowledge.agent.tool.AgentTool;
import com.pony.knowledge.agent.tool.ToolDefinition;
import com.pony.knowledge.agent.tool.ToolSchemaGenerator;
import com.pony.knowledge.agent.tool.input.KnowledgeAskInput;
import com.pony.knowledge.agent.tool.input.ToolInput;
import com.pony.knowledge.agent.tool.output.KnowledgeAskOutput;
import com.pony.knowledge.agent.tool.output.ToolResult;
import com.pony.knowledge.service.rag.PythonRagPipeline;
import com.pony.knowledge.service.rag.model.RagEvidence;
import com.pony.knowledge.service.rag.model.RagQueryOutcome;
import com.pony.knowledge.exception.RagQueryFailureException;
import org.springframework.http.HttpStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * knowledge_ask：检索 + LLM 生成接地答案。与 knowledge_search 共用同一套 AgentTool 抽象，
 * 因此一处定义即同时暴露给 Agent Runtime 与 MCP Server。
 *
 * <p>一次调用 Python `/rag/query`（{@link PythonRagPipeline}），kbIds 由 AgentExecutionContext
 * 显式传入——本工具跑在 agentToolExecutor 池线程上，ThreadLocal 不传播，身份一律显式来自 Context。</p>
 */
@Component
@RequiredArgsConstructor
public class KnowledgeAskTool
        implements AgentTool {

    /** 与 RagService.ask 的空证据分支一致：无证据不生成，避免 LLM 编造。 */
    static final String NO_EVIDENCE_ANSWER = "知识库中没有找到足够相关的信息。";

    private final PythonRagPipeline pythonRagPipeline;

    /**
     * 登记知识库问答工具的用途、风险等级和可由模型填写的参数。
     * 上游由 {@code KnowledgeAskTool.success} 调用。
     * 下游调用 {@code ToolSchemaGenerator.schemaOf}。
     *
     * @return 公开给模型或页面目录的工具定义
     */
    @Override
    public ToolDefinition definition() {
        return ToolDefinition.builder()
                .name("knowledge_ask")
                .description("Ask the enterprise knowledge base a question and get a grounded "
                        + "answer with source references. Slower than knowledge_search because "
                        + "it calls an LLM.")
                .inputClass(KnowledgeAskInput.class)
                .parameters(ToolSchemaGenerator.schemaOf(KnowledgeAskInput.class))
                .build();
    }

    /**
     * 在可信上下文确定的库范围内执行问答，将获准证据、答案和错误状态映射为工具结果。
     * 上游由 {@code ToolExecutor（执行 knowledge_ask）} 调用。
     * 下游调用 {@code pythonRagPipeline.query}、{@code contractMismatch}、{@code success}。
     *
     * @param input 本次操作的结构化输入
     * @param context 可信执行上下文，包含身份、范围和本轮执行状态
     * @return 带载荷或可识别错误状态的工具结果
     */
    @Override
    public ToolResult execute(
            ToolInput input,
            AgentExecutionContext context) {

        KnowledgeAskInput askInput =
                (KnowledgeAskInput) input;

        // 规则一：权限集合恒为非 null 实例；空集合 → 检索层返回空结果（default-deny）
        List<Long> accessibleKnowledgeBaseIds =
                context.getAccessibleKnowledgeBaseIds() == null
                        ? List.of()
                        : new ArrayList<>(context.getAccessibleKnowledgeBaseIds());

        // topK 允许省略（schema 无默认值）→ 回退 5，与 RagService.chat 的默认口径一致。
        int topK = askInput.getTopK() == null ? 5 : askInput.getTopK();
        RagQueryOutcome out = pythonRagPipeline.query(
                accessibleKnowledgeBaseIds, askInput.getQuestion(), List.of(), topK);
        if (out == null) {
            throw contractMismatch(null);
        }

        String outcome = out.getOutcome();
        List<RagEvidence> evidences =
                out.getEvidences() == null ? List.of() : out.getEvidences();
        if ("no_evidence".equals(outcome)) {
            if (!evidences.isEmpty()) {
                throw contractMismatch(out);
            }
            return success(KnowledgeAskOutput.builder()
                    .answer(NO_EVIDENCE_ANSWER)
                    .rewrittenQuery(out.getRewrittenQuery())
                    .tokenCost(0)
                    .outcome(out.getOutcome())
                    .reasonCode(out.getReasonCode())
                    .failedStage(out.getFailedStage())
                    .requestId(out.getRequestId())
                    .sources(List.of())
                    .build());
        }
        if (isUnavailableOutcome(outcome) && evidences.isEmpty()) {
            throw unavailableFailure(out);
        }
        if (!("success".equals(outcome) && !evidences.isEmpty()
                || isUnavailableOutcome(outcome) && !evidences.isEmpty())) {
            throw contractMismatch(out);
        }

        String answer = out.getAnswer();
        if (answer == null || answer.isBlank()) {
            // 与 RagService 的兜底文案一致：degraded=true 时 Python 返回空串
            answer = "服务繁忙，请稍后重试";
        }

        return success(KnowledgeAskOutput.builder()
                .answer(answer)
                .rewrittenQuery(out.getRewrittenQuery())
                .tokenCost(out.getTokenCost() == null ? 0 : out.getTokenCost())
                .outcome(out.getOutcome())
                .reasonCode(out.getReasonCode())
                .failedStage(out.getFailedStage())
                .requestId(out.getRequestId())
                .sources(toSources(evidences))
                .build(), !isUnavailableOutcome(out.getOutcome()));
    }

    /**
     * 把获准证据转换成工具可交付的公开来源元数据。
     * 上游由 {@code KnowledgeAskTool.execute} 调用。
     *
     * @param evidences 本次调用传入的 {@code List<RagEvidence>}
     * @return 调用方使用的 {@code List<KnowledgeAskOutput.Source>}
     */
    private List<KnowledgeAskOutput.Source> toSources(
            List<RagEvidence> evidences) {

        return evidences.stream()
                .map(evidence -> KnowledgeAskOutput.Source.builder()
                        .documentId(evidence.getDocumentId())
                        .chunkId(evidence.getChunkId())
                        .knowledgeBaseId(evidence.getKnowledgeBaseId())
                        .chunkIndex(evidence.getChunkIndex())
                        .content(evidence.getContent())
                        .headingPath(evidence.getHeadingPath())
                        .pageNo(evidence.getPageNo())
                        .score(evidence.getScore())
                        .distance(evidence.getDistance())
                        .sourceType(evidence.getSourceType())
                        .build())
                .toList();
    }

    /**
     * 封装可以返给运行时的知识问答内容及诊断状态。
     * 下游调用 {@code success}。
     *
     * @param output 本次调用传入的 {@code KnowledgeAskOutput}
     * @return 带载荷或可识别错误状态的工具结果
     */
    private ToolResult success(KnowledgeAskOutput output) {
        return success(output, true);
    }

    /**
     * 封装可以返给运行时的知识问答内容及诊断状态。
     * 下游调用 {@code definition}。
     *
     * @param output 本次调用传入的 {@code KnowledgeAskOutput}
     * @param success 本次调用传入的 {@code boolean}
     * @return 带载荷或可识别错误状态的工具结果
     */
    private ToolResult success(KnowledgeAskOutput output, boolean success) {
        return ToolResult.builder()
                .toolName(definition().getName())
                .success(success)
                .data(output)
                .outcome(output.getOutcome())
                .reasonCode(output.getReasonCode())
                .build();
    }

    /**
     * 判断管线终态是否表示依赖不可用或超时。
     * 上游由 {@code KnowledgeAskTool.execute} 调用。
     *
     * @param outcome 问答或检索的业务终态
     * @return 条件成立时为 true，其他状态按 false 处理
     */
    private static boolean isUnavailableOutcome(String outcome) {
        return "dependency_unavailable".equals(outcome) || "timeout".equals(outcome);
    }

    /**
     * 将管线故障终态和诊断信息映射为调用方可识别的问答失败。
     * 上游由 {@code KnowledgeAskTool.execute} 调用。
     *
     * @param out 本次调用传入的 {@code RagQueryOutcome}
     * @return 调用方使用的 {@code RagQueryFailureException}
     */
    private static RagQueryFailureException unavailableFailure(RagQueryOutcome out) {
        boolean timeout = "timeout".equals(out.getOutcome());
        return new RagQueryFailureException(timeout ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.SERVICE_UNAVAILABLE,
                out.getOutcome(), out.getReasonCode() == null ? out.getOutcome() : out.getReasonCode(),
                out.getFailedStage() == null ? "rag.query" : out.getFailedStage(), out.getRequestId(),
                timeout ? "问答处理超时，请稍后重试" : "问答服务暂时不可用，请稍后重试", null);
    }

    /**
     * 为缺失或不一致的问答管线结果生成明确的契约故障。
     * 上游由 {@code KnowledgeAskTool.execute} 调用。
     *
     * @param out 本次调用传入的 {@code RagQueryOutcome}
     * @return 调用方使用的 {@code RagQueryFailureException}
     */
    private static RagQueryFailureException contractMismatch(RagQueryOutcome out) {
        return new RagQueryFailureException(HttpStatus.SERVICE_UNAVAILABLE,
                "dependency_unavailable", "ai_contract_mismatch", "ai.response_contract",
                out == null ? null : out.getRequestId(),
                "问答结果不符合契约", null);
    }
}
