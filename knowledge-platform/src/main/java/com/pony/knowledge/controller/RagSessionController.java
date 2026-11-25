/* 管理普通问答会话、分页历史及会话内的同步提问。 */

package com.pony.knowledge.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pony.knowledge.common.Result;
import com.pony.knowledge.dto.RAGResponseDTO;
import com.pony.knowledge.service.rag.RagService;
import com.pony.knowledge.service.rag.RagSessionService;
import com.pony.knowledge.service.rag.RagStrategy;
import com.pony.knowledge.utils.CurrentUserUtil;
import com.pony.knowledge.validation.RagInputRules;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/rag/sessions")
@RequiredArgsConstructor
public class RagSessionController {
    private final RagSessionService sessions;
    private final RagService rag;
    private final ObjectMapper json;
    private final CurrentUserUtil user;

    /**
     * 为当前账号创建普通问答会话并返回会话标识。
     * 上游为 HTTP POST /rag/sessions。
     * 下游为 Result.success、RagSessionService.create。
     *
     * @param request 当前调用的请求参数
     * @return 包含 RagSessionService.SessionView 数据的业务响应
     */
    @PostMapping
    public Result<RagSessionService.SessionView> create(@Valid @RequestBody SessionInput request) {
        return Result.success(sessions.create(user.getUserId(), request.knowledgeBaseId()));
    }

    /**
     * 按列表游标读取当前账号可见的问答会话。
     * 上游为 HTTP GET /rag/sessions。
     * 下游为 Result.success、RagSessionService.list。
     *
     * @param offset 从窗口起点计算的偏移
     * @return 包含 RagSessionService.SessionPage 数据的业务响应
     */
    @GetMapping
    public Result<RagSessionService.SessionPage> list(@RequestParam(defaultValue = "0") int offset) {
        return Result.success(sessions.list(user.getUserId(), offset));
    }

    /**
     * 按问答会话和历史游标读取已提交的回合。
     * 上游为 HTTP GET /rag/sessions/{id}/history。
     * 下游为 Result.success、RagSessionService.history。
     *
     * @param id 问答会话 ID
     * @param beforeId 要定位的关联标识
     * @return 包含 RagSessionService.TurnPage 数据的业务响应
     */
    @GetMapping("/{id}/history")
    public Result<RagSessionService.TurnPage> history(@PathVariable UUID id, @RequestParam(required = false) Long beforeId) {
        return Result.success(sessions.history(user.getUserId(), id, beforeId));
    }

    /**
     * 删除指定的用户问答会话。
     * 上游为 HTTP DELETE /rag/sessions/{id}。
     * 下游为 RagSessionService.delete、Result.success。
     *
     * @param id 问答会话 ID
     * @return 包含 String 数据的业务响应
     */
    @DeleteMapping("/{id}")
    public Result<String> delete(@PathVariable UUID id) {
        sessions.delete(user.getUserId(), id);
        return Result.success("会话已删除");
    }

    /**
     * 先获取会话回合的执行权，再提交问答和历史，并归还执行权。
     * 上游为 HTTP POST /rag/sessions/{id}/ask。
     * 下游为 RagSessionService.begin、RagService.askInSession。
     *
     * @param id 问答会话 ID
     * @param request 当前调用的请求参数
     * @return 同步知识问答的答案、获准来源及执行状态，以 RAGResponseDTO 返回
     */
    @PostMapping("/{id}/ask")
    public RAGResponseDTO ask(@PathVariable UUID id, @Valid @RequestBody KnowledgeChatController.AskRequest request) {
        var lease = sessions.begin(user.getUserId(), id);
        try {
            int topK = request.getTopK() == null ? RagInputRules.DEFAULT_TOP_K : request.getTopK();
            RAGResponseDTO answer = rag.askInSession(lease, request.getQuestion(), sessions.context(lease), topK,
                    request.getPipelineEngine(), RagStrategy.fromRequest(request.getStrategy()));
            sessions.complete(lease, request.getQuestion(), json.valueToTree(answer));
            return answer;
        } finally {
            sessions.end(lease);
        }
    }

    public record SessionInput(@Positive Long knowledgeBaseId) { }
}
