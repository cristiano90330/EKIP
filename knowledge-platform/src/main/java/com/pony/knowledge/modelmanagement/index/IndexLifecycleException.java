/* 携带知识库索引错误的 HTTP 状态和业务原因码。 */

package com.pony.knowledge.modelmanagement.index;

import com.pony.knowledge.exception.BusinessException;
import org.springframework.http.HttpStatus;

/** Safe public index failures; no model snapshot or upstream response is attached. */
public class IndexLifecycleException extends BusinessException {
    private final HttpStatus status;
    private final String reasonCode;

    /**
     * 创建带状态和诊断信息的失败异常，供调用方映射响应。
     *
     * @param status 要读取或写入的状态
     * @param reasonCode 用于区分失败或降级路径的原因码
     * @param message 用于构建结果或异常的说明文字
     */
    public IndexLifecycleException(HttpStatus status, String reasonCode, String message) {
        super(message);
        this.status = status;
        this.reasonCode = reasonCode;
    }

    /**
     * 返回本次失败的 HTTP 状态或记录状态。
     * 上游为 IndexLifecycleAdvice.indexFailure。
     *
     * @return 本次失败的 HTTP 状态或记录状态
     */
    public HttpStatus getStatus() { return status; }
    /**
     * 返回定位执行结果的原因码。
     * 上游为 IndexLifecycleAdvice.indexFailure。
     *
     * @return 定位执行结果的原因码
     */
    public String getReasonCode() { return reasonCode; }

    /**
     * 构建用于报告索引操作状态冲突的异常。
     * 上游为 KnowledgeIndexService.checkWritable、KnowledgeIndexService.writeDocumentVectors。
     *
     * @param message 用于构建结果或异常的说明文字
     * @return 知识库索引错误的 HTTP 状态和业务原因码，以 IndexLifecycleException 返回
     */
    static IndexLifecycleException conflict(String message) {
        return new IndexLifecycleException(HttpStatus.CONFLICT, "index_maintenance_conflict", message);
    }

    /**
     * 构建用于报告索引依赖或配置不可用的异常。
     * 上游为 KnowledgeIndexService.resolveIndexScopes、KnowledgeIndexService.embeddingExecution。
     *
     * @param reason 解释本次状态或拒绝的原因
     * @param message 用于构建结果或异常的说明文字
     * @return 知识库索引错误的 HTTP 状态和业务原因码，以 IndexLifecycleException 返回
     */
    static IndexLifecycleException unavailable(String reason, String message) {
        return new IndexLifecycleException(HttpStatus.SERVICE_UNAVAILABLE, reason, message);
    }
}
