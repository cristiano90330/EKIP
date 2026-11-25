/* 解析并校验工具参数，复核权限，按可重试策略运行工具并收敛结果。 */

package com.pony.knowledge.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pony.knowledge.agent.model.AgentExecutionContext;
import com.pony.knowledge.agent.model.ToolCall;
import com.pony.knowledge.agent.tool.approval.ToolApprovalService;
import com.pony.knowledge.agent.tool.memory.MemorySaveTool;
import com.pony.knowledge.agent.tool.input.ToolInput;
import com.pony.knowledge.agent.tool.output.ToolResult;
import com.pony.knowledge.exception.ForbiddenException;
import com.pony.knowledge.exception.RagQueryFailureException;
import com.pony.knowledge.modelmanagement.ModelExecutionContext;
import com.pony.knowledge.observability.RagStageTracer;
import com.pony.knowledge.observability.RagTrace;
import com.pony.knowledge.observability.RagTraceContext;
import com.pony.knowledge.service.KnowledgeBaseAccessService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * ToolExecutor：Agent Runtime 与具体 Tool 之间的唯一执行入口。
 * 统一完成：查工具 → JSON→ToolInput → Bean Validation → 审批门 → 超时 + 退避重试 → 执行。
 *
 * 错误分类（决定是否重试）用白名单，与权限的 default-deny 同一思路：
 *   只有下列瞬时故障可重试——执行超时、IO/超时类异常、Spring HTTP 客户端异常（含 5xx/429）、
 *   工具执行池饱和拒绝；其余一律不重试。
 *   若把任意异常都当作可重试，工具里的代码 bug（如 NPE）会被伪装成不稳定，
 *   白等一次重试、白占线程，还会带偏排查方向。
 *   解析失败、校验失败、ForbiddenException、业务失败（retryable=false）同样不重试。
 * 重试前按 tool.retry-backoff-ms 起指数退避：下游已经慢到超时，立即再打一次只会加重负载，
 *   而此刻上一次的孤儿执行可能还在跑。
 * 永不抛：工具执行异常一律转失败 ToolResult，Tool A 失败不影响 Tool B。
 * 超时执行在 agentToolExecutor 池，业务 Trace（RagTraceContext）手动从调用线程传播到池线程。
 */
@Component
public class ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutor.class);

    private final ToolRegistry registry;

    private final ObjectMapper objectMapper;

    private final Validator validator;

    private final RagStageTracer ragStageTracer;

    /** HITL 审批门：requiresApproval 的工具未获批 → fail-close。 */
    private final ToolApprovalService approvalService;

    /**
     * 每次工具调用前重解析可读知识库范围（BD-08）。
     * 回合开始时的范围只代表「那一刻」；撤权后仍按旧范围执行等于撤权不生效。
     */
    private final KnowledgeBaseAccessService knowledgeBaseAccessService;

    private final Executor toolExecutorPool;

    private final long timeoutMs;

    private final int maxRetries;

    private final long retryBackoffMs;

    /**
     * 用注入依赖和构造参数建立此实例执行时使用的状态。
     *
     * @param registry 本次调用传入的 {@code ToolRegistry}
     * @param objectMapper 负责 JSON 协议编码和解码的映射器
     * @param validator 本次调用传入的 {@code Validator}
     * @param ragStageTracer 本次调用传入的 {@code RagStageTracer}
     * @param approvalService 本次调用传入的 {@code ToolApprovalService}
     * @param knowledgeBaseAccessService 本次调用传入的 {@code KnowledgeBaseAccessService}
     * @param toolExecutorPool 本次调用传入的 {@code Executor}
     * @param timeoutMs 本次调用传入的 {@code long}
     * @param maxRetries 本次调用传入的 {@code int}
     * @param retryBackoffMs 本次调用传入的 {@code long}
     */
    public ToolExecutor(
            ToolRegistry registry,
            ObjectMapper objectMapper,
            Validator validator,
            RagStageTracer ragStageTracer,
            ToolApprovalService approvalService,
            KnowledgeBaseAccessService knowledgeBaseAccessService,
            @Qualifier("agentToolExecutor") Executor toolExecutorPool,
            @Value("${tool.timeout-ms:5000}") long timeoutMs,
            @Value("${tool.max-retries:1}") int maxRetries,
            @Value("${tool.retry-backoff-ms:500}") long retryBackoffMs) {
        this.registry = registry;
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.ragStageTracer = ragStageTracer;
        this.approvalService = approvalService;
        this.knowledgeBaseAccessService = knowledgeBaseAccessService;
        this.toolExecutorPool = toolExecutorPool;
        this.timeoutMs = timeoutMs;
        this.maxRetries = maxRetries;
        this.retryBackoffMs = retryBackoffMs;
    }

    /**
     * 按已登记的工具定义解析和校验参数，复核授权并按受控重试策略返回工具结果。
     * 上游由 {@code ToolActExecutor.execute}、{@code ToolActExecutor.executeBounded}、{@code ToolActExecutor.runBatch} 调用。
     * 下游调用 {@code context.allowsTool}、{@code ragStageTracer.observeRoot}、{@code objectMapper.readValue}。
     *
     * @param call 模型提出的单次工具调用及参数
     * @param context 可信执行上下文，包含身份、范围和本轮执行状态
     * @return 带载荷或可识别错误状态的工具结果
     */
    public ToolResult execute(ToolCall call, AgentExecutionContext context) {
        revalidateReadableScope(context);
        // 配置白名单是执行边界：模型伪造一个未暴露的工具名也不能越过它。
        if (!context.allowsTool(call.getToolName())) {
            return failure(call,
                    "工具 " + call.getToolName() + " 不在当前 Agent 允许的工具范围内，或已被记忆策略禁用", false);
        }

        AgentTool tool;
        try {
            tool = registry.get(call.getToolName());
        } catch (IllegalArgumentException e) {
            return failure(call, e.getMessage(), false);
        }

        // 工具在独立线程池执行，线程上没有认证上下文；归属沿用本次回合已确定的用户，
        // 否则工具级 Trace 会变成无主记录，用户在自己的观测页看不到它。
        return ragStageTracer.observeRoot("tool." + call.getToolName(), context.getUserId(), () -> {
            ToolInput input;
            try {
                input = (ToolInput) objectMapper.readValue(
                        call.getArguments(), tool.definition().getInputClass());
            } catch (Exception e) {
                return failure(call, "参数解析失败: " + e.getMessage(), false);
            }

            Set<ConstraintViolation<ToolInput>> violations = validator.validate(input);
            if (!violations.isEmpty()) {
                String message = violations.stream()
                        .map(v -> v.getPropertyPath() + " " + v.getMessage())
                        .collect(Collectors.joining("; "));
                return failure(call, "参数校验失败: " + message, false);
            }

            // MemorySaveTool only prepares an intent. The decision endpoint owns the actual write.
            if (tool.definition().isRequiresApproval()
                    && !(tool instanceof MemorySaveTool)
                    && !approvalService.isApproved(context.getSessionId(), call.getToolName(), call.getArguments())) {
                return failure(call,
                        "工具 " + call.getToolName() + " 需要人工审批，当前无有效审批（fail-close，拒绝执行）", false);
            }

            // 重试循环：仅白名单内的瞬时失败重试，重试前退避；其余立即返回
            int attempt = 0;
            ToolResult result;
            while (true) {
                attempt++;
                result = runWithTimeout(tool, input, context, call);
                if (result.isSuccess() || !result.isRetryable() || attempt > maxRetries) {
                    break;
                }
                if (!backoff(attempt)) {
                    // 退避期间被中断：不再重试，但保留「可重试」标记——没重试成功不等于不该重试
                    result.setRetryable(true);
                    break;
                }
            }
            result.setCallId(call.getCallId());
            return result;
        });
    }

    /**
     * 在可重试的调用之间等待指定退避时长；被中断时恢复线程中断标记并停止重试。
     * 上游由 {@code ToolExecutor.execute} 调用。
     * 下游调用 {@code Thread.sleep}、{@code Thread.currentThread}。
     *
     * @param retryIndex 当前重试序号，用于计算本次退避
     * @return 条件成立时为 true，其他状态按 false 处理
     */
    private boolean backoff(int retryIndex) {
        if (retryBackoffMs <= 0) {
            return true;
        }
        long sleepMs = retryBackoffMs * (1L << Math.min(6, retryIndex - 1));
        try {
            Thread.sleep(sleepMs);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 在工具线程池运行单次调用，超时或取消时中止工作并转成统一结果。
     * 上游由 {@code ToolExecutor.execute} 调用。
     * 下游调用 {@code ModelExecutionContext.wrap}、{@code toolExecutorPool.execute}、{@code task.cancel}。
     *
     * @param tool 注册表中的工具实例
     * @param input 本次操作的结构化输入
     * @param context 可信执行上下文，包含身份、范围和本轮执行状态
     * @param call 模型提出的单次工具调用及参数
     * @return 带载荷或可识别错误状态的工具结果
     */
    private ToolResult runWithTimeout(AgentTool tool, ToolInput input, AgentExecutionContext context, ToolCall call) {
        RagTrace parent = RagTraceContext.get();
        FutureTask<ToolResult> task =
                new FutureTask<>(ModelExecutionContext.wrap(() -> runTool(tool, input, context, call, parent)));
        try {
            toolExecutorPool.execute(task);
        } catch (RejectedExecutionException e) {
            // 派发阶段就被拒 = 池饱和，属瞬时负载问题：可重试（但先退避）
            return failure(call, "工具执行池饱和，任务被拒绝: " + e.getMessage(), true);
        }
        try {
            return task.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            task.cancel(true);
            ToolResult result = failure(call, "工具执行超时（> " + timeoutMs + "ms），已中断执行线程", true);
            result.setOutcome("timeout");
            result.setReasonCode("tool_timeout");
            return result;
        } catch (InterruptedException e) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            return failure(call, "工具执行被中断", false);
        } catch (CancellationException e) {
            return failure(call, "工具执行已取消", false);
        } catch (Exception e) {
            return failure(call, "工具执行失败: " + e.getMessage(), isTransient(e));
        }
    }

    /**
     * 按执行时的用户授权复核上下文范围，阻止失权后的访问。
     * 上游由 {@code ToolExecutor.execute} 调用。
     * 下游调用 {@code knowledgeBaseAccessService.resolveReadableIds}、{@code retained.retainAll}。
     *
     * @param context 可信执行上下文，包含身份、范围和本轮执行状态
     */
    private void revalidateReadableScope(AgentExecutionContext context) {
        if (context == null || context.getUserId() == null) {
            return;
        }
        Set<Long> current;
        try {
            current = knowledgeBaseAccessService.resolveReadableIds(context.getUserId());
        } catch (RuntimeException e) {
            log.warn("工具调用前重校验可读范围失败，沿用本轮范围：userId={} 原因={}",
                    context.getUserId(), e.getMessage());
            return;
        }
        if (current == null) {
            return;
        }
        synchronized (context) {
            Set<Long> granted = context.getAccessibleKnowledgeBaseIds();
            if (granted == null) {
                return;
            }
            Set<Long> retained = new LinkedHashSet<>(granted);
            retained.retainAll(current);
            if (!retained.equals(granted)) {
                log.info("工具调用前重校验：可读知识库 {} 个 → {} 个，本回合后续工具调用按新范围执行",
                        granted.size(), retained.size());
                context.setAccessibleKnowledgeBaseIds(retained);
            }
        }
    }

    /**
     * 执行已经校验的工具输入，统一绑定调用标识、观测与异常结果。
     * 上游由 {@code ToolExecutor.runWithTimeout} 调用。
     * 下游调用 {@code tool.execute}、{@code RagTraceContext.clear}、{@code failure}。
     *
     * @param tool 注册表中的工具实例
     * @param input 本次操作的结构化输入
     * @param context 可信执行上下文，包含身份、范围和本轮执行状态
     * @param call 模型提出的单次工具调用及参数
     * @param parentTrace 本次调用传入的 {@code RagTrace}
     * @return 带载荷或可识别错误状态的工具结果
     */
    private ToolResult runTool(AgentTool tool, ToolInput input, AgentExecutionContext context, ToolCall call,
            RagTrace parentTrace) {
        // 业务 Trace 上下文从调用线程传播到池线程（observationTaskDecorator 只传播 Micrometer 观测）
        RagTrace prev = RagTraceContext.get();
        if (parentTrace != null) {
            RagTraceContext.set(parentTrace);
        }
        try {
            try {
                ToolResult result = tool.execute(input, context);
                result.setCallId(call.getCallId());
                return result;
            } catch (ForbiddenException e) {
                return failure(call, "权限不足: " + e.getMessage(), false);
            } catch (RagQueryFailureException e) {
                ToolResult result = failure(call, e.getMessage(), false);
                result.setOutcome(e.getOutcome());
                result.setReasonCode(e.getReasonCode());
                return result;
            } catch (Exception e) {
                return failure(call, "工具执行异常: " + e.getMessage(), isTransient(e));
            }
        } finally {
            if (prev != null) {
                RagTraceContext.set(prev);
            } else {
                RagTraceContext.clear();
            }
        }
    }

    /**
     * 判断异常是否可进入工具重试，由执行循环决定继续次数。
     * 上游由 {@code ToolExecutor.runTool}、{@code ToolExecutor.runWithTimeout} 调用。
     *
     * @param error 用于分类与诊断的异常
     * @return 条件成立时为 true，其他状态按 false 处理
     */
    private static boolean isTransient(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof InterruptedIOException
                    || current instanceof IOException
                    || current instanceof RestClientException
                    || current instanceof RejectedExecutionException) {
                return true;
            }
            if (current == current.getCause()) {
                break;      // 自引用 cause，防死循环
            }
        }
        return false;
    }

    /**
     * 带原工具调用标识构建失败结果，并标明是否可以重试。
     * 上游由 {@code ToolExecutor.execute}、{@code ToolExecutor.runTool}、{@code ToolExecutor.runWithTimeout} 调用。
     *
     * @param call 模型提出的单次工具调用及参数
     * @param message 待处理的消息或对外提示
     * @param retryable 本次调用传入的 {@code boolean}
     * @return 带载荷或可识别错误状态的工具结果
     */
    private ToolResult failure(ToolCall call, String message, boolean retryable) {
        return ToolResult.builder()
                .toolName(call.getToolName())
                .callId(call.getCallId())
                .success(false)
                .errorMessage(message)
                .retryable(retryable)
                .build();
    }
}
