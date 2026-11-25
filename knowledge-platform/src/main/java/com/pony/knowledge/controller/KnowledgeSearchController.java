/* 解析当前用户的知识库权限，执行纯检索并映射切片结果。 */

package com.pony.knowledge.controller;

import com.pony.knowledge.dto.ChunkSearchResultDTO;
import com.pony.knowledge.observability.RagStageTracer;
import com.pony.knowledge.service.KnowledgeBaseAccessService;
import com.pony.knowledge.service.rag.PythonRagPipeline;
import com.pony.knowledge.service.rag.model.RagEvidence;
import com.pony.knowledge.service.rag.model.RagQueryOutcome;
import com.pony.knowledge.utils.CurrentUserUtil;
import com.pony.knowledge.validation.RagInputRules;
import com.pony.knowledge.validation.RagQuestion;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 纯检索入口 GET /knowledge/search。
 * 当前用户的可读库范围由 Java 计算；请求不生成答案，返回排序候选，与问答的获准来源分别处理。
 */
@RestController
@RequestMapping("/knowledge")
public class KnowledgeSearchController {
    private final PythonRagPipeline pythonRagPipeline;

    private final KnowledgeBaseAccessService knowledgeBaseAccessService;

    private final CurrentUserUtil currentUserUtil;

    private final RagStageTracer ragStageTracer;

    /**
     * 保存解析当前用户的知识库权限所需的依赖和初始配置。
     * 上游为 Spring 组件装配。
     *
     * @param pythonRagPipeline 本次操作使用的 PythonRagPipeline 组件或记录
     * @param knowledgeBaseAccessService KnowledgeBaseAccessService，负责约定知识库可读范围和创建者操作的权限判定
     * @param currentUserUtil 本次操作使用的 CurrentUserUtil 组件或记录
     * @param ragStageTracer 本次操作使用的 RagStageTracer 组件或记录
     */
    public KnowledgeSearchController(
            PythonRagPipeline pythonRagPipeline,
            KnowledgeBaseAccessService knowledgeBaseAccessService,
            CurrentUserUtil currentUserUtil,
            RagStageTracer ragStageTracer) {

        this.pythonRagPipeline = pythonRagPipeline;
        this.knowledgeBaseAccessService = knowledgeBaseAccessService;
        this.currentUserUtil = currentUserUtil;
        this.ragStageTracer = ragStageTracer;
    }

    /**
     * 校验可读库范围，执行纯检索并公开映射后的切片。
     *
     * @param query 调用方的查询内容或查询条件
     * @param topK 保留的候选数量上限
     * @return 与过滤条件匹配的 List&lt;ChunkSearchResultDTO&gt; 数据集合
     */
    public List<ChunkSearchResultDTO> search(String query, int topK) {
        return search(query, topK, null);
    }

    /**
     * 校验可读库范围，执行纯检索并公开映射后的切片。
     * 上游为 HTTP GET /knowledge/search。
     * 下游为 RagStageTracer.observeRoot、PythonRagPipeline.prepareTrace。
     *
     * @param query 调用方的查询内容或查询条件
     * @param topK 保留的候选数量上限
     * @param pipelineEngine 问答管线名称
     * @return 与过滤条件匹配的 List&lt;ChunkSearchResultDTO&gt; 数据集合
     */
    @GetMapping("/search")
    public List<ChunkSearchResultDTO> search(@RequestParam @RagQuestion String query,
            @RequestParam(defaultValue = "5") @Min(RagInputRules.MIN_TOP_K) @Max(RagInputRules.MAX_TOP_K) int topK,
            @RequestParam(required = false)
            @Pattern(regexp = RagInputRules.PIPELINE_ENGINE_PATTERN,
                    message = "pipelineEngine 只能是 fixed 或 langchain") String pipelineEngine) {
        return ragStageTracer.observeRoot("search", () -> {
            pythonRagPipeline.prepareTrace(pipelineEngine);
            // default-deny：空集合 → Python 侧返回空证据，绝不做全局检索
            List<Long> authorized = new ArrayList<>(knowledgeBaseAccessService.resolveReadableIds(
                    currentUserUtil.getUserId()));
            RagQueryOutcome out = pipelineEngine == null
                    ? pythonRagPipeline.retrieveOnly(authorized, query, List.of(), topK)
                    : pythonRagPipeline.retrieveOnly(authorized, query, List.of(), topK, pipelineEngine);
            // 纯检索返回原始排序候选；问答对外来源则使用获准证据。
            return out.getSources().stream().map(KnowledgeSearchController::toDto).toList();
        });
    }

    /**
     * 把内部检索候选转换为纯检索响应中的切片及来源。
     * 下游为 RagEvidence.getChunkId、RagEvidence.getDocumentId。
     *
     * @param e 本次执行捕获的异常
     * @return 纯检索返回的切片、知识库、位置与距离信息，以 ChunkSearchResultDTO 返回
     */
    private static ChunkSearchResultDTO toDto(RagEvidence e) {
        ChunkSearchResultDTO dto = new ChunkSearchResultDTO();
        dto.setId(e.getChunkId());
        dto.setDocumentId(e.getDocumentId());
        dto.setKnowledgeBaseId(e.getKnowledgeBaseId());
        dto.setChunkIndex(e.getChunkIndex());
        dto.setContent(e.getContent());
        dto.setDistance(e.getDistance());
        dto.setHeadingPath(e.getHeadingPath());
        dto.setPageNo(e.getPageNo());
        dto.setSourceType(e.getSourceType());
        return dto;
    }
}
