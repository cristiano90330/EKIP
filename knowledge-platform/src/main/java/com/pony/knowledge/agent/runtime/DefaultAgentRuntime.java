/* 驱动 Agent 的模型决策、工具执行与证据约束，并生成统一回合终态。 */

package com.pony.knowledge.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pony.knowledge.agent.context.ContextBudget;
import com.pony.knowledge.agent.context.ContextBudgetManager;
import com.pony.knowledge.agent.context.ContextBuildRequest;
import com.pony.knowledge.agent.llm.AgentLlm;
import com.pony.knowledge.agent.llm.AgentInputTokens;
import com.pony.knowledge.agent.llm.TokenScaleCalibrator;
import com.pony.knowledge.agent.memory.summary.TokenEstimator;
import com.pony.knowledge.agent.model.AgentDecision;
import com.pony.knowledge.agent.model.AgentExecutionContext;
import com.pony.knowledge.agent.model.AgentMessage;
import com.pony.knowledge.agent.model.AgentPrompts;
import com.pony.knowledge.agent.model.AgentResponse;
import com.pony.knowledge.agent.model.StopReason;
import com.pony.knowledge.agent.model.ToolCall;
import com.pony.knowledge.agent.observability.AgentMetrics;
import com.pony.knowledge.agent.stream.AgentEvent;
import com.pony.knowledge.agent.stream.AgentEventSink;
import com.pony.knowledge.agent.tool.ToolDefinition;
import com.pony.knowledge.agent.tool.ToolExecutor;
import com.pony.knowledge.agent.tool.ToolRegistry;
import com.pony.knowledge.agent.tool.output.ToolResult;
import com.pony.knowledge.agent.tool.output.KnowledgeAskOutput;
import com.pony.knowledge.agent.tool.output.KnowledgeSearchOutput;
import com.pony.knowledge.agent.tool.output.ReadDocumentOutput;
import com.pony.knowledge.dto.SourceDTO;
import com.pony.knowledge.service.rag.model.RagEvidence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Agent Runtime：显式五阶段的 ReAct 循环。
 *
 * <pre>
 * run()
 *  └─ executeLoop()                      while 轮次 &lt; maxIterations
 *      └─ reactIteration()              ① Think  ② Guards  ③ Analyze  ④ Act  ⑤ Observe
 *          返回 NEXT / CONTINUE / BREAK  → 循环据此推进、重跑或退出
 *  └─ finalizeTurn()                    轮次或预算用尽且已有工具结果 → 无工具收尾合成
 * </pre>
 *
 * <p>{@link ResponseAnalyzer} 将模型结果划分为最终答案、工具调用和可识别的异常结束。
 * 空回复会加入有次数上限的追问提示；连续长度截断提前结束。符合回合策略时可用已有工具结果
 * 生成收尾答案，单轮控制结果由 {@link IterOutcome} 表达。
 *
 * <p>只依赖 ToolRegistry / ToolActExecutor / AgentLlm / ContextBudgetManager，
 * 不认识具体 Tool 与具体供应商。身份（userId/权限）只存在于 {@link AgentExecutionContext}，
 * runtime 从不从模型参数里读身份。
 *
 * <p>观测：逐轮 token 与降级次数在每条退出路径上都上报（{@link #recordUsage} / {@link #degradedTurn}）。
 * 「已经花掉的 token」不因终止原因而消失，所以 MAX_ITERATIONS、BUDGET_EXCEEDED 这类非成功终止同样计数。
 */
@Component
public class DefaultAgentRuntime implements AgentRuntime {

    private static final Logger log = LoggerFactory.getLogger(DefaultAgentRuntime.class);
    private static final int MAX_PUBLIC_SOURCES = 10;
    private static final Pattern CITATION_MARKER = Pattern.compile("\\[\\[cite:([^\\]\\r\\n]+)\\]\\]");

    /**
     * 治理层降级时给用户的兜底文案。
     *
     * <p>这句话同时被两个读者读：用户（稍后重试）与下一轮的模型（本轮没检索到任何东西）。
     * 因此不写系统异常也不写具体原因——原因在 degradeReason 里给运维看，
     * 把 circuit open 糊到用户界面上是另一种噪声。
     */
    public static final String DEGRADED_ANSWER = "AI 服务暂时不可用，本轮未能检索知识库，请稍后重试。";

    public static final String NO_EVIDENCE_ANSWER = "本轮没有找到可用于回答的获准知识库证据，请补充资料或缩小问题范围。";

    private final AgentLlm agentLlm;

    private final ToolRegistry toolRegistry;

    private final ToolActExecutor actExecutor;

    private final FinalAnswerSynthesizer synthesizer;

    private final ObjectMapper objectMapper;

    private final int maxIterations;

    private final int maxToolCalls;

    private final long budgetTimeMs;

    private final ReactSettings settings;

    /** 上下文预算管理器（可为 null → 不裁剪；测试/直连可不传）。 */
    private final ContextBudgetManager budgetManager;

    /** LLM token 与降级遥测：逐轮上报，供 agent.llm.tokens.* / agent.llm.degraded 使用（可为 null → 不打点）。 */
    private final AgentMetrics agentMetrics;

    /** token 估算校准（可为 null → 不校准，系数恒为 1.0）。 */
    private final TokenScaleCalibrator tokenScale;

    /** 正文工具意图恢复器：模型不产出原生 tool_calls 时的唯一兜底。 */

    /** 溢出恢复时留给提示词的下限：再小就连 system + 当前问题都放不下，压缩也没意义。 */
    private static final int MIN_SQUEEZE_PROMPT_TOKENS = 512;

    /**
     * 识别模型决策里的上下文容量故障，以便调用方区分可缩减重试和普通依赖失败。
     * 上游由 {@code DefaultAgentRuntime.reactIteration} 调用。
     *
     * @param decision 待处理的决策及其终态信息
     * @return 条件成立时为 true，其他状态按 false 处理
     */
    private static boolean isContextOverflow(AgentDecision decision) {
        String reason = decision == null || decision.getDegradeReason() == null
                ? "" : decision.getDegradeReason().toLowerCase(java.util.Locale.ROOT);
        if (reason.isBlank()) {
            return false;
        }
        return reason.contains("超预算")
                || reason.contains("context length")
                || reason.contains("context_length")
                || reason.contains("maximum context")
                || reason.contains("input length")
                || reason.contains("too many tokens")
                || reason.contains("too long");
    }

    /**
     * 尝试采用更紧的预算重建消息，仅在能缩减模型输入时继续重试。
     * 上游由 {@code DefaultAgentRuntime.reactIteration} 调用。
     * 下游调用 {@code budgetManager.budget}、{@code TokenEstimator.estimateMessages}、{@code buildWith}。
     *
     * @param context 可信执行上下文，包含身份、范围和本轮执行状态
     * @param state 随本轮执行累积的消息、用量、证据和终态
     * @return 条件成立时为 true，其他状态按 false 处理
     */
    private boolean squeezeContext(AgentExecutionContext context, AgentTurnState state) {
        if (budgetManager == null) {
            return false;
        }

        ContextBudget current = budgetManager.budget();
        int reserved = current.getReservedOutputTokens() + current.getReservedToolTokens();
        int squeezedMax = Math.max(reserved + MIN_SQUEEZE_PROMPT_TOKENS, current.getMaxTokens() / 2);
        ContextBudget squeezed = ContextBudget.builder()
                .maxTokens(squeezedMax)
                .reservedOutputTokens(current.getReservedOutputTokens())
                .reservedToolTokens(current.getReservedToolTokens())
                .build();

        // 两次装配对比，而不是拿「当前 transcript」当基准：首轮的 transcript 可能只有一条用户问题，
        // 而任何一次装配都必然带上 system + 当前问题，直接比会把「压半生效」误判成「变大了」。
        // 代价是溢出时多一次装配（Redis 两次读），但溢出不常发生且每回合只走一次。
        List<AgentMessage> full = buildWith(context, state, current);
        List<AgentMessage> squeezedMessages = buildWith(context, state, squeezed);
        if (TokenEstimator.estimateMessages(squeezedMessages) >= TokenEstimator.estimateMessages(full)) {
            return false;   // 压不动：重试只会得到同样的降级，白花一次调用
        }

        state.setTranscript(squeezedMessages);
        return true;
    }

    /**
     * 在当前历史、提问与工具消息基础上使用指定本轮预算构建送给模型的消息。
     * 上游由 {@code DefaultAgentRuntime.squeezeContext}、{@code DefaultAgentRuntime.think} 调用。
     * 下游调用 {@code context.isMemoryReadEnabled}。
     *
     * @param context 可信执行上下文，包含身份、范围和本轮执行状态
     * @param state 随本轮执行累积的消息、用量、证据和终态
     * @param budget 本次调用传入的 {@code ContextBudget}
     * @return 按当前约定排列的模型消息列表
     */
    private List<AgentMessage> buildWith(AgentExecutionContext context, AgentTurnState state,
                                         ContextBudget budget) {
        return budgetManager.build(ContextBuildRequest.builder()
                .userId(context.getUserId())
                .memoryReadEnabled(context.isMemoryReadEnabled())
                .systemPrompt(context.getSystemPrompt())
                .sessionId(context.getSessionId())
                .currentQuery(context.getCurrentQuery())
                .toolMessages(state.getTurnTool())
                .budget(budget)
                .build()).getMessages();
    }

    /**
     * 用注入依赖和构造参数建立此实例执行时使用的状态。
     * 下游调用 {@code ReactSettings.defaults}。
     *
     * @param agentLlm 本次调用传入的 {@code AgentLlm}
     * @param toolExecutor 本次调用传入的 {@code ToolExecutor}
     * @param toolRegistry 本次调用传入的 {@code ToolRegistry}
     * @param agentToolExecutor 本次调用传入的 {@code Executor}
     * @param objectMapper 负责 JSON 协议编码和解码的映射器
     * @param maxIterations 本次调用传入的 {@code int}
     * @param maxToolCalls 本次调用传入的 {@code int}
     * @param maxConcurrentTools 本次调用传入的 {@code int}
     * @param parallelTools 本次调用传入的 {@code boolean}
     * @param budgetTimeMs 本次调用传入的 {@code long}
     * @param budgetManager 本次调用传入的 {@code ContextBudgetManager}
     * @param agentMetrics 本次调用传入的 {@code AgentMetrics}
     * @param settings 本次调用传入的 {@code ReactSettings}
     * @param tokenScale 本次调用传入的 {@code TokenScaleCalibrator}
     */
    @Autowired
    public DefaultAgentRuntime(
            AgentLlm agentLlm,
            ToolExecutor toolExecutor,
            ToolRegistry toolRegistry,
            @Qualifier("agentToolDispatchExecutor") Executor agentToolExecutor,
            ObjectMapper objectMapper,
            @Value("${agent.max-iterations:12}") int maxIterations,
            @Value("${agent.max-tool-calls:10}") int maxToolCalls,
            @Value("${agent.max-concurrent-tools:4}") int maxConcurrentTools,
            @Value("${agent.parallel-tools:true}") boolean parallelTools,
            @Value("${agent.budget-time-ms:120000}") long budgetTimeMs,
            ContextBudgetManager budgetManager,
            AgentMetrics agentMetrics,
            ReactSettings settings,
            TokenScaleCalibrator tokenScale) {
        this.agentLlm = agentLlm;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        this.maxIterations = maxIterations;
        this.maxToolCalls = maxToolCalls;
        this.budgetTimeMs = budgetTimeMs;
        this.budgetManager = budgetManager;
        this.agentMetrics = agentMetrics;
        this.tokenScale = tokenScale;
        this.settings = settings == null ? ReactSettings.defaults() : settings;
        this.synthesizer = new FinalAnswerSynthesizer(agentLlm);
        this.actExecutor = new ToolActExecutor(toolExecutor, objectMapper, agentToolExecutor,
                new ToolConcurrencyPolicy(toolRegistry, this.settings.getBarrierTools()),
                parallelTools, maxConcurrentTools);
    }

    /**
     * 将当前参数交给共有构造入口，并补齐这组重载省略的设置。
     *
     * @param agentLlm 本次调用传入的 {@code AgentLlm}
     * @param toolExecutor 本次调用传入的 {@code ToolExecutor}
     * @param toolRegistry 本次调用传入的 {@code ToolRegistry}
     * @param agentToolExecutor 本次调用传入的 {@code Executor}
     * @param objectMapper 负责 JSON 协议编码和解码的映射器
     * @param maxIterations 本次调用传入的 {@code int}
     * @param maxToolCalls 本次调用传入的 {@code int}
     * @param maxConcurrentTools 本次调用传入的 {@code int}
     * @param parallelTools 本次调用传入的 {@code boolean}
     * @param budgetTimeMs 本次调用传入的 {@code long}
     * @param budgetManager 本次调用传入的 {@code ContextBudgetManager}
     * @param agentMetrics 本次调用传入的 {@code AgentMetrics}
     * @param settings 本次调用传入的 {@code ReactSettings}
     */
    public DefaultAgentRuntime(AgentLlm agentLlm, ToolExecutor toolExecutor, ToolRegistry toolRegistry,
                               Executor agentToolExecutor, ObjectMapper objectMapper,
                               int maxIterations, int maxToolCalls, int maxConcurrentTools,
                               boolean parallelTools, long budgetTimeMs,
                               ContextBudgetManager budgetManager, AgentMetrics agentMetrics,
                               ReactSettings settings) {
        this(agentLlm, toolExecutor, toolRegistry, agentToolExecutor, objectMapper,
                maxIterations, maxToolCalls, maxConcurrentTools, parallelTools, budgetTimeMs,
                budgetManager, agentMetrics, settings, null);
    }

    /**
     * 将当前参数交给共有构造入口，并补齐这组重载省略的设置。
     * 下游调用 {@code ReactSettings.defaults}。
     *
     * @param agentLlm 本次调用传入的 {@code AgentLlm}
     * @param toolExecutor 本次调用传入的 {@code ToolExecutor}
     * @param toolRegistry 本次调用传入的 {@code ToolRegistry}
     * @param agentToolExecutor 本次调用传入的 {@code Executor}
     * @param objectMapper 负责 JSON 协议编码和解码的映射器
     * @param maxIterations 本次调用传入的 {@code int}
     * @param maxToolCalls 本次调用传入的 {@code int}
     * @param maxConcurrentTools 本次调用传入的 {@code int}
     * @param parallelTools 本次调用传入的 {@code boolean}
     * @param budgetTimeMs 本次调用传入的 {@code long}
     * @param budgetManager 本次调用传入的 {@code ContextBudgetManager}
     * @param agentMetrics 本次调用传入的 {@code AgentMetrics}
     */
    public DefaultAgentRuntime(AgentLlm agentLlm, ToolExecutor toolExecutor, ToolRegistry toolRegistry,
                               Executor agentToolExecutor, ObjectMapper objectMapper,
                               int maxIterations, int maxToolCalls, int maxConcurrentTools,
                               boolean parallelTools, long budgetTimeMs,
                               ContextBudgetManager budgetManager, AgentMetrics agentMetrics) {
        this(agentLlm, toolExecutor, toolRegistry, agentToolExecutor, objectMapper,
                maxIterations, maxToolCalls, maxConcurrentTools, parallelTools, budgetTimeMs,
                budgetManager, agentMetrics, ReactSettings.defaults());
    }

    /**
     * 将当前参数交给共有构造入口，并补齐这组重载省略的设置。
     * 下游调用 {@code ReactSettings.defaults}。
     *
     * @param agentLlm 本次调用传入的 {@code AgentLlm}
     * @param toolExecutor 本次调用传入的 {@code ToolExecutor}
     * @param toolRegistry 本次调用传入的 {@code ToolRegistry}
     * @param agentToolExecutor 本次调用传入的 {@code Executor}
     * @param objectMapper 负责 JSON 协议编码和解码的映射器
     * @param maxIterations 本次调用传入的 {@code int}
     * @param maxToolCalls 本次调用传入的 {@code int}
     * @param maxConcurrentTools 本次调用传入的 {@code int}
     * @param parallelTools 本次调用传入的 {@code boolean}
     * @param budgetTimeMs 本次调用传入的 {@code long}
     * @param budgetManager 本次调用传入的 {@code ContextBudgetManager}
     */
    public DefaultAgentRuntime(AgentLlm agentLlm, ToolExecutor toolExecutor, ToolRegistry toolRegistry,
                               Executor agentToolExecutor, ObjectMapper objectMapper,
                               int maxIterations, int maxToolCalls, int maxConcurrentTools,
                               boolean parallelTools, long budgetTimeMs,
                               ContextBudgetManager budgetManager) {
        this(agentLlm, toolExecutor, toolRegistry, agentToolExecutor, objectMapper,
                maxIterations, maxToolCalls, maxConcurrentTools, parallelTools, budgetTimeMs,
                budgetManager, null, ReactSettings.defaults());
    }

    /**
     * 将当前参数交给共有构造入口，并补齐这组重载省略的设置。
     * 下游调用 {@code ReactSettings.defaults}。
     *
     * @param agentLlm 本次调用传入的 {@code AgentLlm}
     * @param toolExecutor 本次调用传入的 {@code ToolExecutor}
     * @param toolRegistry 本次调用传入的 {@code ToolRegistry}
     * @param agentToolExecutor 本次调用传入的 {@code Executor}
     * @param maxIterations 本次调用传入的 {@code int}
     * @param maxToolCalls 本次调用传入的 {@code int}
     * @param budgetTimeMs 本次调用传入的 {@code long}
     */
    public DefaultAgentRuntime(AgentLlm agentLlm, ToolExecutor toolExecutor, ToolRegistry toolRegistry,
                               Executor agentToolExecutor, int maxIterations, int maxToolCalls, long budgetTimeMs) {
        this(agentLlm, toolExecutor, toolRegistry, agentToolExecutor, new ObjectMapper(),
                maxIterations, maxToolCalls, 4, true, budgetTimeMs, null, null, ReactSettings.defaults());
    }

    /**
     * 初始化单回合状态，执行循环后生成带来源、用量和停止原因的最终响应。
     * 上游由 {@code AgentService.runLoop} 调用。
     * 下游调用 {@code DefaultAgentRuntime.executeLoop}、{@code DefaultAgentRuntime.toResponse}。
     *
     * @param context 可信执行上下文，包含身份、范围和本轮执行状态
     * @param messages 按照交互顺序提供的消息列表
     * @return 带答案、工具轨迹、来源和停止原因的回合结果
     */
    @Override
    public AgentResponse run(AgentExecutionContext context, List<AgentMessage> messages) {
        return run(context, messages, AgentEventSink.NOOP);
    }

    /**
     * 初始化单回合状态，执行循环后生成带来源、用量和停止原因的最终响应。
     * 上游由 {@code AgentService.runLoop} 调用。
     * 下游调用 {@code DefaultAgentRuntime.executeLoop}、{@code DefaultAgentRuntime.toResponse}。
     *
     * @param context 可信执行上下文，包含身份、范围和本轮执行状态
     * @param messages 按照交互顺序提供的消息列表
     * @param sink 本轮可选事件接收方
     * @return 带答案、工具轨迹、来源和停止原因的回合结果
     */
    public AgentResponse run(AgentExecutionContext context, List<AgentMessage> messages, AgentEventSink sink) {
        long start = System.nanoTime();
        AgentTurnState state = new AgentTurnState(messages);
        sink.emit(AgentEvent.of(AgentEvent.ACCEPTED, Map.of(
                "maxIterations", maxIterations,
                "maxToolCalls", maxToolCalls)));

        // 将循环和输出转换收束到同一入口，同步与 SSE 共用终态生成。
        executeLoop(context, state, sink, start);
        return toResponse(state, sink);
    }

    // ---------------------------------------------------------------- 循环本体

    /**
     * 在每轮前检查取消和预算，根据单轮的执行结果继续或收尾。
     * 上游由 {@code DefaultAgentRuntime.run} 调用。
     * 下游调用 {@code DefaultAgentRuntime.reactIteration}、{@code DefaultAgentRuntime.finalizeTurn}。
     *
     * @param context 可信执行上下文，包含身份、范围和本轮执行状态
     * @param state 随本轮执行累积的消息、用量、证据和终态
     * @param sink 本轮可选事件接收方
     * @param startedAtNanos 以单调时钟记录的起始时间，单位纳秒
     */
    private void executeLoop(AgentExecutionContext context, AgentTurnState state,
                             AgentEventSink sink, long startedAtNanos) {
        while (state.getRound() < maxIterations) {
            if (context.isCancelled()) {
                // 用户主动停止：不再消耗模型调用，也不做兜底合成（他要的就是「停」）。
                stop(state, StopReason.CANCELLED, null);
                return;
            }
            if (context.requestedMemoryContent() != null && !context.allowsTool("memory_save")) {
                stop(state, StopReason.FINAL_ANSWER, "当前 Agent 未开放长期记忆写入，无法保存。请检查记忆写入开关与工具白名单。");
                return;
            }
            if (budgetExceeded(startedAtNanos)) {
                stop(state, StopReason.BUDGET_EXCEEDED, null);
                finalizeTurn(context, state, sink);
                return;
            }

            // 检查通过后才消费迭代，纠偏重试以单轮结果单独决定是否累计轮次。
            IterOutcome outcome = reactIteration(context, state, sink, state.getRound() + 1, startedAtNanos);
            if (outcome == IterOutcome.BREAK) {
                // 本轮已经真实发生（一次决策、可能还有工具），迭代计数必须包含它。
                state.setRound(state.getRound() + 1);
                return;
            }
            if (outcome == IterOutcome.NEXT) {
                state.setRound(state.getRound() + 1);
            }
        }
        stop(state, StopReason.MAX_ITERATIONS, null);
        finalizeTurn(context, state, sink);
    }

    /**
     * 执行一轮模型决策、工具调用和上下文更新，并返回继续或停止指示。
     * 上游由 {@code DefaultAgentRuntime.executeLoop} 调用。
     * 下游调用 {@code DefaultAgentRuntime.think}、{@code ResponseAnalyzer.analyze}、{@code ToolActExecutor.execute}。
     *
     * @param context 可信执行上下文，包含身份、范围和本轮执行状态
     * @param state 随本轮执行累积的消息、用量、证据和终态
     * @param sink 本轮可选事件接收方
     * @param round 当前模型决策轮次
     * @param startedAtNanos 以单调时钟记录的起始时间，单位纳秒
     * @return 继续循环或停止的指示
     */
    private IterOutcome reactIteration(AgentExecutionContext context, AgentTurnState state, AgentEventSink sink,
                                       int round, long startedAtNanos) {
        sink.emit(AgentEvent.of(AgentEvent.ROUND, Map.of("round", round, "phase", "think")));

        // ① Think：一次受治理的模型决策（流式时逐字转发）
        AgentDecision decision = think(context, state, sink, round);
        recordUsage(state, decision);
        if (context.isCancelled()) {
            stop(state, StopReason.CANCELLED, null);
            return IterOutcome.BREAK;
        }

        // ①' 上下文溢出恢复：治理层判「提示词超预算」或供应商报超窗时，把上下文对半压一次再试。
        // 只救一次（state.overflowRetried）：压不动就该老实降级，而不是在同一个失败上反复付费。
        if (decision.isDegraded() && !state.isOverflowRetried() && isContextOverflow(decision)) {
            state.setOverflowRetried(true);
            if (squeezeContext(context, state)) {
                if (agentMetrics != null) {
                    agentMetrics.countContextOverflowRetry();
                }
                sink.emit(AgentEvent.of(AgentEvent.ROUND, Map.of("round", round, "phase", "squeeze")));
                decision = think(context, state, sink, round);
                recordUsage(state, decision);
                if (!decision.isDegraded() && agentMetrics != null) {
                    agentMetrics.countContextOverflowRecovered();
                }
            }
        }

        // 在压缩重试结束后按最终送出的消息校准，避免将原始长上下文误作实际用量。
        calibrateTokenScale(context, state.getTranscript(), decision);

        // 治理层判降级：立刻终止，不再转下一圈。能走到这里说明预算、熔断、降级链都试过了，
        // 再转一圈只是把同一个失败重犯一次；而 Java 侧不知道该等多久，Python 侧知道。
        if (decision.isDegraded()) {
            return degradedTurn(state, decision);
        }
        // 只有收到一次完整的模型决策，才确认本次输入中的来源确实进入了模型回合。
        // 治理层可能在预算或熔断处直接降级，此时仅构造了请求，不能公开新来源。
        captureVisibleSources(state, state.getTranscript());
        if (context.isCancelled()) {
            stop(state, StopReason.CANCELLED, null);
            return IterOutcome.BREAK;
        }
        // 决策本身也花时间：超预算后不再执行工具（工具是一次真实的外部调用，做完也没人用了）。
        if (budgetExceeded(startedAtNanos)) {
            stop(state, StopReason.BUDGET_EXCEEDED, null);
            finalizeTurn(context, state, sink);
            return IterOutcome.BREAK;
        }

        // ② Guards：无进展止损（必须在 Analyze 之前，否则「相同内容」会被当成正常答案交出去）
        if (guardsTripped(state, decision)) {
            if (requiresEvidence(context) && state.getVisibleSources().isEmpty()) state.setFinalAnswer(null);
            return IterOutcome.BREAK;
        }

        // ③ Analyze：这一次决策意味着终局还是继续调工具
        ResponseAnalyzer.Verdict verdict = ResponseAnalyzer.analyze(decision);
        switch (verdict.getAction()) {
            case FINAL_ANSWER -> {
                if (context.requestedMemoryContent() != null) {
                    stop(state, StopReason.FINAL_ANSWER, "本轮未生成长期记忆保存申请，请重试。");
                    return IterOutcome.BREAK;
                }
                if (!requiresEvidence(context) || !state.getVisibleSources().isEmpty()) {
                    stop(state, StopReason.FINAL_ANSWER, verdict.getAnswer());
                    return IterOutcome.BREAK;
                }
                // 模型跳过检索时仅补一次真实工具调用；仍走下面同一套 Act/Observe 与额度统计。
                // 原始问题留在 context，检索参数只按工具已有的 200 字符上限截取。
                if (!state.isKnowledgeToolExecuted() && !state.isForcedKnowledgeSearch()
                        && round < maxIterations && state.getToolCalls() < maxToolCalls
                        && offeredToolNames(context).contains("knowledge_search")
                        && context.getAccessibleKnowledgeBaseIds() != null
                        && !context.getAccessibleKnowledgeBaseIds().isEmpty()
                        && context.getCurrentQuery() != null && !context.getCurrentQuery().isBlank()) {
                    state.setForcedKnowledgeSearch(true);
                    String query = context.getCurrentQuery().trim();
                    if (query.length() > 200) {
                        int end = Character.isHighSurrogate(query.charAt(199)) ? 199 : 200;
                        query = query.substring(0, end);
                    }
                    decision = copyWith(decision, "按证据回答策略检索本轮问题。", List.of(ToolCall.builder()
                            .callId("grounded-search-" + round)
                            .toolName("knowledge_search")
                            .arguments(serialize(Map.of("query", query)))
                            .build()));
                } else {
                    stopWithoutEvidence(state);
                    return IterOutcome.BREAK;
                }
            }
            case FINAL_ANSWER_TRUNCATED -> {
                stop(state, StopReason.FINAL_ANSWER_TRUNCATED,
                        context.requestedMemoryContent() != null || requiresEvidence(context) && state.getVisibleSources().isEmpty()
                                ? null : verdict.getAnswer());
                return IterOutcome.BREAK;
            }
            case CONTENT_FILTERED -> {
                stop(state, StopReason.CONTENT_FILTERED,
                        requiresEvidence(context) && state.getVisibleSources().isEmpty()
                                ? ResponseAnalyzer.CONTENT_FILTERED_FALLBACK : verdict.getAnswer());
                return IterOutcome.BREAK;
            }
            case EMPTY_CONTENT -> {
                return handleEmptyContent(state, round);
            }
            case TOOL_CALLS -> {
                // 落到下面的 Act
            }
        }

        // 超限立即停止：超出剩余额度就不执行
        if (state.getToolCalls() + decision.getToolCalls().size() > maxToolCalls) {
            stop(state, StopReason.MAX_TOOL_CALLS, null);
            return IterOutcome.BREAK;
        }

        state.setToolCalls(state.getToolCalls() + decision.getToolCalls().size());
        state.setToolRounds(state.getToolRounds() + 1);

        // ④ Act
        sink.emit(AgentEvent.of(AgentEvent.ROUND, Map.of("round", round, "phase", "act")));
        for (ToolCall call : decision.getToolCalls()) {
            sink.emit(AgentEvent.of(AgentEvent.TOOL_CALL, toolCallPayload(round, call)));
        }

        List<ToolResult> results = normalize(actExecutor.execute(context, decision.getToolCalls()),
                decision.getToolCalls());
        for (int i = 0; i < results.size(); i++) {
            sink.emit(AgentEvent.of(AgentEvent.TOOL_RESULT,
                    toolResultPayload(round, decision.getToolCalls().get(i), results.get(i))));
        }

        state.getInvocations().addAll(toInvocations(decision.getToolCalls(), results));
        for (int i = 0; i < results.size(); i++) {
            observeKnowledgeResult(state, decision.getToolCalls().get(i), results.get(i));
        }
        if (context.isCancelled()) {
            stop(state, StopReason.CANCELLED, null);
            return IterOutcome.BREAK;
        }

        // 记忆建议成为待确认终态，后续写入由会话审批处理。
        for (ToolResult result : results) {
            if (result.getMemorySaveProposal() != null) {
                state.setMemorySaveProposal(result.getMemorySaveProposal());
                stop(state, StopReason.MEMORY_APPROVAL_REQUIRED, null);
                return IterOutcome.BREAK;
            }
        }

        // 仅通用回答可以采用工具给出的直接答案，证据策略继续走获准正文判断。
        if (!requiresEvidence(context) && !state.isKnowledgeToolExecuted()
                && results.stream().allMatch(result -> result.isSuccess()
                        && result.getDirectAnswer() != null && !result.getDirectAnswer().isBlank())) {
            String answer = results.stream().map(ToolResult::getDirectAnswer)
                    .collect(java.util.stream.Collectors.joining("\n\n"));
            state.setToolRenderedAnswer(true);
            stop(state, StopReason.FINAL_ANSWER, answer);
            return IterOutcome.BREAK;
        }

        // ⑤ Observe：把本轮往返并入对话，并按预算重新装配上下文
        List<AgentMessage> batch = new ArrayList<>();
        appendAssistantCalls(batch, decision);
        appendToolResults(state, batch, decision.getToolCalls(), results);
        state.setTranscript(rebuildContext(context, state, batch, sink));
        sink.emit(AgentEvent.of(AgentEvent.ROUND, Map.of("round", round, "phase", "observe")));
        return IterOutcome.NEXT;
    }

    /**
     * 检查本轮消息与工具定义能否放入模型窗口，再经 AgentLlm 请求同步或流式决策。
     * 上游由 {@code DefaultAgentRuntime.reactIteration} 调用。
     * 下游调用 {@code toolRegistry.definitionsFor}、{@code AgentInputTokens.estimate}、{@code sink.emit}。
     *
     * @param context 可信执行上下文，包含身份、范围和本轮执行状态
     * @param state 随本轮执行累积的消息、用量、证据和终态
     * @param sink 本轮可选事件接收方
     * @param round 当前模型决策轮次
     * @return 模型本轮的正文、工具调用和完成状态
     */
    private AgentDecision think(AgentExecutionContext context, AgentTurnState state,
                                AgentEventSink sink, int round) {
        List<AgentMessage> messages = state.getTranscript();

        // 工具声明和对话内容一起占用输入窗口，预算校验时计入两部分。
        List<ToolDefinition> tools = toolRegistry.definitionsFor(context);
        if (budgetManager != null) {
            ContextBudget budget = callBudget(context);
            int allowed = budget.getMaxTokens() - budget.getReservedOutputTokens();
            int before = AgentInputTokens.estimate(messages, tools);
            if (before > allowed) {
                sink.emit(AgentEvent.of(AgentEvent.ROUND, Map.of("round", round,
                        "phase", "context_organize", "inputTokensBefore", before, "inputBudget", allowed)));
                if (context.isCancelled()) return capacityDecision("model_context_capacity_exceeded");
                messages = buildWith(context, state, budget);
                state.setTranscript(messages);
                int after = AgentInputTokens.estimate(messages, tools);
                sink.emit(AgentEvent.of(AgentEvent.ROUND, Map.of("round", round,
                        "phase", "context_ready", "inputTokensBefore", before,
                        "inputTokensAfter", after, "inputBudget", allowed)));
                if (after > allowed) return capacityDecision("model_context_capacity_exceeded");
            }
        }
        if (!sink.isActive()) {
            return agentLlm.respond(messages, tools);
        }
        // grounded 答案仍只在最终证据检查后交付；general 保留普通文本增量。
        boolean streamAnswer = !requiresEvidence(context) && context.requestedMemoryContent() == null;
        return agentLlm.respondStream(messages, tools, delta -> {
            if (streamAnswer) emitDelta(sink, round, delta);
        });
    }

    /**
     * 构造带稳定容量错误码的降级决策，供无法安全发送模型输入时返回。
     * 上游由 {@code DefaultAgentRuntime.think} 调用。
     *
     * @param reason 诊断或终止原因
     * @return 模型本轮的正文、工具调用和完成状态
     */
    private static AgentDecision capacityDecision(String reason) {
        return AgentDecision.builder().degraded(true).degradeReason(reason)
                .outcome("dependency_unavailable").reasonCode(reason).toolCalls(List.of()).build();
    }

    /**
     * 确定本轮模型调用的可用上下文预算和工具声明占用。
     * 上游由 {@code DefaultAgentRuntime.rebuildContext}、{@code DefaultAgentRuntime.think} 调用。
     * 下游调用 {@code budgetManager.budget}、{@code AgentInputTokens.estimate}、{@code toolRegistry.definitionsFor}。
     *
     * @param context 可信执行上下文，包含身份、范围和本轮执行状态
     * @return 本轮模型消息可占用的令牌预算
     */
    private ContextBudget callBudget(AgentExecutionContext context) {
        ContextBudget selected = budgetManager.budget();
        int protocol = AgentInputTokens.estimate(List.of(
                AgentMessage.builder().role("system").content("").build()),
                toolRegistry.definitionsFor(context));
        return ContextBudget.builder().maxTokens(selected.getMaxTokens())
                .reservedOutputTokens(selected.getReservedOutputTokens())
                .reservedToolTokens(protocol + 128).build();
    }

    /**
     * 把模型返回的正文增量写为当前轮事件，并过滤空正文和失活接收方。
     * 上游由 {@code DefaultAgentRuntime.think} 调用。
     * 下游调用 {@code sink.emit}。
     *
     * @param sink 本轮可选事件接收方
     * @param round 当前模型决策轮次
     * @param text 待处理正文
     */
    private void emitDelta(AgentEventSink sink, int round, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("round", round);
        payload.put("text", text);
        payload.put("channel", "answer");
        sink.emit(AgentEvent.of(AgentEvent.DELTA, payload));
    }

    /**
     * 保留原决策的终止和用量信息，替换经过本轮处理的正文与工具调用。
     * 上游由 {@code DefaultAgentRuntime.reactIteration} 调用。
     *
     * @param source 待处理的来源或原始对象
     * @param content 待处理的正文内容
     * @param calls 模型本轮提出的工具调用列表
     * @return 模型本轮的正文、工具调用和完成状态
     */
    private static AgentDecision copyWith(AgentDecision source, String content, List<ToolCall> calls) {
        return AgentDecision.builder()
                .content(content)
                .toolCalls(calls == null ? List.of() : calls)
                .promptTokens(source.getPromptTokens())
                .completionTokens(source.getCompletionTokens())
                .finishReason(source.getFinishReason())
                .providerUsed(source.getProviderUsed())
                .build();
    }

    /**
     * 按可信上下文过滤模型本轮可以看到的工具名称。
     * 上游由 {@code DefaultAgentRuntime.reactIteration} 调用。
     * 下游调用 {@code toolRegistry.definitionsFor}、{@code java.util.stream.Collectors.toUnmodifiableSet}。
     *
     * @param context 可信执行上下文，包含身份、范围和本轮执行状态
     * @return 调用方使用的 {@code Set<String>}
     */
    private Set<String> offeredToolNames(AgentExecutionContext context) {
        return toolRegistry.definitionsFor(context).stream()
                .map(ToolDefinition::getName)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * 统计连续因输出长度被截断的模型轮次，在达到阈值时写入无进展终态。
     * 上游由 {@code DefaultAgentRuntime.reactIteration} 调用。
     * 下游调用 {@code ResponseAnalyzer.isLength}、{@code stop}。
     *
     * @param state 随本轮执行累积的消息、用量、证据和终态
     * @param decision 待处理的决策及其终态信息
     * @return 条件成立时为 true，其他状态按 false 处理
     */
    private boolean guardsTripped(AgentTurnState state, AgentDecision decision) {
        if (!ResponseAnalyzer.isLength(decision.getFinishReason())) {
            state.setConsecutiveLengthRounds(0);
            return false;
        }

        state.setConsecutiveLengthRounds(state.getConsecutiveLengthRounds() + 1);
        if (state.getConsecutiveLengthRounds() < settings.getMaxConsecutiveTruncatedRounds()) {
            return false;
        }

        log.warn("连续 {} 轮在输出上限处被截断，终止回合", state.getConsecutiveLengthRounds());
        String answer = decision.getContent();
        stop(state, StopReason.STUCK_REPEATED,
                answer == null || answer.isBlank() ? ResponseAnalyzer.EMPTY_RESPONSE_FALLBACK : answer);
        return true;
    }

    /**
     * 对空模型输出进行有界纠偏，达到限制后生成明确的空答终态。
     * 上游由 {@code DefaultAgentRuntime.reactIteration} 调用。
     * 下游调用 {@code stop}。
     *
     * @param state 随本轮执行累积的消息、用量、证据和终态
     * @param round 当前模型决策轮次
     * @return 继续循环或停止的指示
     */
    private IterOutcome handleEmptyContent(AgentTurnState state, int round) {
        if (state.getEmptyRetries() < settings.getMaxEmptyRetries()) {
            state.setEmptyRetries(state.getEmptyRetries() + 1);
            log.warn("第 {} 轮空回复，nudge 重试 {}/{}", round, state.getEmptyRetries(), settings.getMaxEmptyRetries());
            List<AgentMessage> nudged = new ArrayList<>(state.getTranscript());
            nudged.add(AgentMessage.builder().role("user").content(AgentPrompts.NUDGE_EMPTY_RESPONSE).build());
            state.setTranscript(nudged);
            return IterOutcome.CONTINUE;
        }
        stop(state, StopReason.EMPTY_RESPONSE, ResponseAnalyzer.EMPTY_RESPONSE_FALLBACK);
        return IterOutcome.BREAK;
    }

    /**
     * 在允许收尾的情况下依据当前消息合成答案，并保存为回合终态。
     * 上游由 {@code DefaultAgentRuntime.executeLoop}、{@code DefaultAgentRuntime.reactIteration} 调用。
     * 下游调用 {@code settings.isFinalizeOnExhaust}、{@code state.anyToolExecuted}、{@code context.isCancelled}。
     *
     * @param context 可信执行上下文，包含身份、范围和本轮执行状态
     * @param state 随本轮执行累积的消息、用量、证据和终态
     * @param sink 本轮可选事件接收方
     */
    private void finalizeTurn(AgentExecutionContext context, AgentTurnState state, AgentEventSink sink) {
        if (!settings.isFinalizeOnExhaust() || !state.anyToolExecuted() || state.getFinalAnswer() != null
                || context.isCancelled()) {
            state.setComplete(true);
            return;
        }
        sink.emit(AgentEvent.of(AgentEvent.ROUND, Map.of("round", state.getRound(), "phase", "finalize")));
        try {
            AgentDecision decision = synthesizer.synthesize(state.getTranscript(),
                    sink.isActive() && !requiresEvidence(context) ? delta -> {
                        if (delta != null && !delta.isEmpty()) {
                            Map<String, Object> payload = new LinkedHashMap<>();
                            payload.put("round", state.getRound());
                            payload.put("text", delta);
                            payload.put("channel", "answer");
                            sink.emit(AgentEvent.of(AgentEvent.DELTA, payload));
                        }
                    } : null);
            recordUsage(state, decision);
            String content = decision.getContent();
            if (decision.isDegraded()) {
                updateOutcome(state,"timeout".equals(decision.getOutcome()) ? "timeout" : "dependency_unavailable",
                        decision.getReasonCode() == null ? "model_unavailable" : decision.getReasonCode());
            }
            if (!decision.isDegraded() && content != null && !content.isBlank()) {
                captureVisibleSources(state, state.getTranscript());
                if (!requiresEvidence(context) || !state.getVisibleSources().isEmpty()
                        && ResponseAnalyzer.analyze(decision).getAction() == ResponseAnalyzer.Action.FINAL_ANSWER) {
                    state.setFinalAnswer(content);
                    state.setSynthesized(true);
                }
            }
        } catch (RuntimeException e) {
            // 兜底失败不改终止原因：这一次只是「没多拿到一句话」，原有占位语义保持不变。
            log.warn("兜底合成失败（stopReason={}）：{}", state.getStopReason(), e.getMessage());
        }
        state.setComplete(true);
    }

    // ---------------------------------------------------------------- 终止与响应

    /**
     * 判断当前回答策略是否要求答案使用已准入的知识库证据。
     * 上游由 {@code DefaultAgentRuntime.finalizeTurn}、{@code DefaultAgentRuntime.reactIteration}、{@code DefaultAgentRuntime.think} 调用。
     *
     * @param context 可信执行上下文，包含身份、范围和本轮执行状态
     * @return 条件成立时为 true，其他状态按 false 处理
     */
    private static boolean requiresEvidence(AgentExecutionContext context) {
        return !"general".equals(context.getAnswerPolicy());
    }

    /**
     * 在无法交付合格证据时产生缺证或已有依赖失败的终态。
     * 上游由 {@code DefaultAgentRuntime.reactIteration} 调用。
     * 下游调用 {@code stop}。
     *
     * @param state 随本轮执行累积的消息、用量、证据和终态
     */
    private void stopWithoutEvidence(AgentTurnState state) {
        String answer;
        if ("dependency_unavailable".equals(state.getOutcome())) {
            answer = "依赖服务暂时不可用，本轮未能获得可靠知识库证据，请稍后重试。";
        } else if ("timeout".equals(state.getOutcome())) {
            answer = "本轮检索超时，未能获得可靠知识库证据，请稍后重试。";
        } else {
            boolean hadSuccess = "success".equals(state.getOutcome());
            state.setOutcome("no_evidence");
            if (state.getReasonCode() == null || hadSuccess) {
                state.setReasonCode("grounded_evidence_required");
            }
            answer = NO_EVIDENCE_ANSWER;
        }
        stop(state, StopReason.FINAL_ANSWER, answer);
    }

    /**
     * 把停止原因和最终答案写入回合状态，由统一出口读取。
     * 上游由 {@code DefaultAgentRuntime.executeLoop}、{@code DefaultAgentRuntime.guardsTripped}、{@code DefaultAgentRuntime.handleEmptyContent} 调用。
     *
     * @param state 随本轮执行累积的消息、用量、证据和终态
     * @param reason 诊断或终止原因
     * @param answer 本次调用传入的 {@code String}
     */
    private void stop(AgentTurnState state, StopReason reason, String answer) {
        state.setStopReason(reason);
        if (answer != null) {
            state.setFinalAnswer(answer);
        }
        state.setComplete(true);
    }

    /**
     * 将降级决策映射成稳定的停止原因、问题诊断和可读终态。
     * 上游由 {@code DefaultAgentRuntime.reactIteration} 调用。
     * 下游调用 {@code agentMetrics.countLlmDegraded}、{@code updateOutcome}。
     *
     * @param state 随本轮执行累积的消息、用量、证据和终态
     * @param decision 待处理的决策及其终态信息
     * @return 继续循环或停止的指示
     */
    private IterOutcome degradedTurn(AgentTurnState state, AgentDecision decision) {
        // 降级也是「这一回合没问成模型」，与正常决策分开计数
        if (agentMetrics != null) {
            agentMetrics.countLlmDegraded();
        }

        state.setStopReason(StopReason.LLM_DEGRADED);
        state.setFinalAnswer("model_context_capacity_exceeded".equals(decision.getReasonCode())
                ? "已尝试整理上下文，但系统规则、完整问题和必需证据仍超出当前模型的单次输入容量。请缩小范围或选择上下文更大的模型。"
                : DEGRADED_ANSWER);
        state.setDegraded(true);
        state.setDegradeReason(decision.getDegradeReason());
        updateOutcome(state,"timeout".equals(decision.getOutcome()) ? "timeout" : "dependency_unavailable",
                decision.getReasonCode() == null ? "model_unavailable" : decision.getReasonCode());
        state.setProviderUsed(decision.getProviderUsed());
        state.setComplete(true);
        return IterOutcome.BREAK;
    }

    /**
     * 读取工具返回的可用证据、候选和诊断，更新后续回答与引用使用的状态。
     * 上游由 {@code DefaultAgentRuntime.reactIteration} 调用。
     * 下游调用 {@code result.isSuccess}、{@code addCandidate}、{@code updateOutcome}。
     *
     * @param state 随本轮执行累积的消息、用量、证据和终态
     * @param call 模型提出的单次工具调用及参数
     * @param result 此次工具或管线的结果
     */
    private void observeKnowledgeResult(AgentTurnState state, ToolCall call, ToolResult result) {
        if (!("knowledge_search".equals(call.getToolName())
                || "knowledge_ask".equals(call.getToolName())
                || "read_document".equals(call.getToolName()))) {
            return;
        }

        state.setKnowledgeToolExecuted(true);
        Object data = result.getData();
        String outcome = result.getOutcome();
        String reasonCode = result.getReasonCode();
        if (data instanceof KnowledgeSearchOutput output) {
            outcome = outcome == null ? output.getOutcome() : outcome;
            reasonCode = reasonCode == null ? output.getReasonCode() : reasonCode;
            if ("success".equals(outcome) && result.isSuccess() && output.getEvidences() != null) {
                for (RagEvidence evidence : output.getEvidences()) {
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
                    addCandidate(state, call, evidence, source);
                }
            }
        } else if (data instanceof KnowledgeAskOutput output) {
            outcome = outcome == null ? output.getOutcome() : outcome;
            reasonCode = reasonCode == null ? output.getReasonCode() : reasonCode;
            if ("success".equals(outcome) && result.isSuccess() && output.getSources() != null) {
                for (KnowledgeAskOutput.Source item : output.getSources()) {
                    SourceDTO source = new SourceDTO();
                    source.setDocumentId(item.getDocumentId());
                    source.setChunkId(item.getChunkId());
                    source.setKnowledgeBaseId(item.getKnowledgeBaseId());
                    source.setChunkIndex(item.getChunkIndex());
                    source.setContent(item.getContent());
                    source.setDistance(item.getDistance());
                    source.setHeadingPath(item.getHeadingPath());
                    source.setPageNo(item.getPageNo());
                    source.setSourceType(item.getSourceType());
                    addCandidate(state, call, item, source);
                }
            }
        } else if (data instanceof ReadDocumentOutput output) {
            outcome = outcome == null ? output.getOutcome() : outcome;
            if ("success".equals(outcome) && result.isSuccess() && output.getSnippets() != null) {
                for (ReadDocumentOutput.Snippet item : output.getSnippets()) {
                    SourceDTO source = new SourceDTO();
                    source.setDocumentId(item.getDocumentId());
                    source.setChunkId(item.getChunkId());
                    source.setKnowledgeBaseId(item.getKnowledgeBaseId());
                    source.setChunkIndex(item.getChunkIndex());
                    source.setContentOffset(item.getContentOffset());
                    source.setContent(item.getContent());
                    source.setHeadingPath(item.getHeadingPath());
                    source.setPageNo(item.getPageNo());
                    source.setSourceType(item.getSourceType());
                    addCandidate(state, call, item, source);
                }
            }
        }
        if (outcome == null || !List.of("success", "no_evidence", "dependency_unavailable", "timeout").contains(outcome)
                || "success".equals(outcome) && !result.isSuccess()) {
            outcome = "dependency_unavailable";
            reasonCode = result.isSuccess() ? "knowledge_tool_contract_invalid" : "knowledge_tool_failed";
        }

        updateOutcome(state, outcome, reasonCode);
    }

    /**
     * 从工具输出建立公开来源候选，并用调用与片段标识区分连续读取结果。
     * 上游由 {@code DefaultAgentRuntime.observeKnowledgeResult} 调用。
     * 下游调用 {@code serialize}。
     *
     * @param state 随本轮执行累积的消息、用量、证据和终态
     * @param call 模型提出的单次工具调用及参数
     * @param item 本次操作涉及的待存储或返回项
     * @param source 待处理的来源或原始对象
     */
    private void addCandidate(AgentTurnState state, ToolCall call, Object item, SourceDTO source) {
        if (call.getCallId() == null || source.getDocumentId() == null
                || source.getChunkId() == null || source.getContent() == null || source.getContent().isBlank()) {
            return;
        }
        source.setCitationId("S" + source.getDocumentId() + "_" + source.getChunkId()
                + (source.getContentOffset() == null ? "" : "_O" + source.getContentOffset()));
        state.getSourceCandidates().add(new AgentTurnState.SourceCandidate(
                call.getCallId(), serialize(item), source));
    }

    /**
     * 按检索与工具结果更新回合业务状态，避免较弱结果覆盖已知错误。
     * 上游由 {@code DefaultAgentRuntime.degradedTurn}、{@code DefaultAgentRuntime.finalizeTurn}、{@code DefaultAgentRuntime.observeKnowledgeResult} 调用。
     *
     * @param state 随本轮执行累积的消息、用量、证据和终态
     * @param outcome 问答或检索的业务终态
     * @param reasonCode 供调用方识别的稳定原因码
     */
    private void updateOutcome(AgentTurnState state, String outcome, String reasonCode) {
        if (outcome == null || !List.of("success", "no_evidence", "dependency_unavailable", "timeout")
                .contains(outcome)) {
            return;
        }

        String current = state.getOutcome();
        boolean currentFailure = "dependency_unavailable".equals(current) || "timeout".equals(current);
        boolean incomingFailure = "dependency_unavailable".equals(outcome) || "timeout".equals(outcome);
        if (currentFailure && !incomingFailure || "success".equals(current) && "no_evidence".equals(outcome)) {
            return;
        }

        state.setOutcome(outcome);
        state.setReasonCode(reasonCode);
    }

    /**
     * 仅将实际进入模型消息的完整正文片段纳入可交付来源。
     * 上游由 {@code DefaultAgentRuntime.finalizeTurn}、{@code DefaultAgentRuntime.reactIteration} 调用。
     * 下游调用 {@code candidate.source}、{@code candidate.callId}、{@code candidate.serialized}。
     *
     * @param state 随本轮执行累积的消息、用量、证据和终态
     * @param sent 本次调用传入的 {@code List<AgentMessage>}
     */
    private void captureVisibleSources(AgentTurnState state, List<AgentMessage> sent) {
        if (state.getVisibleSources().size() >= MAX_PUBLIC_SOURCES) {
            return;
        }
        for (AgentTurnState.SourceCandidate candidate : state.getSourceCandidates()) {
            SourceDTO source = candidate.source();
            String key = source.getCitationId();
            if (state.getVisibleSources().containsKey(key)) {
                continue;
            }
            boolean visible = sent.stream().anyMatch(message -> "tool".equals(message.getRole())
                    && candidate.callId().equals(message.getToolCallId())
                    && message.getContent() != null
                    && message.getContent().contains(candidate.serialized())
                    && message.getContent().contains(citationMapping(source)));
            if (visible) {
                state.getVisibleSources().put(key, source);
                if (state.getVisibleSources().size() >= MAX_PUBLIC_SOURCES) {
                    break;
                }
            }
        }
    }

    /**
     * 根据最终回合状态组装回答、工具轨迹和可见引用，同时向事件接收方交付一致终态。
     * 上游由 {@code DefaultAgentRuntime.run} 调用。
     * 下游调用 {@code state.isToolRenderedAnswer}、{@code java.util.stream.Collectors.toSet}、{@code CITATION_MARKER.matcher}。
     *
     * @param state 随本轮执行累积的消息、用量、证据和终态
     * @param sink 本轮可选事件接收方
     * @return 带答案、工具轨迹、来源和停止原因的回合结果
     */
    private AgentResponse toResponse(AgentTurnState state, AgentEventSink sink) {
        if (state.getFinalAnswer() != null && !state.isToolRenderedAnswer()) {
            Set<String> knownCitations = state.getVisibleSources().values().stream()
                    .map(SourceDTO::getCitationId).collect(java.util.stream.Collectors.toSet());
            String answer = CITATION_MARKER.matcher(state.getFinalAnswer())
                    .replaceAll(match -> knownCitations.contains(match.group(1))
                            ? Matcher.quoteReplacement(match.group()) : "");
            state.setFinalAnswer(answer);
        }
        AgentResponse response = AgentResponse.builder()
                .answer(state.getFinalAnswer())
                .sources(List.copyOf(state.getVisibleSources().values()))
                .outcome(state.getOutcome())
                .reasonCode(state.getReasonCode())
                .stopReason(state.getStopReason())
                .memorySaveProposal(state.getMemorySaveProposal())
                .iterations(state.getRound())
                .toolCalls(state.getToolCalls())
                .toolInvocations(state.getInvocations())
                .promptTokens(state.getPromptTokens())
                .completionTokens(state.getCompletionTokens())
                .degraded(state.isDegraded())
                .degradeReason(state.getDegradeReason())
                .providerUsed(state.getProviderUsed())
                .synthesized(state.isSynthesized())
                .build();

        if (sink.isActive()) {
            if (state.getFinalAnswer() != null) {
                Map<String, Object> answer = new LinkedHashMap<>();
                answer.put("text", state.getFinalAnswer());
                answer.put("synthesized", state.isSynthesized());
                answer.put("sources", response.getSources());
                answer.put("outcome", response.getOutcome());
                answer.put("reasonCode", response.getReasonCode());
                sink.emit(AgentEvent.of(AgentEvent.ANSWER, answer));
            }
            Map<String, Object> done = new LinkedHashMap<>();
            done.put("stopReason", state.getStopReason());
            done.put("iterations", state.getRound());
            done.put("toolCalls", state.getToolCalls());
            done.put("promptTokens", state.getPromptTokens());
            done.put("completionTokens", state.getCompletionTokens());
            done.put("synthesized", state.isSynthesized());
            done.put("sources", response.getSources());
            done.put("outcome", response.getOutcome());
            done.put("reasonCode", response.getReasonCode());
            if (state.isDegraded()) {
                done.put("degraded", true);
                done.put("degradeReason", state.getDegradeReason());
            }
            sink.emit(AgentEvent.of(AgentEvent.DONE, done));
        }
        return response;
    }

    /**
     * 为公开来源构造正文可用的引用标识提示。
     * 上游由 {@code DefaultAgentRuntime.appendToolResults}、{@code DefaultAgentRuntime.captureVisibleSources} 调用。
     *
     * @param source 待处理的来源或原始对象
     * @return 供后续处理使用的文本或标识
     */
    private static String citationMapping(SourceDTO source) {
        return "\nsource_reference=[[cite:" + source.getCitationId() + "]] documentId="
                + source.getDocumentId() + " chunkId=" + source.getChunkId()
                + (source.getContentOffset() == null ? "" : " contentOffset=" + source.getContentOffset());
    }

    // ---------------------------------------------------------------- 内部工具

    /**
     * 在工具结果追加后按当前模型预算重新构建下一轮消息。
     * 上游由 {@code DefaultAgentRuntime.reactIteration} 调用。
     * 下游调用 {@code merged.addAll}、{@code context.isMemoryReadEnabled}、{@code built.isTrimmed}。
     *
     * @param context 可信执行上下文，包含身份、范围和本轮执行状态
     * @param state 随本轮执行累积的消息、用量、证据和终态
     * @param batch 本次调用传入的 {@code List<AgentMessage>}
     * @param sink 本轮可选事件接收方
     * @return 按当前约定排列的模型消息列表
     */
    private List<AgentMessage> rebuildContext(AgentExecutionContext context, AgentTurnState state,
                                              List<AgentMessage> batch, AgentEventSink sink) {
        if (budgetManager == null) {
            List<AgentMessage> merged = new ArrayList<>(state.getTranscript());
            merged.addAll(batch);
            return merged;
        }
        state.getTurnTool().addAll(batch);
        var built = budgetManager.build(ContextBuildRequest.builder()
                .userId(context.getUserId())
                .memoryReadEnabled(context.isMemoryReadEnabled())
                .systemPrompt(context.getSystemPrompt())
                .sessionId(context.getSessionId())
                .currentQuery(context.getCurrentQuery())
                .toolMessages(state.getTurnTool())
                .budget(callBudget(context))
                .build());
        if (built.isTrimmed() || built.getOverBudgetTokens() > 0) {
            sink.emit(AgentEvent.of(AgentEvent.ROUND, Map.of("round", state.getRound(),
                    "phase", "context_ready", "inputTokensBefore", built.getTokensBefore(),
                    "inputTokensAfter", built.getTokensUsed(), "inputBudget", callBudget(context).allowedTokens())));
        }
        return built.getMessages();
    }

    /**
     * 把一轮模型决策的输入和输出用量累积到回合。
     * 上游由 {@code DefaultAgentRuntime.finalizeTurn}、{@code DefaultAgentRuntime.reactIteration} 调用。
     * 下游调用 {@code state.addUsage}、{@code agentMetrics.recordLlmTokens}。
     *
     * @param state 随本轮执行累积的消息、用量、证据和终态
     * @param decision 待处理的决策及其终态信息
     */
    private void recordUsage(AgentTurnState state, AgentDecision decision) {
        state.addUsage(decision);
        if (agentMetrics != null && decision != null
                && (decision.getPromptTokens() > 0 || decision.getCompletionTokens() > 0)) {
            agentMetrics.recordLlmTokens(decision.getPromptTokens(), decision.getCompletionTokens());
        }
    }

    /**
     * 用服务商实际输入量与本地估算更新模型令牌比例。
     * 上游由 {@code DefaultAgentRuntime.reactIteration} 调用。
     * 下游调用 {@code TokenEstimator.estimateMessages}、{@code tokenScale.observe}、{@code toolSchemaTokens}。
     *
     * @param context 可信执行上下文，包含身份、范围和本轮执行状态
     * @param sent 本次调用传入的 {@code List<AgentMessage>}
     * @param decision 待处理的决策及其终态信息
     */
    private void calibrateTokenScale(AgentExecutionContext context, List<AgentMessage> sent,
                                     AgentDecision decision) {
        if (tokenScale == null || decision == null || decision.getPromptTokens() <= 0) {
            return;
        }
        int estimated = TokenEstimator.estimateMessages(sent) + toolSchemaTokens(context);
        tokenScale.observe(estimated, decision.getPromptTokens());
    }

    /**
     * 计算当前获准工具 Schema 随模型请求发送时的令牌开销。
     * 上游由 {@code DefaultAgentRuntime.calibrateTokenScale} 调用。
     * 下游调用 {@code TokenEstimator.estimate}、{@code objectMapper.writeValueAsString}、{@code toolRegistry.definitionsFor}。
     *
     * @param context 可信执行上下文，包含身份、范围和本轮执行状态
     * @return 按当前方法口径计算或读取的数值
     */
    private int toolSchemaTokens(AgentExecutionContext context) {
        try {
            return TokenEstimator.estimate(objectMapper.writeValueAsString(
                    toolRegistry.definitionsFor(context)));
        } catch (Exception e) {
            // 序列化失败不该打断回合：退回「不计 schema」，只让系数偏保守一点。
            log.debug("工具 schema 估算失败，本次不计入：{}", e.getMessage());
            return 0;
        }
    }

    /**
     * 比较本轮单调时钟经过的时间与总运行预算。
     * 上游由 {@code DefaultAgentRuntime.executeLoop}、{@code DefaultAgentRuntime.reactIteration} 调用。
     *
     * @param startedAtNanos 以单调时钟记录的起始时间，单位纳秒
     * @return 条件成立时为 true，其他状态按 false 处理
     */
    private boolean budgetExceeded(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000 >= budgetTimeMs;
    }

    /**
     * 组装工具调用事件的轮次、名称与参数，并限制可公开的内容。
     * 上游由 {@code DefaultAgentRuntime.reactIteration} 调用。
     * 下游调用 {@code args.length}。
     *
     * @param round 当前模型决策轮次
     * @param call 模型提出的单次工具调用及参数
     * @return 当前记录中的 {@code payload}
     */
    private Map<String, Object> toolCallPayload(int round, ToolCall call) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("round", round);
        payload.put("tool", call.getToolName());
        String args = call.getArguments();
        payload.put("args", args != null && args.length() > 300 ? args.substring(0, 300) + "..." : args);
        return payload;
    }

    /**
     * 把工具执行结果映射为页面事件需要的摘要和状态。
     * 上游由 {@code DefaultAgentRuntime.reactIteration} 调用。
     * 下游调用 {@code result.isSuccess}、{@code ToolOutputBudget.truncate}、{@code summarizeData}。
     *
     * @param round 当前模型决策轮次
     * @param call 模型提出的单次工具调用及参数
     * @param result 此次工具或管线的结果
     * @return 当前记录中的 {@code payload}
     */
    private Map<String, Object> toolResultPayload(int round, ToolCall call, ToolResult result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("round", round);
        payload.put("tool", result.getToolName() != null ? result.getToolName() : call.getToolName());
        payload.put("ok", result.isSuccess());
        if (result.getOutcome() != null) {
            payload.put("outcome", result.getOutcome());
        }
        if (result.getReasonCode() != null) {
            payload.put("reasonCode", result.getReasonCode());
        }

        payload.put("summary", summarizeData(result.getData()));
        if (result.getErrorMessage() != null) {
            payload.put("error", ToolOutputBudget.truncate(result.getErrorMessage(), 300));
        }

        return payload;
    }

    /**
     * 按工具调用顺序整理执行结果，为缺失结果补齐可显式识别的失败项。
     * 上游由 {@code DefaultAgentRuntime.reactIteration} 调用。
     *
     * @param results 与调用顺序对应的工具执行结果
     * @param calls 模型本轮提出的工具调用列表
     * @return 工具执行结果列表
     */
    private List<ToolResult> normalize(List<ToolResult> results, List<ToolCall> calls) {
        List<ToolResult> normalized = new ArrayList<>(calls.size());
        for (int i = 0; i < calls.size(); i++) {
            ToolResult result = i < results.size() ? results.get(i) : null;
            if (result == null) {
                result = ToolResult.builder()
                        .toolName(calls.get(i).getToolName())
                        .callId(calls.get(i).getCallId())
                        .success(false)
                        .errorMessage("工具未返回结果")
                        .retryable(false)
                        .build();
            }
            normalized.add(result);
        }
        return normalized;
    }

    /**
     * 配对工具调用及其结果，形成回合响应中的工具摘要列表。
     * 上游由 {@code DefaultAgentRuntime.reactIteration} 调用。
     * 下游调用 {@code result.isSuccess}、{@code summarizeData}。
     *
     * @param calls 模型本轮提出的工具调用列表
     * @param results 与调用顺序对应的工具执行结果
     * @return 当前记录中的 {@code invocations}
     */
    private List<AgentResponse.ToolInvocation> toInvocations(List<ToolCall> calls, List<ToolResult> results) {        List<AgentResponse.ToolInvocation> invocations = new ArrayList<>(calls.size());
        for (int i = 0; i < calls.size(); i++) {
            ToolResult result = results.get(i);
            invocations.add(AgentResponse.ToolInvocation.builder()
                    .toolName(result.getToolName() != null ? result.getToolName() : calls.get(i).getToolName())
                    .success(result.isSuccess())
                    .outcome(result.getOutcome())
                    .reasonCode(result.getReasonCode())
                    .errorMessage(result.getErrorMessage())
                    .dataSummary(summarizeData(result.getData()))
                    .arguments(calls.get(i).getArguments())
                    .build());
        }
        return invocations;
    }

    /**
     * 把模型本轮的工具调用记为 assistant 消息，保持工具往返协议完整。
     * 上游由 {@code DefaultAgentRuntime.reactIteration} 调用。
     *
     * @param history 当前会话的历史消息
     * @param decision 待处理的决策及其终态信息
     */
    private void appendAssistantCalls(List<AgentMessage> history, AgentDecision decision) {
        history.add(AgentMessage.builder()
                .role("assistant")
                .content(decision.getContent())
                .toolCalls(decision.getToolCalls())
                .build());
    }

    /**
     * 把工具输出关联到对应调用并写入后续模型消息，同时记录来源候选。
     * 上游由 {@code DefaultAgentRuntime.reactIteration} 调用。
     * 下游调用 {@code candidate.callId}、{@code candidate.serialized}、{@code candidate.source}。
     *
     * @param state 随本轮执行累积的消息、用量、证据和终态
     * @param history 当前会话的历史消息
     * @param calls 模型本轮提出的工具调用列表
     * @param results 与调用顺序对应的工具执行结果
     */
    private void appendToolResults(AgentTurnState state, List<AgentMessage> history,
                                   List<ToolCall> calls, List<ToolResult> results) {
        for (int i = 0; i < calls.size(); i++) {
            String text = toToolResultText(results.get(i));
            String callId = calls.get(i).getCallId();
            StringBuilder citations = new StringBuilder();
            // 仅为截断后仍完整保留的片段提供标识；映射与正文作为同一条工具消息接受上下文裁剪。
            for (AgentTurnState.SourceCandidate candidate : state.getSourceCandidates()) {
                if (candidate.callId().equals(callId) && text.contains(candidate.serialized())) {
                    SourceDTO source = candidate.source();
                    citations.append(citationMapping(source));
                }
            }
            history.add(AgentMessage.builder()
                    .role("tool")
                    .content(ToolOutputBudget.truncate(citations + "\n" + text, settings.getToolOutputMaxChars()))
                    .toolCallId(calls.get(i).getCallId())
                    .toolName(calls.get(i).getToolName())
                    .build());
        }
    }

    /**
     * 将工具结果转换为模型可读的有限长度文本。
     * 上游由 {@code DefaultAgentRuntime.appendToolResults} 调用。
     * 下游调用 {@code sb.append}、{@code result.isSuccess}、{@code ToolOutputBudget.truncate}。
     *
     * @param result 此次工具或管线的结果
     * @return 供后续处理使用的文本或标识
     */
    private String toToolResultText(ToolResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("tool=").append(result.getToolName());
        sb.append(" success=").append(result.isSuccess());
        if (result.getOutcome() != null) {
            sb.append(" outcome=").append(result.getOutcome());
        }
        if (result.getReasonCode() != null) {
            sb.append(" reasonCode=").append(result.getReasonCode());
        }
        if (result.getErrorMessage() != null) {
            sb.append(" error=").append(result.getErrorMessage());
        }
        if (result.getData() != null) {
            sb.append(" data=").append(ToolOutputBudget.truncate(
                    serialize(result.getData()), settings.getToolOutputMaxChars()));
        }

        return sb.toString();
    }

    /**
     * 把工具数据裁剪为适合诊断或轨迹展示的摘要。
     * 上游由 {@code DefaultAgentRuntime.toInvocations}、{@code DefaultAgentRuntime.toolResultPayload} 调用。
     * 下游调用 {@code text.length}、{@code serialize}。
     *
     * @param data 本次调用传入的 {@code Object}
     * @return 供后续处理使用的文本或标识
     */
    private String summarizeData(Object data) {
        if (data == null) {
            return null;
        }
        String text = serialize(data);
        return text.length() > 300 ? text.substring(0, 300) + "..." : text;
    }

    /**
     * 将模型侧工具输出序列化为字符串，并在序列化失败时保留可识别文本。
     * 上游由 {@code DefaultAgentRuntime.addCandidate}、{@code DefaultAgentRuntime.reactIteration}、{@code DefaultAgentRuntime.summarizeData} 调用。
     * 下游调用 {@code objectMapper.writeValueAsString}。
     *
     * @param data 本次调用传入的 {@code Object}
     * @return 供后续处理使用的文本或标识
     */
    private String serialize(Object data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            // 极端情况兜底：序列化失败退回 toString
            return String.valueOf(data);
        }
    }
}
