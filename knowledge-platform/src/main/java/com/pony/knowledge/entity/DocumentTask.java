/* 映射后台文档任务、重试次数、处理时间与错误状态。 */

package com.pony.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档处理任务记录，保存状态、尝试次数、错误和处理时间。
 * 失败重试复用 taskId，取消后重新解析创建新任务；两种路径均累计历史尝试次数。
 */
@Data
@TableName("document_task")
public class DocumentTask {

    private Long id;

    private Long documentId;

    private DocumentTaskStatus status;

    /** 引擎侧认领次数（首次处理 + 自动退避重试 + 恢复重投 + 手动提交后的处理认领）。 */
    private Integer autoRetryCount;

    /** 累计处理认领次数；手动重试不会清零。 */
    private Integer totalAttemptCount;

    /** 用户手动重试次数（POST /document/{id}/retry）。 */
    private Integer manualRetryCount;

    private String errorMessage;

    private String errorCode;

    private String lastErrorMessage;

    private String lastErrorCode;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private LocalDateTime startTime;

    private LocalDateTime finishTime;
}
