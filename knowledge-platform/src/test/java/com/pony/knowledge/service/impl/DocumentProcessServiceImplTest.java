/* 验证文档后台认领、调用解析、完成结果及失败重试的编排。 */

package com.pony.knowledge.service.impl;

import com.pony.knowledge.config.TaskReliabilityProperties;
import com.pony.knowledge.entity.Document;
import com.pony.knowledge.entity.DocumentTask;
import com.pony.knowledge.entity.DocumentStatus;
import com.pony.knowledge.entity.DocumentTaskStatus;
import com.pony.knowledge.mapper.DocumentMapper;
import com.pony.knowledge.mapper.DocumentTaskMapper;
import com.pony.knowledge.service.DocumentChunkService;
import com.pony.knowledge.service.ai.AiServiceClient;
import com.pony.knowledge.service.task.TaskFailureClassifier;
import com.pony.knowledge.service.task.TaskDispatchCoordinator;
import com.pony.knowledge.service.task.TaskLeaseHeartbeat;
import com.pony.knowledge.service.task.TaskStatusEventBus;
import com.pony.knowledge.service.task.TaskCompletionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentProcessServiceImplTest {

    private DocumentTaskMapper taskMapper;
    private DocumentMapper documentMapper;
    private AiServiceClient ai;
    private TaskDispatchCoordinator dispatchCoordinator;
    private TaskStatusEventBus eventBus;
    private TaskReliabilityProperties properties;
    private DocumentProcessServiceImpl service;
    private TaskCompletionService completionService;
    private DocumentChunkService chunkService;
    private TaskLeaseHeartbeat heartbeat;

    /** 准备本组用例共享的测试数据和隔离环境。 */
    @BeforeEach
    void setUp() {
        taskMapper = mock(DocumentTaskMapper.class);
        documentMapper = mock(DocumentMapper.class);
        ai = mock(AiServiceClient.class);
        chunkService = mock(DocumentChunkService.class);
        dispatchCoordinator = mock(TaskDispatchCoordinator.class);
        eventBus = mock(TaskStatusEventBus.class);
        properties = new TaskReliabilityProperties(); // maxAttempts=3
        completionService = mock(TaskCompletionService.class);
        heartbeat = mock(TaskLeaseHeartbeat.class);
        service = new DocumentProcessServiceImpl(
                documentMapper, taskMapper, ai, chunkService,
                "knowledge-files", new TaskFailureClassifier(),
                dispatchCoordinator, eventBus, properties, completionService, heartbeat);
    }

    /**
     * 返回当前计算采用的 {@code t}。
     *
     * @param autoRetryCount 本次调用传入的 {@code int}
     * @return 当前记录中的 {@code t}
     */
    private static DocumentTask task(int autoRetryCount) {
        DocumentTask t = new DocumentTask();
        t.setId(1L);
        t.setDocumentId(10L);
        t.setStatus(autoRetryCount == 0 ? DocumentTaskStatus.WAITING : DocumentTaskStatus.PROCESSING);
        t.setAutoRetryCount(autoRetryCount);
        t.setTotalAttemptCount(autoRetryCount);
        return t;
    }

    /**
     * 返回当前计算采用的 {@code d}。
     *
     * @return 当前记录中的 {@code d}
     */
    private static Document doc() {
        Document d = new Document();
        d.setId(10L);
        d.setKnowledgeBaseId(50L);
        d.setFileName("a.pdf");
        d.setFilePath("a.pdf");
        d.setStatus(DocumentStatus.UPLOADED);
        return d;
    }

    /** 通过 {@code taskMapper.selectById}、{@code documentMapper.selectById} 驱动该场景，跳过受保护的调用。 */
    @Test
    void successClaimsOnceMarksSuccessAndPublishes() {
        DocumentTask attempt = task(1);
        when(taskMapper.selectById(1L)).thenReturn(task(0), attempt);
        when(documentMapper.selectById(10L)).thenReturn(doc());
        when(completionService.claim(1L, 10L)).thenReturn(attempt);
        var registration = mock(TaskLeaseHeartbeat.Registration.class);
        when(heartbeat.maintain(attempt)).thenReturn(registration);
        when(chunkService.completeTask(eq(attempt), eq(50L), any())).thenReturn(true);
        when(ai.parseDocument(anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(new AiServiceClient.ParseResult());

        service.process(1L);

        var order = inOrder(heartbeat, ai, chunkService, registration);
        order.verify(heartbeat).maintain(attempt);
        order.verify(ai).parseDocument(anyLong(), anyString(), anyString(), anyString());
        order.verify(chunkService).completeTask(eq(attempt), eq(50L), any());
        order.verify(registration).close();
        verify(completionService, never()).markFailed(any(DocumentTask.class), anyString(), anyString());
        verify(dispatchCoordinator, never()).scheduleRetry(any(), anyLong());
        verify(eventBus, never()).publish(any(DocumentTask.class));
    }

    /** 通过 {@code taskMapper.selectById}、{@code documentMapper.selectById} 驱动该场景，跳过受保护的调用。 */
    @Test
    void transientFailureUnderMaxSchedulesRetryAndKeepsProcessing() {
        DocumentTask attempt = task(1);
        when(taskMapper.selectById(1L)).thenReturn(task(0), attempt);
        when(documentMapper.selectById(10L)).thenReturn(doc());
        when(completionService.claim(1L, 10L)).thenReturn(attempt);
        when(taskMapper.recordRetryFailure(1L, 1, 5_000L, "connect timeout", "AI_UNAVAILABLE")).thenReturn(1);
        when(ai.parseDocument(anyLong(), anyString(), anyString(), anyString()))
                .thenThrow(new ResourceAccessException("connect timeout"));

        service.process(1L);

        verify(dispatchCoordinator).scheduleRetry(attempt, properties.backoffMs(1));
        verify(taskMapper, never()).markFailed(anyLong(), anyInt(), anyString(), anyString());
        verify(taskMapper, never()).markSuccess(anyLong(), anyInt());
        verify(eventBus).publish(any(DocumentTask.class)); // 认领的事件由完成服务发布。
    }

    /** 通过 {@code taskMapper.selectById}、{@code documentMapper.selectById} 驱动该场景，跳过受保护的调用。 */
    @Test
    void rejectedRetryDispatchUsesAtomicWaitingTransition() {
        DocumentTask initial = task(0);
        DocumentTask retryFailure = task(1);
        retryFailure.setStatus(DocumentTaskStatus.PROCESSING);
        retryFailure.setErrorCode("AI_UNAVAILABLE");
        retryFailure.setErrorMessage("connect timeout");
        retryFailure.setLastErrorCode("AI_UNAVAILABLE");
        retryFailure.setLastErrorMessage("connect timeout");
        when(taskMapper.selectById(1L)).thenReturn(initial, retryFailure);
        when(documentMapper.selectById(10L)).thenReturn(doc());
        when(completionService.claim(1L, 10L)).thenReturn(retryFailure);
        when(taskMapper.recordRetryFailure(1L, 1, 5_000L, "connect timeout", "AI_UNAVAILABLE")).thenReturn(1);
        when(ai.parseDocument(anyLong(), anyString(), anyString(), anyString()))
                .thenThrow(new ResourceAccessException("connect timeout"));
        doThrow(new IllegalStateException("timer queue full"))
                .when(dispatchCoordinator).scheduleRetry(retryFailure, properties.backoffMs(1));

        service.process(1L);

        verify(completionService).recoverRejectedRetry(eq(retryFailure), any(IllegalStateException.class));
        verify(taskMapper, never()).markWaitingAfterRetryScheduleFailure(anyLong(), anyInt(), anyString(), anyString());
        verify(eventBus, never()).publish(any(DocumentTask.class));
    }

    /** 通过 {@code taskMapper.selectById}、{@code documentMapper.selectById} 驱动该场景，跳过受保护的调用。 */
    @Test
    void transientFailureAtMaxMarksFailed() {
        DocumentTask attempt = task(3);
        when(taskMapper.selectById(1L)).thenReturn(task(2), attempt);
        when(documentMapper.selectById(10L)).thenReturn(doc());
        when(completionService.claim(1L, 10L)).thenReturn(attempt);
        when(completionService.markFailed(eq(attempt), anyString(), anyString())).thenReturn(true);
        when(ai.parseDocument(anyLong(), anyString(), anyString(), anyString()))
                .thenThrow(new ResourceAccessException("connect timeout"));

        service.process(1L);

        verify(completionService).markFailed(eq(attempt), anyString(), eq("AI_UNAVAILABLE"));
        verify(dispatchCoordinator, never()).scheduleRetry(any(), anyLong());
    }

    /** 通过 {@code taskMapper.selectById}、{@code documentMapper.selectById} 驱动该场景，跳过受保护的调用。 */
    @Test
    void permanentFailureMarksFailedImmediatelyWithoutRetry() {
        DocumentTask attempt = task(1);
        when(taskMapper.selectById(1L)).thenReturn(task(0), attempt);
        when(documentMapper.selectById(10L)).thenReturn(doc());
        when(completionService.claim(1L, 10L)).thenReturn(attempt);
        when(completionService.markFailed(eq(attempt), anyString(), anyString())).thenReturn(true);
        when(ai.parseDocument(anyLong(), anyString(), anyString(), anyString()))
                .thenThrow(new RestClientResponseException(
                        "unsupported", 422, "Unprocessable Entity", null,
                        new byte[0], StandardCharsets.UTF_8));

        service.process(1L);

        verify(completionService).markFailed(eq(attempt), anyString(), eq("DOCUMENT_INVALID"));
        verify(dispatchCoordinator, never()).scheduleRetry(any(), anyLong());
        verify(eventBus, never()).publish(any(DocumentTask.class));
    }

    /** 通过 {@code taskMapper.selectById}、{@code documentMapper.selectById} 驱动该场景，跳过受保护的调用。 */
    @Test
    void claimFailureReturnsImmediately() {
        when(taskMapper.selectById(1L)).thenReturn(task(0));
        when(documentMapper.selectById(10L)).thenReturn(doc());

        service.process(1L);

        verify(ai, never()).parseDocument(anyLong(), anyString(), anyString(), anyString());
        verify(eventBus, never()).publish(any(DocumentTask.class));
    }

    /** 通过 {@code taskMapper.selectById}、{@code service.process} 驱动该场景，跳过受保护的调用。 */
    @Test
    void missingTaskOrDocumentIsNoOp() {
        when(taskMapper.selectById(1L)).thenReturn(null);
        service.process(1L);
        verify(completionService, never()).claim(anyLong(), anyLong());

        DocumentTask task = task(0);
        when(taskMapper.selectById(1L)).thenReturn(task);
        when(documentMapper.selectById(10L)).thenReturn(null);
        service.process(1L);
        verify(completionService, never()).claim(anyLong(), anyLong());
    }

    /** 让文档解析连续发生临时错误，检查 5 秒和 10 秒退避及尝试上限。 */
    @Test
    void threeAutomaticAttemptsUseFiveThenTenSecondBackoffAndStop() {
        when(taskMapper.selectById(1L)).thenReturn(task(0), task(1), task(1), task(2), task(2), task(3));
        when(documentMapper.selectById(10L)).thenReturn(doc());
        when(completionService.claim(1L, 10L)).thenReturn(task(1), task(2), task(3));
        when(taskMapper.recordRetryFailure(eq(1L), anyInt(), anyLong(), eq("connect timeout"), eq("AI_UNAVAILABLE"))).thenReturn(1);
        when(ai.parseDocument(anyLong(), anyString(), anyString(), anyString()))
                .thenThrow(new ResourceAccessException("connect timeout"));

        service.process(1L);
        service.process(1L);
        service.process(1L);

        var order = inOrder(dispatchCoordinator, completionService);
        order.verify(dispatchCoordinator).scheduleRetry(task(1), 5_000L);
        order.verify(dispatchCoordinator).scheduleRetry(task(2), 10_000L);
        order.verify(completionService).markFailed(task(3), "connect timeout", "AI_UNAVAILABLE");
        verify(dispatchCoordinator, times(2)).scheduleRetry(any(), anyLong());
        verify(completionService, times(3)).claim(1L, 10L);
        verify(ai, times(3)).parseDocument(anyLong(), anyString(), anyString(), anyString());
    }

    /** 通过 {@code taskMapper.selectById}、{@code documentMapper.selectById} 驱动该场景，跳过受保护的调用。 */
    @Test
    void taskDeletedDuringParserFailureCannotBeRetriedOrCompleted() {
        when(taskMapper.selectById(1L)).thenReturn(task(0), null);
        when(documentMapper.selectById(10L)).thenReturn(doc());
        when(completionService.claim(1L, 10L)).thenReturn(task(1));
        when(ai.parseDocument(anyLong(), anyString(), anyString(), anyString()))
                .thenThrow(new ResourceAccessException("connect timeout"));

        service.process(1L);

        verify(dispatchCoordinator, never()).scheduleRetry(any(), anyLong());
        verify(completionService, never()).markFailed(any(DocumentTask.class), anyString(), anyString());
        verify(taskMapper, never()).recordRetryFailure(anyLong(), anyInt(), anyLong(), anyString(), anyString());
    }

    /** 使重试恢复事务失败，确认异常继续传播并跳过额外的状态通知。 */
    @Test
    void retryRecoveryTransactionFailureEscapesWithoutPublishingAnotherSnapshot() {
        when(taskMapper.selectById(1L)).thenReturn(task(0), task(1));
        when(documentMapper.selectById(10L)).thenReturn(doc());
        when(completionService.claim(1L, 10L)).thenReturn(task(1));
        when(taskMapper.recordRetryFailure(1L, 1, 5_000L, "connect timeout", "AI_UNAVAILABLE")).thenReturn(1);
        when(ai.parseDocument(anyLong(), anyString(), anyString(), anyString()))
                .thenThrow(new ResourceAccessException("connect timeout"));
        IllegalStateException rejected = new IllegalStateException("timer unavailable");
        doThrow(rejected).when(dispatchCoordinator).scheduleRetry(task(1), 5_000L);
        doThrow(new IllegalStateException("waiting transaction failed"))
                .when(completionService).recoverRejectedRetry(task(1), rejected);

        assertThatThrownBy(() -> service.process(1L)).hasMessage("waiting transaction failed");

        verify(eventBus, never()).publish(any(DocumentTask.class));
    }

    /** 让文档在解析等待期间取消，验证晚到的结果不会再进入嵌入或重试。 */
    @Test
    void cancelledWhileParsingDoesNotReachEmbeddingOrScheduleRetry() {
        DocumentTask cancelled = task(1);
        cancelled.setStatus(DocumentTaskStatus.CANCELLED);
        when(taskMapper.selectById(1L)).thenReturn(task(0), cancelled);
        when(documentMapper.selectById(10L)).thenReturn(doc());
        when(completionService.claim(1L, 10L)).thenReturn(task(1));
        when(ai.parseDocument(anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(new AiServiceClient.ParseResult());

        service.process(1L);

        verify(chunkService, never()).completeTask(any(), anyLong(), any());
        verify(dispatchCoordinator, never()).scheduleRetry(any(), anyLong());
    }
}
