/* 判定文档解析异常是否可重试并生成稳定失败码。 */

package com.pony.knowledge.service.task;

import com.pony.knowledge.resilience.AiServiceUnavailableException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 文档处理失败的业务分类，供处理主线决定是否自动重试。
 * 连接、服务不可用、HTTP 408 / 429 / 5xx 归为可重试，其余默认直接失败；尝试次数由调用方检查。
 */
@Component
public class TaskFailureClassifier {

    /**
     * 依据异常及上游状态码判定处理失败能否重试。
     * 上游为 DocumentProcessServiceImpl.processAttempt。
     *
     * @param ex 当前流程捕获的 Throwable 异常
     * @return 本次得到的 TaskFailureKind 实例
     */
    public TaskFailureKind classify(Throwable ex) {
        if (ex instanceof AiServiceUnavailableException) {
            return TaskFailureKind.TRANSIENT;
        }
        if (ex instanceof ResourceAccessException) {
            return TaskFailureKind.TRANSIENT; // Java HttpClient 拒连/读超时 → Python 未起或忙
        }
        if (ex instanceof RestClientResponseException httpEx) {
            int status = httpEx.getStatusCode().value();
            return status >= 500 || status == 408 || status == 429
                    ? TaskFailureKind.TRANSIENT : TaskFailureKind.PERMANENT;
        }
        return TaskFailureKind.PERMANENT; // 保守默认：不认识的失败不重试
    }

    /**
     * 将解析或依赖异常归入稳定原因码供任务状态展示。
     * 上游为 DocumentProcessServiceImpl.processAttempt。
     *
     * @param ex 当前流程捕获的 Throwable 异常
     * @return 供前端任务详情识别的稳定错误码
     */
    public String reasonCode(Throwable ex) {
        if (ex instanceof AiServiceUnavailableException || ex instanceof ResourceAccessException) {
            return "AI_UNAVAILABLE";
        }
        if (ex instanceof RestClientResponseException httpEx) {
            int status = httpEx.getStatusCode().value();
            if (status == 401 || status == 403) return "AI_INTERNAL_AUTH";
            if (status == 408) return "AI_TIMEOUT";
            if (status == 429) return "AI_RATE_LIMITED";
            if (status >= 500) return "AI_SERVICE_ERROR";
            if (status == 422) return "DOCUMENT_INVALID";
            return "AI_REQUEST_REJECTED";
        }
        return "PROCESSING_FAILED";
    }
}
