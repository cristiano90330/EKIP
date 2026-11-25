/* 验证有副作用的工具执行前必须取得审批。 */

package com.pony.knowledge.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pony.knowledge.agent.model.AgentExecutionContext;
import com.pony.knowledge.agent.model.ToolCall;
import com.pony.knowledge.agent.tool.approval.ToolApprovalService;
import com.pony.knowledge.agent.tool.input.ToolInput;
import com.pony.knowledge.agent.tool.output.ToolResult;
import com.pony.knowledge.observability.RagStageTracer;
import com.pony.knowledge.service.KnowledgeBaseAccessService;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * HITL 审批门（决策文档 §4 第 4 项）的 fail-close 语义：
 * requiresApproval 的工具，未获批 → 拒绝执行且不调 tool.execute；获批 → 放行；安全工具不查审批。
 */
class ToolExecutorApprovalTest {

    static class NoArgInput implements ToolInput {
    }

    private final ToolApprovalService approval = mock(ToolApprovalService.class);
    private final AgentTool tool = mock(AgentTool.class);

    private final AgentExecutionContext context =
            AgentExecutionContext.builder().sessionId("u1:s1").build();

    /**
     * 协调 {@code tool.definition} 完成当前阶段，供后续处理使用。
     * 下游调用 {@code tool.definition}、{@code tracer.observeRoot}、{@code Validation.buildDefaultValidatorFactory}。
     *
     * @param requiresApproval 本次调用传入的 {@code boolean}
     * @return 调用方使用的 {@code ToolExecutor}
     */
    private ToolExecutor executor(boolean requiresApproval) {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.get("danger")).thenReturn(tool);
        when(tool.definition()).thenReturn(ToolDefinition.builder()
                .name("danger")
                .inputClass(NoArgInput.class)
                .parameters(Map.of("type", "object", "properties", Map.of()))
                .requiresApproval(requiresApproval)
                .build());

        RagStageTracer tracer = mock(RagStageTracer.class);
        when(tracer.observeRoot(anyString(), any())).thenAnswer(inv ->
                ((Supplier<?>) inv.getArgument(1)).get());
        // 工具级 Trace 现在带显式归属重载；两个签名都要放行，否则 mock 返回 null。
        when(tracer.observeRoot(anyString(), nullable(Long.class), any())).thenAnswer(inv ->
                ((Supplier<?>) inv.getArgument(2)).get());
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        return new ToolExecutor(registry, new ObjectMapper(), validator, tracer, approval,
                mock(KnowledgeBaseAccessService.class), Runnable::run, 1000L, 0, 0L);
    }

    /** 在该类型内部准备后续运行所需的数据。 */
    private ToolCall call() {
        return ToolCall.builder().callId("c1").toolName("danger").arguments("{}").build();
    }

    /** 准备本组用例共享的测试数据和隔离环境，通过 {@code tool.execute} 初始化依赖。 */
    @BeforeEach
    void defaultToolSucceeds() {
        when(tool.execute(any(), any()))
                .thenReturn(ToolResult.builder().success(true).build());
    }

    /** 通过 {@code approval.isApproved}、{@code r.isSuccess} 驱动该场景，核对 {@code r.isSuccess()} 为 false、{@code r.isRetryable()} 为 false。 */
    @Test
    void requiresApproval_withoutApproval_failClosed_andDoesNotExecute() {
        when(approval.isApproved("u1:s1", "danger", "{}")).thenReturn(false);

        ToolResult<?> r = executor(true).execute(call(), context);

        assertThat(r.isSuccess()).isFalse();
        assertThat(r.isRetryable()).isFalse();
        assertThat(r.getErrorMessage()).contains("需要人工审批");
        verify(tool, never()).execute(any(), any());
    }

    /** 通过 {@code approval.isApproved}、{@code r.isSuccess} 驱动该场景，核对 {@code r.isSuccess()} 为 true。 */
    @Test
    void requiresApproval_withApproval_executes() {
        when(approval.isApproved("u1:s1", "danger", "{}")).thenReturn(true);

        ToolResult<?> r = executor(true).execute(call(), context);

        assertThat(r.isSuccess()).isTrue();
        verify(tool).execute(any(), any());
    }

    /** 通过 {@code r.isSuccess}、{@code executor} 驱动该场景，核对 {@code r.isSuccess()} 为 true。 */
    @Test
    void safeTool_skipsApprovalGate_evenWhenNotApproved() {
        // 只读工具（requiresApproval=false）不查审批，直接执行
        ToolResult<?> r = executor(false).execute(call(), context);

        assertThat(r.isSuccess()).isTrue();
        verify(tool).execute(any(), any());
        verify(approval, never()).isApproved(anyString(), anyString(), anyString());
    }
}
