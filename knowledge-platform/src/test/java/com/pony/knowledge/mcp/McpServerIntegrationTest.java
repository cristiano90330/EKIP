/* 通过真实 MCP 协议入口验证工具发现及执行上下文。 */

package com.pony.knowledge.mcp;

import com.pony.knowledge.mcp.auth.McpApiKey;
import com.pony.knowledge.mcp.auth.McpApiKeyService;
import com.pony.knowledge.mcp.auth.McpAuthResult;
import com.pony.knowledge.service.KnowledgeBaseAccessService;
import com.pony.knowledge.service.rag.PythonRagPipeline;
import com.pony.knowledge.service.rag.model.RagEvidence;
import com.pony.knowledge.service.rag.model.RagQueryOutcome;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 真实 MCP 协议握手 + 调用（spec §10.2）：用 SDK 自带客户端连自己的 /mcp，覆盖
 * initialize 版本协商、tools/list 白名单、tools/call 身份链路，以及三种认证拒绝。
 *
 * API Key 服务、知识库授权和 Python RAG 检索均打桩；Spring Boot 测试上下文仍需连接隔离 PostgreSQL 执行 Flyway，
 * 不启动 Milvus、MinIO 或 Python AI 服务。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "task.reliability.recovery-enabled=false",
        "mcp.enabled=true",
        // 随机端口：放开 Host 校验的端口位，但仍走 DefaultServerTransportSecurityValidator，不绕过校验器
        "mcp.security.allowed-hosts=127.0.0.1:*,localhost:*"
})
class McpServerIntegrationTest {

    private static final String ACTIVE_KEY = "ekip_mcp_integrationActiveKey00000000000";

    private static final String REVOKED_KEY = "ekip_mcp_integrationRevokedKey0000000000";

    private static final Long OWNER_ID = 42L;

    private static final String INITIALIZE_BODY = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":\
            {"protocolVersion":"2025-06-18","capabilities":{},\
            "clientInfo":{"name":"raw-probe","version":"1.0.0"}}}""";

    @LocalServerPort
    private int port;

    @MockitoBean
    private PythonRagPipeline pythonRagPipeline;

    @MockitoBean
    private McpApiKeyService mcpApiKeyService;

    @MockitoBean
    private KnowledgeBaseAccessService knowledgeBaseAccessService;

    // 2026-09-29：这里原先有一个 @MockitoBean VectorStore（打桩 MilvusVectorStore / PgVectorStore）。
    // 它当年的理由是「让本测试不依赖外部基础设施」——而 PgVectorStore 自读路径 search 删除后
    // 已无任何依赖（只返回一个引擎名字符串），VectorStoreConfig 用真实 bean 即可。
    // 留着一个无理由的 mock 只会掩盖「这个 bean 其实是纯的」这一事实，故删除。

    private final HttpClient rawHttpClient = HttpClient.newHttpClient();

    private McpSyncClient client;

    /** 每个用例前准备夹具，调用 {@code mcpApiKeyService.authenticate}、{@code McpAuthResult.granted}。 */
    @BeforeEach
    void stubCollaborators() {
        when(mcpApiKeyService.authenticate(ACTIVE_KEY)).thenReturn(McpAuthResult.granted(key(OWNER_ID)));
        when(mcpApiKeyService.authenticate(REVOKED_KEY)).thenReturn(McpAuthResult.denied("凭证已吊销"));
        when(knowledgeBaseAccessService.resolveReadableIds(OWNER_ID)).thenReturn(Set.of(1L));
        when(pythonRagPipeline.retrieveAdmitted(any(), any(), any(), anyInt())).thenAnswer(invocation -> {
            List<Long> knowledgeBaseIds = invocation.getArgument(0);
            if (knowledgeBaseIds.isEmpty()) {
                return RagQueryOutcome.builder()
                        .outcome("no_evidence")
                        .originalQuery("连接池上限")
                        .sources(List.of())
                        .evidences(List.of())
                        .build();
            }
            return RagQueryOutcome.builder()
                    .outcome("success")
                    .originalQuery("连接池上限")
                    .rewrittenQuery("Redis 连接池 上限")
                    .sources(List.of(evidence()))
                    .evidences(List.of(evidence()))
                    .build();
        });
    }

    /** 回收当前用例的资源和上下文，调用 {@code client.closeGracefully}。 */
    @AfterEach
    void closeClient() {
        if (client != null) {
            client.closeGracefully();
        }
    }

    /**
     * 构造 {@code McpApiKey} 供测试使用，使用 {@code entity.setId}、{@code entity.setUserId}。
     * 由 {@code stubCollaborators} 调用。
     * @param userId 账号 ID
     * 返回 {@code entity}。
     */
    private McpApiKey key(Long userId) {
        McpApiKey entity = new McpApiKey();
        entity.setId(9L);
        entity.setUserId(userId);
        entity.setKeyHash("a".repeat(64));
        return entity;
    }

    /**
     * 构造 {@code RagEvidence} 供测试使用，使用 {@code RagEvidence.builder}。
     * 由 {@code stubCollaborators} 调用。
     * 返回 {@code RagEvidence} 结果。
     */
    private RagEvidence evidence() {
        return RagEvidence.builder()
                .documentId(10L)
                .knowledgeBaseId(1L)
                .chunkIndex(3)
                .content("连接池上限设为 20")
                .score(0.8)
                .build();
    }

    /**
     * 构造 {@code McpSyncClient} 供测试使用，使用 {@code HttpClientStreamableHttpTransport.builder}、{@code builder.customizeRequest}。
     * 由 {@code initialize_negotiatesAKnownProtocolVersionAndAdvertisesTools}、{@code listTools_returnsExactlyTheWhitelistedTwo_withInputSchemas} 调用。
     * @param authorizationHeader 本用例传入的{@code authorizationHeader}
     * 返回 {@code client}。
     */
    private McpSyncClient connect(String authorizationHeader) {
        HttpClientStreamableHttpTransport.Builder builder = HttpClientStreamableHttpTransport
                .builder("http://127.0.0.1:" + port)
                .endpoint("/mcp");
        if (authorizationHeader != null) {
            builder.customizeRequest(request -> request.header("Authorization", authorizationHeader));
        }
        client = McpClient.sync(builder.build())
                .requestTimeout(Duration.ofSeconds(60))
                .build();
        return client;
    }

    /** 核对 {@code result.serverInfo().name()} 与预期 {@code "ekip-knowledge"} 一致。调用路径经过 {@code client.initialize}。 */
    @Test
    void initialize_negotiatesAKnownProtocolVersionAndAdvertisesTools() {
        connect("Bearer " + ACTIVE_KEY);

        McpSchema.InitializeResult result = client.initialize();

        assertTrue(List.of("2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25")
                .contains(result.protocolVersion()), "未知协议版本: " + result.protocolVersion());
        assertEquals("ekip-knowledge", result.serverInfo().name());
        assertNotNull(result.capabilities().tools());
    }

    /** 核对 {@code search.inputSchema().properties().containsKey("query")} 为真。调用路径经过 {@code client.initialize}。 */
    @Test
    void listTools_returnsExactlyTheWhitelistedTwo_withInputSchemas() {
        connect("Bearer " + ACTIVE_KEY);
        client.initialize();

        List<McpSchema.Tool> tools = client.listTools().tools();

        assertEquals(List.of("knowledge_ask", "knowledge_search"),
                tools.stream().map(McpSchema.Tool::name).sorted().toList());

        McpSchema.Tool search = tools.stream()
                .filter(tool -> tool.name().equals("knowledge_search"))
                .findFirst().orElseThrow();
        assertTrue(search.inputSchema().properties().containsKey("query"));
        assertTrue(search.inputSchema().required().contains("query"));
    }

    /** 核对 {@code Set.copyOf(captor.getValue())} 与预期 {@code Set.of(1L)} 一致。调用路径经过 {@code client.initialize}。 */
    @Test
    void callTool_carriesSessionIdentityIntoRetrievalPermissionSet() {
        connect("Bearer " + ACTIVE_KEY);
        client.initialize();

        McpSchema.CallToolResult result = client.callTool(
                new McpSchema.CallToolRequest("knowledge_search", Map.of("query", "连接池上限")));

        assertFalse(result.isError(), result.toString());
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(text.contains("连接池上限设为 20"), text);

        // 工具委托一次 Python /rag/query（Agent 准入检索），身份只经可访问集合透传（default-deny）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(pythonRagPipeline).retrieveAdmitted(captor.capture(), eq("连接池上限"), eq(List.of()), anyInt());
        assertEquals(Set.of(1L), Set.copyOf(captor.getValue()));
    }

    /**
     * 核对 {@code client.listTools().tools().size()} 与预期 {@code 2} 一致。
     * 调用路径经过 {@code knowledgeBaseAccessService.resolveReadableIds}。
     */
    @Test
    void userWithoutAnyKnowledgeBase_listsToolsButSearchesNothing() {
        when(knowledgeBaseAccessService.resolveReadableIds(OWNER_ID)).thenReturn(Set.of());
        connect("Bearer " + ACTIVE_KEY);
        client.initialize();

        assertEquals(2, client.listTools().tools().size());

        McpSchema.CallToolResult result = client.callTool(
                new McpSchema.CallToolRequest("knowledge_search", Map.of("query", "任意问题")));

        assertFalse(result.isError(), "无权限应是空结果而非报错: " + result);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(pythonRagPipeline).retrieveAdmitted(captor.capture(), eq("任意问题"), eq(List.of()), anyInt());
        assertTrue(captor.getValue().isEmpty(),
                "必须是空集合（default-deny），不得退化为全库检索");
    }

    /** 核对 {@code response.statusCode()} 与预期 {@code 401} 一致。 */
    @Test
    void missingCredentialIsHttp401AndCreatesNoSession() throws Exception {
        HttpResponse<String> response = postInitialize(null);

        assertEquals(401, response.statusCode());
        assertTrue(response.body().contains("\"code\":401"), response.body());
    }

    /** 核对 {@code response.statusCode()} 与预期 {@code 401} 一致。 */
    @Test
    void revokedCredentialIsHttp401() throws Exception {
        HttpResponse<String> response = postInitialize("Bearer " + REVOKED_KEY);

        assertEquals(401, response.statusCode());
        assertTrue(response.body().contains("凭证已吊销"), response.body());
    }

    /** 核对 {@code response.statusCode()} 与预期 {@code 401} 一致。 */
    @Test
    void jwtTokenOnMcpEndpointIsRejectedWithoutTouchingTheKeyStore() throws Exception {
        HttpResponse<String> response = postInitialize("Bearer eyJhbGciOiJIUzI1Ni.jwt.like.a.token");

        assertEquals(401, response.statusCode());
        verify(mcpApiKeyService, never()).authenticate(any());
    }

    /**
     * 构造 {@code HttpResponse<String>} 供测试使用，使用 {@code HttpRequest.newBuilder}、{@code URI.create}。
     * 由 {@code missingCredentialIsHttp401AndCreatesNoSession}、{@code revokedCredentialIsHttp401} 调用。
     * @param authorizationHeader 本用例传入的{@code authorizationHeader}
     * 返回 {@code rawHttpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())}。
     */
    private HttpResponse<String> postInitialize(String authorizationHeader) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(INITIALIZE_BODY));
        if (authorizationHeader != null) {
            builder.header("Authorization", authorizationHeader);
        }
        return rawHttpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
