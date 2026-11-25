/* 承接跨可读知识库的同步问答请求。 */

package com.pony.knowledge.controller;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.pony.knowledge.dto.RAGResponseDTO;
import com.pony.knowledge.service.rag.RagService;
import com.pony.knowledge.service.rag.RagStrategy;
import com.pony.knowledge.validation.RagInputRules;
import com.pony.knowledge.validation.RagQuestion;
import com.pony.knowledge.validation.StrictIntegerDeserializer;
import com.pony.knowledge.validation.StrictStringDeserializer;
import lombok.Data;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 单轮问答入口 POST /knowledge/ask：补齐 topK 后交给 RagService。
 * 检索范围由 Service 根据当前用户的创建库和成员库合并，最终直接返回专用问答响应。
 */
@RestController
@RequestMapping("/knowledge")
public class KnowledgeChatController {
    private final RagService ragService;

    /**
     * 保存承接跨可读知识库的同步问答请求所需的依赖和初始配置。
     * 上游为 Spring 组件装配。
     *
     * @param ragService 本次操作使用的 RagService 组件或记录
     */
    public KnowledgeChatController(
            RagService ragService
    ){
        this.ragService = ragService;
    }

    /**
     * 按当前用户的可读知识库执行同步问答并返回响应。
     * 上游为 HTTP POST /knowledge/ask。
     * 下游为 RagStrategy.fromRequest、RagService.ask。
     *
     * @param request 当前调用的请求参数
     * @return 同步知识问答的答案、获准来源及执行状态，以 RAGResponseDTO 返回
     */
    @PostMapping("/ask")
    public RAGResponseDTO ask(@Valid @RequestBody AskRequest request){
        Integer topKValue = request.getTopK();
        int topK = topKValue == null ? RagInputRules.DEFAULT_TOP_K : topKValue;

        RagStrategy strategy = RagStrategy.fromRequest(request.getStrategy());

        if (strategy != RagStrategy.DEFAULT) {
            return ragService.ask(request.getQuestion(), topK, request.getPipelineEngine(), strategy);
        }
        return request.getPipelineEngine() == null
                ? ragService.ask(request.getQuestion(), topK)
                : ragService.ask(request.getQuestion(), topK, request.getPipelineEngine());
    }

    /** 单轮问答输入。静态内部类可由 JSON 转换器独立创建，编排引擎字段限定为支持的取值。 */
    @Data
    public static class AskRequest{
        @Pattern(regexp = RagInputRules.PIPELINE_ENGINE_PATTERN,
                message = "pipelineEngine 只能是 fixed 或 langchain")
        @JsonDeserialize(using = StrictStringDeserializer.class)
        private String pipelineEngine;

        @Pattern(regexp = RagInputRules.STRATEGY_PATTERN, message = "不支持该问答策略")
        @JsonDeserialize(using = StrictStringDeserializer.class)
        private String strategy;

        @RagQuestion
        @JsonDeserialize(using = StrictStringDeserializer.class)
        private String question;

        @Min(RagInputRules.MIN_TOP_K)
        @Max(RagInputRules.MAX_TOP_K)
        @JsonDeserialize(using = StrictIntegerDeserializer.class)
        private Integer topK;
    }
}
