/* 验证网关决策请求映射、SSE 终态、失败分类和模型快照传递。 */

package com.pony.knowledge.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pony.knowledge.agent.model.AgentDecision;
import com.pony.knowledge.agent.model.AgentMessage;
import com.pony.knowledge.agent.model.AgentPrompts;
import com.pony.knowledge.agent.model.ToolCall;
import com.pony.knowledge.agent.tool.ToolDefinition;
import com.pony.knowledge.resilience.AiAvailabilityGuard;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 3.1 P1-1：{@link GatewayAgentLlm} 的跨进程契约测试。
 *
 * <p>🔴 全部走**真 HTTP**（JDK 自带的 {@code HttpServer}，随机端口），不是 mock 一个客户端方法。
 * 理由就是本章 P1-5 挖出来的那条教训：当时 25 个测试类全绿，却因为全都 mock 了 {@code AgentLlm}，
 * 「生产从来没把 system 提示发给模型」这件事没人抓得到——**断言要打在出了进程的报文上**。
 * 这里断的是 AI 服务真正收到的那串 JSON，以及本类真正解析出来的那个对象。
 */
class GatewayAgentLlmTest {

    private HttpServer server;

    private final AtomicReference<String> receivedBody = new AtomicReference<>();

    private final AtomicReference<String> receivedRequestId = new AtomicReference<>();

    private volatile String responseBody = "{}";

    private volatile String streamBody = "";

    private volatile int responseStatus = 200;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 准备本组用例共享的测试数据和隔离环境，通过 {@code HttpServer.create}、{@code server.createContext} 初始化依赖。 */
    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/agent/decide", exchange -> {
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            receivedRequestId.set(exchange.getRequestHeaders().getFirst("X-Request-ID"));
            byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.createContext("/agent/decide/stream", exchange -> {
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = streamBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
    }

    /** 在用例结束后释放该用例创建的资源。 */
    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    /**
     * 协调 {@code AiAvailabilityGuard.disabled} 完成当前阶段，供后续处理使用。
     * @param baseUrl 本次调用传入的 {@code String}
     * @return 调用方使用的 {@code GatewayAgentLlm}
     */
    private GatewayAgentLlm llmAt(String baseUrl) {
        return llmAt(baseUrl, AiAvailabilityGuard.disabled(new SimpleMeterRegistry()));
    }

    /**
     * 在该类型内部准备后续运行所需的数据。
     * @param baseUrl 本次调用传入的 {@code String}
     * @param guard 本次调用传入的 {@code AiAvailabilityGuard}
     * @return 调用方使用的 {@code GatewayAgentLlm}
     */
    private GatewayAgentLlm llmAt(String baseUrl, AiAvailabilityGuard guard) {
        return new GatewayAgentLlm(new AgentGatewayClient(AgentGatewayProperties.builder()
                .baseUrl(baseUrl)
                .connectTimeoutMs(2000)
                .readTimeoutMs(5000)
                .build(), objectMapper, "test-internal-token", null, guard));
    }

    /**
     * 协调 {@code llmAt} 完成当前阶段，供后续处理使用。
     * @return 调用方使用的 {@code GatewayAgentLlm}
     */
    private GatewayAgentLlm llm() {
        return llmAt("http://127.0.0.1:" + server.getAddress().getPort());
    }

    /**
     * 协调 {@code objectMapper.readTree} 完成当前阶段，供后续处理使用。
     * @return 调用方使用的 {@code JsonNode}
     */
    private JsonNode sent() throws IOException {
        return objectMapper.readTree(receivedBody.get());
    }

    /**
     * 提供指定角色和内容的模型消息。
     * @param role 本次调用传入的 {@code String}
     * @param content 待处理的正文内容
     * @return 指定角色和内容的模型消息
     */
    private AgentMessage msg(String role, String content) {
        return AgentMessage.builder().role(role).content(content).build();
    }

    // ---------- 出口：AI 服务收到什么 ----------

    /** 给同步和流式入口同一显式模型快照，核对两种请求都携带该模型。 */
    @Test
    void explicitModelSnapshotReachesBothDecisionTransports() throws IOException {
        responseBody = "{\"content\":\"ok\",\"tool_calls\":[]}";
        streamBody = "event: final\ndata: {\"content\":\"ok\",\"tool_calls\":[]}\n\n";
        Map<String,Object> snapshot = Map.of("schemaVersion",1,"budgetMs",30000,
                "bindings",Map.of("agent",Map.of("id","chosen-model","credentialVersion",4)),"indexScopes",List.of());
        try (com.pony.knowledge.modelmanagement.ModelExecutionContext ignored =
                     com.pony.knowledge.modelmanagement.ModelExecutionContext.open(snapshot,71L)) {
            llm().respond(List.of(msg("user","q")),List.of());
            assertEquals("chosen-model",sent().path("modelExecution").path("bindings").path("agent").path("id").asText());
            llm().respondStream(List.of(msg("user","q")),List.of(),text -> { });
            assertEquals(4,sent().path("modelExecution").path("bindings").path("agent").path("credentialVersion").asInt());
        }
        assertNull(com.pony.knowledge.modelmanagement.ModelExecutionContext.current());
    }

    /** 通过 {@code messages.get}、{@code m.get} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void callerSuppliedSystem_reachesTheWire_exactlyOnce() throws IOException {
        responseBody = "{\"content\":\"答案\",\"tool_calls\":[],\"prompt_tokens\":1,\"completion_tokens\":1}";

        llm().respond(List.of(msg("system", "宿主装配的提示"), msg("user", "Q")), List.of());

        JsonNode messages = sent().get("messages");
        assertEquals("system", messages.get(0).get("role").asText());
        assertEquals("宿主装配的提示", messages.get(0).get("content").asText());
        assertEquals(1, stream(messages).filter(m -> "system".equals(m.get("role").asText())).count(),
                "system 只应有一条：兜底注入的守卫在治理层档同样生效");
    }

    /** 通过 {@code first.get} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void noSystemFromCaller_injectsCanonicalPrompt() throws IOException {
        responseBody = "{\"content\":\"答案\"}";

        llm().respond(List.of(msg("user", "差旅标准是什么")), List.of());

        JsonNode first = sent().get("messages").get(0);
        assertEquals("system", first.get("role").asText());
        assertEquals(AgentPrompts.KNOWLEDGE_ASSISTANT_SYSTEM, first.get("content").asText(),
                "兜底提示必须与直连档同一份常量（共用 mapper 的结果）");
    }

    /** 通过 {@code sent.get} 驱动用例，布尔状态符合断言，实际结果与固定预期一致。 */
    @Test
    void toolRoundTripMessages_areOpenAiShaped() throws IOException {
        responseBody = "{\"content\":\"答案\"}";
        List<AgentMessage> messages = List.of(
                msg("system", "sys"),
                msg("user", "年假几天"),
                AgentMessage.builder().role("assistant").content(null)
                        .toolCalls(List.of(ToolCall.builder().callId("c1").toolName("knowledge_search")
                                .arguments("{\"query\":\"年假\"}").build()))
                        .build(),
                AgentMessage.builder().role("tool").content("tool=knowledge_search success=true")
                        .toolCallId("c1").build());

        llm().respond(messages, List.of());

        JsonNode sent = sent().get("messages");
        // assistant：content 为 null 归一成空串（与直连档一致），tool_calls 挂在同一条上
        assertEquals("", sent.get(2).get("content").asText(), "null content 必须归一成空串，不能缺键");
        assertEquals("c1", sent.get(2).get("tool_calls").get(0).get("id").asText());
        assertEquals("{\"query\":\"年假\"}",
                sent.get(2).get("tool_calls").get(0).get("function").get("arguments").asText());
        // tool：只有 role=tool 带 tool_call_id
        assertEquals("c1", sent.get(3).get("tool_call_id").asText());
        assertFalse(sent.get(1).has("tool_call_id"), "user 消息不该带 tool_call_id");
    }

    /** 通过 {@code tool.get} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void toolSchemas_areSentVerbatim() throws IOException {
        responseBody = "{\"content\":\"答案\"}";
        ToolDefinition def = ToolDefinition.builder()
                .name("knowledge_search")
                .description("检索知识库")
                .parameters(Map.of("type", "object", "properties",
                        Map.of("query", Map.of("type", "string"))))
                .build();

        llm().respond(List.of(msg("user", "q")), List.of(def));

        JsonNode tool = sent().get("tools").get(0);
        assertEquals("function", tool.get("type").asText());
        assertEquals("knowledge_search", tool.get("function").get("name").asText());
        assertEquals("检索知识库", tool.get("function").get("description").asText());
        assertEquals("object", tool.get("function").get("parameters").get("type").asText());
    }

    /** 通过 {@code receivedRequestId.get} 驱动用例，布尔状态符合断言。 */
    @Test
    void sendsRequestIdHeader() {
        responseBody = "{\"content\":\"答案\"}";

        llm().respond(List.of(msg("user", "q")), List.of());

        assertNotNull(receivedRequestId.get());
        assertFalse(receivedRequestId.get().isBlank());
    }

    // ---------- 入口：响应怎么变成 AgentDecision ----------

    /** 通过 {@code decision.isDegraded}、{@code decision.hasToolCalls} 驱动该场景，布尔状态符合断言，实际结果与固定预期一致。 */
    @Test
    void toolCallsFromGateway_becomeDecisionToolCalls() throws IOException {
        responseBody = "{\"content\":null,"
                + "\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\",\"function\":"
                + "{\"name\":\"knowledge_search\",\"arguments\":\"{\\\"query\\\":\\\"年假\\\"}\"}}],"
                + "\"prompt_tokens\":1300,\"completion_tokens\":24,\"provider_used\":\"ollama\"}";

        AgentDecision decision = llm().respond(List.of(msg("user", "q")), List.of());

        assertFalse(decision.isDegraded());
        assertTrue(decision.hasToolCalls());
        ToolCall call = decision.getToolCalls().get(0);
        assertEquals("c1", call.getCallId());
        assertEquals("knowledge_search", call.getToolName());
        assertEquals("{\"query\":\"年假\"}", call.getArguments(), "arguments 保持 JSON 字符串，不提前解析");
        assertEquals(1300, decision.getPromptTokens());
        assertEquals(24, decision.getCompletionTokens());
        assertEquals("ollama", decision.getProviderUsed());
    }

    /** 通过 {@code decision.hasToolCalls}、{@code decision.isDegraded} 驱动该场景，布尔状态符合断言，实际结果与固定预期一致。 */
    @Test
    void finalAnswer_hasNoToolCalls() {
        responseBody = "{\"content\":\"年假 5 天\",\"tool_calls\":[],\"prompt_tokens\":10,\"completion_tokens\":3}";

        AgentDecision decision = llm().respond(List.of(msg("user", "q")), List.of());

        assertEquals("年假 5 天", decision.getContent());
        assertFalse(decision.hasToolCalls());
        assertFalse(decision.isDegraded());
    }

    /** 通过 {@code decision.isDegraded}、{@code decision.hasToolCalls} 驱动该场景，布尔状态符合断言，实际结果与固定预期一致。 */
    @Test
    void degradedResponse_becomesDegradedDecision_notAnEmptyAnswer() {
        responseBody = "{\"content\":null,\"tool_calls\":[],\"degraded\":true,"
                + "\"degrade_reason\":\"circuit open（主 provider 熔断，降级也失败）\",\"provider_used\":\"ollama\"}";

        AgentDecision decision = llm().respond(List.of(msg("user", "q")), List.of());

        assertTrue(decision.isDegraded());
        assertEquals("circuit open（主 provider 熔断，降级也失败）", decision.getDegradeReason());
        assertFalse(decision.hasToolCalls());
    }

    /** 模拟正文增量后收到 error，检查部分正文未作为最终答复交付。 */
    @Test
    void streamDeltaThenError_doesNotBecomeFinalAnswer() {
        streamBody = "event: delta\ndata: {\"text\":\"半段答案\"}\n\n"
                + "event: error\ndata: {\"reason\":\"upstream failed\"}\n\n";
        StringBuilder deltas = new StringBuilder();

        AgentDecision decision = llm().respondStream(
                List.of(msg("user", "q")), List.of(), deltas::append);

        assertEquals("半段答案", deltas.toString());
        assertTrue(decision.isDegraded());
        assertNull(decision.getContent());
        assertFalse(decision.hasToolCalls());
        assertEquals("dependency_unavailable",decision.getOutcome());
    }

    /** 在部分流式正文之后触发超时，检查稳定错误码和空的最终回答。 */
    @Test
    void streamingTimeoutPreservesTheStableFailureStateAndDiscardsPartialAnswer() {
        streamBody = "event: delta\ndata: {\"text\":\"partial\"}\n\n"
                + "event: error\ndata: {\"code\":\"timeout\",\"reasonCode\":\"model_timeout\",\"message\":\"untrusted-secret\"}\n\n";
        AgentDecision decision=llm().respondStream(List.of(msg("user","q")),List.of(),ignored -> { });
        assertTrue(decision.isDegraded());assertNull(decision.getContent());
        assertEquals("timeout",decision.getOutcome());assertEquals("model_timeout",decision.getReasonCode());
        assertFalse(decision.getDegradeReason().contains("untrusted-secret"));
    }

    /** 通过 {@code llm}、{@code msg} 驱动该场景，布尔状态符合断言，实际结果与固定预期一致。 */
    @Test
    void unavailableBindingKeepsTheSafeCodeWithoutReturningTheUpstreamBody() {
        responseStatus=503;responseBody="{\"detail\":{\"code\":\"model_not_configured\",\"message\":\"untrusted-secret\"}}";
        AgentDecision decision=llm().respond(List.of(msg("user","q")),List.of());
        assertEquals("dependency_unavailable",decision.getOutcome());
        assertEquals("model_not_configured",decision.getReasonCode());
        assertFalse(decision.getDegradeReason().contains("untrusted-secret"));
    }

    /** 通过 {@code llm}、{@code msg} 驱动该场景，实际结果与固定预期一致。 */
    @Test
    void httpTimeoutIsNotReportedAsOrdinaryDependencyUnavailability() {
        responseStatus=504;responseBody="{}";
        AgentDecision decision=llm().respond(List.of(msg("user","q")),List.of());
        assertEquals("timeout",decision.getOutcome());assertEquals("model_timeout",decision.getReasonCode());
    }

    /** 让上游在输出增量后直接结束连接，检查缺失终态时丢弃部分答复。 */
    @Test
    void streamDeltaThenEofWithoutFinal_doesNotBecomeFinalAnswer() {
        streamBody = "event: delta\ndata: {\"text\":\"半段答案\"}\n\n";
        StringBuilder deltas = new StringBuilder();

        AgentDecision decision = llm().respondStream(
                List.of(msg("user", "q")), List.of(), deltas::append);

        assertEquals("半段答案", deltas.toString());
        assertTrue(decision.isDegraded());
        assertTrue(decision.getDegradeReason().contains("final"));
        assertNull(decision.getContent());
    }

    /** 模拟一条完整终态帧，验证 SSE 按终态载荷确认最终答案。 */
    @Test
    void streamFinalFrame_confirmsAnswer() {
        streamBody = "event: delta\ndata: {\"text\":\"完整\"}\n\n"
                + "event: final\ndata: {\"content\":\"完整答案\",\"tool_calls\":[],\"finish_reason\":\"stop\"}\n\n";

        AgentDecision decision = llm().respondStream(
                List.of(msg("user", "q")), List.of(), ignored -> { });

        assertFalse(decision.isDegraded());
        assertEquals("完整答案", decision.getContent());
    }

    // ---------- 失败口径：本类永不抛异常 ----------

    /** 通过 {@code decision.isDegraded}、{@code llm} 驱动该场景，布尔状态符合断言。 */
    @Test
    void http500_becomesDegradedDecision_withStatusInReason() {
        responseStatus = 500;
        responseBody = "{\"detail\":\"服务内部错误\",\"request_id\":\"abc\"}";

        AgentDecision decision = llm().respond(List.of(msg("user", "q")), List.of());

        assertTrue(decision.isDegraded(), "AI 服务 5xx 不该让整条对话线 500");
        assertTrue(decision.getDegradeReason().contains("HTTP 500"),
                "原因里要能看出是哪一层拒的，实际=" + decision.getDegradeReason());
    }

    /** 通过 {@code decision.isDegraded}、{@code llm} 驱动该场景，布尔状态符合断言。 */
    @Test
    void http404_becomesDegradedDecision_notException() {
        responseStatus = 404;
        responseBody = "{\"detail\":\"Not Found\"}";

        AgentDecision decision = llm().respond(List.of(msg("user", "q")), List.of());

        assertTrue(decision.isDegraded());
        assertTrue(decision.getDegradeReason().contains("HTTP 404"));
    }

    /** 通过 {@code decision.isDegraded}、{@code guard.state} 驱动该场景，布尔状态符合断言，实际结果与固定预期一致。 */
    @Test
    void connectionRefused_becomesDegradedDecision() {
        int deadPort = server.getAddress().getPort();
        stopServer();   // 端口立刻没人监听

        // 评审 B3：连接被拒属于"AI 服务不可达"，必须计入熔断失败（阈值 1 → 一次即打开）。
        AiAvailabilityGuard guard = new AiAvailabilityGuard(
                true, 1, 1, 50, 60_000, 1, new SimpleMeterRegistry());
        AgentDecision decision = llmAt("http://127.0.0.1:" + deadPort, guard)
                .respond(List.of(msg("user", "q")), List.of());

        assertTrue(decision.isDegraded());
        assertNotNull(decision.getDegradeReason());
        assertFalse(decision.getDegradeReason().isBlank());
        assertEquals(AiAvailabilityGuard.State.OPEN, guard.state(),
                "连接被拒必须计入熔断失败——否则「进程不在」这一类永远不会快速失败");
    }

    /** 通过 {@code HttpServer.create}、{@code llm.respond} 驱动用例，布尔状态符合断言，实际结果与固定预期一致。 */
    @Test
    void readTimeout_becomesDegradedDecision() throws Exception {
        server.stop(0);
        HttpServer slow = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        slow.createContext("/agent/decide", exchange -> {
            try {
                Thread.sleep(3000);          // 超过下面 readTimeoutMs=300
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        slow.start();
        try {
            // 🔴 本条同时是「读超时不得打开熔断器」的反例测试（评审 B3 的判据边界）：
            // 读超时说明 TCP 已建连、服务活着，只是这一单慢。把它计入失败的话，
            // 一次 rerank 冷启动（实测可达 38.9s）就会把熔断打开，连健康调用一起拒掉。
            AiAvailabilityGuard guard = new AiAvailabilityGuard(
                    true, 1, 1, 50, 60_000, 1, new SimpleMeterRegistry());
            GatewayAgentLlm llm = new GatewayAgentLlm(new AgentGatewayClient(
                    AgentGatewayProperties.builder()
                            .baseUrl("http://127.0.0.1:" + slow.getAddress().getPort())
                            .connectTimeoutMs(1000).readTimeoutMs(300).build(),
                    objectMapper, "test-internal-token", null, guard));

            AgentDecision decision = llm.respond(List.of(msg("user", "q")), List.of());

            assertTrue(decision.isDegraded());
            assertTrue(decision.getDegradeReason().contains("Timeout"),
                    "原因里要能看出是超时，实际=" + decision.getDegradeReason());
            assertEquals(AiAvailabilityGuard.State.CLOSED, guard.state(),
                    "读超时**不得**打开熔断器：服务可达，只是这一单慢（阈值 1，若误计则立刻变 OPEN）");
        } finally {
            slow.stop(0);
        }
    }

    /**
     * 协调 {@code array.forEach} 完成当前阶段，供后续处理使用。
     * 下游调用 {@code array.forEach}。
     *
     * @param array 本次调用传入的 {@code JsonNode}
     * @return 调用方使用的 {@code java.util.stream.Stream<JsonNode>}
     */
    private java.util.stream.Stream<JsonNode> stream(JsonNode array) {
        java.util.List<JsonNode> list = new java.util.ArrayList<>();
        array.forEach(list::add);
        return list.stream();
    }
}
