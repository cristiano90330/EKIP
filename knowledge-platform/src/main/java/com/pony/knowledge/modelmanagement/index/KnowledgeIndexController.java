/* 提供知识库索引状态、重建申请、任务查询和取消接口。 */

package com.pony.knowledge.modelmanagement.index;

import com.pony.knowledge.common.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/knowledge-bases/{knowledgeBaseId}/index")
public class KnowledgeIndexController {
    private final KnowledgeIndexService indexes;

    /**
     * 保存提供知识库索引状态、重建申请、任务查询和取消接口所需的依赖和初始配置。
     * 上游为 Spring 组件装配。
     *
     * @param indexes KnowledgeIndexService，负责管理活动索引代际、向量空间和可恢复的影子索引重建任务
     */
    public KnowledgeIndexController(KnowledgeIndexService indexes) { this.indexes = indexes; }

    /**
     * 返回当前账号可读取的知识库索引状态。
     * 上游为 HTTP GET /knowledge-bases/{knowledgeBaseId}/index。
     * 下游为 KnowledgeIndexService.status。
     *
     * @param knowledgeBaseId 知识库 ID
     * @return 包含 Map&lt;String, Object&gt; 数据的业务响应
     */
    @GetMapping
    public Result<Map<String, Object>> status(@PathVariable long knowledgeBaseId) {
        return new Result<>(200, "success", indexes.status(knowledgeBaseId));
    }

    /**
     * 申请用目标模型重建知识库索引，并返回任务状态。
     * 上游为 HTTP POST /knowledge-bases/{knowledgeBaseId}/index/rebuilds。
     * 下游为 KnowledgeIndexService.startRebuild、RebuildRequest.modelId。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param request 当前调用的请求参数
     * @return 包含 Map&lt;String, Object&gt; 数据的业务响应
     */
    @PostMapping("/rebuilds")
    public Result<Map<String, Object>> rebuild(@PathVariable long knowledgeBaseId,
                                               @Valid @RequestBody RebuildRequest request) {
        return new Result<>(200, "重建任务已创建", indexes.startRebuild(knowledgeBaseId,
                request.modelId(), request.expectedActiveGenerationId()));
    }

    /**
     * 按知识库和任务标识读取索引重建进度。
     * 上游为 HTTP GET /knowledge-bases/{knowledgeBaseId}/index/rebuilds/{taskId}。
     * 下游为 KnowledgeIndexService.taskStatus。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param taskId 索引重建任务 ID
     * @return 包含 Map&lt;String, Object&gt; 数据的业务响应
     */
    @GetMapping("/rebuilds/{taskId}")
    public Result<Map<String, Object>> task(@PathVariable long knowledgeBaseId, @PathVariable String taskId) {
        return new Result<>(200, "success", indexes.taskStatus(knowledgeBaseId, taskId));
    }

    /**
     * 申请取消仍可中止的索引重建任务。
     * 上游为 HTTP POST /knowledge-bases/{knowledgeBaseId}/index/rebuilds/{taskId}/cancel。
     * 下游为 KnowledgeIndexService.cancelRebuild。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param taskId 索引重建任务 ID
     * @return 包含 Map&lt;String, Object&gt; 数据的业务响应
     */
    @PostMapping("/rebuilds/{taskId}/cancel")
    public Result<Map<String, Object>> cancel(@PathVariable long knowledgeBaseId, @PathVariable String taskId) {
        Map<String, Object> task = indexes.cancelRebuild(knowledgeBaseId, taskId);
        String message = switch (String.valueOf(task.get("status"))) {
            case "cancelled" -> "重建任务已取消";
            case "completed" -> "重建任务已完成，无需取消";
            case "failed" -> "重建任务已失败，无需取消";
            case "waiting", "processing" -> "重建任务尚未取消，请查询最新状态";
            default -> "已返回重建任务当前状态";
        };
        return new Result<>(200, message, task);
    }

    public record RebuildRequest(@NotBlank String modelId, String expectedActiveGenerationId) { }
}
