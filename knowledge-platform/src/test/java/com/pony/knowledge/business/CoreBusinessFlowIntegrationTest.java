/* 在隔离依赖上串联账号、知识库、文档和问答，并记录主要业务检查点。 */

package com.pony.knowledge.business;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pony.knowledge.mcp.auth.McpApiKeyService;
import com.pony.knowledge.service.ai.AiServiceClient;
import com.pony.knowledge.modelmanagement.ModelRuntimeClient;
import com.pony.knowledge.service.rag.ConversationKey;
import com.pony.knowledge.service.storage.MinioService;
import com.pony.knowledge.utils.JwtUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 四条 Java 核心业务的连续场景：真实 MVC / Security / Service / Mapper / 事务 / 后台任务，
 * 使用专用 PostgreSQL 和 Redis。只替换 AI 与对象存储出口，不替换业务 Service、SQL 或授权。
 * 通过 tests/e2e/run_core_business_test.py 创建并销毁测试依赖，禁止默认连接现有业务库。
 */
@SpringBootTest(properties = {
        "task.reliability.recovery-enabled=false",
        "storage.reclaim.enabled=false",
        "mcp.enabled=false",
        "accounts.admin-username=core-flow-admin",
        "model-management.defaults.rerank-api-key=core-flow-test-placeholder",
        "rag.conversation.store=redis"
})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CoreBusinessFlowIntegrationTest {
    private static final String PASSWORD = "CoreFlow@123456";
    private static final String POLICY = "差旅报销应在7天内提交。";
    private static final String CONVERSATION = "core-flow-" + UUID.randomUUID();

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;
    @Autowired private JwtUtil jwt;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private McpApiKeyService mcpKeys;
    @MockitoBean private AiServiceClient ai;
    @MockitoBean private ModelRuntimeClient modelRuntime;
    @MockitoBean private MinioService files;

    private final AtomicReference<AiServiceClient.RagQueryRequest> lastQuery = new AtomicReference<>();
    private final List<String> checkpoints = new ArrayList<>();

    /** 从隔离运行器提供的节点配置装配测试依赖。接收{@code properties}。 */
    @DynamicPropertySource
    static void isolatedDependencies(DynamicPropertyRegistry properties) {
        String database = required("CORE_FLOW_DATABASE");
        if (!database.matches("core_flow_[a-f0-9]{12}")) {
            throw new IllegalStateException("Use tests/e2e/run_core_business_test.py with its isolated database");
        }
        properties.add("spring.datasource.url", () -> "jdbc:postgresql://127.0.0.1:"
                + required("CORE_FLOW_POSTGRES_PORT") + "/" + database);
        properties.add("spring.datasource.username", () -> "postgres");
        properties.add("spring.datasource.password", () -> required("CORE_FLOW_POSTGRES_PASSWORD"));
        properties.add("spring.data.redis.host", () -> "127.0.0.1");
        properties.add("spring.data.redis.port", () -> required("CORE_FLOW_REDIS_PORT"));
        properties.add("spring.data.redis.password", () -> "");
        properties.add("spring.data.redis.database", () -> "0");
    }

    /** 读取隔离运行所需的环境项，缺失时立即失败。由 {@code isolatedDependencies} 调用。接收属性或资源名称。返回 {@code value}。 */
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing " + name + "; run the isolated test runner");
        }
        return value;
    }

    /** 依次验证账号、知识库、文档任务和问答四条流程，将隔离环境的关键结果写为检查点。 */
    @Test
    @DisplayName("账户→知识库→文档任务→问答，并验证权限、幂等、重试与清理")
    void runsTheFourCoreBusinessFlows() throws Exception {
        stubExternalBoundaries();

        // 账户：实际注册、密码认证、令牌轮换及请求认证，没有人工构造登录身份。
        mvc.perform(get("/knowledge-base/list")).andExpect(status().isUnauthorized());
        Actor owner = registerAndLogin("owner");
        Actor member = registerAndLogin("member");
        Actor outsider = registerAndLogin("outsider");
        configureEmbeddingModel(owner);
        mvc.perform(post("/user/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("username", owner.username(), "password", "wrong"))))
                .andExpect(status().isUnauthorized());
        JsonNode rotated = data(post("/user/refresh"), Map.of("refreshToken", owner.refresh()));
        assertThat(rotated.path("refreshToken").asText()).isNotEqualTo(owner.refresh());
        assertThat(redis.hasKey("auth:refresh:" + jwt.parseRefreshToken(owner.refresh()).tokenId())).isFalse();
        owner = new Actor(owner.id(), owner.username(), rotated.path("token").asText(),
                rotated.path("refreshToken").asText());
        assertThat(data(bearer(get("/knowledge-base/list"), owner), null)).isEmpty();
        verifyAccountAdministration();
        checkpoint("account: register/login/authentication/refresh");

        // 知识库：当前用户绑定创建者，成员获得读取权而不是管理权。
        long kbId = createKnowledgeBase(owner, "shared");
        long privateKbId = createKnowledgeBase(outsider, "private");
        expectJson(bearer(post("/knowledge-base/" + kbId + "/members"), owner),
                Map.of("userId", member.id()), 200);
        expectJson(bearer(post("/knowledge-base/" + kbId + "/members"), owner),
                Map.of("userId", member.id()), 200);
        assertThat(count("SELECT count(*) FROM knowledge_base_member WHERE knowledge_base_id=?", kbId))
                .isEqualTo(1);
        JsonNode visible = data(bearer(get("/knowledge-base/list"), member), null);
        assertThat(visible).hasSize(1);
        assertThat(visible.get(0).path("id").asLong()).isEqualTo(kbId);
        expectJson(bearer(get("/knowledge-base/" + kbId + "/members"), member), null, 403);
        expectJson(bearer(get("/document/list").param("knowledgeBaseId", Long.toString(kbId)), outsider), null, 403);
        verifyLibraryManagement(owner, member, kbId);
        checkpoint("knowledge-base: owner/member permissions and duplicate membership");

        // 上传：复用回执且不重复写文件；不同内容同键冲突，成员不能上传。
        MockMultipartFile guide = new MockMultipartFile("file", "guide.md", "text/markdown",
                POLICY.getBytes(StandardCharsets.UTF_8));
        expectJson(bearer(multipart("/document/upload").file(guide)
                .param("knowledgeBaseId", Long.toString(kbId)), member), null, 403);
        JsonNode receipt = upload(owner, kbId, "upload-once", guide);
        long documentId = receipt.path("documentId").asLong();
        long taskId = receipt.path("taskId").asLong();
        assertThat(documentId).isPositive();
        assertThat(taskId).isPositive();
        JsonNode replay = upload(owner, kbId, "upload-once", guide);
        assertThat(replay.path("reused").asBoolean()).isTrue();
        assertThat(replay.path("documentId").asLong()).isEqualTo(documentId);
        assertThat(replay.path("taskId").asLong()).isEqualTo(taskId);
        JsonNode lookup = data(bearer(get("/document/upload/receipt")
                .param("knowledgeBaseId", Long.toString(kbId)).param("requestKey", "upload-once"), owner), null);
        assertThat(lookup.path("found").asBoolean()).isTrue();
        assertThat(lookup.path("receipt").path("taskId").asLong()).isEqualTo(taskId);
        MockMultipartFile changed = new MockMultipartFile("file", "guide.md", "text/markdown", new byte[]{1});
        expectJson(bearer(multipart("/document/upload").file(changed)
                .param("knowledgeBaseId", Long.toString(kbId)).header("Idempotency-Key", "upload-once"), owner), null, 409);
        verify(files, times(1)).upload(any(MultipartFile.class));
        JsonNode completed = waitForTask(owner, documentId, "SUCCESS");
        assertThat(completed.path("attempt").asInt()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM document WHERE knowledge_base_id=?", kbId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM document_task WHERE document_id=?", documentId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM document_upload_receipt WHERE document_id=?", documentId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM document_chunk WHERE document_id=?", documentId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT status FROM document WHERE id=?", Integer.class, documentId)).isEqualTo(2);
        data(bearer(get("/document/" + documentId + "/task"), member), null);
        expectJson(bearer(delete("/document/" + documentId), member), null, 403);
        expectJson(bearer(post("/document/" + documentId + "/retry"), member), null, 403);
        checkpoint("document: receipt/idempotency/background chunks and joint SUCCESS state");

        // 失败任务：不可解析文件进入 FAILED，手动重试复用原 taskId 并累计次数。
        JsonNode failedReceipt = upload(owner, kbId, "invalid-once",
                new MockMultipartFile("file", "invalid.pdf", "application/pdf", new byte[]{0}));
        long failedDocument = failedReceipt.path("documentId").asLong();
        waitForTask(owner, failedDocument, "FAILED");
        data(bearer(post("/document/" + failedDocument + "/retry"), owner), null);
        JsonNode retried = waitForTask(owner, failedDocument, "FAILED");
        assertThat(retried.path("manualRetryCount").asInt()).isEqualTo(1);
        assertThat(retried.path("attempt").asInt()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT id FROM document_task WHERE document_id=?", Long.class, failedDocument))
                .isEqualTo(failedReceipt.path("taskId").asLong());
        checkpoint("document-task: permanent failure and retry of the original task");

        // 问答：实际 Java 授权、契约映射、Redis 会话隔离与候选/获准来源区别。
        JsonNode answer = expectJson(bearer(post("/knowledge/ask"), owner),
                Map.of("question", "报销几天内提交？", "pipelineEngine", "fixed"), 200);
        assertAnswer(answer, documentId, kbId);
        assertThat(lastQuery.get().getKnowledgeBaseIds()).containsExactly(kbId).doesNotContain(privateKbId);
        JsonNode firstChat = chat(owner, kbId, "first-question", 200);
        assertAnswer(firstChat, documentId, kbId);
        assertThat(lastQuery.get().getHistory()).isEmpty();
        chat(owner, kbId, "follow-up", 200);
        assertThat(lastQuery.get().getHistory()).hasSize(2);
        chat(member, kbId, "member-question", 200);
        assertThat(lastQuery.get().getHistory()).isEmpty();
        assertThat(historySize(owner, kbId)).isEqualTo(4);
        assertThat(historySize(member, kbId)).isEqualTo(2);
        JsonNode empty = chat(owner, kbId, "no-evidence", 200);
        assertThat(empty.path("outcome").asText()).isEqualTo("no_evidence");
        assertThat(empty.path("sources")).isEmpty();
        assertThat(historySize(owner, kbId)).isEqualTo(6);
        JsonNode timeout = chat(owner, kbId, "timeout", 504);
        assertThat(timeout.path("data").path("outcome").asText()).isEqualTo("timeout");
        JsonNode unavailable = chat(owner, kbId, "dependency", 503);
        assertThat(unavailable.path("data").path("outcome").asText()).isEqualTo("dependency_unavailable");
        assertThat(historySize(owner, kbId)).isEqualTo(6);
        AiServiceClient.RagQueryRequest lastAuthorized = lastQuery.get();
        chat(outsider, kbId, "forbidden", 403);
        assertThat(lastQuery.get()).isSameAs(lastAuthorized);
        JsonNode candidates = expectJson(bearer(get("/knowledge/search")
                .param("query", "报销").param("pipelineEngine", "fixed"), owner), null, 200);
        assertThat(candidates).hasSize(2);
        assertThat(lastQuery.get().isGenerate()).isFalse();
        checkpoint("rag: authorized scope/admitted sources/history isolation/no-evidence/503/504/search");

        // 移除成员后立即失去读取权；删除文档验证真实数据库级联，而非仅检查返回文案。
        data(bearer(delete("/knowledge-base/" + kbId + "/members/" + member.id()), owner), null);
        chat(member, kbId, "removed-member", 403);
        for (long id : List.of(documentId, failedDocument)) {
            String key = jdbc.queryForObject("SELECT file_path FROM document WHERE id=?", String.class, id);
            data(bearer(delete("/document/" + id), owner), null);
            assertThat(count("SELECT count(*) FROM document WHERE id=?", id)).isZero();
            assertThat(count("SELECT count(*) FROM document_chunk WHERE document_id=?", id)).isZero();
            assertThat(count("SELECT count(*) FROM document_task WHERE document_id=?", id)).isZero();
            assertThat(count("SELECT count(*) FROM document_upload_receipt WHERE document_id=?", id)).isZero();
            verify(files).delete(key);
        }
        JsonNode missing = data(bearer(get("/document/upload/receipt")
                .param("knowledgeBaseId", Long.toString(kbId)).param("requestKey", "upload-once"), owner), null);
        assertThat(missing.path("found").asBoolean()).isFalse();
        verifyLibraryRemoval(owner, member, kbId, privateKbId, guide);
        data(post("/user/logout"), Map.of("refreshToken", owner.refresh()));
        expectJson(post("/user/refresh"), Map.of("refreshToken", owner.refresh()), 401);
        checkpoint("cleanup: member revocation/document FK cascades/logout");
        assertThat(checkpoints).hasSize(6);
    }

    /** 以所有者和成员的请求核对共享、撤权及可见范围。接收知识库所有者夹具、共享成员夹具、知识库 ID。 */
    private void verifyLibraryManagement(Actor owner, Actor member, long kbId) throws Exception {
        String path = "/knowledge-base/" + kbId;
        Map<String, String> details = Map.of("name", "更新后的共享知识库", "description", "新版说明");
        expectJson(bearer(put(path), member), details, 403);
        expectJson(bearer(delete(path), member), null, 403);
        expectJson(bearer(put(path), owner), Map.of("name", " "), 400);
        data(bearer(put(path), owner), details);

        JsonNode libraries = data(bearer(get("/knowledge-base/list"), member), null);
        JsonNode shared = libraries.get(0);
        assertThat(shared.path("name").asText()).isEqualTo(details.get("name"));
        assertThat(shared.path("description").asText()).isEqualTo(details.get("description"));
        assertThat(shared.path("creatorId").asLong()).isEqualTo(owner.id());
        JsonNode members = data(bearer(get(path + "/members"), owner), null);
        assertThat(members).hasSize(1);
        assertThat(members.get(0).path("username").asText()).isEqualTo(member.username());
        JsonNode users = data(bearer(get("/user/search").param("username", member.username()), owner), null);
        assertThat(users.get(0).path("id").asLong()).isEqualTo(member.id());
    }

    /** 删除指定知识库，核对级联记录和其他库数据的范围。接收知识库所有者夹具、共享成员夹具、知识库 ID、用于核对隔离的其他库 ID、测试文件。 */
    private void verifyLibraryRemoval(Actor owner, Actor member, long kbId, long otherKbId,
                                      MockMultipartFile file) throws Exception {
        String path = "/knowledge-base/" + kbId;
        data(bearer(post(path + "/members"), owner), Map.of("userId", member.id()));
        Map<Long, String> storedFiles = new java.util.LinkedHashMap<>();
        for (String requestKey : List.of("whole-library-a", "whole-library-b")) {
            long id = upload(owner, kbId, requestKey, file).path("documentId").asLong();
            waitForTask(owner, id, "SUCCESS");
            storedFiles.put(id, jdbc.queryForObject("SELECT file_path FROM document WHERE id=?", String.class, id));
        }
        String unavailableObject = storedFiles.values().iterator().next();
        doThrow(new java.io.IOException("isolated storage cleanup failure")).when(files).delete(unavailableObject);
        assertThat(count("SELECT count(*) FROM document_chunk_vector_2560 WHERE knowledge_base_id=?", kbId)).isPositive();
        JsonNode removal = data(bearer(delete(path), owner), null);
        assertThat(removal.path("filesPendingCleanup").asInt()).isEqualTo(1);
        for (String table : List.of("document", "document_chunk", "document_upload_receipt", "knowledge_base_member",
                "knowledge_index_binding", "knowledge_index_generation", "document_chunk_vector_2560")) {
            assertThat(count("SELECT count(*) FROM " + table + " WHERE knowledge_base_id=?", kbId)).as(table).isZero();
        }
        for (var entry : storedFiles.entrySet()) {
            assertThat(count("SELECT count(*) FROM document_task WHERE document_id=?", entry.getKey())).isZero();
            verify(files).delete(entry.getValue());
        }
        assertThat(count("SELECT count(*) FROM knowledge_base WHERE id=?", kbId)).isZero();
        assertThat(count("SELECT count(*) FROM knowledge_base WHERE id=?", otherKbId)).isEqualTo(1);
        assertThat(data(bearer(get("/knowledge-base/list"), member), null)).isEmpty();
        chat(member, kbId, "deleted-library", 403);
        expectJson(bearer(post(path + "/members"), owner), Map.of("userId", member.id()), 403);
    }

    /** 替换本轮集成流程所用的 AI 和对象存储外部调用。由 {@code runsTheFourCoreBusinessFlows} 调用。 */
    private void stubExternalBoundaries() throws Exception {
        when(modelRuntime.resolve(anyMap())).thenReturn(Map.of());
        when(files.upload(any(MultipartFile.class))).thenAnswer(call -> "test-object-" + UUID.randomUUID());
        when(ai.parseDocument(anyLong(), anyString(), anyString(), anyString(), any())).thenAnswer(call -> {
            long documentId = call.getArgument(0);
            String name = jdbc.queryForObject("SELECT file_name FROM document WHERE id=?", String.class, documentId);
            if ("invalid.pdf".equals(name)) {
                throw new RestClientResponseException("controlled invalid document", 422, "Unprocessable Entity",
                        null, new byte[0], StandardCharsets.UTF_8);
            }
            AiServiceClient.ParseResult result = new AiServiceClient.ParseResult();
            result.setDocumentId(documentId);
            result.setChunks(List.of(chunk(0, POLICY), chunk(1, "会议结束后关闭会议室电源。")));
            return result;
        });
        when(ai.embed(anyList(), anyMap())).thenAnswer(call -> {
            List<String> contents = call.getArgument(0);
            List<Double> vector = new ArrayList<>(Collections.nCopies(2560, 0.0));
            vector.set(0, 1.0);
            AiServiceClient.EmbeddingResponse response = new AiServiceClient.EmbeddingResponse();
            response.setEmbeddings(contents.stream().map(text -> vector).toList());
            return response;
        });
        when(ai.ragQuery(any(AiServiceClient.RagQueryRequest.class))).thenAnswer(call -> {
            AiServiceClient.RagQueryRequest request = call.getArgument(0);
            lastQuery.set(request);
            AiServiceClient.RagQueryResponse response = new AiServiceClient.RagQueryResponse();
            response.setPipelineEngine(request.getPipelineEngine());
            response.setRetrievalVersion(request.getVersion());
            response.setOriginalQuery(request.getQuestion());
            response.setRewrittenQuery(request.getQuestion());
            response.setProviderUsed("test-boundary");
            response.setRetrievalStatus("complete");
            response.setOutcome("success");
            response.setAnswer(POLICY);
            List<AiServiceClient.RagQuerySource> sources = new ArrayList<>();
            for (long kbId : request.getKnowledgeBaseIds()) {
                sources.addAll(jdbc.query("SELECT id,document_id,knowledge_base_id,chunk_index,content "
                                + "FROM document_chunk WHERE knowledge_base_id=? ORDER BY chunk_index",
                        (row, index) -> {
                            AiServiceClient.RagQuerySource source = new AiServiceClient.RagQuerySource();
                            source.setChunkId(row.getLong("id"));
                            source.setDocumentId(row.getLong("document_id"));
                            source.setKnowledgeBaseId(row.getLong("knowledge_base_id"));
                            source.setChunkIndex(row.getInt("chunk_index"));
                            source.setContent(row.getString("content"));
                            return source;
                        }, kbId));
            }
            response.setSources(sources);
            response.setEvidenceSources(sources.isEmpty() ? List.of() : List.of(sources.get(0)));
            switch (request.getQuestion()) {
                case "no-evidence" -> {
                    response.setOutcome("no_evidence");
                    response.setEvidenceSources(List.of());
                }
                case "timeout", "dependency" -> {
                    response.setOutcome("timeout".equals(request.getQuestion()) ? "timeout" : "dependency_unavailable");
                    response.setEvidenceSources(List.of());
                    response.setFailedStage("generate");
                }
                default -> { }
            }
            return response;
        });
    }

    /**
     * 构造带切片索引、正文与来源的解析响应夹具。
     * 由 {@code stubExternalBoundaries} 调用。
     * 接收{@code index}、{@code content}。
     * 返回 {@code result}。
     */
    private AiServiceClient.ChunkResult chunk(int index, String content) {
        AiServiceClient.ChunkResult result = new AiServiceClient.ChunkResult();
        result.setChunkIndex(index);
        result.setContent(content);
        result.setSearchText("差旅 报销 提交 会议 电源");
        result.setSourceType("text");
        return result;
    }

    /** 验证管理员进行账号或部门变更时的页面 API 契约。由 {@code runsTheFourCoreBusinessFlows} 调用。 */
    private void verifyAccountAdministration() throws Exception {
        String adminName = "core-flow-admin";
        String path = "/user/account-admin";
        expectJson(post("/user/register"), Map.of("username", "CORE-FLOW-ADMIN", "password", PASSWORD), 422);
        jdbc.update("INSERT INTO sys_user(username, password) VALUES (?, ?)", adminName, passwordEncoder.encode(PASSWORD));
        JsonNode login = data(post("/user/login"), Map.of("username", adminName, "password", PASSWORD));
        Actor admin = new Actor(login.path("id").asLong(), adminName, login.path("token").asText(),
                login.path("refreshToken").asText());
        Actor managed = registerAndLogin("managed");
        createKnowledgeBase(managed, "admin-boundary");
        assertThat(data(bearer(get("/knowledge-base/list"), admin), null)).isEmpty();
        assertThat(data(bearer(get(path + "/access"), admin), null).asBoolean()).isTrue();
        assertThat(data(bearer(get(path + "/access"), managed), null).asBoolean()).isFalse();
        expectJson(get(path), null, 401);
        expectJson(bearer(get(path), managed), null, 403);
        String target = path + "/" + managed.id() + "/status";
        expectJson(bearer(put(target), managed), Map.of("enabled", false), 403);
        expectJson(bearer(put(path + "/" + admin.id() + "/status"), admin), Map.of("enabled", false), 403);
        expectJson(bearer(put(target), admin), Map.of(), 400);

        JsonNode accounts = data(bearer(get(path), admin), null).path("accounts");
        assertThat(accounts).anySatisfy(account -> {
            assertThat(account.path("id").asLong()).isEqualTo(admin.id());
            assertThat(account.path("currentAdmin").asBoolean()).isTrue();
        });
        assertThat(accounts).allSatisfy(account -> assertThat(account.has("password")).isFalse());
        JsonNode issued = data(bearer(post("/mcp/keys"), managed), Map.of("name", "isolated-account-check"));
        String key = issued.path("apiKey").asText();
        long keyId = issued.path("id").asLong();
        JsonNode keyList = data(bearer(get("/mcp/keys"), managed), null);
        assertThat(keyList).hasSize(1);
        assertThat(keyList.get(0).path("keyPrefix")).isEqualTo(issued.path("keyPrefix"));
        assertThat(keyList.toString()).doesNotContain(key, "apiKey", "keyHash");
        assertThat(data(bearer(get("/mcp/keys"), admin), null)).isEmpty();
        assertThat(mcpKeys.authenticate(key).isAuthenticated()).isTrue();

        assertThat(data(bearer(put(target), admin), Map.of("enabled", false)).path("enabled").asBoolean()).isFalse();
        expectJson(post("/user/login"), Map.of("username", managed.username(), "password", PASSWORD), 403);
        expectJson(bearer(get("/knowledge-base/list"), managed), null, 401);
        expectJson(post("/user/refresh"), Map.of("refreshToken", managed.refresh()), 401);
        assertThat(mcpKeys.authenticate(key).isAuthenticated()).isFalse();
        assertThat(data(bearer(put(target), admin), Map.of("enabled", true)).path("enabled").asBoolean()).isTrue();
        expectJson(bearer(get("/knowledge-base/list"), managed), null, 401);
        expectJson(post("/user/refresh"), Map.of("refreshToken", managed.refresh()), 401);
        assertThat(mcpKeys.authenticate(key).isAuthenticated()).isTrue();

        JsonNode restored = data(post("/user/login"), Map.of("username", managed.username(), "password", PASSWORD));
        Actor current = new Actor(managed.id(), managed.username(), restored.path("token").asText(), restored.path("refreshToken").asText());
        data(bearer(put(target), admin), Map.of("enabled", true));
        assertThat(data(bearer(get("/knowledge-base/list"), current), null)).hasSize(1);
        data(post("/user/refresh"), Map.of("refreshToken", current.refresh()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action='account.status_changed' AND target_id=?",
                Long.class, Long.toString(managed.id()))).isEqualTo(2L);
        verifyAuditLogs(admin, current, keyId, key);
    }

    /** 验证管理员能读取核心流程的操作记录及敏感字段的边界。接收管理员夹具、知识库所有者夹具、被测 MCP Key ID、本次测试使用的键。 */
    private void verifyAuditLogs(Actor admin, Actor owner, long keyId, String key) throws Exception {
        String auditPath = "/audit/logs";
        expectJson(get(auditPath), null, 401);
        expectJson(bearer(get(auditPath), owner), null, 403);
        expectJson(bearer(get(auditPath).param("from", "2026-02-02").param("to", "2026-02-01"), admin),
                null, 400);

        data(bearer(delete("/mcp/keys/" + keyId), owner), null);
        assertThat(mcpKeys.authenticate(key).isAuthenticated()).isFalse();
        JsonNode revoked = data(bearer(get("/mcp/keys"), owner), null).get(0);
        assertThat(revoked.path("status").asText()).isEqualTo("REVOKED");

        for (String action : List.of("system.api_key_created", "system.api_key_revoked")) {
            JsonNode entries = data(bearer(get(auditPath).param("action", action).param("outcome", "success"), admin),
                    null).path("entries");
            assertThat(entries).hasSize(1);
            JsonNode entry = entries.get(0);
            assertThat(entry.path("actorUsername").asText()).isEqualTo(owner.username());
            assertThat(entry.path("targetId").asText()).isEqualTo(Long.toString(keyId));
            assertThat(entry.path("requestId").asText()).isNotBlank();
            assertThat(entry.toString()).doesNotContain(key, "details");
        }

        jdbc.update("""
                INSERT INTO audit_log (actor_user_id, action, target_id, created_at)
                SELECT ?, 'test.audit_pagination', n::text, TIMESTAMP '2026-01-15 12:00:00'
                FROM generate_series(1, 51) AS n
                """, admin.id());
        String day = "2026-01-15";
        JsonNode first = data(bearer(get(auditPath).param("action", "test.audit_pagination")
                .param("from", day).param("to", day), admin), null);
        assertThat(first.path("entries")).hasSize(50);
        JsonNode second = data(bearer(get(auditPath).param("action", "test.audit_pagination")
                .param("from", day).param("to", day).param("beforeId", first.path("nextBeforeId").asText()), admin), null);
        assertThat(second.path("entries")).hasSize(1);
        assertThat(second.path("entries").get(0).path("id").asLong()).isLessThan(first.path("nextBeforeId").asLong());
        assertThat(second.path("nextBeforeId").isNull()).isTrue();
    }

    /**
     * 创建临时账号并返回附带令牌的测试身份。
     * 由 {@code runsTheFourCoreBusinessFlows}、{@code verifyAccountAdministration} 调用。
     * @param role 本用例传入的{@code role}
     */
    private Actor registerAndLogin(String role) throws Exception {
        String name = "core-" + role + "-" + UUID.randomUUID().toString().substring(0, 12);
        data(post("/user/register"), Map.of("username", name, "password", PASSWORD, "email", name + "@example.test"));
        JsonNode login = data(post("/user/login"), Map.of("username", name, "password", PASSWORD));
        assertThat(login.path("token").asText()).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT password FROM sys_user WHERE id=?", String.class, login.path("id").asLong()))
                .startsWith("$2").isNotEqualTo(PASSWORD);
        return new Actor(login.path("id").asLong(), name, login.path("token").asText(), login.path("refreshToken").asText());
    }

    /** 为当前测试所有者登记并绑定本轮向量模型。由 {@code runsTheFourCoreBusinessFlows} 调用。接收知识库所有者夹具。 */
    private void configureEmbeddingModel(Actor owner) throws Exception {
        JsonNode model = data(bearer(post("/models"), owner), Map.ofEntries(
                Map.entry("name", "core-flow embedding"),
                Map.entry("type", "embedding"),
                Map.entry("source", "remote"),
                Map.entry("provider", "generic"),
                Map.entry("protocol", "openai_embedding"),
                Map.entry("model", "core-flow-embedding"),
                Map.entry("baseUrl", "http://127.0.0.1:19111/v1"),
                Map.entry("dimension", 2560),
                Map.entry("capabilities", Map.of()),
                Map.entry("compat", Map.of()),
                Map.entry("runtime", Map.of())));
        String modelId = model.path("id").asText();
        assertThat(modelId).isNotBlank();
        data(bearer(put("/models/bindings"), owner), Map.of("bindings", Map.of("embedding", modelId)));
    }

    /**
     * 通过当前测试身份创建独立知识库并取得库 ID。
     * 由 {@code runsTheFourCoreBusinessFlows}、{@code verifyAccountAdministration} 调用。
     * @param actor 测试操作身份
     * @param suffix 本用例传入的{@code suffix}
     * 返回 {@code row.path("id").asLong()}。
     */
    private long createKnowledgeBase(Actor actor, String suffix) throws Exception {
        String name = "core-" + suffix + "-" + UUID.randomUUID();
        data(bearer(post("/knowledge-base"), actor), Map.of("name", name, "description", "isolated core business test"));
        JsonNode rows = data(bearer(get("/knowledge-base/list"), actor), null);
        for (JsonNode row : rows) {
            if (name.equals(row.path("name").asText())) {
                assertThat(row.path("creatorId").asLong()).isEqualTo(actor.id());
                return row.path("id").asLong();
            }
        }
        throw new AssertionError("Created knowledge base is not visible to its owner");
    }

    /** 以指定账号和请求键提交文档，返回解包后的上传回执。由 {@code verifyLibraryRemoval} 调用。接收测试操作身份、知识库 ID、本次测试使用的键、测试文件。 */
    private JsonNode upload(Actor actor, long kbId, String key, MockMultipartFile file) throws Exception {
        return data(bearer(multipart("/document/upload").file(file)
                .param("knowledgeBaseId", Long.toString(kbId)).header("Idempotency-Key", key), actor), null);
    }

    /** 按预期终态等待文档任务，超时时报告当前状态。由 {@code verifyLibraryRemoval} 调用。接收测试操作身份、文档 ID、预期终态。返回 {@code task}。 */
    private JsonNode waitForTask(Actor actor, long documentId, String terminal) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        JsonNode task;
        do {
            task = data(bearer(get("/document/" + documentId + "/task"), actor), null);
            if (List.of("SUCCESS", "FAILED").contains(task.path("status").asText())) {
                assertThat(task.path("status").asText()).isEqualTo(terminal);
                return task;
            }
            Thread.sleep(40);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Task did not reach " + terminal + ": " + task.path("status").asText());
    }

    /**
     * 用认证身份在给定知识库提交问答并核对响应状态。
     * 由 {@code verifyLibraryRemoval} 调用。
     * 接收测试操作身份、知识库 ID、发给测试入口的问答文本、期望的响应或断言值。
     * 返回 {@code JsonNode} 结果。
     */
    private JsonNode chat(Actor actor, long kbId, String question, int expected) throws Exception {
        return expectJson(bearer(post("/rag/chat"), actor), Map.of("knowledgeBaseId", kbId,
                "conversationId", CONVERSATION, "question", question, "pipelineEngine", "fixed"), expected);
    }

    /** 核对问答正文与来源绑定到预期文档和知识库。接收{@code answer}、文档 ID、知识库 ID。 */
    private void assertAnswer(JsonNode answer, long documentId, long kbId) {
        assertThat(answer.path("outcome").asText()).isEqualTo("success");
        assertThat(answer.path("pipelineEngine").asText()).isEqualTo("fixed");
        assertThat(answer.path("answer").asText()).isEqualTo(POLICY);
        assertThat(answer.path("sources")).hasSize(1);
        assertThat(answer.path("sources").get(0).path("documentId").asLong()).isEqualTo(documentId);
        assertThat(answer.path("sources").get(0).path("knowledgeBaseId").asLong()).isEqualTo(kbId);
    }

    /** 读取当前账号和库对应的问答历史长度。接收测试操作身份、知识库 ID。返回 {@code size == null ? 0 : size}。 */
    private long historySize(Actor actor, long kbId) {
        String id = new ConversationKey(actor.id(), kbId, CONVERSATION).storageId();
        Long size = redis.opsForList().size("rag:conversation:" + id + ":messages");
        return size == null ? 0 : size;
    }

    /**
     * 执行指定计数 SQL，供测试核对写入或删除范围。
     * 由 {@code runsTheFourCoreBusinessFlows}、{@code verifyLibraryRemoval} 调用。
     * @param sql 本测试执行的参数化 SQL
     * @param id 目标记录 ID
     * 返回 {@code result == null ? 0 : result}。
     */
    private long count(String sql, long id) {
        Long result = jdbc.queryForObject(sql, Long.class, id);
        return result == null ? 0 : result;
    }

    /**
     * 给 MockMvc 请求附加测试账号的 Bearer 令牌。
     * 由 {@code runsTheFourCoreBusinessFlows}、{@code verifyLibraryManagement} 调用。
     * @param request MockMvc 请求构造器
     * @param actor 测试操作身份
     * 返回 {@code request.header(HttpHeaders.AUTHORIZATION, "Bearer " + actor.token())}。
     */
    private MockHttpServletRequestBuilder bearer(MockHttpServletRequestBuilder request, Actor actor) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + actor.token());
    }

    /**
     * 提交测试请求并读出标准业务包装中的 data。
     * 由 {@code runsTheFourCoreBusinessFlows}、{@code verifyLibraryManagement} 调用。
     * @param request MockMvc 请求构造器
     * @param body 请求载荷
     * 返回 {@code result.path("data")}。
     */
    private JsonNode data(MockHttpServletRequestBuilder request, Object body) throws Exception {
        JsonNode result = expectJson(request, body, 200);
        assertThat(result.path("code").asInt()).isEqualTo(200);
        return result.path("data");
    }

    /**
     * 发送测试请求并验证状态，返回解析后的 JSON。
     * 由 {@code runsTheFourCoreBusinessFlows}、{@code verifyLibraryManagement} 调用。
     * @param request MockMvc 请求构造器
     * @param body 请求载荷
     * @param expected 期望的响应或断言值
     */
    private JsonNode expectJson(MockHttpServletRequestBuilder request, Object body, int expected) throws Exception {
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body));
        MvcResult result = mvc.perform(request).andExpect(status().is(expected)).andReturn();
        byte[] content = result.getResponse().getContentAsByteArray();
        return content.length == 0 ? json.nullNode() : json.readTree(content);
    }

    /** 记录一条已完成的业务检查点供隔离运行器读取。由 {@code runsTheFourCoreBusinessFlows} 调用。接收属性或资源名称。 */
    private void checkpoint(String name) {
        checkpoints.add(name);
        System.out.println("CORE_FLOW_PASS " + name);
    }

    private record Actor(long id, String username, String token, String refresh) { }
}
