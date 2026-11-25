/* 在 Agent 的可信知识库范围内搜索获准证据，并保留降级原因。 */

package com.pony.knowledge.agent.tool.knowledge;

import com.pony.knowledge.agent.model.AgentExecutionContext;
import com.pony.knowledge.agent.tool.AgentTool;
import com.pony.knowledge.agent.tool.ToolDefinition;
import com.pony.knowledge.agent.tool.ToolSchemaGenerator;
import com.pony.knowledge.agent.tool.input.KnowledgeSearchInput;
import com.pony.knowledge.agent.tool.input.ToolInput;
import com.pony.knowledge.agent.tool.output.KnowledgeSearchOutput;
import com.pony.knowledge.agent.tool.output.ToolResult;
import com.pony.knowledge.service.rag.PythonRagPipeline;
import com.pony.knowledge.service.rag.model.RagQueryOutcome;
import com.pony.knowledge.service.rag.model.RagEvidence;
import com.pony.knowledge.exception.RagQueryFailureException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class KnowledgeSearchTool
        implements AgentTool {

    private final PythonRagPipeline pythonRagPipeline;

    /**
     * 声明使用可信知识库范围的检索工具与可能的模型参数。
     * 上游由 {@code KnowledgeSearchTool.execute} 调用。
     * 下游调用 {@code ToolSchemaGenerator.schemaOf}。
     *
     * @return 公开给模型或页面目录的工具定义
     */
    @Override
    public ToolDefinition definition() {
        return ToolDefinition.builder()
                .name("knowledge_search")
                .description("Search the user's authorized " +
                        "enterprise knowledge base for " +
                        "relevant information.")
                .inputClass(KnowledgeSearchInput.class)
                .parameters(ToolSchemaGenerator.schemaOf(KnowledgeSearchInput.class))
                .build();
    }

    /**
     * 依据当前用户问题和获准范围查询知识库，交付准入片段或明确失败状态。
     * 上游由 {@code ToolExecutor（执行 knowledge_search）} 调用。
     * 下游调用 {@code pythonRagPipeline.retrieveAdmitted}、{@code contractMismatch}、{@code isUnavailable}。
     *
     * @param input 本次操作的结构化输入
     * @param context 可信执行上下文，包含身份、范围和本轮执行状态
     * @return 带载荷或可识别错误状态的工具结果
     */
    @Override
    public ToolResult execute(
            ToolInput input,
            AgentExecutionContext context) {

        KnowledgeSearchInput searchInput =
                (KnowledgeSearchInput) input;

        // 安全边界：身份/权限只来自 AgentExecutionContext，LLM 的 ToolInput 无法控制。
        // null 必须收敛成空集合：本方法跑在池线程上，ThreadLocal 的 SecurityContext 不可用。
        List<Long> accessibleKnowledgeBaseIds =
                context.getAccessibleKnowledgeBaseIds() == null
                        ? List.of()
                        : new ArrayList<>(context.getAccessibleKnowledgeBaseIds());

        // 一次调用 Python /rag/query（generate=false, admitEvidence=true）：检索、证据准入和后处理
        // 照跑但不生成；只把获准证据交给模型，原始排序候选不进入工具输出。
        // topK 允许省略（schema 无默认值）→ 回退 5，与 RagService.chat 的默认口径一致。
        int topK = searchInput.getTopK() == null ? 5 : searchInput.getTopK();
        String question = context.getCurrentQuery();
        RagQueryOutcome out = question == null || question.isBlank()
                ? pythonRagPipeline.retrieveAdmitted(
                        accessibleKnowledgeBaseIds, searchInput.getQuery(), List.of(), topK)
                : pythonRagPipeline.retrieveAdmitted(
                        accessibleKnowledgeBaseIds, question, context.getQuestionHistory(), topK,
                        null, searchInput.getQuery());
        if (out == null) {
            throw contractMismatch(null);
        }

        String outcome = out.getOutcome();
        List<RagEvidence> evidences =
                out.getEvidences() == null ? List.of() : out.getEvidences();
        if (isUnavailable(outcome)) {
            throw unavailableFailure(out);
        }
        if (!("success".equals(outcome) && !evidences.isEmpty()
                || "no_evidence".equals(outcome) && evidences.isEmpty())) {
            throw contractMismatch(out);
        }

        KnowledgeSearchOutput output = KnowledgeSearchOutput.builder()
                .originalQuery(out.getOriginalQuery())
                .rewrittenQuery(out.getRewrittenQuery())
                .evidences(evidences)
                .outcome(outcome)
                .reasonCode(out.getReasonCode())
                .build();

        return ToolResult.builder()
                .toolName(definition().getName())
                .success(true)
                .data(output)
                .outcome(outcome)
                .reasonCode(out.getReasonCode())
                .build();
    }

    /**
     * 判断当前检索结果是否属于依赖失败或超时。
     * 上游由 {@code KnowledgeSearchTool.execute} 调用。
     *
     * @param outcome 问答或检索的业务终态
     * @return 条件成立时为 true，其他状态按 false 处理
     */
    private static boolean isUnavailable(String outcome) {
        return "dependency_unavailable".equals(outcome) || "timeout".equals(outcome);
    }

    /**
     * 将管线故障终态和诊断信息映射为调用方可识别的检索失败。
     * 上游由 {@code KnowledgeSearchTool.execute} 调用。
     *
     * @param out 本次调用传入的 {@code RagQueryOutcome}
     * @return 调用方使用的 {@code RagQueryFailureException}
     */
    private static RagQueryFailureException unavailableFailure(RagQueryOutcome out) {
        boolean timeout = "timeout".equals(out.getOutcome());
        return new RagQueryFailureException(
                timeout ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.SERVICE_UNAVAILABLE,
                out.getOutcome(),
                out.getReasonCode() == null ? out.getOutcome() : out.getReasonCode(),
                out.getFailedStage() == null ? "rag.query" : out.getFailedStage(),
                out.getRequestId(),
                timeout ? "知识检索超时，请稍后重试" : "知识检索服务暂时不可用，请稍后重试",
                null);
    }

    /**
     * 为缺失或不一致的检索管线结果生成明确的契约故障。
     * 上游由 {@code KnowledgeSearchTool.execute} 调用。
     *
     * @param out 本次调用传入的 {@code RagQueryOutcome}
     * @return 调用方使用的 {@code RagQueryFailureException}
     */
    private static RagQueryFailureException contractMismatch(RagQueryOutcome out) {
        return new RagQueryFailureException(HttpStatus.SERVICE_UNAVAILABLE,
                "dependency_unavailable", "ai_contract_mismatch", "ai.response_contract",
                out == null ? null : out.getRequestId(),
                "知识检索结果不符合契约", null);
    }
}
