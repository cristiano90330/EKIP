/* 把用户授权和会话上下文与检索、问答结果及公开来源关联。 */

package com.pony.knowledge.service.rag;

import com.pony.knowledge.dto.RAGResponseDTO;
import com.pony.knowledge.dto.RagChatResponseDTO;
import com.pony.knowledge.dto.SourceDTO;
import com.pony.knowledge.exception.ForbiddenException;
import com.pony.knowledge.exception.RagQueryFailureException;
import com.pony.knowledge.observability.RagStageTracer;
import com.pony.knowledge.observability.RagTrace;
import com.pony.knowledge.observability.RagTraceContext;
import com.pony.knowledge.service.KnowledgeBaseAccessService;
import com.pony.knowledge.service.rag.model.RagEvidence;
import com.pony.knowledge.service.rag.model.RagQueryOutcome;
import com.pony.knowledge.service.rag.query.ChatMessage;
import com.pony.knowledge.utils.CurrentUserUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * 问答业务主线：身份与知识库授权、可选会话历史、调用查询、映射获准来源和业务结果。
 * ask 面向当前用户全部可读库；chat 先校验指定库，再按用户、知识库和会话标识读取历史。
 * 成功回答和正常无证据拒答可写入会话，依赖故障或超时提示不作为正常对话追加。
 */
@Service
@RequiredArgsConstructor
public class RagService {

    private final KnowledgeBaseAccessService knowledgeBaseAccessService;

    private final CurrentUserUtil currentUserUtil;

    private final ConversationService conversationService;

    private final RagStageTracer ragStageTracer;

    private final PythonRagPipeline pythonRagPipeline;

    /**
     * 在当前账号可读的知识库中执行普通问答。
     * 上游为 KnowledgeChatController.ask。
     *
     * @param question 用户问题正文
     * @param topK 保留的候选数量上限
     * @return 同步知识问答的答案、获准来源及执行状态，以 RAGResponseDTO 返回
     */
    public RAGResponseDTO ask(String question, int topK) {
        return ask(question, topK, null);
    }

    /**
     * 在当前账号可读的知识库中执行普通问答。
     * 上游为 KnowledgeChatController.ask。
     *
     * @param question 用户问题正文
     * @param topK 保留的候选数量上限
     * @param pipelineEngine 问答管线名称
     * @return 同步知识问答的答案、获准来源及执行状态，以 RAGResponseDTO 返回
     */
    public RAGResponseDTO ask(String question, int topK, String pipelineEngine) {
        return ask(question, topK, pipelineEngine, RagStrategy.DEFAULT);
    }

    /**
     * 在当前账号可读的知识库中执行普通问答。
     * 上游为 KnowledgeChatController.ask。
     * 下游为 RagStageTracer.observeRoot、PythonRagPipeline.prepareTrace。
     *
     * @param question 用户问题正文
     * @param topK 保留的候选数量上限
     * @param pipelineEngine 问答管线名称
     * @param strategy 显式指定或部署默认的检索和证据准入策略
     * @return 同步知识问答的答案、获准来源及执行状态，以 RAGResponseDTO 返回
     */
    public RAGResponseDTO ask(String question, int topK, String pipelineEngine, RagStrategy strategy) {
        return ragStageTracer.observeRoot("ask", () -> {
            pythonRagPipeline.prepareTrace(pipelineEngine, strategy);
            // 授权范围由 Java 依据当前用户解析，Python 只消费范围，不重新判断用户与知识库关系。
            Collection<Long> accessible = knowledgeBaseAccessService.resolveReadableIds(
                    currentUserUtil.getUserId());
            return answer(new ArrayList<>(accessible), question, List.of(), topK, pipelineEngine, strategy);
        });
    }

    /**
     * 使用指定问答会话已获准的范围和历史生成答案。
     * 上游为 RagSessionController.ask。
     * 下游为 RagStageTracer.observeRoot、TurnLease.scoped。
     *
     * @param lease 本次取得的处理或问答回合租约
     * @param question 用户问题正文
     * @param history 按时间顺序提供的对话历史
     * @param topK 保留的候选数量上限
     * @param engine 问答引擎配置值
     * @param strategy 显式指定或部署默认的检索和证据准入策略
     * @return 同步知识问答的答案、获准来源及执行状态，以 RAGResponseDTO 返回
     */
    public RAGResponseDTO askInSession(RagSessionService.TurnLease lease, String question, List<ChatMessage> history,
                                      int topK, String engine, RagStrategy strategy) {
        return ragStageTracer.observeRoot(lease.scoped() ? "chat" : "ask", () -> {
            pythonRagPipeline.prepareTrace(engine, strategy);
            return answer(lease.scope(), question, history, topK, engine, strategy);
        });
    }

    /**
     * 执行问答并把获准证据与成功或拒答结果转换为前端响应。
     * 上游为 RagService.ask、RagService.askInSession。
     * 下游为 RagService.query、RagService.copyPipeline。
     *
     * @param scope 需要本方法按序读取或筛选的 List&lt;Long&gt; 数据
     * @param question 用户问题正文
     * @param history 按时间顺序提供的对话历史
     * @param topK 保留的候选数量上限
     * @param engine 问答引擎配置值
     * @param strategy 显式指定或部署默认的检索和证据准入策略
     * @return 同步知识问答的答案、获准来源及执行状态，以 RAGResponseDTO 返回
     */
    private RAGResponseDTO answer(List<Long> scope, String question, List<ChatMessage> history,
                                  int topK, String engine, RagStrategy strategy) {
        RagQueryOutcome out = query(scope, question, history, topK, engine, strategy);
        RAGResponseDTO response = new RAGResponseDTO();
        response.setEvidenceMode(out.getEvidenceMode());
        copyPipeline(out, engine, response::setPipelineEngine, response::setRetrievalVersion);
        copyState(out, response::setOutcome, response::setReasonCode, response::setFailedStage,
                response::setRequestId, response::setRetrievalStatus, response::setEvidenceDecision,
                response::setDegraded, response::setDegradeReason, response::setProviderUsed);

        if (isNoEvidence(out)) {
            response.setAnswer(NO_EVIDENCE_ANSWER);
            response.setSources(List.of());
            return response;
        }
        ensureFailureHasEvidence(out);
        String answer = out.getAnswer();
        response.setAnswer(answer == null || answer.isBlank() ? fallbackAnswer(out) : answer);
        response.setSources(convertSources(out.getEvidences()));
        return response;
    }

    /**
     * 校验指定知识库与会话，结合历史完成传统同步问答。
     * 上游为 RagController.chat。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param conversationId 传统问答会话的外部标识
     * @param question 用户问题正文
     * @param topK 保留的候选数量上限
     * @return 指定知识库问答的答案、证据、降级与执行信息，以 RagChatResponseDTO 返回
     */
    public RagChatResponseDTO chat(Long knowledgeBaseId, String conversationId, String question, Integer topK) {
        return chat(knowledgeBaseId, conversationId, question, topK, null);
    }

    /**
     * 校验指定知识库与会话，结合历史完成传统同步问答。
     * 上游为 RagController.chat。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param conversationId 传统问答会话的外部标识
     * @param question 用户问题正文
     * @param topK 保留的候选数量上限
     * @param pipelineEngine 问答管线名称
     * @return 指定知识库问答的答案、证据、降级与执行信息，以 RagChatResponseDTO 返回
     */
    public RagChatResponseDTO chat(Long knowledgeBaseId, String conversationId, String question,
                                   Integer topK, String pipelineEngine) {
        return chat(knowledgeBaseId, conversationId, question, topK, pipelineEngine, RagStrategy.DEFAULT);
    }

    /**
     * 校验指定知识库与会话，结合历史完成传统同步问答。
     * 上游为 RagController.chat。
     * 下游为 RagStageTracer.observeRoot、PythonRagPipeline.prepareTrace。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param conversationId 传统问答会话的外部标识
     * @param question 用户问题正文
     * @param topK 保留的候选数量上限
     * @param pipelineEngine 问答管线名称
     * @param strategy 显式指定或部署默认的检索和证据准入策略
     * @return 指定知识库问答的答案、证据、降级与执行信息，以 RagChatResponseDTO 返回
     */
    public RagChatResponseDTO chat(Long knowledgeBaseId, String conversationId, String question,
                                   Integer topK, String pipelineEngine, RagStrategy strategy) {
        return ragStageTracer.observeRoot("chat", () -> {
            pythonRagPipeline.prepareTrace(pipelineEngine, strategy);
            Long userId = currentUserUtil.getUserId();
            if (!knowledgeBaseAccessService.canRead(userId, knowledgeBaseId)) {
                throw new ForbiddenException("无权访问该知识库");
            }

            // 会话历史按用户和知识库隔离，不能只用客户端传入的 conversationId 定位。
            ConversationKey conversationKey = new ConversationKey(userId, knowledgeBaseId, conversationId);
            List<ChatMessage> history = conversationService.getHistory(conversationKey);

            int k = topK == null ? 5 : topK;
            RagQueryOutcome out = query(List.of(knowledgeBaseId), question, history, k, pipelineEngine, strategy);

            if (isNoEvidence(out)) {
                String emptyAnswer = NO_EVIDENCE_ANSWER;
                conversationService.append(conversationKey, question, emptyAnswer);

                RagChatResponseDTO response = new RagChatResponseDTO();
                response.setEvidenceMode(out.getEvidenceMode());
                copyPipeline(out, pipelineEngine, response::setPipelineEngine, response::setRetrievalVersion);
                response.setAnswer(emptyAnswer);
                response.setOriginalQuery(question);
                response.setRewrittenQuery(out.getRewrittenQuery());
                response.setSources(List.of());
                copyState(out, response::setOutcome, response::setReasonCode, response::setFailedStage,
                        response::setRequestId, response::setRetrievalStatus, response::setEvidenceDecision,
                        response::setDegraded, response::setDegradeReason, response::setProviderUsed);

                RagTrace current = RagTraceContext.get();
                if (current != null) {
                    current.setOriginalQuery(question);
                    current.setRewrittenQuery(out.getRewrittenQuery());
                }
                return response;
            }

            ensureFailureHasEvidence(out);

            String answer = out.getAnswer();
            if (answer == null || answer.isBlank()) {
                answer = fallbackAnswer(out);
            }

            // 仅将成功生成的这一轮追加到历史；基础设施失败保留状态供调用方识别。
            if (isSuccessful(out)) {
                conversationService.append(conversationKey, question, answer);
            }

            RagChatResponseDTO response = new RagChatResponseDTO();
            response.setEvidenceMode(out.getEvidenceMode());
            copyPipeline(out, pipelineEngine, response::setPipelineEngine, response::setRetrievalVersion);
            response.setAnswer(answer);
            response.setOriginalQuery(question);
            response.setRewrittenQuery(out.getRewrittenQuery());
            response.setSources(convertSources(out.getEvidences()));
            copyState(out, response::setOutcome, response::setReasonCode, response::setFailedStage,
                    response::setRequestId, response::setRetrievalStatus, response::setEvidenceDecision,
                    response::setDegraded, response::setDegradeReason, response::setProviderUsed);

            RagTrace current = RagTraceContext.get();
            if (current != null) {
                current.setOriginalQuery(question);
                current.setRewrittenQuery(out.getRewrittenQuery());
            }

            return response;
        });
    }

    /**
     * 通过 Python 管线执行用户问答并录入可观测结果。
     * 上游为 RagService.answer、RagService.chat。
     * 下游为 PythonRagPipeline.query。
     *
     * @param scope 需要本方法按序读取或筛选的 List&lt;Long&gt; 数据
     * @param question 用户问题正文
     * @param history 按时间顺序提供的对话历史
     * @param topK 保留的候选数量上限
     * @param engine 问答引擎配置值
     * @param strategy 显式指定或部署默认的检索和证据准入策略
     * @return 服务内部问答结果、候选与证据、执行信息和降级状态，以 RagQueryOutcome 返回
     */
    private RagQueryOutcome query(List<Long> scope, String question, List<ChatMessage> history, int topK,
                                  String engine, RagStrategy strategy) {
        if (strategy != RagStrategy.DEFAULT) {
            return pythonRagPipeline.query(scope, question, history, topK, engine, strategy);
        }
        return engine == null ? pythonRagPipeline.query(scope, question, history, topK)
                : pythonRagPipeline.query(scope, question, history, topK, engine);
    }

    /**
     * 把获准证据转换为前端可显示的引用来源。
     * 上游为 RagService.answer、RagService.chat。
     *
     * @param evidences 通过准入的证据列表
     * @return 与过滤条件匹配的 List&lt;SourceDTO&gt; 数据集合
     */
    private List<SourceDTO> convertSources(List<RagEvidence> evidences) {
        return evidences.stream()
                .map(evidence -> {
                    SourceDTO source = new SourceDTO();
                    source.setDocumentId(evidence.getDocumentId());
                    source.setChunkId(evidence.getChunkId());
                    source.setKnowledgeBaseId(evidence.getKnowledgeBaseId());
                    source.setChunkIndex(evidence.getChunkIndex());
                    source.setContent(evidence.getContent());
                    source.setDistance(evidence.getDistance());
                    source.setHeadingPath(evidence.getHeadingPath());
                    source.setPageNo(evidence.getPageNo());
                    source.setSourceType(evidence.getSourceType());
                    return source;
                })
                .toList();
    }

    private static final String NO_EVIDENCE_ANSWER = "知识库中没有找到足够相关的信息。";

    /**
     * 判断内部执行结果是否属于正常的证据不足终态。
     * 上游为 RagService.answer、RagService.chat。
     * 下游为 RagService.effectiveOutcome、RagQueryOutcome.getEvidences。
     *
     * @param out 上游返回的问答执行结果
     * @return 条件是否成立（判断内部执行结果是否属于正常的证据不足终态）
     */
    private static boolean isNoEvidence(RagQueryOutcome out) {
        String outcome = effectiveOutcome(out);

        if ("no_evidence".equals(outcome)) {
            return true;
        }
        if ("success".equals(outcome) && (out.getEvidences() == null || out.getEvidences().isEmpty())) {
            throw new IllegalStateException("Python returned success without evidence-gate sources");
        }
        return false;
    }

    /**
     * 检查问答结果的结构和故障约束，防止无证据成功被公开。
     * 上游为 RagService.answer、RagService.chat。
     * 下游为 RagService.effectiveOutcome、RagQueryOutcome.getEvidences。
     *
     * @param out 上游返回的问答执行结果
     */
    private static void ensureFailureHasEvidence(RagQueryOutcome out) {
        String outcome = effectiveOutcome(out);
        if (("dependency_unavailable".equals(outcome) || "timeout".equals(outcome))
                && (out.getEvidences() == null || out.getEvidences().isEmpty())) {
            HttpStatus status = "timeout".equals(outcome)
                    ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.SERVICE_UNAVAILABLE;
            throw new RagQueryFailureException(status, outcome,
                    valueOr(out.getReasonCode(), "dependency_unavailable"),
                    valueOr(out.getFailedStage(), "rag.query"), out.getRequestId(),
                    "timeout".equals(outcome) ? "问答处理超时，请稍后重试" : "问答服务暂时不可用，请稍后重试", null);
        }
    }

    /**
     * 为证据不足时选择面向用户的回退提示。
     * 上游为 RagService.answer、RagService.chat。
     * 下游为 RagService.effectiveOutcome。
     *
     * @param out 上游返回的问答执行结果
     * @return 证据不足时用于前端展示的回答文本
     */
    private static String fallbackAnswer(RagQueryOutcome out) {
        return "timeout".equals(effectiveOutcome(out))
                ? "已找到相关资料，但生成答案超时，请稍后重试。"
                : "已找到相关资料，但问答服务暂时不可用，请稍后重试。";
    }

    /**
     * 判断一次 RAG 执行能否作为成功问答处理。
     * 上游为 RagService.chat。
     * 下游为 RagService.effectiveOutcome。
     *
     * @param out 上游返回的问答执行结果
     * @return 条件是否成立（判断一次 RAG 执行能否作为成功问答处理）
     */
    private static boolean isSuccessful(RagQueryOutcome out) {
        return "success".equals(effectiveOutcome(out));
    }

    /**
     * 优先保留明确的执行状态，旧响应缺少状态时由获准证据推断结果。
     * 上游为 RagService.isNoEvidence、RagService.ensureFailureHasEvidence。
     * 下游为 RagQueryOutcome.getOutcome、RagQueryOutcome.getEvidences。
     *
     * @param out 上游返回的问答执行结果
     * @return 明确的结果分类，或由获准证据推断的兼容分类
     */
    private static String effectiveOutcome(RagQueryOutcome out) {
        if (out.getOutcome() != null && !out.getOutcome().isBlank()) {
            return out.getOutcome();
        }
        return out.getEvidences() == null || out.getEvidences().isEmpty()
                ? "no_evidence" : "success";
    }

    /**
     * 字段无有效值时采用调用方指定的默认值。
     * 上游为 RagService.ensureFailureHasEvidence、RagService.copyPipeline。
     *
     * @param value 待校验或转换的值
     * @param fallback 原始值缺失或无效时使用的备选值
     * @return 本次计算或查询得到的 String 结果
     */
    private static String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * 把实际管线、检索版本和证据模式写到指定库问答响应。
     * 上游为 RagService.answer、RagService.chat。
     * 下游为 RagService.valueOr、PythonRagPipeline.getDefaultPipelineEngine。
     *
     * @param out 上游返回的问答执行结果
     * @param requestedEngine 调用方指定的问答引擎
     * @param engine 问答引擎配置值
     * @param retrievalVersion 需要校验或使用的版本
     */
    private void copyPipeline(RagQueryOutcome out, String requestedEngine,
                              java.util.function.Consumer<String> engine,
                              java.util.function.Consumer<String> retrievalVersion) {
        String selected = requestedEngine == null
                ? valueOr(pythonRagPipeline.getDefaultPipelineEngine(), "fixed") : requestedEngine;
        engine.accept(valueOr(out.getPipelineEngine(), selected));
        retrievalVersion.accept(valueOr(out.getRetrievalVersion(), pythonRagPipeline.getRetrievalVersion()));
    }

    /**
     * 把失败、降级、调试原因和请求标识映射给调用方。
     * 上游为 RagService.answer、RagService.chat。
     * 下游为 RagQueryOutcome.isDegraded。
     *
     * @param out 上游返回的问答执行结果
     * @param outcome 查询或操作的业务结果状态
     * @param reasonCode 用于区分失败或降级路径的原因码
     * @param failedStage 故障发生的执行阶段
     * @param requestId 关联客户端请求与日志的请求标识
     * @param retrievalStatus 本次操作所用的 java.util.function.Consumer&lt;String&gt; 数据
     * @param decision 本次操作所用的 java.util.function.Consumer&lt;com.pony.knowledge.service.rag.model.RagEvidenceDecision&gt; 数据
     * @param degraded 本次操作所用的 java.util.function.Consumer&lt;Boolean&gt; 数据
     * @param degradeReason 本次操作所用的 java.util.function.Consumer&lt;String&gt; 数据
     * @param providerUsed 本次操作所用的 java.util.function.Consumer&lt;String&gt; 数据
     */
    private static void copyState(RagQueryOutcome out,
                                  java.util.function.Consumer<String> outcome,
                                  java.util.function.Consumer<String> reasonCode,
                                  java.util.function.Consumer<String> failedStage,
                                  java.util.function.Consumer<String> requestId,
                                  java.util.function.Consumer<String> retrievalStatus,
                                  java.util.function.Consumer<com.pony.knowledge.service.rag.model.RagEvidenceDecision> decision,
                                  java.util.function.Consumer<Boolean> degraded,
                                  java.util.function.Consumer<String> degradeReason,
                                  java.util.function.Consumer<String> providerUsed) {
        outcome.accept(effectiveOutcome(out));
        reasonCode.accept(valueOr(out.getReasonCode(),
                "no_evidence".equals(effectiveOutcome(out)) ? "no_match" : "none"));
        failedStage.accept(out.getFailedStage());
        requestId.accept(out.getRequestId());
        retrievalStatus.accept(out.getRetrievalStatus());
        decision.accept(out.getEvidenceDecision());
        boolean isDegraded = out.isDegraded() || "dependency_unavailable".equals(effectiveOutcome(out))
                || "timeout".equals(effectiveOutcome(out));
        degraded.accept(isDegraded);
        degradeReason.accept(isDegraded ? valueOr(out.getReasonCode(), "dependency_unavailable") : null);
        providerUsed.accept(out.getProviderUsed());
    }
}
