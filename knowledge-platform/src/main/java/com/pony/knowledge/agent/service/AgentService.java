/* 从认证信息装配 Agent 回合，管理会话互斥、平台运行时调用和结果提交。 */

package com.pony.knowledge.agent.service;

import com.pony.knowledge.agent.context.ContextBudgetManager;
import com.pony.knowledge.agent.context.ContextBuildRequest;
import com.pony.knowledge.agent.context.ContextBuildResult;
import com.pony.knowledge.agent.memory.summary.SummaryCoordinator;
import com.pony.knowledge.agent.memory.longterm.MemoryPreferenceExtractor;
import com.pony.knowledge.agent.model.AgentExecutionContext;
import com.pony.knowledge.agent.model.AgentMessage;
import com.pony.knowledge.agent.model.AgentRequest;
import com.pony.knowledge.agent.model.AgentResponse;
import com.pony.knowledge.agent.model.StopReason;
import com.pony.knowledge.agent.model.ToolStep;
import com.pony.knowledge.agent.model.ToolSteps;
import com.pony.knowledge.agent.observability.AgentTracer;
import com.pony.knowledge.agent.profile.AgentProfileService;
import com.pony.knowledge.agent.profile.ResolvedAgentProfile;
import com.pony.knowledge.agent.runtime.AgentRuntime;
import com.pony.knowledge.agent.runtime.DefaultAgentRuntime;
import com.pony.knowledge.agent.stream.AgentEventSink;
import com.pony.knowledge.agent.stream.AgentEvent;
import com.pony.knowledge.agent.session.AgentSessionService;
import com.pony.knowledge.agent.session.AgentPolicyFingerprint;
import com.pony.knowledge.agent.tool.approval.MemorySaveApproval;
import com.pony.knowledge.agent.tool.approval.MemorySaveApprovalService;
import com.pony.knowledge.modelmanagement.ModelExecutionContext;
import com.pony.knowledge.modelmanagement.ModelRegistryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import com.pony.knowledge.agent.stream.SseAgentEventSink;
import com.pony.knowledge.service.KnowledgeBaseAccessService;
import com.pony.knowledge.utils.CurrentUserUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Set;

/**
 * 同步和 SSE 请求共用 {@link #runTurn}，可信用户、当前可读库和模型配置在获取会话回合槽前解析。
 * SSE 在持久化前缓冲终态；已确认回合经 {@link AgentSessionService#complete} 写入用户问题、
 * 答案和有界工具步骤。取消会直接释放回合槽。
 */
@Service
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class AgentService {

    private final CurrentUserUtil currentUserUtil;

    private final KnowledgeBaseAccessService knowledgeBaseAccessService;

    private final AgentProfileService agentProfileService;

    private final AgentRuntime agentRuntime;

    private final SummaryCoordinator summaryCoordinator;

    private final ContextBudgetManager contextBudgetManager;

    /** 回合级观测：建业务 Trace 与 Micrometer span、计时、请求计数，并在结束时落 TraceStore。 */
    private final AgentTracer agentTracer;

    private final AgentSessionService sessions;

    private final ModelRegistryService modelRegistry;

    private final MemorySaveApprovalService approvals;

    @Value("${agent.memory.long-term.enabled:true}")
    private boolean longTermMemoryEnabled = true;

    /**
     * 通过空事件接收方执行问答，向同步接口返回完整 AgentResponse。
     * 上游由 {@code AgentController.chat} 调用。
     * 下游调用 {@code runTurn}。
     *
     * @param request 包含服务端会话 ID、当前问题及可选 Agent 配置 ID 的问答请求
     * @return 带答案、工具轨迹、来源和停止原因的回合结果
     */
    public AgentResponse chat(AgentRequest request) {
        return runTurn(request, AgentEventSink.NOOP);
    }

    /**
     * 通过 SSE 接收方执行问答，向控制器返回已确认终态，传输或运行异常交回控制器处理。
     * 上游由 {@code AgentController.chatStream} 调用。
     * 下游调用 {@code runTurn}。
     *
     * @param request 包含服务端会话 ID、当前问题及可选 Agent 配置 ID 的问答请求
     * @param sink 本轮可选事件接收方
     * @return 带答案、工具轨迹、来源和停止原因的回合结果
     */
    public AgentResponse chatStream(AgentRequest request, SseAgentEventSink sink) {
        return runTurn(request, sink);
    }

    /**
     * 根据认证身份解析可读知识库、配置与模型，在模型可用后获取互斥回合槽，并在退出时释放。
     * 上游由 {@code AgentService.chat}、{@code AgentService.chatStream} 调用。
     * 下游调用 {@code AgentProfileService.resolveForExecution}、{@code ModelRegistryService.agentExecution}、{@code AgentSessionService.beginExecution}。
     *
     * @param request 包含服务端会话 ID、当前问题及可选 Agent 配置 ID 的问答请求
     * @param sink 本轮可选事件接收方
     * @return 带答案、工具轨迹、来源和停止原因的回合结果
     */
    private AgentResponse runTurn(AgentRequest request, AgentEventSink sink) {
        Long userId = currentUserUtil.getUserId();
        Set<Long> accessibleKnowledgeBaseIds = knowledgeBaseAccessService.resolveReadableIds(userId);

        // 自建 Agent 在当前可读集上重新解析配置；默认 Agent 的策略在会话阶段装配。
        ResolvedAgentProfile profile = request.getAgentId() == null
                ? null
                : agentProfileService.resolveForExecution(
                        userId, request.getAgentId(), accessibleKnowledgeBaseIds);

        // 先预解析实际模型身份与运行快照，续聊才可比较本轮模型和会话绑定。
        String chatModelId = profile == null ? null : profile.chatModelId();
        String modelIdentity = modelRegistry.agentIdentity(userId, chatModelId);

        // 将模型凭证解析提前到认领之前，配置故障可直接退出而无需释放回合槽。
        var execution = modelRegistry.agentExecution(userId, chatModelId);
        AgentSessionService.ExecutionTurn turn = sessions.beginExecution(userId, request.getSessionId(), request.getAgentId(),
                answerPolicy -> AgentPolicyFingerprint.bindModel(AgentPolicyFingerprint.resolve(
                        userId, profile, accessibleKnowledgeBaseIds, longTermMemoryEnabled, answerPolicy), modelIdentity));
        AgentSessionService.TurnHandle sessionTurn = turn.handle();

        // 用独立终态缓冲将 SSE 中间事件与已确认结果的交付分开。
        BufferedTerminalSink bufferedSink = new BufferedTerminalSink(sink);
        AgentResponse response;
        try {
            response = executeTurn(request.getQuestion(), userId, turn.policy(), sessionTurn, execution, bufferedSink);
        } finally {
            sessions.end(sessionTurn);
        }
        if (response.getMemoryApproval() != null) {
            bufferedSink.flush(response.getMemoryApproval());
        }

        return response;
    }

    /**
     * 用本轮授权和模型快照构建上下文，运行 Agent，按完整终态提交历史、摘要任务与待确认记忆。
     * 上游由 {@code AgentService.runTurn} 调用。
     * 下游调用 {@code ContextBudgetManager.build}、{@code AgentService.runLoop}、{@code AgentSessionService.complete}。
     *
     * @param question 本轮用户提问
     * @param userId 当前用户 ID
     * @param policy 本轮使用的授权、回答与记忆策略
     * @param sessionTurn 本次调用传入的 {@code AgentSessionService.TurnHandle}
     * @param execution 登记用途所解析出的模型执行快照
     * @param bufferedSink 本次调用传入的 {@code BufferedTerminalSink}
     * @return 带答案、工具轨迹、来源和停止原因的回合结果
     */
    private AgentResponse executeTurn(String question, Long userId, AgentPolicyFingerprint.Policy policy,
                                      AgentSessionService.TurnHandle sessionTurn,
                                      java.util.Map<String, Object> execution, BufferedTerminalSink bufferedSink) {
        String sessionId = sessionTurn.session().memoryId();

        // 把当前授权放入同一份执行上下文，工具只接收已解析范围和策略。
        AgentExecutionContext context = AgentExecutionContext.builder()
                .userId(userId)
                .accessibleKnowledgeBaseIds(policy.knowledgeBaseIds())
                .allowedToolNames(policy.allowedTools())
                .sessionId(sessionId)
                .systemPrompt(policy.systemPrompt())
                .answerPolicy(policy.answerPolicy())
                .memoryReadEnabled(policy.memoryReadEnabled())
                .memoryWriteEnabled(policy.memoryWriteEnabled())
                .currentQuery(question)
                .build();

        // 客户端断连 = 取消本回合：这是 cancel() 唯一的真实调用方（同步接口无人取消）。
        if (bufferedSink.delegate instanceof SseAgentEventSink sseSink) {
            sseSink.onDisconnect(context::cancel);
        }

        // 整个回合包在业务 Trace 里：traceId 串起 Agent → Tool → RAG → Memory，
        // 结束时由 AgentTracer 写入 TraceStore，/observability/trace 可查。
        // 同步与流式两个入口都经过这里，因此两条路径的观测口径一致。
        String traceQuestion = context.requestedMemoryContent() == null ? question : "长期记忆保存请求";
        try (ModelExecutionContext selected = ModelExecutionContext.open(execution, userId)) {
            return agentTracer.observeRoot("agent.chat", traceQuestion, () -> {
                context.setTraceId(agentTracer.currentTraceId());

                // 装配上下文（Summary + 最近窗口 = 上一轮消息 + 当前问题）。此刻本轮 user 尚未落内存，
                // 窗口里只有之前轮次，避免当前问题重复出现在窗口与 currentQuery 两处。
                ContextBuildResult built = contextBudgetManager.build(ContextBuildRequest.builder()
                        .userId(userId)
                        .memoryReadEnabled(policy.memoryReadEnabled())
                        .systemPrompt(policy.systemPrompt())
                        .sessionId(sessionId)
                        .currentQuery(question)
                        .build());
                if (built.isTrimmed() || built.getOverBudgetTokens() > 0) {
                    bufferedSink.emit(com.pony.knowledge.agent.stream.AgentEvent.of("round", java.util.Map.of(
                            "round", 0, "phase", "context_ready", "inputTokensBefore", built.getTokensBefore(),
                            "inputTokensAfter", built.getTokensUsed(), "inputBudget", contextBudgetManager.budget().allowedTokens())));
                }

                // 将历史问答和当前提问拆开传给证据审查，避免重复解释当前查询。
                List<AgentMessage> priorMessages = new java.util.ArrayList<>(built.getMessages());
                if (!priorMessages.isEmpty()) {
                    AgentMessage last = priorMessages.get(priorMessages.size() - 1);
                    if ("user".equals(last.getRole()) && question.equals(last.getContent())) {
                        priorMessages.remove(priorMessages.size() - 1);
                    }
                }
                context.setQuestionHistory(priorMessages.stream()
                        .filter(message -> "user".equals(message.getRole()) || "assistant".equals(message.getRole()))
                        .map(message -> {
                            var history = new com.pony.knowledge.service.rag.query.ChatMessage();
                            history.setRole(message.getRole());
                            history.setContent(message.getContent());
                            return history;
                        }).toList());

                AgentResponse response = runLoop(context, built, bufferedSink);

                // 回合结束统一落内存 user + assistant（供下一轮窗口与摘要；不触碰身份上下文）。
                // assistant 额外带上「本轮工具步骤」的一行摘要：不回写它，下一轮就看不到上一轮查过什么，
                // 跨轮追问只能重新检索或凭结论编造。工具输出全文不入史（见 ToolSteps 的容量约束）。
                if (!context.isCancelled() && response.getStopReason() != StopReason.CANCELLED
                        && response.getStopReason() != null) {
                    if (response.getMemorySaveProposal() != null) {
                        response.setMemoryApproval(approvals.create(sessionTurn.session(), response.getMemorySaveProposal()));
                    } else if (policy.memorySuggestionsEnabled() && response.getStopReason() == StopReason.FINAL_ANSWER) {
                        var suggestion = MemoryPreferenceExtractor.suggest(question);
                        if (suggestion != null) {
                            try {
                                response.setMemoryApproval(approvals.create(sessionTurn.session(), suggestion));
                            } catch (RuntimeException failure) {
                                log.warn("无法创建自动记忆建议，保留本轮回答 traceId={}", context.getTraceId());
                            }
                        }
                    }

                    // 历史只持久化可显示的步骤摘要和最终回答，续聊无需重放工具全文。
                    List<ToolStep> steps = ToolSteps.from(response.getToolInvocations());
                    List<AgentMessage> messages = List.of(
                            AgentMessage.builder().role("user").content(question).timestamp(Instant.now()).build(),
                            AgentMessage.builder().role("assistant")
                                    .content(response.getAnswer() == null ? stoppedPlaceholder(response.getStopReason()) : response.getAnswer())
                                    .steps(steps).timestamp(Instant.now()).build());
                    sessions.complete(sessionTurn, question, response, messages);
                    // managed 存储的摘要写入会检查元数据仍存在，删除/过期后晚到摘要会被丢弃。
                    try {
                        String summaryModelId = modelRegistry.effectiveModelId(userId, "summarize");
                        summaryCoordinator.maybeSummarize(sessionId, userId, summaryModelId);
                    } catch (RuntimeException e) {
                        // 完成回合已经保存；摘要调度失败不能把已完成终态变成流式 error。
                        log.warn("Agent 完成回合摘要调度失败，保留已保存终态; traceId={}", context.getTraceId(), e);
                    }
                }
                if (response.getMemoryApproval() == null) bufferedSink.flush(null);
                return response;
            });
        }
    }

    /**
     * 根据事件接收方是否活动选择带事件或同步运行入口，返回 Agent 的回合结果。
     * 上游由 {@code AgentService.executeTurn} 调用。
     * 下游调用 {@code sink.isActive}、{@code evented.run}、{@code agentRuntime.run}。
     *
     * @param context 可信执行上下文，包含身份、范围和本轮执行状态
     * @param built 本次调用传入的 {@code ContextBuildResult}
     * @param sink 本轮可选事件接收方
     * @return 带答案、工具轨迹、来源和停止原因的回合结果
     */
    private AgentResponse runLoop(AgentExecutionContext context, ContextBuildResult built, AgentEventSink sink) {
        if (sink.isActive() && agentRuntime instanceof DefaultAgentRuntime evented) {
            return evented.run(context, built.getMessages(), sink);
        }
        return agentRuntime.run(context, built.getMessages());
    }

    /** 完成结果成功保存后才公开 answer/done；存储失败仍由 Controller 发 error。 */
    private static final class BufferedTerminalSink implements AgentEventSink {
        private final AgentEventSink delegate;
        private final List<AgentEvent> terminal = new ArrayList<>();

        /**
         * 用注入依赖和构造参数建立此实例执行时使用的状态。
         *
         * @param delegate 实际处理事件的接收方
         */
        private BufferedTerminalSink(AgentEventSink delegate) { this.delegate = delegate; }

        /**
         * 返回底层 SSE 接收方的当前可用状态，供循环选择模型流式调用。
         * 下游调用 {@code delegate.isActive}。
         *
         * @return 条件成立时为 true，其他状态按 false 处理
         */
        @Override public boolean isActive() { return delegate.isActive(); }

        /**
         * 暂存 answer 和 done，立即转发中间事件，使终态交付排在会话持久化之后。
         * 上游由 {@code AgentService.executeTurn} 调用。
         * 下游调用 {@code delegate.emit}。
         *
         * @param event 本次调用传入的 {@code AgentEvent}
         */
        @Override public void emit(AgentEvent event) {
            if (AgentEvent.ANSWER.equals(event.getType()) || AgentEvent.DONE.equals(event.getType())) terminal.add(event);
            else delegate.emit(event);
        }

        /**
         * 将已保存的终态发送给实际接收方；有记忆申请时补入 done 载荷。
         * 上游由 {@code AgentService.executeTurn}、{@code AgentService.runTurn} 调用。
         * 下游调用 {@code delegate.emit}。
         *
         * @param approval 待附加到本轮结果中的记忆申请
         */
        private void flush(MemorySaveApproval approval) {
            for (AgentEvent event : terminal) {
                if (approval != null && AgentEvent.DONE.equals(event.getType())) {
                    var data = new LinkedHashMap<>(event.getData());
                    data.put("memoryApproval", approval);
                    delegate.emit(AgentEvent.of(AgentEvent.DONE, data));
                } else {
                    delegate.emit(event);
                }
            }
        }
    }

    /**
     * 为缺少实际答案的终止原因生成历史占位，使后续上下文能辨认未完成回合。
     * 上游由 {@code AgentService.executeTurn} 调用。
     *
     * @param reason 诊断或终止原因
     * @return 供后续处理使用的文本或标识
     */
    private static String stoppedPlaceholder(StopReason reason) {
        if (reason == null) {
            return "（本轮未产生回答。）";
        }
        return switch (reason) {
            case MAX_ITERATIONS -> "（本轮未能在最大轮次内得出结论，请重试或换一种问法。）";
            case MAX_TOOL_CALLS -> "（本轮工具调用次数已达上限，未能得出结论，请重试或缩小问题范围。）";
            case BUDGET_EXCEEDED -> "（本轮超出时间预算，未能得出结论，请重试。）";
            case CANCELLED -> "（本轮请求已被取消。）";
            case MEMORY_APPROVAL_REQUIRED -> "（已提出长期记忆保存申请，结果以用户确认后的状态为准。）";
            // 降级回合通常由 runtime 直接带回兜底 answer、走不到这里；
            // 这一档让「answer 为 null 但 stopReason=LLM_DEGRADED」的组合也留得下痕迹，
            // 而不是让 switch 漏一个 case（编译不过 = 强制以后加终止原因时必须想清楚占位写什么）。
            case LLM_DEGRADED -> "（AI 服务本轮不可用，未检索知识库，请重试。）";
            // 截断/过滤/空回复/卡死这四档在 runtime 里都带了可读 answer，正常走不到这里；
            // 保留分支是为了让「新增终止原因必须想清楚历史占位」这条约束继续由编译器执行。
            case FINAL_ANSWER_TRUNCATED -> "（本轮回答在输出上限处被截断，请缩小问题范围后重试。）";
            case CONTENT_FILTERED -> "（本轮回答被内容安全策略拦截，请换一种问法。）";
            case EMPTY_RESPONSE -> "（本轮模型未生成任何内容，请重试。）";
            case STUCK_REPEATED -> "（本轮模型反复给出相同内容且无进展，已中止，请换一种问法。）";
            case FINAL_ANSWER -> "（本轮未产生回答。）";
        };
    }
}
