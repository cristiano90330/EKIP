/* 核对索引生命周期 API 的权限及请求、响应契约。 */

package com.pony.knowledge.modelmanagement.index;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeIndexControllerTest {
    /**
     * 核对 {@code result.getCode()} 与预期 {@code 200} 一致。
     * 调用路径经过 {@code indexes.cancelRebuild}。
     * 接收拟使用的状态值、{@code message}。
     */
    @ParameterizedTest
    @CsvSource({
            "cancelled,重建任务已取消",
            "completed,重建任务已完成，无需取消",
            "failed,重建任务已失败，无需取消",
            "waiting,重建任务尚未取消，请查询最新状态",
            "processing,重建任务尚未取消，请查询最新状态",
            "unknown,已返回重建任务当前状态"
    })
    void cancelMessageMatchesReturnedTaskWithoutChangingIt(String status, String message) {
        KnowledgeIndexService indexes = mock(KnowledgeIndexService.class);
        Map<String, Object> task = Map.of("id", "t1", "status", status);
        when(indexes.cancelRebuild(7L, "t1")).thenReturn(task);

        var result = new KnowledgeIndexController(indexes).cancel(7L, "t1");

        assertEquals(200, result.getCode());
        assertEquals(message, result.getMessage());
        assertSame(task, result.getData());
        verify(indexes).cancelRebuild(7L, "t1");
    }
}
