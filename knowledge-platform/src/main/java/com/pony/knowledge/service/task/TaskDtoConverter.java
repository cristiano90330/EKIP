/* 将文档任务的状态、尝试次数和错误记录转换为对外快照。 */

package com.pony.knowledge.service.task;

import com.pony.knowledge.dto.DocumentTaskDTO;
import com.pony.knowledge.entity.DocumentTask;
import com.pony.knowledge.entity.DocumentTaskStatus;

/** DocumentTask 实体 → DTO（含 attempt），供 getTask 与 SSE 事件共用，避免两处各写一遍。 */
public final class TaskDtoConverter {

    /** 初始化 TaskDtoConverter，供本文件定义的构建入口使用。 */
    private TaskDtoConverter() {
    }

    /**
     * 将持久化任务的状态、尝试次数和失败原因复制到 API 快照。
     * 上游为 TaskCompletionService.cancel、TaskStatusEventBus.deliver。
     * 下游为 DocumentTask.getDocumentId、DocumentTask.getId。
     *
     * @param task 任务记录
     * @return 本次得到的 DocumentTaskDTO 实例
     */
    public static DocumentTaskDTO toDto(DocumentTask task) {
        DocumentTaskDTO dto = new DocumentTaskDTO();
        dto.setDocumentId(task.getDocumentId());
        dto.setTaskId(task.getId());
        dto.setStatus(getStatusName(task.getStatus()));
        int auto = task.getAutoRetryCount() == null ? 0 : task.getAutoRetryCount();
        int manual = task.getManualRetryCount() == null ? 0 : task.getManualRetryCount();
        int attempts = task.getTotalAttemptCount() == null ? auto + manual : task.getTotalAttemptCount();
        dto.setAutoRetryCount(auto);
        dto.setManualRetryCount(manual);
        dto.setRetryCount(attempts);           // 累计认领次数，兼容旧前端
        dto.setAttempt(attempts);              // 前端「第几次处理认领」
        dto.setErrorMessage(task.getErrorMessage());
        dto.setErrorCode(task.getErrorCode());
        dto.setLastErrorMessage(task.getLastErrorMessage());
        dto.setLastErrorCode(task.getLastErrorCode());
        dto.setStartTime(task.getStartTime());
        dto.setFinishTime(task.getFinishTime());
        return dto;
    }

    /**
     * 返回任务状态的枚举名，空状态时返回 UNKNOWN。
     * 上游为 TaskDtoConverter.toDto。
     * 下游为 DocumentTaskStatus.name。
     *
     * @param status 要读取或写入的状态
     * @return 任务状态名称，状态为空时为 UNKNOWN
     */
    public static String getStatusName(DocumentTaskStatus status) {
        return status == null ? "UNKNOWN" : status.name();
    }
}
