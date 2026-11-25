/* 核对 SSE 建连前的认证、校验、执行器与模型故障继续返回结构化 JSON。 */

package com.pony.knowledge.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pony.knowledge.exception.ForbiddenException;
import com.pony.knowledge.exception.GlobalExceptionHandler;
import com.pony.knowledge.modelmanagement.ModelManagementException;
import com.pony.knowledge.modelmanagement.ModelManagementExceptionHandler;
import com.pony.knowledge.service.DocumentService;
import com.pony.knowledge.service.DocumentTaskService;
import com.pony.knowledge.service.KnowledgeBaseAccessService;
import com.pony.knowledge.service.rag.PythonRagPipeline;
import com.pony.knowledge.service.rag.RagStreamClient;
import com.pony.knowledge.service.task.TaskStatusEventBus;
import com.pony.knowledge.utils.CurrentUserUtil;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SsePreflightErrorContractTest {
    private final DocumentTaskService tasks = mock(DocumentTaskService.class);
    private final PythonRagPipeline pipeline = mock(PythonRagPipeline.class);

    /**
     * 构造 {@code MockMvc} 供测试使用，使用 {@code users.getUserId}、{@code access.resolveReadableIds}。
     * 由 {@code taskPermissionFailureRemains403JsonForSseOnlyAccept}、{@code unsupportedStreamEngineRemains400Json} 调用。
     * @param executor 本用例传入的{@code executor}
     * 返回 {@code MockMvc} 结果。
     */
    private MockMvc mvc(Executor executor) {
        CurrentUserUtil users = mock(CurrentUserUtil.class);
        KnowledgeBaseAccessService access = mock(KnowledgeBaseAccessService.class);
        when(users.getUserId()).thenReturn(7L);
        when(access.resolveReadableIds(7L)).thenReturn(Set.of(10L));
        return MockMvcBuilders.standaloneSetup(
                new DocumentController(mock(DocumentService.class), tasks, new TaskStatusEventBus()),
                new KnowledgeChatStreamController(users, access, pipeline, mock(RagStreamClient.class),
                        new ObjectMapper(), executor, 40000))
                .setControllerAdvice(new ModelManagementExceptionHandler(), new GlobalExceptionHandler()).build();
    }

    /** 核对 HTTP 403，并确认{@code $.code} 为 {@code 403}、{@code $.code} 为 {@code 403}。调用路径经过 {@code tasks.getTask}。 */
    @Test void taskPermissionFailureRemains403JsonForSseOnlyAccept() throws Exception {
        when(tasks.getTask(12L)).thenThrow(new ForbiddenException("无权查看该文档任务"));
        mvc(Runnable::run).perform(get("/document/12/task/stream").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isForbidden()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(403)).andExpect(request().asyncNotStarted());
    }

    /** 核对 HTTP 400。 */
    @Test void unsupportedStreamEngineRemains400Json() throws Exception {
        mvc(Runnable::run).perform(post("/knowledge/ask/stream").accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"test\",\"pipelineEngine\":\"langchain\"}"))
                .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(400)).andExpect(request().asyncNotStarted());
        verifyNoInteractions(pipeline);
    }

    /** 核对 HTTP 400。 */
    @Test void validationFailureRemains400JsonWithoutOpeningStream() throws Exception {
        mvc(Runnable::run).perform(post("/knowledge/ask/stream").accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"\"}"))
                .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(400)).andExpect(request().asyncNotStarted());
    }

    /** 核对 HTTP 503。 */
    @Test void saturatedStreamExecutorRemains503Json() throws Exception {
        mvc(task -> { throw new RejectedExecutionException("pool full"); })
                .perform(post("/knowledge/ask/stream").accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"test\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(503)).andExpect(request().asyncNotStarted());
    }

    /** 核对 HTTP 503。调用路径经过 {@code pipeline.buildRequest}。 */
    @Test void missingCurrentModelRemains503JsonWithReasonCode() throws Exception {
        when(pipeline.buildRequest(anyList(), anyString(), anyList(), anyInt(), anyBoolean(), anyString()))
                .thenThrow(ModelManagementException.unavailable("请配置当前版本模型"));
        mvc(Runnable::run).perform(post("/knowledge/ask/stream").accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"test\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(503)).andExpect(jsonPath("$.data.reasonCode").value("model_not_configured"))
                .andExpect(request().asyncNotStarted());
    }
}
