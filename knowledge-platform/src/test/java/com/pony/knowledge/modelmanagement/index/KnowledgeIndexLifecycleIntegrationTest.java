/* 在 PostgreSQL 验证知识库向量空间、重建、切换和并发写入边界。 */

package com.pony.knowledge.modelmanagement.index;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pony.knowledge.modelmanagement.ModelRegistryService;
import com.pony.knowledge.service.KnowledgeBaseAccessService;
import com.pony.knowledge.service.ai.AiServiceClient;
import com.pony.knowledge.utils.CurrentUserUtil;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Requires a disposable PostgreSQL+pgvector database, never the business database. */
@EnabledIfEnvironmentVariable(named = "EKIP_INDEX_TEST_ISOLATED", matches = "true")
class KnowledgeIndexLifecycleIntegrationTest {
    private static DriverManagerDataSource data;
    private static JdbcTemplate jdbc;
    private static final AtomicLong IDS = new AtomicLong(100000);
    private ModelRegistryService models;
    private AiServiceClient ai;
    private KnowledgeIndexService indexes;
    private long kb;
    private long document;
    private long owner;
    private String m1024;
    private String m2560;

    /** 初始化本组用例共用依赖，调用 {@code url.matches}、{@code jdbc.queryForObject}。 */
    @BeforeAll static void database() {
        String url = System.getenv("EKIP_INDEX_TEST_JDBC_URL");
        assertNotNull(url, "EKIP_INDEX_TEST_JDBC_URL must identify a disposable database");
        assertTrue(url.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/(ekip_model_test|ekip_index_test)[A-Za-z0-9_]*(\\?.*)?"),
                "Only a loopback dedicated test database may be migrated");
        data = new DriverManagerDataSource(url, System.getenv("EKIP_INDEX_TEST_DB_USER"), System.getenv("EKIP_INDEX_TEST_DB_PASSWORD"));
        jdbc = new JdbcTemplate(data);
        String name = jdbc.queryForObject("SELECT current_database()", String.class);
        assertTrue(name != null && (name.startsWith("ekip_model_test") || name.startsWith("ekip_index_test")));
        Flyway.configure().dataSource(data).locations("classpath:db/migration").load().migrate();
    }

    /** 每个用例前准备夹具，调用 {@code IDS.incrementAndGet}、{@code jdbc.update}。 */
    @BeforeEach void setup() {
        owner = IDS.incrementAndGet(); kb = IDS.incrementAndGet(); document = IDS.incrementAndGet();
        jdbc.update("INSERT INTO sys_user(id,username,password) VALUES(?,?,?)", owner, "index-test-" + owner, "unused-test-hash");
        jdbc.update("INSERT INTO knowledge_base(id,name,creator_id) VALUES(?,?,?)", kb, "isolated index fixture", owner);
        jdbc.update("INSERT INTO document(id,knowledge_base_id,file_name,status) VALUES(?,?,?,2)", document, kb, "fixture.txt");
        models = mock(ModelRegistryService.class); ai = mock(AiServiceClient.class);
        m1024 = insertModel(owner, 1024); m2560 = insertModel(owner, 2560);
        when(models.resolveOwned(owner, m1024)).thenReturn(model(m1024, 1024));
        when(models.resolveOwned(owner, m2560)).thenReturn(model(m2560, 2560));
        when(models.resolveForPurpose(eq(owner), eq("embedding"), anyString()))
                .thenAnswer(call -> models.resolveOwned(owner, call.getArgument(2)));
        indexes = service(true);
        when(ai.embed(anyList(), anyMap())).thenAnswer(call -> {
            List<?> texts = call.getArgument(0); Map<String, Object> execution = call.getArgument(1);
            @SuppressWarnings("unchecked") Map<String, Object> bindings = (Map<String, Object>) execution.get("bindings");
            @SuppressWarnings("unchecked") Map<String, Object> selected = (Map<String, Object>) bindings.get("embedding");
            int dimension = ((Number) selected.get("dimension")).intValue();
            return response(texts.size(), dimension);
        });
    }

    /** 回收当前用例的资源和上下文，调用 {@code indexes.shutdown}。 */
    @AfterEach void close() { if (indexes != null) indexes.shutdown(); }

    /** 核对 {@code old.get("dimension")} 与预期 {@code 1024} 一致。调用路径经过 {@code indexes.startRebuild}。 */
    @Test void rebuildChangesDimensionOnlyAfterCompleteShadowValidation() throws Exception {
        insertChunks(3);
        Map<String, Object> first = indexes.startRebuild(kb, m1024, null);
        await(first, "completed");
        Map<String, Object> old = indexes.status(kb);
        assertEquals(1024, old.get("dimension"));
        String oldGeneration = old.get("activeGenerationId").toString();
        assertEquals(3L, count("document_chunk_vector_1024", oldGeneration));
        assertNull(jdbc.queryForObject("SELECT config_snapshot->>'apiKey' FROM embedding_space WHERE id=?", String.class, old.get("spaceId")));
        Map<String, Object> second = indexes.startRebuild(kb, m2560, oldGeneration);
        await(second, "completed");
        Map<String, Object> active = indexes.status(kb);
        assertEquals(2560, active.get("dimension"));
        assertNotEquals(oldGeneration, active.get("activeGenerationId"));
        assertEquals(3L, count("document_chunk_vector_2560", active.get("activeGenerationId").toString()));
        assertEquals(3L, count("document_chunk_vector_1024", oldGeneration));
        assertEquals("retired", jdbc.queryForObject("SELECT state FROM knowledge_index_generation WHERE id=?", String.class, oldGeneration));
        assertFalse((Boolean) active.get("maintenance"));
        String middleGeneration = active.get("activeGenerationId").toString();
        Map<String, Object> reverse = indexes.startRebuild(kb, m1024, middleGeneration);
        await(reverse, "completed");
        assertEquals(1024, indexes.status(kb).get("dimension"));
        assertEquals(3L, count("document_chunk_vector_2560", middleGeneration));
    }

    /**
     * 核对 {@code indexes.status(kb).get("activeGenerationId")} 与预期 {@code old} 一致。
     * 调用路径经过 {@code indexes.startRebuild}。
     */
    @Test void failedBatchPreservesOldGenerationAndReleasesMaintenance() throws Exception {
        insertChunks(20);
        Map<String, Object> first = indexes.startRebuild(kb, m1024, null); await(first, "completed");
        String old = indexes.status(kb).get("activeGenerationId").toString();
        doAnswer(call -> {
            List<?> texts = call.getArgument(0);
            if (texts.size() == 16) return response(16, 2560);
            throw new IllegalStateException("synthetic second batch model failure");
        }).when(ai).embed(anyList(), anyMap());
        Map<String, Object> failed = indexes.startRebuild(kb, m2560, old); await(failed, "failed");
        assertEquals(old, indexes.status(kb).get("activeGenerationId"));
        assertEquals(20L, count("document_chunk_vector_1024", old));
        assertEquals(16L, count("document_chunk_vector_2560", failed.get("generationId").toString()));
        assertFalse((Boolean) indexes.status(kb).get("maintenance"));
        assertDoesNotThrow(() -> indexes.assertWritable(kb));
    }

    /** 核对 {@code current.get("dimension")} 与预期 {@code 1024} 一致。调用路径经过 {@code indexes.startRebuild}。 */
    @Test void sameDimensionDifferentModelCreatesAnotherSpaceAndGeneration() throws Exception {
        insertChunks(2);
        Map<String, Object> first = indexes.startRebuild(kb, m1024, null); await(first, "completed");
        Map<String, Object> old = indexes.status(kb);
        String other = insertModel(owner, 1024);
        when(models.resolveOwned(owner, other)).thenReturn(model(other, 1024));

        Map<String, Object> rebuilt = indexes.startRebuild(kb, other, old.get("activeGenerationId").toString());
        await(rebuilt, "completed");

        Map<String, Object> current = indexes.status(kb);
        assertEquals(1024, current.get("dimension"));
        assertNotEquals(old.get("spaceId"), current.get("spaceId"));
        assertNotEquals(old.get("activeGenerationId"), current.get("activeGenerationId"));
        assertEquals(other, current.get("modelId"));
    }

    /**
     * 核对 {@code indexes.resolveIndexScopes(List.of(kb)).get(0).get("generationId")} 与预期 {@code old} 一致。
     * 调用路径经过 {@code indexes.startRebuild}。
     */
    @Test void cancellationFencesLateModelResultAndKeepsExistingIndex() throws Exception {
        insertChunks(2);
        Map<String, Object> first = indexes.startRebuild(kb, m1024, null); await(first, "completed");
        String old = indexes.status(kb).get("activeGenerationId").toString();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), interrupted = new CountDownLatch(1);
        doAnswer(call -> {
            Map<String, Object> execution = call.getArgument(1);
            @SuppressWarnings("unchecked") Map<String, Object> bindings = (Map<String, Object>) execution.get("bindings");
            @SuppressWarnings("unchecked") Map<String, Object> model = (Map<String, Object>) bindings.get("embedding");
            if (((Number) model.get("dimension")).intValue() == 1024) return response(2, 1024);
            entered.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException cancelled) {
                interrupted.countDown();
                // Simulate a transport whose late response survives cancellation.
                assertTrue(release.await(5, TimeUnit.SECONDS));
            }
            return response(2, 2560);
        }).when(ai).embed(anyList(), anyMap());
        Map<String, Object> task = indexes.startRebuild(kb, m2560, old);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertEquals(old, indexes.resolveIndexScopes(List.of(kb)).get(0).get("generationId"));
        assertThrows(IndexLifecycleException.class, () -> indexes.assertWritable(kb));
        Map<String, Object> cancelled = indexes.cancelRebuild(kb, task.get("id").toString());
        assertEquals("cancelled", cancelled.get("status"));
        assertTrue(interrupted.await(5, TimeUnit.SECONDS));
        assertEquals(old, indexes.status(kb).get("activeGenerationId"));
        release.countDown();
        // Completion of the next claim proves the late result from the cancelled
        // claim has already passed through the worker's fencing check.
        Map<String, Object> next = indexes.startRebuild(kb, m1024, old);
        await(next, "completed");
        assertEquals(0, count("document_chunk_vector_2560", task.get("generationId").toString()));
        assertFalse((Boolean) indexes.status(kb).get("maintenance"));
    }

    /** 覆盖过期、嵌入的相关边界。通过 {@code indexes.shutdown} 执行本例并核对输出或记录。 */
    @Test void expiredProcessingLeaseResumesAfterPersistedBatchWithoutReembeddingIt() throws Exception {
        indexes.shutdown();
        indexes = service(false);
        insertChunks(20);
        Map<String, Object> task = indexes.startRebuild(kb, m1024, null);
        String taskId = task.get("id").toString(), generation = task.get("generationId").toString();
        List<Long> firstBatch = jdbc.queryForList("SELECT id FROM document_chunk WHERE knowledge_base_id=? ORDER BY id LIMIT 16", Long.class, kb);
        String vector = "[" + "1," + "0,".repeat(1022) + "0]";
        for (Long chunk : firstBatch) jdbc.update("INSERT INTO document_chunk_vector_1024(generation_id,chunk_id,knowledge_base_id,embedding) VALUES(?,?,?,?::halfvec)",
                generation, chunk, kb, vector);
        jdbc.update("UPDATE knowledge_index_rebuild_task SET status='processing',run_token=?,lease_until=now()-interval '1 second',completed_chunks=16,last_chunk_id=? WHERE id=?",
                UUID.randomUUID().toString(), firstBatch.get(15), taskId);
        indexes.shutdown();
        indexes = service(true);

        indexes.recoverAtStartup();
        await(task, "completed");

        assertEquals(20L, count("document_chunk_vector_1024", generation));
        assertEquals(20L, ((Number) indexes.taskStatus(kb, taskId).get("processedChunks")).longValue());
        verify(ai, times(1)).embed(argThat(texts -> texts.size() == 4), anyMap());
    }

    /** 断言抛出 {@code ExecutionException}。调用路径经过 {@code CompletableFuture.runAsync}。 */
    @Test void committedDocumentWriterIsVisibleToRebuildAdmissionAfterTheSharedBarrier() throws Exception {
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1), starting = new CountDownLatch(1);
        CompletableFuture<Void> writer = CompletableFuture.runAsync(() ->
                new TransactionTemplate(new DataSourceTransactionManager(data)).executeWithoutResult(status -> {
                    indexes.acquireWriteBarrier(kb);
                    jdbc.update("INSERT INTO document_task(document_id,status) VALUES(?,0)", document);
                    locked.countDown();
                    try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
                }));
        assertTrue(locked.await(5, TimeUnit.SECONDS));
        CompletableFuture<Map<String, Object>> rebuild = CompletableFuture.supplyAsync(() -> {
            starting.countDown(); return indexes.startRebuild(kb, m1024, null);
        });
        assertTrue(starting.await(5, TimeUnit.SECONDS));
        release.countDown();
        writer.get(5, TimeUnit.SECONDS);

        ExecutionException failure = assertThrows(ExecutionException.class, () -> rebuild.get(5, TimeUnit.SECONDS));
        assertInstanceOf(IndexLifecycleException.class, failure.getCause());
        assertFalse((Boolean) indexes.status(kb).get("maintenance"));
        verifyNoInteractions(ai);
    }

    /** 核对 {@code indexes.status(kb).get("activeGenerationId")} 为空。调用路径经过 {@code entered.countDown}。 */
    @Test void sourceChangeDuringShadowBuildFailsValidationRatherThanActivatingPartialVectors() throws Exception {
        insertChunks(2);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); return response(2, 1024);
        }).when(ai).embed(anyList(), anyMap());
        Map<String, Object> task = indexes.startRebuild(kb, m1024, null);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        jdbc.update("UPDATE document_chunk SET content='unexpected out-of-band edit' WHERE knowledge_base_id=?", kb);
        release.countDown();

        await(task, "failed");

        assertNull(indexes.status(kb).get("activeGenerationId"));
        assertFalse((Boolean) indexes.status(kb).get("maintenance"));
    }

    /** 核对 {@code indexes.status(kb).get("status")} 与预期 {@code "requires_rebuild"} 一致。 */
    @Test void existingUnprovenVectorsRequireRebuildAndCannotBeSilentlyBound() {
        insertChunks(1);
        assertEquals("requires_rebuild", indexes.status(kb).get("status"));
        assertThrows(IndexLifecycleException.class, () -> indexes.embeddingExecution(kb, 120000));
        assertThrows(IndexLifecycleException.class, () -> indexes.resolveIndexScopes(List.of(kb)));
        verifyNoInteractions(ai);
    }

    /** 断言抛出 {@code IndexLifecycleException}。调用路径经过 {@code jdbc.update}。 */
    @Test void waitingDocumentTaskAndStaleActiveGenerationRejectRebuild() throws Exception {
        jdbc.update("INSERT INTO document_task(document_id,status) VALUES(?,0)", document);
        assertThrows(IndexLifecycleException.class, () -> indexes.startRebuild(kb, m1024, null));
        jdbc.update("DELETE FROM document_task WHERE document_id=?", document);
        Map<String, Object> task = indexes.startRebuild(kb, m1024, null); await(task, "completed");
        assertThrows(IndexLifecycleException.class, () -> indexes.startRebuild(kb, m2560, null));
    }

    /** 通过 {@code jdbc.queryForObject} 执行本例并核对输出或记录。 */
    @Test void readonlyRoleCannotReadCredentialsOrWriteVectorTables() {
        assertEquals(Boolean.TRUE, jdbc.queryForObject("SELECT has_table_privilege('rag_readonly','document_chunk_vector_1024','SELECT')", Boolean.class));
        assertEquals(Boolean.TRUE, jdbc.queryForObject("SELECT has_table_privilege('rag_readonly','document_chunk_vector_2560','SELECT')", Boolean.class));
        assertEquals(Boolean.FALSE, jdbc.queryForObject("SELECT has_table_privilege('rag_readonly','managed_model','SELECT')", Boolean.class));
        assertEquals(Boolean.FALSE, jdbc.queryForObject("SELECT has_table_privilege('rag_readonly','document_chunk_vector_1024','INSERT')", Boolean.class));
    }

    /** 断言抛出 {@code IndexLifecycleException}。调用路径经过 {@code IDS.incrementAndGet}。 */
    @Test void creatorCannotRebuildWithAnotherUsersModel() {
        long other = IDS.incrementAndGet();
        jdbc.update("INSERT INTO sys_user(id,username,password) VALUES(?,?,?)", other, "index-test-" + other, "unused-test-hash");
        String foreign = insertModel(other, 1024);

        IndexLifecycleException error = assertThrows(IndexLifecycleException.class,
                () -> indexes.startRebuild(kb, foreign, null));

        assertEquals(404, error.getStatus().value());
        assertFalse((Boolean) indexes.status(kb).get("maintenance"));
        verifyNoInteractions(ai);
    }

    /**
     * 在测试中处理 {@code await} 阶段，使用 {@code Duration.ofSeconds}、{@code indexes.taskStatus}。
     * 由 {@code rebuildChangesDimensionOnlyAfterCompleteShadowValidation}、{@code failedBatchPreservesOldGenerationAndReleasesMaintenance} 调用。
     * @param task 本用例传入的{@code task}
     * @param expected 期望的响应或断言值
     */
    private void await(Map<String, Object> task, String expected) throws InterruptedException {
        long end = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        Map<String, Object> current;
        do {
            current = indexes.taskStatus(kb, task.get("id").toString());
            if (SetHolder.TERMINAL.contains(current.get("status"))) {
                assertEquals(expected, current.get("status"), current.toString()); return;
            }
            Thread.sleep(20);
        } while (System.nanoTime() < end);
        fail("rebuild did not reach terminal status: " + current);
    }

    /**
     * 向夹具写入记录，使用 {@code jdbc.update}。
     * 由 {@code rebuildChangesDimensionOnlyAfterCompleteShadowValidation}、{@code failedBatchPreservesOldGenerationAndReleasesMaintenance} 调用。
     * @param count 本用例传入的{@code count}
     */
    private void insertChunks(int count) {
        for (int i = 0; i < count; i++) jdbc.update("INSERT INTO document_chunk(document_id,knowledge_base_id,chunk_index,content,source_type) VALUES(?,?,?,?,?)",
                document, kb, i, "fixture passage " + i, "paragraph");
    }

    /**
     * 构造 {@code KnowledgeIndexService} 供测试使用，使用 {@code users.getUserId}、{@code access.canRead}。
     * 由 {@code setup}、{@code expiredProcessingLeaseResumesAfterPersistedBatchWithoutReembeddingIt} 调用。
     * @param workerEnabled 本用例传入的{@code workerEnabled}
     * 返回 {@code KnowledgeIndexService} 结果。
     */
    private KnowledgeIndexService service(boolean workerEnabled) {
        CurrentUserUtil users = mock(CurrentUserUtil.class); when(users.getUserId()).thenReturn(owner);
        KnowledgeBaseAccessService access = mock(KnowledgeBaseAccessService.class);
        when(access.canRead(owner, kb)).thenReturn(true); when(access.canManageKnowledgeBase(owner, kb)).thenReturn(true);
        return new KnowledgeIndexService(jdbc, models, ai, users, access, new ObjectMapper(),
                new DataSourceTransactionManager(data), workerEnabled);
    }

    /**
     * 向夹具写入记录，使用 {@code UUID.randomUUID}、{@code jdbc.update}。
     * 由 {@code setup}、{@code sameDimensionDifferentModelCreatesAnotherSpaceAndGeneration} 调用。
     * @param owner 知识库所有者夹具
     * @param dimension 本用例传入的{@code dimension}
     * 返回 {@code id}。
     */
    private String insertModel(long owner, int dimension) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO managed_model(id,owner_id,revision,credential_version,semantic_fingerprint,config) VALUES(?,?,1,0,?,?::jsonb)",
                id, owner, "fp-" + id, "{}");
        return id;
    }

    /**
     * 构造 {@code Map<String, Object>} 供测试使用，使用 {@code model.put}。
     * 由 {@code setup}、{@code sameDimensionDifferentModelCreatesAnotherSpaceAndGeneration} 调用。
     * @param id 目标记录 ID
     * @param dimension 本用例传入的{@code dimension}
     * 返回 {@code model}。
     */
    private static Map<String, Object> model(String id, int dimension) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("id", id); model.put("type", "embedding"); model.put("dimension", dimension);
        model.put("semanticFingerprint", "fp-" + id); model.put("revision", 1);
        model.put("provider", "generic"); model.put("protocol", "openai_embedding");
        model.put("model", "isolated-" + dimension); model.put("baseUrl", "http://127.0.0.1:1/v1");
        model.put("apiKey", "isolated-test-secret");
        return model;
    }

    /**
     * 构造 {@code AiServiceClient.EmbeddingResponse} 供测试使用，使用 {@code Collections.nCopies}、{@code vector.set}。
     * 由 {@code setup}、{@code failedBatchPreservesOldGenerationAndReleasesMaintenance} 调用。
     * @param count 本用例传入的{@code count}
     * @param dimension 本用例传入的{@code dimension}
     * 返回 {@code result}。
     */
    private static AiServiceClient.EmbeddingResponse response(int count, int dimension) {
        List<List<Double>> vectors = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            List<Double> vector = new ArrayList<>(Collections.nCopies(dimension, 0.0)); vector.set(0, 1.0); vectors.add(vector);
        }
        AiServiceClient.EmbeddingResponse result = new AiServiceClient.EmbeddingResponse(); result.setEmbeddings(vectors); return result;
    }

    /**
     * 执行指定计数 SQL，供测试核对写入或删除范围。
     * 由 {@code rebuildChangesDimensionOnlyAfterCompleteShadowValidation}、{@code failedBatchPreservesOldGenerationAndReleasesMaintenance} 调用。
     * @param table 本用例传入的{@code table}
     * @param generation 本用例传入的{@code generation}
     * 返回 {@code result == null ? 0 : result}。
     */
    private static long count(String table, String generation) {
        Long result = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE generation_id=?", Long.class, generation);
        return result == null ? 0 : result;
    }
    private static final class SetHolder {
        private static final java.util.Set<Object> TERMINAL = java.util.Set.of("completed", "failed", "cancelled");
    }
}
