/* 提供上传、回执、切片、删除和后台任务查询及订阅接口。 */

package com.pony.knowledge.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.pony.knowledge.common.Result;
import com.pony.knowledge.dto.DocumentPageQueryDTO;
import com.pony.knowledge.dto.DocumentChunkDTO;
import com.pony.knowledge.dto.DocumentChunkPageQueryDTO;
import com.pony.knowledge.dto.DocumentTaskDTO;
import com.pony.knowledge.dto.DocumentUploadReceiptDTO;
import com.pony.knowledge.dto.DocumentUploadReceiptLookupDTO;
import com.pony.knowledge.entity.Document;
import com.pony.knowledge.service.DocumentService;
import com.pony.knowledge.service.DocumentTaskService;
import com.pony.knowledge.service.task.TaskStatusEventBus;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 文档业务入口：上传与回执、文档和切片查询、删除、任务快照、状态订阅和失败重试。
 * 上传交给 DocumentService，任务查询与重试交给 DocumentTaskService；SSE 先鉴权再订阅并补读快照。
 */
@RestController
@RequestMapping("/document")
@RequiredArgsConstructor
public class DocumentController {

    private static final Logger log = LoggerFactory.getLogger(DocumentController.class);

    private final DocumentService documentService;

    private final DocumentTaskService documentTaskService;

    private final TaskStatusEventBus eventBus;

    /**
     * 按知识库和可选幂等键接收 multipart 原文件，返回包含文档与任务 ID 的受理回执。
     * 上游为 前端 frontend/src/api/library.ts 的 uploadDocument（POST /document/upload）。
     * 下游为 Result.success、DocumentService.upload。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param file 上传的原文件
     * @param requestKey 本次上传使用的幂等请求键
     * @return 包含 DocumentUploadReceiptDTO 数据的业务响应
     */
    @PostMapping("/upload")
    public Result<DocumentUploadReceiptDTO> upload(
            @RequestParam Long knowledgeBaseId,
            @RequestParam MultipartFile file,
            @RequestHeader(value = "Idempotency-Key", required = false) String requestKey
            ){
        return Result.success(documentService.upload(knowledgeBaseId, file, requestKey));
    }

    /**
     * 按原上传请求键找回当前用户在指定知识库的回执。
     * 上游为 前端 frontend/src/api/library.ts 的 lookupUploadReceipt（GET /document/upload/receipt）。
     * 下游为 Result.success、DocumentService.findUploadReceipt。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param requestKey 本次上传使用的幂等请求键
     * @return 包含 DocumentUploadReceiptLookupDTO 数据的业务响应
     */
    @GetMapping("/upload/receipt")
    public Result<DocumentUploadReceiptLookupDTO> uploadReceipt(
            @RequestParam Long knowledgeBaseId,
            @RequestParam String requestKey) {
        return Result.success(documentService.findUploadReceipt(knowledgeBaseId, requestKey));
    }

    /**
     * 请求删除指定文档，返回操作状态。
     * 上游为 前端 frontend/src/api/library.ts 的 deleteDocument（DELETE /document/{documentId}）。
     * 下游为 DocumentService.delete、Result.success。
     *
     * @param documentId 文档 ID
     * @return 包含 String 数据的业务响应
     */
    @DeleteMapping("/{documentId}")
    public Result<String> delete(@PathVariable Long documentId){
        documentService.delete(documentId);

        return Result.success("文档删除成功");
    }

    /**
     * 按知识库与分页参数读取当前用户可见的文档列表。
     * 上游为 前端 frontend/src/api/library.ts 的 listDocuments（GET /document/list）。
     * 下游为 Result.success、DocumentService.page。
     *
     * @param dto 携带当前操作参数的 DocumentPageQueryDTO 数据
     * @return 包含 IPage&lt;Document&gt; 数据的业务响应
     */
    @GetMapping("/list")
    public Result<IPage<Document>> page(@Valid DocumentPageQueryDTO dto){
        return Result.success(documentService.page(dto));
    }

    /**
     * 读取已授权文档的切片正文和来源元数据页。
     * 上游为 前端 frontend/src/api/library.ts 的 listDocumentChunks（GET /document/{documentId}/chunks）。
     * 下游为 Result.success、DocumentService.pageChunks。
     *
     * @param documentId 文档 ID
     * @param query 调用方的查询内容或查询条件
     * @return 包含 IPage&lt;DocumentChunkDTO&gt; 数据的业务响应
     */
    @GetMapping("/{documentId}/chunks")
    public Result<IPage<DocumentChunkDTO>> chunks(@PathVariable Long documentId,
                                                @Valid DocumentChunkPageQueryDTO query) {
        return Result.success(documentService.pageChunks(documentId, query));
    }

    /**
     * 返回指定文档当前任务的状态快照。
     * 上游为 前端 frontend/src/api/library.ts 的 getDocumentTask（GET /document/{documentId}/task）。
     * 下游为 Result.success。
     *
     * @param documentId 文档 ID
     * @return 包含 DocumentTaskDTO 数据的业务响应
     */
    @GetMapping("/{documentId}/task")
    public Result<DocumentTaskDTO> getTask(@PathVariable Long documentId){
        return Result.success(documentTaskService.getTask(documentId));
    }

    /**
     * 为已授权文档建立任务 SSE，在推送前后协调初始状态和订阅清理。
     * 上游为 前端 frontend/src/api/library.ts 的 watchDocumentTask（GET /document/{documentId}/task/stream）。
     * 下游为 TaskStatusEventBus.subscribe、TaskStatusEventBus.unsubscribe。
     *
     * @param documentId 文档 ID
     * @return 持续发送进度或结果、完成后关闭的 SSE 连接
     */
    @GetMapping("/{documentId}/task/stream")
    public SseEmitter streamTask(@PathVariable Long documentId) {
        // 先通过 getTask 做鉴权与存在性检查（KB creator ∪ member，越权抛 ForbiddenException→403），
        // 再订阅并重新读快照，避免「第一次读完、事件发布、订阅尚未建立」的丢事件窗口。
        documentTaskService.getTask(documentId);

        SseEmitter emitter = new SseEmitter(180_000L);
        TaskStatusEventBus.Subscription subscription = eventBus.subscribe(documentId, emitter);
        emitter.onCompletion(() -> eventBus.unsubscribe(subscription));
        emitter.onTimeout(() -> {
            try {
                // Complete as a normal SSE response so an idle stream timeout does not fall
                // through to exception resolution for the text/event-stream representation.
                emitter.complete();
            } catch (RuntimeException completionFailure) {
                // A container may already have dispatched an async error. Do not let cleanup
                // from a client lifecycle callback escape into application request handling.
                log.debug("SSE 任务流超时后的完成回调失败，documentId={}", documentId, completionFailure);
            } finally {
                eventBus.unsubscribe(subscription);
            }
        });
        emitter.onError(e -> eventBus.unsubscribe(subscription));

        try {
            DocumentTaskDTO freshSnapshot = documentTaskService.getTask(documentId);
            // If an event was published after subscribe(), the bus already sent that newer state;
            // its revision check suppresses this possibly stale DB snapshot.
            eventBus.sendInitialSnapshotIfUnchanged(subscription, freshSnapshot);
        } catch (RuntimeException e) {
            eventBus.unsubscribe(subscription);
            try {
                emitter.completeWithError(e);
            } catch (RuntimeException completionFailure) {
                // Preserve the original snapshot failure if the async response has already errored.
                log.debug("SSE 任务流错误完成回调失败，documentId={}", documentId, completionFailure);
            }
            throw e;
        }
        return emitter;
    }

    /**
     * 为文档申请重试，并返回重试后重新读取的任务状态。
     * 上游为 前端 frontend/src/api/library.ts 的 retryDocumentTask（POST /document/{documentId}/retry）。
     * 下游为 DocumentTaskService.retry、Result.success。
     *
     * @param documentId 文档 ID
     * @return 包含 DocumentTaskDTO 数据的业务响应
     */
    @PostMapping("/{documentId}/retry")
    public Result<DocumentTaskDTO> retry(@PathVariable Long documentId){
        documentTaskService.retry(documentId);
        return Result.success(documentTaskService.getTask(documentId));
    }

    /**
     * 取消指定文档和任务 ID 对应的处理，并返回最新状态。
     * 上游为 前端 frontend/src/api/library.ts 的 cancelDocumentTask（POST /document/{documentId}/task/{taskId}/cancel）。
     * 下游为 Result.success、DocumentTaskService.cancel。
     *
     * @param documentId 文档 ID
     * @param taskId 文档任务 ID
     * @return 包含 DocumentTaskDTO 数据的业务响应
     */
    @PostMapping("/{documentId}/task/{taskId}/cancel")
    public Result<DocumentTaskDTO> cancelTask(@PathVariable Long documentId, @PathVariable Long taskId) {
        return Result.success(documentTaskService.cancel(documentId, taskId));
    }
}
