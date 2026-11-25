/* 验证网关摘要提示词与请求信息，以及异常、截断和空白回复处理。 */

package com.pony.knowledge.agent.memory.summary;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pony.knowledge.agent.exception.AgentException;
import com.pony.knowledge.agent.llm.AgentGatewayClient;
import com.pony.knowledge.agent.llm.AgentLlmProperties;
import com.pony.knowledge.agent.llm.AgentGatewayProperties;
import com.pony.knowledge.agent.llm.LlmClient;
import com.pony.knowledge.agent.llm.LlmResponse;
import com.pony.knowledge.agent.model.AgentMessage;
import com.pony.knowledge.agent.model.AgentPrompts;
import com.pony.knowledge.resilience.AiAvailabilityGuard;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 08 章 3.1 P1-8：会话摘要走治理层那一档的契约测试。
 *
 * <p>与 {@code GatewayAgentLlmTest} 同一套做法——**真 HTTP、随机端口、断进程外那串 JSON**。
 * 摘要这一档多一条更要紧的断言：它和直连档必须喂模型**同一份 prompt**
 * （{@link #promptIsByteIdenticalToDirectPath}），否则「切开关之后摘要质量变了」将无法归因。
 */
class GatewaySummaryServiceTest {

    private HttpServer server;

    private final AtomicReference<String> receivedBody = new AtomicReference<>();

    private final AtomicReference<String> receivedRequestId = new AtomicReference<>();

    private volatile String responseBody = "{}";

    private volatile int responseStatus = 200;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 准备本组用例共享的测试数据和隔离环境，通过 {@code HttpServer.create}、{@code server.createContext} 初始化依赖。 */
    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/agent/summarize", exchange -> {
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            receivedRequestId.set(exchange.getRequestHeaders().getFirst("X-Request-ID"));
            byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, out.length);
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
     * @return 调用方使用的 {@code GatewaySummaryService}
     */
    private GatewaySummaryService serviceAt(String baseUrl) {
        return new GatewaySummaryService(new AgentGatewayClient(
                AgentGatewayProperties.builder()
                        .baseUrl(baseUrl).connectTimeoutMs(2000).readTimeoutMs(5000).build(),
                objectMapper, "test-internal-token", null,
                AiAvailabilityGuard.disabled(new SimpleMeterRegistry())));
    }

    /**
     * 协调 {@code serviceAt} 完成当前阶段，供后续处理使用。
     * @return 调用方使用的 {@code GatewaySummaryService}
     */
    private GatewaySummaryService service() {
        return serviceAt("http://127.0.0.1:" + server.getAddress().getPort());
    }

    /** 提供摘要需要的已有摘要及待折叠消息批次。 */
    private MemorySnapshot snapshotWithOldSummary() {
        return MemorySnapshot.builder()
                .sessionId("A")
                .messageCount(2)
                .summary(ConversationSummary.builder().summary("用户此前问过差旅标准").build())
                .messages(List.of(
                        AgentMessage.builder().role("user").content("住宿上限多少").build(),
                        AgentMessage.builder().role("assistant").content("500 元").build()))
                .build();
    }

    /**
     * 协调 {@code objectMapper.readTree} 完成当前阶段，供后续处理使用。
     * @return 调用方使用的 {@code JsonNode}
     */
    private JsonNode sent() throws IOException {
        return objectMapper.readTree(receivedBody.get());
    }

    // ---------- 出口 ----------

    /** 通过 {@code body.get} 驱动用例，布尔状态符合断言，实际结果与固定预期一致。 */
    @Test
    void sendsTwoTextPromptWithTheSharedSystemInstruction() throws IOException {
        responseBody = "{\"content\":\"要点\",\"prompt_tokens\":10,\"completion_tokens\":2}";

        service().summarize(snapshotWithOldSummary());

        JsonNode body = sent();
        assertEquals(AgentPrompts.SUMMARY_SYSTEM, body.get("system_prompt").asText(),
                "system 必须是两处实现共用的那一份常量（AgentPrompts.SUMMARY_SYSTEM）");
        String user = body.get("user_prompt").asText();
        assertTrue(user.startsWith("[已有摘要]\n用户此前问过差旅标准"), "旧摘要要放在最前，实际=" + user);
        assertTrue(user.contains("user: 住宿上限多少"), "待折消息按 role: content 逐行拼");
        assertTrue(user.contains("assistant: 500 元"));
    }

    /** 通过 {@code direct.summarize}、{@code directMessages.get} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void promptIsByteIdenticalToDirectPath() throws IOException {
        responseBody = "{\"content\":\"要点\"}";
        List<Map<String, Object>> directMessages = new ArrayList<>();
        LlmClient recording = new LlmClient("k", "http://unused", 100, 100, objectMapper) {
            /**
             * 协调 {@code directMessages.addAll} 完成当前阶段，供后续处理使用。
             * 下游调用 {@code directMessages.addAll}。
             *
             * @param model 本次调用传入的 {@code String}
             * @param messages 按照交互顺序提供的消息列表
             * @param tools 本次调用传入的 {@code List<Map<String, Object>>}
             * @return 提供商回复及其工具调用和用量
             */
            @Override
            public LlmResponse chatCompletions(String model, List<Map<String, Object>> messages,
                                               List<Map<String, Object>> tools) {
                directMessages.addAll(messages);
                return LlmResponse.builder().content("要点").build();
            }
        };
        LlmSummaryService direct = new LlmSummaryService(recording,
                AgentLlmProperties.builder().model("m").build());

        MemorySnapshot snapshot = snapshotWithOldSummary();
        direct.summarize(snapshot);
        service().summarize(snapshot);

        assertEquals(2, directMessages.size(), "直连档发的是 system + user 两段");
        assertEquals(directMessages.get(0).get("content"), sent().get("system_prompt").asText(),
                "两档的 system 必须一字不差");
        assertEquals(directMessages.get(1).get("content"), sent().get("user_prompt").asText(),
                "两档的 user prompt 必须一字不差");
    }

    /** 通过 {@code receivedRequestId.get} 驱动用例，布尔状态符合断言。 */
    @Test
    void sendsRequestIdHeader() {
        responseBody = "{\"content\":\"要点\"}";

        service().summarize(snapshotWithOldSummary());

        assertNotNull(receivedRequestId.get());
        assertTrue(!receivedRequestId.get().isBlank(), "Python 的 request_id 中间件读的就是这个头");
    }

    // ---------- 入口 ----------

    /** 通过 {@code service}、{@code snapshotWithOldSummary} 驱动该场景，实际结果与固定预期一致。 */
    @Test
    void summaryFromGateway_becomesConversationSummary() {
        responseBody = "{\"content\":\"用户问住宿上限，答 500 元\","
                + "\"prompt_tokens\":820,\"completion_tokens\":18,\"provider_used\":\"ollama\"}";

        ConversationSummary cs = service().summarize(snapshotWithOldSummary());

        assertEquals("A", cs.getSessionId());
        assertEquals("用户问住宿上限，答 500 元", cs.getSummary());
        assertEquals(2, cs.getSummarizedMessageCount(), "count 取快照的本批消息数（Coordinator 随后按游标覆盖）");
        assertNotNull(cs.getUpdatedAt());
    }

    // ---------- 失败口径：这一档**要**抛，与决策档相反 ----------

    /** 通过 {@code service}、{@code snapshotWithOldSummary} 驱动该场景，抛出 {@code AgentException}，布尔状态符合断言。 */
    @Test
    void degradedResponse_throwsAgentException_withReason() {
        responseBody = "{\"content\":null,\"degraded\":true,"
                + "\"degrade_reason\":\"circuit open（主 provider 熔断，降级也失败）\"}";

        AgentException e = assertThrows(AgentException.class, () -> service().summarize(snapshotWithOldSummary()));

        assertTrue(e.getMessage().contains("circuit open"), "原因要能看出是治理层降级，实际=" + e.getMessage());
    }

    /** 通过 {@code service}、{@code snapshotWithOldSummary} 驱动该场景，抛出 {@code AgentException}，布尔状态符合断言。 */
    @Test
    void http500_throwsAgentException_withStatusInReason() {
        responseStatus = 500;
        responseBody = "{\"detail\":\"服务内部错误\",\"request_id\":\"abc\"}";

        AgentException e = assertThrows(AgentException.class, () -> service().summarize(snapshotWithOldSummary()));

        assertTrue(e.getMessage().contains("HTTP 500"), "实际=" + e.getMessage());
    }

    /** 通过 {@code stopServer}、{@code serviceAt} 驱动该场景，抛出 {@code AgentException}，布尔状态符合断言。 */
    @Test
    void connectionRefused_throwsAgentException() {
        int deadPort = server.getAddress().getPort();
        stopServer();

        AgentException e = assertThrows(AgentException.class,
                () -> serviceAt("http://127.0.0.1:" + deadPort).summarize(snapshotWithOldSummary()));

        assertNotNull(e.getMessage());
        assertTrue(e.getMessage().contains("会话摘要"), "实际=" + e.getMessage());
    }

    /** 通过 {@code service}、{@code snapshotWithOldSummary} 驱动该场景，抛出 {@code AgentException}。 */
    @Test
    void blankContent_isTreatedAsFailure_notSavedAsEmptySummary() {
        responseBody = "{\"content\":\"   \",\"degraded\":false}";

        assertThrows(AgentException.class, () -> service().summarize(snapshotWithOldSummary()));
    }

    /** 通过 {@code service}、{@code snapshotWithOldSummary} 驱动该场景，抛出 {@code AgentException}，布尔状态符合断言。 */
    @Test
    void truncatedSummary_isTreatedAsFailure() {
        responseBody = "{\"content\":\"用户问住宿上限，答 500\",\"finish_reason\":\"length\"}";

        AgentException e = assertThrows(AgentException.class, () -> service().summarize(snapshotWithOldSummary()));
        assertTrue(e.getMessage().contains("截断"), "原因要能看出是截断，实际=" + e.getMessage());
    }

    /** 通过 {@code service}、{@code snapshotWithOldSummary} 驱动该场景，实际结果与固定预期一致。 */
    @Test
    void normalFinishReason_passes() {
        responseBody = "{\"content\":\"要点\",\"finish_reason\":\"stop\"}";

        assertEquals("要点", service().summarize(snapshotWithOldSummary()).getSummary());
    }
}
