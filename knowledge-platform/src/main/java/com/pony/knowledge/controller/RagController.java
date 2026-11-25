/* 承接指定知识库和会话的同步问答请求。 */

package com.pony.knowledge.controller;

import com.pony.knowledge.dto.RagChatRequestDTO;
import com.pony.knowledge.dto.RagChatResponseDTO;
import com.pony.knowledge.service.rag.RagService;
import com.pony.knowledge.service.rag.RagStrategy;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 指定知识库的会话问答入口 POST /rag/chat。
 * 传递知识库、会话标识、问题及可选引擎；资源授权、历史读取和回答存储由 RagService 编排。
 */
@RestController
@RequestMapping("/rag")
@RequiredArgsConstructor
public class RagController {
    private final RagService ragService;

    /**
     * 把指定知识库和传统会话的同步请求转交给问答服务。
     * 上游为 HTTP POST /rag/chat。
     * 下游为 RagStrategy.fromRequest、RagService.chat。
     *
     * @param request 当前调用的请求参数
     * @return 指定知识库问答的答案、证据、降级与执行信息，以 RagChatResponseDTO 返回
     */
    @PostMapping("/chat")
    public RagChatResponseDTO chat(@Valid @RequestBody RagChatRequestDTO request) {
        RagStrategy strategy = RagStrategy.fromRequest(request.getStrategy());

        if (strategy != RagStrategy.DEFAULT) {
            return ragService.chat(request.getKnowledgeBaseId(), request.getConversationId(),
                    request.getQuestion(), request.getTopK(), request.getPipelineEngine(), strategy);
        }
        if (request.getPipelineEngine() != null) {
            return ragService.chat(request.getKnowledgeBaseId(), request.getConversationId(),
                    request.getQuestion(), request.getTopK(), request.getPipelineEngine());
        }
        return ragService.chat(
                request.getKnowledgeBaseId(),
                request.getConversationId(),
                request.getQuestion(),
                request.getTopK()
        );
    }
}
