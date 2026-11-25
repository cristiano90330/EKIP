/* 验证索引服务的作用域装配、向量校验和生命周期约束。 */

package com.pony.knowledge.modelmanagement.index;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pony.knowledge.exception.ForbiddenException;
import com.pony.knowledge.modelmanagement.ModelRegistryService;
import com.pony.knowledge.service.KnowledgeBaseAccessService;
import com.pony.knowledge.service.ai.AiServiceClient;
import com.pony.knowledge.utils.CurrentUserUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KnowledgeIndexServiceTest {
    private JdbcTemplate jdbc;
    private ModelRegistryService models;
    private AiServiceClient ai;
    private CurrentUserUtil users;
    private KnowledgeBaseAccessService access;
    private KnowledgeIndexService indexes;

    /** 每个用例前准备夹具，调用 {@code users.getUserId}。 */
    @BeforeEach void setup() {
        jdbc = mock(JdbcTemplate.class); models = mock(ModelRegistryService.class);
        ai = mock(AiServiceClient.class); users = mock(CurrentUserUtil.class);
        access = mock(KnowledgeBaseAccessService.class);
        indexes = new KnowledgeIndexService(jdbc, models, ai, users, access,
                new ObjectMapper(), new LocalTransactions(), false);
        when(users.getUserId()).thenReturn(11L);
    }

    /** 回收当前用例的资源和上下文，调用 {@code indexes.shutdown}。 */
    @AfterEach void close() { indexes.shutdown(); }

    /** 核对 {@code indexes.resolveIndexScopes(List.of())} 与预期 {@code List.of()} 一致。 */
    @Test void emptyAuthorizedScopePerformsNoDatabaseOrModelWork() {
        assertEquals(List.of(), indexes.resolveIndexScopes(List.of()));
        verifyNoInteractions(jdbc, models, ai);
    }

    /** 断言抛出 {@code ForbiddenException}。调用路径经过 {@code access.canRead}。 */
    @Test void statusRequiresKnowledgeBaseReadAccessBeforeReadingState() {
        when(access.canRead(11L, 7L)).thenReturn(false);
        assertThrows(ForbiddenException.class, () -> indexes.status(7));
        verifyNoInteractions(jdbc, models, ai);
    }

    /** 核对 {@code status.get("status")} 与预期 {@code "ready"} 一致。调用路径经过 {@code access.canRead}。 */
    @Test void statusNeverDecryptsModelsOrProbesTheirEndpoints() {
        when(access.canRead(11L, 7L)).thenReturn(true);
        when(jdbc.queryForList(startsWith("SELECT g.id"), eq(7L))).thenReturn(List.of(active("g1", 1024)));
        when(jdbc.queryForList(startsWith("SELECT maintenance_task_id"), eq(7L))).thenReturn(List.of());
        Map<String, Object> status = indexes.status(7);
        assertEquals("ready", status.get("status"));
        assertEquals("g1", status.get("activeGenerationId"));
        assertEquals(1024, status.get("dimension"));
        verifyNoInteractions(models, ai);
    }

    /** 断言抛出 {@code IndexLifecycleException}。调用路径经过 {@code jdbc.queryForList}。 */
    @Test void ingestionCannotAdoptUnknownLegacyVectorsAsNewSpace() {
        when(jdbc.queryForList(startsWith("SELECT maintenance_task_id"), eq(String.class), eq(7L))).thenReturn(List.of());
        when(jdbc.queryForList(startsWith("SELECT g.id"), eq(7L))).thenReturn(List.of());
        when(jdbc.queryForObject(startsWith("SELECT count(*) FROM document_chunk"), eq(Long.class), eq(7L))).thenReturn(3L);
        IndexLifecycleException error = assertThrows(IndexLifecycleException.class,
                () -> indexes.embeddingExecution(7, 120000));
        assertEquals("requires_rebuild", error.getReasonCode());
        verifyNoInteractions(models, ai);
        verify(jdbc, never()).update(startsWith("INSERT"), any(Object[].class));
    }

    /** 断言抛出 {@code IndexLifecycleException}。调用路径经过 {@code jdbc.queryForList}。 */
    @Test void maintenanceBlocksDocumentWritersInsideTheirTransaction() {
        when(jdbc.queryForList(startsWith("SELECT maintenance_task_id"), eq(String.class), eq(7L)))
                .thenReturn(List.of("task-1"));
        IndexLifecycleException error = assertThrows(IndexLifecycleException.class, () -> indexes.assertWritable(7));
        assertEquals(409, error.getStatus().value());
        verifyNoInteractions(models, ai);
    }

    /** 断言抛出 {@code ForbiddenException}。调用路径经过 {@code access.canManageKnowledgeBase}。 */
    @Test void onlyCreatorMayStartOrCancelRebuild() {
        when(access.canManageKnowledgeBase(11L, 7L)).thenReturn(false);
        assertThrows(ForbiddenException.class, () -> indexes.startRebuild(7, "m1", null));
        assertThrows(ForbiddenException.class, () -> indexes.cancelRebuild(7, "t1"));
        verifyNoInteractions(jdbc, models, ai);
    }

    /** 断言抛出 {@code IndexLifecycleException}。调用路径经过 {@code access.canManageKnowledgeBase}。 */
    @Test void explicitNullExpectedGenerationCannotOverwriteAnExistingActiveIndex() {
        when(access.canManageKnowledgeBase(11L, 7L)).thenReturn(true);
        when(jdbc.queryForObject(startsWith("SELECT count(*) FROM document_task"), eq(Integer.class), eq(7L)))
                .thenReturn(0);
        when(jdbc.queryForList(startsWith("SELECT g.id"), eq(7L))).thenReturn(List.of(active("g1", 1024)));

        IndexLifecycleException error = assertThrows(IndexLifecycleException.class,
                () -> indexes.startRebuild(7, "m2", null));

        assertEquals(409, error.getStatus().value());
        verifyNoInteractions(models, ai);
        verify(jdbc, never()).update(startsWith("INSERT"), any(Object[].class));
    }

    /** 核对 {@code scopes.size()} 与预期 {@code 1} 一致。调用路径经过 {@code jdbc.queryForList}。 */
    @Test void retrievalResolvesTheKnowledgeBaseOwnersModelForAuthorizedMembers() {
        when(jdbc.queryForList(startsWith("SELECT g.id"), eq(7L))).thenReturn(List.of(active("g1", 1024)));
        when(models.resolveForPurpose(11L, "embedding", "m1"))
                .thenReturn(Map.of("semanticFingerprint", "fingerprint", "id", "m1"));

        List<Map<String, Object>> scopes = indexes.resolveIndexScopes(List.of(7L, 7L));

        assertEquals(1, scopes.size());
        assertEquals("g1", scopes.get(0).get("generationId"));
        assertEquals(7L, scopes.get(0).get("knowledgeBaseId"));
        verify(models).resolveForPurpose(11L, "embedding", "m1");
        verifyNoInteractions(users, ai);
    }

    /** 核对 {@code KnowledgeIndexService.table(1024)} 与预期 {@code "document_chunk_vector_1024"} 一致。 */
    @Test void requestedDimensionSelectsOnlyWhitelistedStorage() {
        assertEquals("document_chunk_vector_1024", KnowledgeIndexService.table(1024));
        assertEquals("document_chunk_vector_2560", KnowledgeIndexService.table(2560));
        assertThrows(IndexLifecycleException.class, () -> KnowledgeIndexService.table(1536));
    }

    /** 断言抛出 {@code IllegalStateException}。调用路径经过 {@code KnowledgeIndexService.validateVectors}。 */
    @Test void vectorsMustMatchCountDimensionAndFiniteHalfPrecisionValues() {
        List<Double> valid = vector(1024);
        assertDoesNotThrow(() -> KnowledgeIndexService.validateVectors(List.of(valid), 1, 1024));
        assertDoesNotThrow(() -> KnowledgeIndexService.validateVectors(List.of(vector(2560)), 1, 2560));
        assertThrows(IllegalStateException.class, () -> KnowledgeIndexService.validateVectors(List.of(valid), 2, 1024));
        assertThrows(IllegalStateException.class, () -> KnowledgeIndexService.validateVectors(List.of(valid), 1, 2560));
        for (Double bad : List.of(Double.NaN, Double.POSITIVE_INFINITY, 70000.0)) {
            List<Double> invalid = new ArrayList<>(valid); invalid.set(0, bad);
            assertThrows(IllegalStateException.class, () -> KnowledgeIndexService.validateVectors(List.of(invalid), 1, 1024));
        }
        assertThrows(IllegalStateException.class, () -> KnowledgeIndexService.validateVectors(
                List.of(Collections.nCopies(1024, 0.0)), 1, 1024));
        assertThrows(IllegalStateException.class, () -> KnowledgeIndexService.validateVectors(
                List.of(Collections.nCopies(1024, 1e-20)), 1, 1024));
    }

    /**
     * 构造 {@code List<Double>} 供测试使用，使用 {@code Collections.nCopies}。
     * 由 {@code vectorsMustMatchCountDimensionAndFiniteHalfPrecisionValues} 调用。
     * @param dimension 本用例传入的{@code dimension}
     * 返回 {@code result}。
     */
    private static List<Double> vector(int dimension) {
        List<Double> result = new ArrayList<>(Collections.nCopies(dimension, 0.0)); result.set(0, 1.0); return result;
    }

    /**
     * 构造 {@code Map<String, Object>} 供测试使用。
     * 由 {@code statusNeverDecryptsModelsOrProbesTheirEndpoints}、{@code explicitNullExpectedGenerationCannotOverwriteAnExistingActiveIndex} 调用。
     * @param generation 本用例传入的{@code generation}
     * @param dimension 本用例传入的{@code dimension}
     * 返回 {@code Map<String, Object>} 结果。
     */
    private static Map<String, Object> active(String generation, int dimension) {
        return Map.of("generation_id", generation, "space_id", "s1", "owner_id", 11L,
                "model_id", "m1", "semantic_fingerprint", "fingerprint", "dimension", dimension);
    }
    private static final class LocalTransactions extends AbstractPlatformTransactionManager {
        /** 构造 {@code Object} 供测试使用。返回 {@code new Object()}。 */
        @Override protected Object doGetTransaction() { return new Object(); }

        /** 在测试中处理 {@code doBegin} 阶段。接收{@code transaction}、{@code definition}。 */
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }

        /** 在测试中处理 {@code doCommit} 阶段。接收拟使用的状态值。 */
        @Override protected void doCommit(DefaultTransactionStatus status) { }

        /** 在测试中处理 {@code doRollback} 阶段。接收拟使用的状态值。 */
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
}
