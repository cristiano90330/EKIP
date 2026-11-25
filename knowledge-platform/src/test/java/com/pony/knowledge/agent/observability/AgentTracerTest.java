/* 验证回合追踪的上下文恢复、错误状态和终态记录。 */

package com.pony.knowledge.agent.observability;

import com.pony.knowledge.observability.RagTrace;
import com.pony.knowledge.observability.RagTraceContext;
import com.pony.knowledge.observability.TraceStore;
import com.pony.knowledge.agent.model.AgentResponse;
import com.pony.knowledge.agent.model.StopReason;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.eq;

/**
 * 3.8e 观测验收：AgentTracer.observeRoot 包住整段请求后，RagTraceContext 里建立了业务 Trace，
 * 其 traceId 与当前 span traceId 一致（HTTP 入口 → Agent → Tool → RAG → Memory 共用一个 traceId 的锚点）。
 */
class AgentTracerTest {

    /**
     * 协调 {@code ObservationRegistry.create} 完成当前阶段，供后续处理使用。
     * @param traceStore 本次调用传入的 {@code TraceStore}
     * @return 调用方使用的 {@code AgentTracer}
     */
    private AgentTracer tracer(TraceStore traceStore) {
        return new AgentTracer(ObservationRegistry.create(), new SimpleMeterRegistry(),
                new AgentMetrics(new SimpleMeterRegistry()), null, traceStore);
    }

    /** 在用例结束后释放该用例创建的资源。 */
    @AfterEach
    void clear() {
        RagTraceContext.clear();
    }

    /** 通过 {@code ObservationRegistry.create}、{@code RagTraceContext.get} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void observeRoot_establishesBusinessTraceAndRestoresOuter() {
        AgentTracer tracer = new AgentTracer(
                ObservationRegistry.create(), new SimpleMeterRegistry(),
                new AgentMetrics(new SimpleMeterRegistry()), null, mock(TraceStore.class));

        // 外层已有 trace（模拟调用方/入口）
        RagTraceContext.set(RagTrace.builder().traceId("outer").build());

        String status = tracer.observeRoot("agent.chat", () -> {
            RagTrace inner = RagTraceContext.get();
            return inner.getTraceId();
        });

        // 本类没有真实 tracer → currentTraceId()="unknown"，即业务 Trace 使用当前 span 的 traceId
        assertEquals("unknown", status);
        // observeRoot 结束恢复了外层 trace
        assertEquals("outer", RagTraceContext.get().getTraceId());
    }

    /** 通过 {@code ObservationRegistry.create}、{@code tracer.currentTraceId} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void currentTraceId_withoutTracer_returnsFallback() {
        AgentTracer tracer = new AgentTracer(
                ObservationRegistry.create(), new SimpleMeterRegistry(),
                new AgentMetrics(new SimpleMeterRegistry()), null, mock(TraceStore.class));
        assertEquals("unknown", tracer.currentTraceId());
        assertNull(RagTraceContext.get());
    }

    /** 通过 {@code tracer.observeRoot}、{@code ArgumentCaptor.forClass} 驱动该场景，实际结果与固定预期一致。 */
    @Test
    void observeRoot_recordsOriginalQuestionAndTerminalOutcome() {
        TraceStore traceStore = mock(TraceStore.class);
        AgentTracer tracer = tracer(traceStore);
        AgentResponse response = AgentResponse.builder()
                .answer("answer")
                .outcome("success")
                .stopReason(StopReason.FINAL_ANSWER)
                .build();

        assertEquals(response, tracer.observeRoot("agent.chat", "original question", () -> response));

        ArgumentCaptor<RagTrace> trace = ArgumentCaptor.forClass(RagTrace.class);
        verify(traceStore).save(trace.capture(), eq("agent.chat"));
        assertEquals("original question", trace.getValue().getOriginalQuery());
        assertEquals("success", trace.getValue().getOutcome());
        assertNull(trace.getValue().getReasonCode());
    }

    /** 通过 {@code tracer.observeRoot}、{@code ArgumentCaptor.forClass} 驱动该场景，实际结果与固定预期一致。 */
    @Test
    void observeRoot_marksCancellationEvenWhenKnowledgeToolPreviouslySucceeded() {
        TraceStore traceStore = mock(TraceStore.class);
        AgentTracer tracer = tracer(traceStore);
        AgentResponse response = AgentResponse.builder()
                .outcome("success")
                .reasonCode("retrieval_completed")
                .stopReason(StopReason.CANCELLED)
                .build();

        tracer.observeRoot("agent.chat", "question", () -> response);

        ArgumentCaptor<RagTrace> trace = ArgumentCaptor.forClass(RagTrace.class);
        verify(traceStore).save(trace.capture(), eq("agent.chat"));
        assertEquals("cancelled", trace.getValue().getOutcome());
        assertEquals("agent_cancelled", trace.getValue().getReasonCode());
    }

    /** 通过 {@code tracer.observeRoot}、{@code ArgumentCaptor.forClass} 驱动该场景，抛出 {@code IllegalStateException}，实际结果与固定预期一致。 */
    @Test
    void observeRoot_recordsDependencyFailureBeforeRethrowing() {
        TraceStore traceStore = mock(TraceStore.class);
        AgentTracer tracer = tracer(traceStore);

        assertThrows(IllegalStateException.class, () -> tracer.observeRoot("agent.chat", "question", () -> {
            throw new IllegalStateException("private failure");
        }));

        ArgumentCaptor<RagTrace> trace = ArgumentCaptor.forClass(RagTrace.class);
        verify(traceStore).save(trace.capture(), eq("agent.chat"));
        assertEquals("dependency_unavailable", trace.getValue().getOutcome());
        assertEquals("agent_request_failed", trace.getValue().getReasonCode());
    }
}
