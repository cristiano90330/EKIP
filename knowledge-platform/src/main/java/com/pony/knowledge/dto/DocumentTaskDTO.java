/* 向查询与 SSE 调用方暴露任务状态、尝试次数和错误快照。 */

package com.pony.knowledge.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** 文档处理任务快照，向前端提供状态、尝试次数、错误信息和执行时间。 */
@Data
public class DocumentTaskDTO {

    private Long documentId;

    private Long taskId;

    private String status;

    /** 累计引擎认领次数，保留兼容旧前端。 */
    private Integer retryCount;

    /** 引擎侧认领次数（自动重试 + 恢复 + 手动提交后的处理认领）。 */
    private Integer autoRetryCount;

    /** 用户手动重试次数。 */
    private Integer manualRetryCount;

    /** 当前累计引擎认领次数；用于前端显示处理尝试轮次。 */
    private Integer attempt;

    private String errorMessage;

    private String errorCode;

    /** 手动重试清除当前错误后，仍保留最近一次终态失败信息。 */
    private String lastErrorMessage;

    private String lastErrorCode;

    private LocalDateTime startTime;

    private LocalDateTime finishTime;
}
