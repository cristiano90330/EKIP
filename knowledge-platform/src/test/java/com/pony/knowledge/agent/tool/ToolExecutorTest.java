/* 验证工具参数校验、权限、并发、超时重试和失败收敛。 */

package com.pony.knowledge.agent.tool;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pony.knowledge.agent.model.AgentExecutionContext;
import com.pony.knowledge.agent.model.ToolCall;
import com.pony.knowledge.agent.tool.AgentTool;
import com.pony.knowledge.agent.tool.ToolDefinition;
import com.pony.knowledge.agent.tool.input.ToolInput;
import com.pony.knowledge.agent.tool.knowledge.KnowledgeSearchTool;
import com.pony.knowledge.exception.ForbiddenException;
import com.pony.knowledge.exception.RagQueryFailureException;
import com.pony.knowledge.agent.tool.output.KnowledgeSearchOutput;
import com.pony.knowledge.agent.tool.output.ToolResult;
import com.pony.knowledge.observability.RagMetrics;
import com.pony.knowledge.observability.RagPerformanceBudget;
import com.pony.knowledge.observability.RagStageTracer;
import com.pony.knowledge.observability.RagTraceRecorder;
import com.pony.knowledge.observability.TraceStore;
import com.pony.knowledge.service.KnowledgeBaseAccessService;
import com.pony.knowledge.service.rag.PythonRagPipeline;
import com.pony.knowledge.service.rag.model.RagEvidence;
import com.pony.knowledge.service.rag.model.RagQueryOutcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.http.HttpStatus;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.pony.knowledge.agent.tool.approval.ToolApprovalService;

/**
 * 3.7c 验收：ToolExecutor 统一执行 Tool——
 * JSON arguments → ToolInput、Bean Validation、callId 对应、Micrometer、Trace、委托 PythonRagPipeline。
 */
class ToolExecutorTest {

    /**
     * 在该类型内部准备后续运行所需的数据。
     *
     * @param documentId 目标文档 ID
     * @param chunkIndex 本次调用传入的 {@code int}
     * @param content 待处理的正文内容
     * @return 调用方使用的 {@code RagEvidence}
     */
    private RagEvidence evidence(Long documentId, int chunkIndex, String content) {
        return RagEvidence.builder()
                .chunkId(1L)
                .documentId(documentId)
                .knowledgeBaseId(1L)
                .chunkIndex(chunkIndex)
                .content(content)
                .score(0.05)
                .build();
    }

    /**
     * 协调 {@code pipeline.retrieveAdmitted} 完成当前阶段，供后续处理使用。
     * 下游调用 {@code pipeline.retrieveAdmitted}、{@code anyInt}、{@code evidence}。
     *
     * @param originalQuery 本次调用传入的 {@code String}
     * @param content 待处理的正文内容
     * @return 当前记录中的 {@code pipeline}
     */
    private PythonRagPipeline pipelineReturning(String originalQuery, String content) {
        PythonRagPipeline pipeline = mock(PythonRagPipeline.class);
        when(pipeline.retrieveAdmitted(any(), any(), any(), anyInt())).thenReturn(RagQueryOutcome.builder()
                .originalQuery(originalQuery)
                .rewrittenQuery(originalQuery)
                .outcome("success")
                .sources(List.of(evidence(10L, 0, "raw candidate")))
                .evidences(content == null ? List.of() : List.of(evidence(10L, 0, content)))
                .build());
        return pipeline;
    }

    /**
     * 协调 {@code newExecutor} 完成当前阶段，供后续处理使用。
     * 下游调用 {@code newExecutor}。
     *
     * @param registry 本次调用传入的 {@code SimpleMeterRegistry}
     * @param recorder 本次调用传入的 {@code RagTraceRecorder}
     * @param pipeline 本次调用传入的 {@code PythonRagPipeline}
     * @return 调用方使用的 {@code ToolExecutor}
     */
    private ToolExecutor newExecutor(SimpleMeterRegistry registry, RagTraceRecorder recorder,
            PythonRagPipeline pipeline) {
        return newExecutor(registry, recorder, pipeline, new ObjectMapper());
    }

    /**
     * 协调 {@code ObservationRegistry.create} 完成当前阶段，供后续处理使用。
     * 下游调用 {@code ObservationRegistry.create}、{@code Validation.buildDefaultValidatorFactory}、{@code access.resolveReadableIds}。
     *
     * @param registry 本次调用传入的 {@code SimpleMeterRegistry}
     * @param recorder 本次调用传入的 {@code RagTraceRecorder}
     * @param pipeline 本次调用传入的 {@code PythonRagPipeline}
     * @param objectMapper 负责 JSON 协议编码和解码的映射器
     * @return 调用方使用的 {@code ToolExecutor}
     */
    private ToolExecutor newExecutor(SimpleMeterRegistry registry, RagTraceRecorder recorder,
            PythonRagPipeline pipeline, ObjectMapper objectMapper) {
        RagMetrics metrics = new RagMetrics(registry, "hybrid");
        RagPerformanceBudget budget = new RagPerformanceBudget(metrics, 1000L, 1000L, 1000L, 1000L, 1000L, 10L, 0.01);
        RagStageTracer stageTracer = new RagStageTracer(
                ObservationRegistry.create(), registry, metrics, budget, recorder, null, 2.0, mock(TraceStore.class));

        KnowledgeSearchTool tool = new KnowledgeSearchTool(pipeline);
        ToolRegistry registry_tools = new ToolRegistry(List.of(tool));
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        KnowledgeBaseAccessService access = mock(KnowledgeBaseAccessService.class);
        when(access.resolveReadableIds(any())).thenReturn(null);
        return new ToolExecutor(registry_tools, objectMapper, validator, stageTracer, mock(ToolApprovalService.class),
                access, Runnable::run, 1000L, 1, 0L);
    }

    /**
     * 协调 {@code executorFor} 完成当前阶段，供后续处理使用。
     * 下游调用 {@code executorFor}。
     *
     * @param tool 注册表中的工具实例
     * @param pool 本次调用传入的 {@code Executor}
     * @param timeoutMs 本次调用传入的 {@code long}
     * @param maxRetries 本次调用传入的 {@code int}
     * @return 调用方使用的 {@code ToolExecutor}
     */
    private ToolExecutor executorFor(AgentTool tool, Executor pool, long timeoutMs, int maxRetries) {
        return executorFor(tool, pool, timeoutMs, maxRetries, 0L);
    }

    /**
     * 协调 {@code access.resolveReadableIds} 完成当前阶段，供后续处理使用。
     * 下游调用 {@code access.resolveReadableIds}、{@code executorFor}。
     *
     * @param tool 注册表中的工具实例
     * @param pool 本次调用传入的 {@code Executor}
     * @param timeoutMs 本次调用传入的 {@code long}
     * @param maxRetries 本次调用传入的 {@code int}
     * @param retryBackoffMs 本次调用传入的 {@code long}
     * @return 调用方使用的 {@code ToolExecutor}
     */
    private ToolExecutor executorFor(AgentTool tool, Executor pool, long timeoutMs, int maxRetries,
            long retryBackoffMs) {
        // 本组用例不覆盖撤权路径：返回 null 表示「本次不干预范围」，避免 Mockito 的空集合
        // 默认值把可读范围清空（那会让所有依赖知识库的断言失效）。
        KnowledgeBaseAccessService access = mock(KnowledgeBaseAccessService.class);
        when(access.resolveReadableIds(any())).thenReturn(null);
        return executorFor(tool, pool, timeoutMs, maxRetries, retryBackoffMs, access);
    }

    /**
     * 协调 {@code ObservationRegistry.create} 完成当前阶段，供后续处理使用。
     * 下游调用 {@code ObservationRegistry.create}、{@code Validation.buildDefaultValidatorFactory}。
     *
     * @param tool 注册表中的工具实例
     * @param pool 本次调用传入的 {@code Executor}
     * @param timeoutMs 本次调用传入的 {@code long}
     * @param maxRetries 本次调用传入的 {@code int}
     * @param retryBackoffMs 本次调用传入的 {@code long}
     * @param access 本次调用传入的 {@code KnowledgeBaseAccessService}
     * @return 调用方使用的 {@code ToolExecutor}
     */
    private ToolExecutor executorFor(AgentTool tool, Executor pool, long timeoutMs, int maxRetries,
            long retryBackoffMs, KnowledgeBaseAccessService access) {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RagMetrics metrics = new RagMetrics(registry, "hybrid");
        RagPerformanceBudget budget = new RagPerformanceBudget(metrics, 1000L, 1000L, 1000L, 1000L, 1000L, 10L, 0.01);
        RagStageTracer stageTracer = new RagStageTracer(
                ObservationRegistry.create(), registry, metrics, budget, new RagTraceRecorder(), null, 2.0, mock(TraceStore.class));
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        return new ToolExecutor(new ToolRegistry(List.of(tool)), new ObjectMapper(), validator, stageTracer,
                mock(ToolApprovalService.class), access, pool, timeoutMs, maxRetries, retryBackoffMs);
    }

    /** 提供公开给模型或页面目录的工具定义。 */
    private ToolDefinition flakyDef() {
        return ToolDefinition.builder().name("flaky").inputClass(SimpleInput.class)
                .parameters(Map.of("type", "object", "properties", Map.of())).build();
    }

    /**
     * 在该类型内部准备后续运行所需的数据。
     * @param toolName 工具在注册表中的公开名称
     * @return 调用方使用的 {@code ToolCall}
     */
    private ToolCall call(String toolName) {
        return ToolCall.builder().callId("c").toolName(toolName).arguments("{}").build();
    }

    /** 在该类型内部准备后续运行所需的数据。 */
    private AgentExecutionContext ctx() {
        return AgentExecutionContext.builder().userId(1L).build();
    }

    /** 无字段、无约束的工具入参（供 flaky 工具反序列化）。 */
    public static class SimpleInput implements ToolInput {
    }

    /** 通过 {@code tool.definition}、{@code tool.execute} 驱动用例，布尔状态符合断言，实际结果与固定预期一致。 */
    @Test
    void 工具调用前按当前可读范围收缩() {
        AtomicReference<Set<Long>> seen = new AtomicReference<>();
        AgentTool tool = mock(AgentTool.class);
        when(tool.definition()).thenReturn(ToolDefinition.builder().name("probe")
                .inputClass(SimpleInput.class)
                .parameters(Map.of("type", "object", "properties", Map.of())).build());
        when(tool.execute(any(), any())).thenAnswer(invocation -> {
            AgentExecutionContext passed = invocation.getArgument(1);
            seen.set(passed.getAccessibleKnowledgeBaseIds());
            return ToolResult.builder().toolName("probe").success(true).build();
        });
        KnowledgeBaseAccessService access = mock(KnowledgeBaseAccessService.class);
        when(access.resolveReadableIds(1L)).thenReturn(Set.of(2L));
        ToolExecutor executor = executorFor(tool, Runnable::run, 1000, 0, 0L, access);
        AgentExecutionContext context = AgentExecutionContext.builder().userId(1L)
                .accessibleKnowledgeBaseIds(new LinkedHashSet<>(Set.of(1L, 2L))).build();

        ToolResult result = executor.execute(call("probe"), context);

        assertTrue(result.isSuccess());
        assertEquals(Set.of(2L), seen.get(), "撤权后本次工具调用只能看到仍可读的知识库");
    }

    /** 通过 {@code tool.definition}、{@code tool.execute} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void 权限查询失败时沿用本轮范围() {
        AtomicReference<Set<Long>> seen = new AtomicReference<>();
        AgentTool tool = mock(AgentTool.class);
        when(tool.definition()).thenReturn(ToolDefinition.builder().name("probe")
                .inputClass(SimpleInput.class)
                .parameters(Map.of("type", "object", "properties", Map.of())).build());
        when(tool.execute(any(), any())).thenAnswer(invocation -> {
            seen.set(((AgentExecutionContext) invocation.getArgument(1)).getAccessibleKnowledgeBaseIds());
            return ToolResult.builder().toolName("probe").success(true).build();
        });
        KnowledgeBaseAccessService access = mock(KnowledgeBaseAccessService.class);
        when(access.resolveReadableIds(1L)).thenThrow(new IllegalStateException("数据库不可用"));
        ToolExecutor executor = executorFor(tool, Runnable::run, 1000, 0, 0L, access);
        AgentExecutionContext context = AgentExecutionContext.builder().userId(1L)
                .accessibleKnowledgeBaseIds(new LinkedHashSet<>(Set.of(1L, 2L))).build();

        executor.execute(call("probe"), context);

        assertEquals(Set.of(1L, 2L), seen.get());
    }

    /** 通过 {@code tool.definition}、{@code executor.execute} 驱动用例，跳过受保护的调用，布尔状态符合断言。 */
    @Test
    void forgedMemoryCallsAreRejectedBeforeToolExecution() {
        for (String name : List.of("memory_search", "memory_save")) {
            AgentTool tool = mock(AgentTool.class);
            when(tool.definition()).thenReturn(ToolDefinition.builder().name(name).inputClass(SimpleInput.class).build());
            ToolExecutor executor = executorFor(tool, Runnable::run, 1000, 0);
            AgentExecutionContext context = AgentExecutionContext.builder().userId(1L)
                    .memoryReadEnabled(false).memoryWriteEnabled(false).build();
            assertFalse(executor.execute(call(name), context).isSuccess());
            verify(tool, never()).execute(any(), any());
        }
    }

    /** 通过 {@code executor.execute}、{@code registry.get} 驱动用例，布尔状态符合断言，实际结果与固定预期一致。 */
    @Test
    void execute_deserializesArguments_runsTool_returnsCallIdEvidenceTraceMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RagTraceRecorder recorder = new RagTraceRecorder();
        PythonRagPipeline pipeline = pipelineReturning("Redis连接池怎么配置", "Redis 连接池配置说明");

        ToolExecutor executor = newExecutor(registry, recorder, pipeline);

        ToolResult result = executor.execute(
                ToolCall.builder()
                        .callId("call_123")
                        .toolName("knowledge_search")
                        .arguments("{\"query\":\"Redis连接池怎么配置\",\"topK\":5}")
                        .build(),
                AgentExecutionContext.builder()
                        .userId(1L)
                        .accessibleKnowledgeBaseIds(Set.of(1L))
                        .build());

        assertTrue(result.isSuccess());
        assertEquals("call_123", result.getCallId(), "ToolResult 与 callId 对应");
        KnowledgeSearchOutput output = (KnowledgeSearchOutput) result.getData();
        assertNotNull(output);
        assertEquals(1, output.getEvidences().size());
        assertEquals("Redis 连接池配置说明", output.getEvidences().get(0).getContent());

        // 权限从 Context 传递到 Python 管线（不来自 LLM arguments）
        verify(pipeline).retrieveAdmitted(eq(List.of(1L)), eq("Redis连接池怎么配置"), eq(List.of()), eq(5));

        // 工具调用接入 Micrometer
        assertEquals(1.0, registry.get("rag.requests.total")
                .tag("endpoint", "tool.knowledge_search").counter().count());
    }

    /** 通过 {@code executor.execute} 驱动用例，跳过受保护的调用，布尔状态符合断言。 */
    @Test
    void execute_blankQuery_failsValidation_andSkipsTool() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RagTraceRecorder recorder = new RagTraceRecorder();
        PythonRagPipeline pipeline = mock(PythonRagPipeline.class);
        ToolExecutor executor = newExecutor(registry, recorder, pipeline);

        ToolResult result = executor.execute(
                ToolCall.builder()
                        .callId("call_2")
                        .toolName("knowledge_search")
                        .arguments("{\"query\":\"\"}")
                        .build(),
                AgentExecutionContext.builder().userId(1L).accessibleKnowledgeBaseIds(Set.of(1L)).build());

        assertFalse(result.isSuccess());
        assertEquals("call_2", result.getCallId());
        assertTrue(result.getErrorMessage().contains("校验失败"));
        verify(pipeline, never()).retrieveAdmitted(any(), any(), any(), anyInt());
    }

    /** 通过 {@code executor.execute} 驱动用例，跳过受保护的调用，布尔状态符合断言。 */
    @Test
    void execute_negativeTopK_failsValidation_andSkipsRetrieval() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RagTraceRecorder recorder = new RagTraceRecorder();
        PythonRagPipeline pipeline = mock(PythonRagPipeline.class);
        ToolExecutor executor = newExecutor(registry, recorder, pipeline);

        ToolResult result = executor.execute(
                ToolCall.builder()
                        .callId("call_neg")
                        .toolName("knowledge_search")
                        .arguments("{\"query\":\"住宿标准\",\"topK\":-1}")
                        .build(),
                AgentExecutionContext.builder().userId(1L).accessibleKnowledgeBaseIds(Set.of(1L)).build());

        assertFalse(result.isSuccess(), "topK=-1 不该成功");
        assertTrue(result.getErrorMessage().contains("校验失败"), "应报校验失败，实际=" + result.getErrorMessage());
        verify(pipeline, never()).retrieveAdmitted(any(), any(), any(), anyInt());
    }

    /** 通过 {@code executor.execute} 驱动用例，布尔状态符合断言，实际结果与固定预期一致。 */
    @Test
    void execute_malformedJson_returnsParseError() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RagTraceRecorder recorder = new RagTraceRecorder();
        PythonRagPipeline pipeline = mock(PythonRagPipeline.class);
        ToolExecutor executor = newExecutor(registry, recorder, pipeline);

        ToolResult result = executor.execute(
                ToolCall.builder()
                        .callId("call_3")
                        .toolName("knowledge_search")
                        .arguments("not-json")
                        .build(),
                AgentExecutionContext.builder().userId(1L).accessibleKnowledgeBaseIds(Set.of(1L)).build());

        assertFalse(result.isSuccess());
        assertEquals("call_3", result.getCallId());
        assertTrue(result.getErrorMessage().contains("解析失败"));
    }

    /** 通过 {@code executor.execute} 驱动用例，布尔状态符合断言。 */
    @Test
    void execute_ignoresUnknownFields_permissionComesFromContext() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RagTraceRecorder recorder = new RagTraceRecorder();
        PythonRagPipeline pipeline = pipelineReturning("机密工资数据", "机密工资数据片段");
        // 生产用 Spring Boot ObjectMapper（默认忽略未知字段）；测试对齐
        ObjectMapper objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        ToolExecutor executor = newExecutor(registry, recorder, pipeline, objectMapper);

        ToolResult result = executor.execute(
                ToolCall.builder()
                        .callId("call_5")
                        .toolName("knowledge_search")
                        .arguments("{\"query\":\"机密工资数据\",\"topK\":5,\"knowledgeBaseId\":999}")
                        .build(),
                AgentExecutionContext.builder()
                        .userId(1L)
                        .accessibleKnowledgeBaseIds(Set.of(1L))
                        .build());

        assertTrue(result.isSuccess());
        // LLM 塞的 knowledgeBaseId=999 是未知字段被忽略；真正过滤用 context 的 accessibleKnowledgeBaseIds=[1]
        verify(pipeline).retrieveAdmitted(eq(List.of(1L)), eq("机密工资数据"), eq(List.of()), eq(5));
    }

    /** 从模型参数传入额外知识库列表，确认真实检索范围仅来自可信上下文。 */
    @Test
    void execute_ignoresPluralKnowledgeBaseIds_scopeFromContextOnly() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RagTraceRecorder recorder = new RagTraceRecorder();
        PythonRagPipeline pipeline = pipelineReturning("机密工资数据", "机密工资数据片段");
        ObjectMapper objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        ToolExecutor executor = newExecutor(registry, recorder, pipeline, objectMapper);

        ToolResult result = executor.execute(
                ToolCall.builder()
                        .callId("call_c")
                        .toolName("knowledge_search")
                        .arguments("{\"query\":\"机密工资数据\",\"topK\":5,\"knowledgeBaseIds\":[999]}")
                        .build(),
                AgentExecutionContext.builder()
                        .userId(1L)
                        .accessibleKnowledgeBaseIds(Set.of(1L))
                        .build());

        assertTrue(result.isSuccess());
        assertFalse(result.getData().toString().contains("999"), "越权 knowledgeBaseIds 不应进入检索");
        // 真正 scope = context 的 [1]，而非 LLM 的 [999]
        verify(pipeline).retrieveAdmitted(eq(List.of(1L)), eq("机密工资数据"), eq(List.of()), eq(5));
    }

    /** 通过 {@code slow.definition}、{@code slow.execute} 驱动用例，布尔状态符合断言。 */
    @Test
    void execute_timeout_returnsFailureRetryable() {
        AgentTool slow = mock(AgentTool.class);
        when(slow.definition()).thenReturn(flakyDef());
        when(slow.execute(any(), any())).thenAnswer(inv -> {
            Thread.sleep(500);
            return ToolResult.builder().success(true).build();
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            ToolExecutor executor = executorFor(slow, pool, 50, 0);
            ToolResult result = executor.execute(call("flaky"), ctx());
            assertFalse(result.isSuccess());
            assertTrue(result.isRetryable(), "超时应标记可重试");
            assertTrue(result.getErrorMessage().contains("超时"));
        } finally {
            pool.shutdownNow();
        }
    }

    /** 让工具超过执行期限，验证超时结果与底层工作线程的中断同时生效。 */
    @Test
    void execute_timeout_interruptsUnderlyingTask_noOrphanExecution() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AgentTool slow = new AgentTool() {
            /**
             * 协调 {@code flakyDef} 完成当前阶段，供后续处理使用。
             * @return 公开给模型或页面目录的工具定义
             */
            @Override
            public ToolDefinition definition() {
                return flakyDef();
            }

            /**
             * 执行当前方法封装的业务操作。
             * 下游调用 {@code entered.countDown}、{@code Thread.sleep}、{@code interrupted.countDown}。
             *
             * @param input 本次操作的结构化输入
             * @param context 可信执行上下文，包含身份、范围和本轮执行状态
             * @return 带载荷或可识别错误状态的工具结果
             */
            @Override
            public ToolResult execute(ToolInput input, AgentExecutionContext context) {
                entered.countDown();
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException e) {
                    interrupted.countDown();      // 工具正确响应中断
                    Thread.currentThread().interrupt();
                }
                return ToolResult.builder().success(true).build();
            }
        };
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            ToolExecutor executor = executorFor(slow, pool, 100, 0);
            ToolResult result = executor.execute(call("flaky"), ctx());

            assertTrue(entered.await(2, TimeUnit.SECONDS), "工具应已开始执行");
            assertFalse(result.isSuccess());
            assertTrue(result.isRetryable());
            assertTrue(interrupted.await(2, TimeUnit.SECONDS),
                    "超时后底层任务应收到中断信号（不再有孤儿执行）");
        } finally {
            pool.shutdownNow();
        }
    }

    /** 通过 {@code tool.definition} 驱动用例，跳过受保护的调用，布尔状态符合断言。 */
    @Test
    void execute_poolRejection_isRetryableFailure() {
        AgentTool tool = mock(AgentTool.class);
        when(tool.definition()).thenReturn(flakyDef());
        Executor rejectingPool = task -> {
            throw new RejectedExecutionException("pool exhausted");
        };

        ToolResult result = executorFor(tool, rejectingPool, 1000, 0)
                .execute(call("flaky"), ctx());

        assertFalse(result.isSuccess());
        assertTrue(result.isRetryable(), "池饱和是瞬时故障，应可重试");
        assertEquals("c", result.getCallId());
        verify(tool, never()).execute(any(), any());
    }

    /** 通过 {@code flaky.definition}、{@code flaky.execute} 驱动用例，布尔状态符合断言。 */
    @Test
    void execute_retriesTransientFailure_thenSucceeds() {
        AgentTool flaky = mock(AgentTool.class);
        when(flaky.definition()).thenReturn(flakyDef());
        when(flaky.execute(any(), any()))
                .thenThrow(new ResourceAccessException("I/O error on POST to /embedding"))
                .thenReturn(ToolResult.builder().success(true).build());

        ToolExecutor executor = executorFor(flaky, Runnable::run, 1000, 1);
        ToolResult result = executor.execute(call("flaky"), ctx());

        assertTrue(result.isSuccess());
        verify(flaky, times(2)).execute(any(), any());
    }

    /** 通过 {@code buggy.definition}、{@code buggy.execute} 驱动用例，布尔状态符合断言。 */
    @Test
    void execute_nonWhitelistedException_doesNotRetry() {
        AgentTool buggy = mock(AgentTool.class);
        when(buggy.definition()).thenReturn(flakyDef());
        when(buggy.execute(any(), any())).thenThrow(new NullPointerException("字段没判空"));

        ToolExecutor executor = executorFor(buggy, Runnable::run, 1000, 2);
        ToolResult result = executor.execute(call("flaky"), ctx());

        assertFalse(result.isSuccess());
        assertFalse(result.isRetryable(), "代码 bug 不是瞬时故障，不该标记可重试");
        verify(buggy, times(1)).execute(any(), any());
    }

    /** 模拟检索或生成故障，核对工具结果保留原业务终态与原因码。 */
    @Test
    void execute_preservesRagFailureOutcomeAndReasonCode() {
        AgentTool tool = mock(AgentTool.class);
        when(tool.definition()).thenReturn(flakyDef());
        when(tool.execute(any(), any())).thenThrow(new RagQueryFailureException(
                HttpStatus.SERVICE_UNAVAILABLE, "dependency_unavailable", "reranker_unavailable",
                "rerank", "request-1", "重排服务不可用", null));

        ToolResult result = executorFor(tool, Runnable::run, 1000, 0)
                .execute(call("flaky"), ctx());

        assertFalse(result.isSuccess());
        assertEquals("dependency_unavailable", result.getOutcome());
        assertEquals("reranker_unavailable", result.getReasonCode());
    }

    /** 通过 {@code flaky.definition}、{@code flaky.execute} 驱动用例，布尔状态符合断言。 */
    @Test
    void execute_transientFailure_waitsBackoffBeforeRetry() {
        AgentTool flaky = mock(AgentTool.class);
        when(flaky.definition()).thenReturn(flakyDef());
        when(flaky.execute(any(), any()))
                .thenThrow(new ResourceAccessException("upstream 503"))
                .thenReturn(ToolResult.builder().success(true).build());

        ToolExecutor executor = executorFor(flaky, Runnable::run, 1000, 1, 120L);
        long start = System.nanoTime();
        ToolResult result = executor.execute(call("flaky"), ctx());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(result.isSuccess());
        verify(flaky, times(2)).execute(any(), any());
        assertTrue(elapsedMs >= 100,
                "第一次重试前应睡约 120ms（退避），实际耗时 " + elapsedMs + "ms");
    }

    /** 通过 {@code tool.definition}、{@code tool.execute} 驱动用例，布尔状态符合断言。 */
    @Test
    void execute_doesNotRetryPermissionError() {
        AgentTool tool = mock(AgentTool.class);
        when(tool.definition()).thenReturn(flakyDef());
        when(tool.execute(any(), any())).thenThrow(new ForbiddenException("无权访问"));

        ToolExecutor executor = executorFor(tool, Runnable::run, 1000, 2);
        ToolResult result = executor.execute(call("flaky"), ctx());

        assertFalse(result.isSuccess());
        assertFalse(result.isRetryable());
        assertTrue(result.getErrorMessage().contains("权限不足"));
        verify(tool, times(1)).execute(any(), any());
    }

    /** 通过 {@code tool.definition}、{@code tool.execute} 驱动用例，布尔状态符合断言。 */
    @Test
    void execute_doesNotRetryBusinessFailure() {
        AgentTool tool = mock(AgentTool.class);
        when(tool.definition()).thenReturn(flakyDef());
        when(tool.execute(any(), any()))
                .thenReturn(ToolResult.builder().success(false).errorMessage("业务失败").build());

        ToolExecutor executor = executorFor(tool, Runnable::run, 1000, 2);
        ToolResult result = executor.execute(call("flaky"), ctx());

        assertFalse(result.isSuccess());
        assertFalse(result.isRetryable());
        verify(tool, times(1)).execute(any(), any());
    }

    /** 通过 {@code tool.definition}、{@code executor.execute} 驱动用例，跳过受保护的调用，布尔状态符合断言。 */
    @Test
    void execute_toolOutsideAgentWhitelist_isRejectedWithoutExecution() {
        AgentTool tool = mock(AgentTool.class);
        when(tool.definition()).thenReturn(flakyDef());
        ToolExecutor executor = executorFor(tool, Runnable::run, 1000, 0);

        ToolResult result = executor.execute(call("flaky"),
                AgentExecutionContext.builder().userId(1L)
                        .allowedToolNames(Set.of("knowledge_search")).build());

        assertFalse(result.isSuccess());
        assertFalse(result.isRetryable());
        assertTrue(result.getErrorMessage().contains("不在当前 Agent 允许的工具范围"),
                "应明确拒绝白名单外的调用，实际=" + result.getErrorMessage());
        verify(tool, never()).execute(any(), any());
    }

    /** 通过 {@code tool.definition}、{@code tool.execute} 驱动用例，布尔状态符合断言。 */
    @Test
    void execute_toolInsideAgentWhitelist_runs() {
        AgentTool tool = mock(AgentTool.class);
        when(tool.definition()).thenReturn(flakyDef());
        when(tool.execute(any(), any())).thenReturn(ToolResult.builder().success(true).build());
        ToolExecutor executor = executorFor(tool, Runnable::run, 1000, 0);

        ToolResult result = executor.execute(call("flaky"),
                AgentExecutionContext.builder().userId(1L)
                        .allowedToolNames(Set.of("flaky")).build());

        assertTrue(result.isSuccess());
        verify(tool).execute(any(), any());
    }

    /** 通过 {@code executor.execute} 驱动用例，布尔状态符合断言，实际结果与固定预期一致。 */
    @Test
    void execute_unknownTool_returnsFailureWithCallId() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RagTraceRecorder recorder = new RagTraceRecorder();
        PythonRagPipeline pipeline = mock(PythonRagPipeline.class);
        ToolExecutor executor = newExecutor(registry, recorder, pipeline);

        ToolResult result = executor.execute(
                ToolCall.builder()
                        .callId("call_4")
                        .toolName("nope")
                        .arguments("{}")
                        .build(),
                AgentExecutionContext.builder().userId(1L).build());

        assertFalse(result.isSuccess());
        assertEquals("call_4", result.getCallId());
        assertTrue(result.getErrorMessage().contains("Unknown tool"));
    }
}
