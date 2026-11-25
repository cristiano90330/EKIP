/* 验证批量与单次评测对操作者及知识库范围执行授权检查。 */

package com.pony.knowledge.service.impl;

import com.pony.knowledge.dto.EvaluationBatchRequestDTO;
import com.pony.knowledge.dto.EvaluationRequestDTO;
import com.pony.knowledge.exception.ForbiddenException;
import com.pony.knowledge.service.KnowledgeBaseAccessService;
import com.pony.knowledge.service.ai.AiServiceClient;
import com.pony.knowledge.service.rag.PythonRagPipeline;
import com.pony.knowledge.utils.CurrentUserUtil;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class EvaluationServiceAuthorizationTest {

    private final KnowledgeBaseAccessService access = mock(KnowledgeBaseAccessService.class);
    private final CurrentUserUtil currentUser = mock(CurrentUserUtil.class);
    private final AiServiceClient ai = mock(AiServiceClient.class);
    private final PythonRagPipeline pipeline = mock(PythonRagPipeline.class);
    private final EvaluationServiceImpl service = new EvaluationServiceImpl(access, currentUser, ai,
            pipeline, "hybrid-rerank", 2.0);

    /**
     * 返回当前计算采用的 {@code request}。
     *
     * @param knowledgeBaseId 本次调用传入的 {@code Long}
     * @return 当前记录中的 {@code request}
     */
    private EvaluationRequestDTO request(Long knowledgeBaseId) {
        EvaluationRequestDTO request = new EvaluationRequestDTO();
        request.setKnowledgeBaseId(knowledgeBaseId);
        request.setQuestion("q");
        return request;
    }

    /** 通过 {@code access.canRead}、{@code service.evaluateRetrieval} 驱动该场景，抛出 {@code ForbiddenException}，跳过受保护的调用。 */
    @Test
    void readPermissionDoesNotAuthorizeEvaluationOrCallAnyDependency() {
        when(currentUser.getUserId()).thenReturn(9L);
        when(access.canRead(9L, 8L)).thenReturn(true);

        assertThrows(ForbiddenException.class, () -> service.evaluateRetrieval(request(8L)));

        verify(access).canEvaluate(9L, 8L);
        verifyNoInteractions(ai, pipeline);
    }

    /** 让批次中包含不可执行的知识库，检查首个样本开始前完成全批授权。 */
    @Test
    void batchAuthorizesEveryLibraryBeforeTakingTheFirstSample() {
        when(currentUser.getUserId()).thenReturn(9L);
        when(access.canEvaluate(9L, 3L)).thenReturn(true);
        EvaluationBatchRequestDTO request = new EvaluationBatchRequestDTO();
        request.setCases(List.of(request(3L), request(8L)));
        request.setEvaluateGeneration(true);

        assertThrows(ForbiddenException.class, () -> service.evaluateBatch(request));

        verify(access).canEvaluate(9L, 3L);
        verify(access).canEvaluate(9L, 8L);
        verifyNoInteractions(ai, pipeline);
    }
}
