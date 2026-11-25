/* 在回合预算内准备并执行工具调用，处理并发、超时和错误回传。 */

package com.pony.knowledge.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pony.knowledge.agent.model.AgentExecutionContext;
import com.pony.knowledge.agent.model.AgentPrompts;
import com.pony.knowledge.agent.model.ToolCall;
import com.pony.knowledge.agent.tool.ToolExecutor;
import com.pony.knowledge.agent.tool.output.ToolResult;
import com.pony.knowledge.modelmanagement.ModelExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;

/**
 * Act 阶段：把一次决策的一批工具调用执行掉。
 *
 * <p>与改造前的差别只有三处，但都在「模型输出不可信」这条线上：
 * <ol>
 *   <li><b>参数先修复再执行</b>：解析失败时先做有限修复（代码块包裹、尾逗号、截断括号），
 *       修不好就生成一条带「换个方式重试」提示的失败结果，**不把坏 JSON 交给工具**；</li>
 *   <li><b>可写即屏障</b>：并行只发生在连续的可并发调用之间，写操作/审批类工具前后自成屏障；</li>
 *   <li><b>派发被拒也算结果</b>：线程池满时逐条转成失败结果，保证「工具执行永不抛」这条契约
 *       覆盖到派发阶段，而不是让已跑完的兄弟调用跟着一起丢。</li>
 * </ol>
 *
 * <p>本类只调 {@link ToolExecutor#execute}，不直接调 {@code AgentTool}——校验、审批门、
 * 超时、重试与 trace 传播都留在 ToolExecutor 里（那部分本轮冻结，未改动）。
 */
public class ToolActExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolActExecutor.class);

    private final ToolExecutor toolExecutor;

    private final ObjectMapper objectMapper;

    private final Executor dispatchExecutor;

    private final ToolConcurrencyPolicy concurrencyPolicy;

    private final boolean parallelTools;

    /** 一次决策的工具执行并发上限。 */
    private final Semaphore permits;

    /**
     * 用注入依赖和构造参数建立此实例执行时使用的状态。
     *
     * @param toolExecutor 本次调用传入的 {@code ToolExecutor}
     * @param objectMapper 负责 JSON 协议编码和解码的映射器
     * @param dispatchExecutor 本次调用传入的 {@code Executor}
     * @param concurrencyPolicy 本次调用传入的 {@code ToolConcurrencyPolicy}
     * @param parallelTools 本次调用传入的 {@code boolean}
     * @param maxConcurrentTools 本次调用传入的 {@code int}
     */
    public ToolActExecutor(ToolExecutor toolExecutor, ObjectMapper objectMapper, Executor dispatchExecutor,
                           ToolConcurrencyPolicy concurrencyPolicy, boolean parallelTools, int maxConcurrentTools) {
        this.toolExecutor = toolExecutor;
        this.objectMapper = objectMapper;
        this.dispatchExecutor = dispatchExecutor;
        this.concurrencyPolicy = concurrencyPolicy;
        this.parallelTools = parallelTools;
        this.permits = new Semaphore(Math.max(1, maxConcurrentTools));
    }

    /**
     * 按顺序准备工具调用，执行允许并发的组，并保持结果与调用列表一一对应。
     * 上游由 {@code DefaultAgentRuntime.reactIteration} 调用。
     * 下游调用 {@code Collections.nCopies}、{@code concurrencyPolicy.canRunConcurrently}、{@code toolExecutor.execute}。
     *
     * @param context 可信执行上下文，包含身份、范围和本轮执行状态
     * @param calls 模型本轮提出的工具调用列表
     * @return 工具执行结果列表
     */
    public List<ToolResult> execute(AgentExecutionContext context, List<ToolCall> calls) {
        List<ToolResult> results = new ArrayList<>(Collections.nCopies(calls.size(), (ToolResult) null));
        List<Integer> executable = new ArrayList<>(calls.size());

        // 1. 参数预检：修不好的一律不执行，直接落失败结果
        for (int i = 0; i < calls.size(); i++) {
            ToolResult failure = prepare(calls.get(i));
            if (failure != null) {
                results.set(i, failure);
            } else {
                executable.add(i);
            }
        }

        // 2. 按「连续可并发段」执行，屏障单独跑
        int cursor = 0;
        while (cursor < executable.size()) {
            int first = executable.get(cursor);
            if (!concurrencyPolicy.canRunConcurrently(calls.get(first).getToolName())) {
                ToolResult result = toolExecutor.execute(calls.get(first), context);
                results.set(first, result);
                cursor++;
                if (result != null && result.getMemorySaveProposal() != null) {
                    for (int index : executable.subList(cursor, executable.size())) {
                        ToolResult skipped = failure(calls.get(index), "本轮已提出记忆审批，后续工具未执行。");
                        skipped.setReasonCode("skipped_for_memory_approval");
                        results.set(index, skipped);
                    }
                    break;
                }
                continue;
            }
            int end = cursor + 1;
            while (end < executable.size()
                    && executable.get(end) == executable.get(end - 1) + 1
                    && concurrencyPolicy.canRunConcurrently(calls.get(executable.get(end)).getToolName())) {
                end++;
            }
            runBatch(context, calls, executable.subList(cursor, end), results);
            cursor = end;
        }
        return results;
    }

    /**
     * 校验或修复调用参数，并决定工具能否加入并发执行组。
     * 上游由 {@code ToolActExecutor.execute} 调用。
     * 下游调用 {@code ToolArgRepair.repair}、{@code parses}、{@code failure}。
     *
     * @param call 模型提出的单次工具调用及参数
     * @return 带载荷或可识别错误状态的工具结果
     */
    private ToolResult prepare(ToolCall call) {
        String raw = call.getArguments();
        if (raw == null || raw.isBlank()) {
            // 无参工具是合法情况：空参数归一成空对象，与 OpenAI 的「arguments 必须存在」兼容。
            call.setArguments("{}");
            return null;
        }
        if (parses(raw)) {
            return null;
        }

        String repaired = ToolArgRepair.repair(raw);
        if (repaired != null && parses(repaired)) {
            log.info("工具 {} 参数经修复后可解析（callId={}）", call.getToolName(), call.getCallId());
            call.setArguments(repaired);
            return null;
        }

        log.warn("工具 {} 参数无法解析且修复失败（callId={}）", call.getToolName(), call.getCallId());
        return failure(call, "工具参数不是合法 JSON，已拒绝执行。" + AgentPrompts.TOOL_ARG_RETRY_HINT);
    }

    /**
     * 检查修复后的工具参数是否能按 JSON 输入读取。
     * 上游由 {@code ToolActExecutor.prepare} 调用。
     * 下游调用 {@code objectMapper.readTree}。
     *
     * @param json 本次调用传入的 {@code String}
     * @return 条件成立时为 true，其他状态按 false 处理
     */
    private boolean parses(String json) {
        try {
            objectMapper.readTree(json);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 在当前回合的并发上限内运行一组调用，将结果放回各自位置。
     * 上游由 {@code ToolActExecutor.execute} 调用。
     * 下游调用 {@code toolExecutor.execute}、{@code CompletableFuture.supplyAsync}、{@code ModelExecutionContext.wrapSupplier}。
     *
     * @param context 可信执行上下文，包含身份、范围和本轮执行状态
     * @param calls 模型本轮提出的工具调用列表
     * @param batch 本次调用传入的 {@code List<Integer>}
     * @param results 与调用顺序对应的工具执行结果
     */
    private void runBatch(AgentExecutionContext context, List<ToolCall> calls, List<Integer> batch,
                          List<ToolResult> results) {
        if (!parallelTools || batch.size() <= 1) {
            for (int index : batch) {
                results.set(index, toolExecutor.execute(calls.get(index), context));
            }
            return;
        }
        List<CompletableFuture<ToolResult>> futures = new ArrayList<>(batch.size());
        for (int index : batch) {
            ToolCall call = calls.get(index);
            try {
                futures.add(CompletableFuture.supplyAsync(
                        ModelExecutionContext.wrapSupplier(() -> executeBounded(call, context)), dispatchExecutor));
            } catch (RejectedExecutionException e) {
                futures.add(CompletableFuture.completedFuture(
                        failure(call, "工具执行池饱和，本工具未被派发执行: " + e.getMessage())));
            }
        }
        for (int i = 0; i < batch.size(); i++) {
            results.set(batch.get(i), futures.get(i).join());
        }
    }

    /**
     * 在并发执行边界内运行单次工具调用，异常时交付可识别的工具失败。
     * 上游由 {@code ToolActExecutor.runBatch} 调用。
     * 下游调用 {@code permits.acquire}、{@code Thread.currentThread}、{@code toolExecutor.execute}。
     *
     * @param call 模型提出的单次工具调用及参数
     * @param context 可信执行上下文，包含身份、范围和本轮执行状态
     * @return 带载荷或可识别错误状态的工具结果
     */
    private ToolResult executeBounded(ToolCall call, AgentExecutionContext context) {
        try {
            permits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failure(call, "工具执行被取消（并发信号量中断）");
        }
        try {
            return toolExecutor.execute(call, context);
        } finally {
            permits.release();
        }
    }

    /**
     * 保留调用名称和 ID，构造用于当前轮结果排序的错误记录。
     * 上游由 {@code ToolActExecutor.execute}、{@code ToolActExecutor.executeBounded}、{@code ToolActExecutor.prepare} 调用。
     *
     * @param call 模型提出的单次工具调用及参数
     * @param message 待处理的消息或对外提示
     * @return 带载荷或可识别错误状态的工具结果
     */
    private ToolResult failure(ToolCall call, String message) {
        return ToolResult.builder()
                .toolName(call.getToolName())
                .callId(call.getCallId())
                .success(false)
                .errorMessage(message)
                .retryable(false)
                .build();
    }
}
