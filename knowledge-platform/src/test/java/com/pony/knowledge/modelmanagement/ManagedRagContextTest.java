/* 验证 RAG 请求和模型绑定携带受信任的用户及索引范围。 */

package com.pony.knowledge.modelmanagement;

import com.pony.knowledge.modelmanagement.index.KnowledgeIndexService;
import com.pony.knowledge.observability.RagTraceRecorder;
import com.pony.knowledge.service.ai.AiServiceClient;
import com.pony.knowledge.service.rag.PythonRagPipeline;
import com.pony.knowledge.service.rag.RagStrategy;
import com.pony.knowledge.utils.CurrentUserUtil;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ManagedRagContextTest {
    /** 核对 {@code request.getVersion()} 与预期 {@code "hybrid"} 一致。调用路径经过 {@code users.getUserId}。 */
    @Test void lightQuestionDoesNotResolveAReranker() {
        ModelRegistryService registry = mock(ModelRegistryService.class);
        KnowledgeIndexService indexes = mock(KnowledgeIndexService.class);
        CurrentUserUtil users = mock(CurrentUserUtil.class);
        when(users.getUserId()).thenReturn(42L);
        when(registry.executionForUser(eq(42L), eq(150_000), anySet())).thenReturn(execution(150_000));
        var request = pipeline(registry, indexes, users)
                .buildRequest(List.of(7L), "q", List.of(), 5, "fixed", RagStrategy.LIGHT);

        assertEquals("hybrid", request.getVersion());
        assertEquals("support", request.getEvidenceMode());
        assertEquals(150_000, request.getBudgetMs());
        verify(registry).executionForUser(42L, 150_000, Set.of("generate", "evidence_support"));
    }

    /**
     * 核对 {@code request.getModelExecution().get("indexScopes")} 与预期 {@code List.of()} 一致。
     * 调用路径经过 {@code pipeline.buildRequest}。
     */
    @Test void emptyKnowledgeScopeDoesNotResolveAnyModelOrOwner() {
        ModelRegistryService registry=mock(ModelRegistryService.class);KnowledgeIndexService indexes=mock(KnowledgeIndexService.class);
        CurrentUserUtil users=mock(CurrentUserUtil.class);PythonRagPipeline pipeline=pipeline(registry,indexes,users);
        AiServiceClient.RagQueryRequest request=pipeline.buildRequest(List.of(),"q",List.of(),5,true,"fixed");
        assertEquals(List.of(),request.getModelExecution().get("indexScopes"));
        assertEquals(150_000, request.getBudgetMs());
        assertEquals(150_000, request.getModelExecution().get("budgetMs"));
        verifyNoInteractions(registry,indexes,users);
    }

    /** 核对 {@code request.isGenerate()} 为假。调用路径经过 {@code registry.executionForUser}。 */
    @Test void toolsUseExplicitOwnerWithoutThreadAuthenticationAndDoNotRequireGenerationModel() {
        ModelRegistryService registry=mock(ModelRegistryService.class);KnowledgeIndexService indexes=mock(KnowledgeIndexService.class);
        CurrentUserUtil users=mock(CurrentUserUtil.class);PythonRagPipeline pipeline=pipeline(registry,indexes,users);
        when(registry.executionForUser(eq(42L),eq(35000),anySet())).thenAnswer(call -> execution(35_000));
        when(indexes.resolveIndexScopes(List.of(7L))).thenReturn(List.of(Map.of("knowledgeBaseId",7L,"generationId","g1")));
        try(ModelExecutionContext ignored=ModelExecutionContext.open(execution(35_000),42L)) {
            AiServiceClient.RagQueryRequest request=pipeline.buildRequest(List.of(7L),"q",List.of(),5,false,true,null,"fixed");
            assertFalse(request.isGenerate());assertTrue(request.isAdmitEvidence());
            assertEquals(35_000, request.getBudgetMs());
            assertEquals(35_000, request.getModelExecution().get("budgetMs"));
            verify(registry).executionForUser(42L,35000,Set.of("rerank","evidence_support"));
            assertEquals(List.of(Map.of("knowledgeBaseId",7L,"generationId","g1")),request.getModelExecution().get("indexScopes"));
        }
        verifyNoInteractions(users);
    }

    /** 断言抛出 {@code com.pony.knowledge.exception.RagQueryFailureException}。调用路径经过 {@code users.getUserId}。 */
    @Test void unverifiedIndexIsAnExplicitRagDependencyFailure() {
        ModelRegistryService registry=mock(ModelRegistryService.class);KnowledgeIndexService indexes=mock(KnowledgeIndexService.class);
        CurrentUserUtil users=mock(CurrentUserUtil.class);PythonRagPipeline pipeline=pipeline(registry,indexes,users);
        when(users.getUserId()).thenReturn(1L);
        when(registry.executionForUser(eq(1L),eq(150_000),anySet())).thenAnswer(call -> execution(150_000));
        when(indexes.resolveIndexScopes(List.of(7L))).thenThrow(new com.pony.knowledge.modelmanagement.index.IndexLifecycleException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"requires_rebuild","rebuild required"));
        com.pony.knowledge.exception.RagQueryFailureException error=assertThrows(com.pony.knowledge.exception.RagQueryFailureException.class,
                () -> pipeline.buildRequest(List.of(7L),"q",List.of(),5,true,"fixed"));
        assertEquals("dependency_unavailable",error.getOutcome());assertEquals("requires_rebuild",error.getReasonCode());
    }

    /**
     * 构造 {@code PythonRagPipeline} 供测试使用。
     * 由 {@code lightQuestionDoesNotResolveAReranker}、{@code emptyKnowledgeScopeDoesNotResolveAnyModelOrOwner} 调用。
     * @param registry 本用例传入的{@code registry}
     * @param indexes 本用例传入的{@code indexes}
     * @param users 本用例传入的{@code users}
     * 返回 {@code result}。
     */
    private static PythonRagPipeline pipeline(ModelRegistryService registry,KnowledgeIndexService indexes,CurrentUserUtil users) {
        PythonRagPipeline result=new PythonRagPipeline(mock(AiServiceClient.class),"fixed","hybrid-rerank-rewrite",20,60,"none",1500,false,.7,false,150_000,new RagTraceRecorder());
        result.setManagedModels(registry,indexes,users);return result;
    }

    /**
     * 构造 {@code Map<String, Object>} 供测试使用，使用 {@code value.put}。
     * 由 {@code lightQuestionDoesNotResolveAReranker}、{@code toolsUseExplicitOwnerWithoutThreadAuthenticationAndDoNotRequireGenerationModel} 调用。
     * @param budgetMs 本用例传入的{@code budgetMs}
     * 返回 {@code value}。
     */
    private static Map<String,Object> execution(int budgetMs) {
        Map<String,Object> value=new LinkedHashMap<>();value.put("schemaVersion",1);value.put("budgetMs",budgetMs);
        value.put("bindings",Map.of());value.put("indexScopes",List.of());return value;
    }
}
