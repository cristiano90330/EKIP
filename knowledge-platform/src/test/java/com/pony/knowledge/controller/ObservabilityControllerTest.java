/* 验证观测控制器的状态封装、分页过滤和观测记录的账号隔离。 */

package com.pony.knowledge.controller;

import com.pony.knowledge.common.Result;
import com.pony.knowledge.dto.observability.TraceDTO;
import com.pony.knowledge.dto.observability.TraceSummaryDTO;
import com.pony.knowledge.dto.observability.ObservabilityCapabilitiesDTO;
import com.pony.knowledge.observability.InMemoryTraceStore;
import com.pony.knowledge.observability.RagTrace;
import com.pony.knowledge.observability.TraceStore;
import com.pony.knowledge.service.observability.TraceDtoConverter;
import com.pony.knowledge.service.observability.CapabilityReadinessService;
import com.pony.knowledge.service.vectorstore.VectorStore;
import com.pony.knowledge.utils.CurrentUserUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ObservabilityControllerTest {

    private static final long CURRENT_USER = 7L;

    private final CurrentUserUtil currentUserUtil = new CurrentUserUtil();

    /** 每个用例前准备夹具，调用 {@code authenticateAs}。 */
    @BeforeEach
    void authenticate() {
        authenticateAs(CURRENT_USER);
    }

    /** 回收当前用例的资源和上下文，调用 {@code SecurityContextHolder.clearContext}。 */
    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    /**
     * 在测试中处理 {@code authenticateAs} 阶段，使用 {@code SecurityContextHolder.getContext}。
     * 由 {@code authenticate}、{@code 隔离两个用户时列表与详情只返回本人记录} 调用。
     * @param userId 账号 ID
     */
    private static void authenticateAs(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null, null));
    }

    /**
     * 构造 {@code VectorStore} 供测试使用，使用 {@code vs.name}。
     * 由 {@code controller}、{@code status回显已装配引擎的name} 调用。
     * @param name 属性或资源名称
     * 返回 {@code vs}。
     */
    private static VectorStore vectorStore(String name) {
        VectorStore vs = mock(VectorStore.class);
        when(vs.name()).thenReturn(name);
        return vs;
    }

    /**
     * 构造 {@code ObservabilityController} 供测试使用，使用 {@code vectorStore}。
     * 由 {@code 单条命中返回DTO}、{@code 未命中返回404且业务码一致} 调用。
     * @param store 本用例传入的{@code store}
     */
    private ObservabilityController controller(TraceStore store) {
        return new ObservabilityController(store, new TraceDtoConverter(), vectorStore("pgvector"),
                mock(CapabilityReadinessService.class), currentUserUtil);
    }

    /**
     * status 回显的是**注入进来的那个 VectorStore 的名字**，而不是控制器里硬编码的字符串。
     *
     * <p>2026-09-26：Milvus 副本退役后运行时只剩 pgvector 一个实现，故这里用 "pgvector"。
     * 断言的意思不变 —— 一旦有人把 {@code s.setVectorStore(...)} 改成写死常量，这条就失去意义。</p>
     */
    /** 验证status回显已装配引擎的name。通过 {@code c.status} 执行本例并核对输出或记录。 */
    @Test
    void status回显已装配引擎的name() {
        ObservabilityController c = new ObservabilityController(
                mock(TraceStore.class), new TraceDtoConverter(), vectorStore("pgvector"),
                mock(CapabilityReadinessService.class), currentUserUtil);

        var r = c.status();

        assertThat(r.getCode()).isEqualTo(200);
        assertThat(r.getData().getVectorStore()).isEqualTo("pgvector");
    }

    /** 通过 {@code snapshot.setStatus} 执行本例并核对输出或记录。 */
    @Test
    void capabilitiesReturnsCachedSnapshotInsideResultEnvelope() {
        CapabilityReadinessService readiness = mock(CapabilityReadinessService.class);
        ObservabilityCapabilitiesDTO snapshot = new ObservabilityCapabilitiesDTO();
        snapshot.setStatus("degraded");
        when(readiness.snapshot()).thenReturn(snapshot);
        ObservabilityController controller = new ObservabilityController(
                mock(TraceStore.class), new TraceDtoConverter(), vectorStore("pgvector"), readiness,
                currentUserUtil);

        Result<ObservabilityCapabilitiesDTO> result = controller.capabilities();

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData().getStatus()).isEqualTo("degraded");
    }

    /** 验证单条命中返回DTO。通过 {@code RagTrace.builder} 执行本例并核对输出或记录。 */
    @Test
    void 单条命中返回DTO() {
        TraceStore store = mock(TraceStore.class);
        RagTrace t = RagTrace.builder().traceId("t1").userId(CURRENT_USER).entrypoint("chat")
                .originalQuery("q").startTime(1_000_000_000L).startTimeEpochMs(100L).endTime(1_005_000_000L).build();
        when(store.findByIdForOwner("t1", CURRENT_USER)).thenReturn(t);
        ObservabilityController c = controller(store);

        ResponseEntity<Result<TraceDTO>> r = c.getTrace("t1");

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().getData().getTraceId()).isEqualTo("t1");
    }

    /** 验证未命中返回404且业务码一致。通过 {@code store.findByIdForOwner} 执行本例并核对输出或记录。 */
    @Test
    void 未命中返回404且业务码一致() {
        TraceStore store = mock(TraceStore.class);
        when(store.findByIdForOwner("nope", CURRENT_USER)).thenReturn(null);
        ObservabilityController c = controller(store);

        ResponseEntity<Result<TraceDTO>> r = c.getTrace("nope");

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(r.getBody().getCode()).isEqualTo(404);
    }

    /** 验证列表过滤entrypoint。通过 {@code RagTrace.builder} 执行本例并核对输出或记录。 */
    @Test
    void 列表过滤entrypoint() {
        TraceStore store = mock(TraceStore.class);
        RagTrace a = RagTrace.builder().traceId("a").userId(CURRENT_USER).entrypoint("chat").originalQuery("q")
                .startTime(1_000_000_000L).startTimeEpochMs(200L).endTime(1_005_000_000L).build();
        RagTrace b = RagTrace.builder().traceId("b").userId(CURRENT_USER).entrypoint("agent_chat").originalQuery("q2")
                .startTime(1_000_000_000L).startTimeEpochMs(100L).endTime(1_005_000_000L).build();
        when(store.listRecentForOwner(CURRENT_USER)).thenReturn(List.of(a, b));
        ObservabilityController c = controller(store);

        Result<List<TraceSummaryDTO>> r = c.listTraces(50, "chat");

        assertThat(r.getCode()).isEqualTo(200);
        assertThat(r.getData()).hasSize(1);
        assertThat(r.getData().get(0).getEntrypoint()).isEqualTo("chat");
    }

    /** 验证隔离两个用户时列表与详情只返回本人记录。通过 {@code store.save} 执行本例并核对输出或记录。 */
    @Test
    void 隔离两个用户时列表与详情只返回本人记录() {
        InMemoryTraceStore store = new InMemoryTraceStore(100, currentUserUtil);
        store.save(RagTrace.builder().traceId("mine").startTimeEpochMs(200L).build(), "chat");
        authenticateAs(8L);
        store.save(RagTrace.builder().traceId("other").startTimeEpochMs(100L).build(), "chat");

        authenticateAs(CURRENT_USER);
        ObservabilityController c = controller(store);

        assertThat(c.listTraces(50, null).getData()).extracting(TraceSummaryDTO::getTraceId)
                .containsExactly("mine");
        assertThat(c.getTrace("mine").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(c.getTrace("other").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(c.getTrace("other").getBody().getCode()).isEqualTo(404);
    }

    /** 验证归属未知的记录不向普通用户回显。通过 {@code SecurityContextHolder.clearContext} 执行本例并核对输出或记录。 */
    @Test
    void 归属未知的记录不向普通用户回显() {
        InMemoryTraceStore store = new InMemoryTraceStore(100, currentUserUtil);
        SecurityContextHolder.clearContext();
        store.save(RagTrace.builder().traceId("orphan").startTimeEpochMs(300L).build(), "chat");

        authenticateAs(CURRENT_USER);
        ObservabilityController c = controller(store);

        assertThat(c.listTraces(50, null).getData()).isEmpty();
        assertThat(c.getTrace("orphan").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
