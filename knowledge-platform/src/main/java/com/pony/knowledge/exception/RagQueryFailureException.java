/* 携带问答失败分类、执行阶段和请求标识。 */

package com.pony.knowledge.exception;

import org.springframework.http.HttpStatus;

/** A known dependency/deadline failure at the Java-to-Python RAG boundary. */
public class RagQueryFailureException extends RuntimeException {
    private final HttpStatus status;
    private final String outcome;
    private final String reasonCode;
    private final String failedStage;
    private final String requestId;

    /**
     * 创建带状态和诊断信息的失败异常，供调用方映射响应。
     *
     * @param status 要读取或写入的状态
     * @param outcome 查询或操作的业务结果状态
     * @param reasonCode 用于区分失败或降级路径的原因码
     * @param failedStage 故障发生的执行阶段
     * @param requestId 关联客户端请求与日志的请求标识
     * @param message 用于构建结果或异常的说明文字
     * @param cause 当前流程捕获的 Throwable 异常
     */
    public RagQueryFailureException(HttpStatus status, String outcome, String reasonCode,
                                    String failedStage, String requestId, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.outcome = outcome;
        this.reasonCode = reasonCode;
        this.failedStage = failedStage;
        this.requestId = requestId;
    }

    /**
     * 返回本次失败的 HTTP 状态或记录状态。
     * 上游为 GlobalExceptionHandler.handleRagQueryFailure。
     *
     * @return 本次失败的 HTTP 状态或记录状态
     */
    public HttpStatus getStatus() { return status; }
    /**
     * 返回执行结果的业务分类。
     * 上游为 GlobalExceptionHandler.handleRagQueryFailure。
     *
     * @return 执行结果的业务分类
     */
    public String getOutcome() { return outcome; }
    /**
     * 返回定位执行结果的原因码。
     * 上游为 GlobalExceptionHandler.handleRagQueryFailure。
     *
     * @return 定位执行结果的原因码
     */
    public String getReasonCode() { return reasonCode; }
    /**
     * 返回失败阶段。
     * 上游为 GlobalExceptionHandler.handleRagQueryFailure。
     *
     * @return 失败阶段
     */
    public String getFailedStage() { return failedStage; }
    /**
     * 返回关联日志和观测记录的请求标识。
     * 上游为 GlobalExceptionHandler.handleRagQueryFailure。
     *
     * @return 关联日志和观测记录的请求标识
     */
    public String getRequestId() { return requestId; }
}
