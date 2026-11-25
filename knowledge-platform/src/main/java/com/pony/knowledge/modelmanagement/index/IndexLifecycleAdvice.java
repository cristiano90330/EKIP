/* 将索引生命周期冲突和依赖错误映射为 HTTP 响应。 */

package com.pony.knowledge.modelmanagement.index;

import com.pony.knowledge.common.Result;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class IndexLifecycleAdvice {
    /**
     * 将索引异常的状态和原因映射为客户端可消费的错误响应。
     * 下游为 IndexLifecycleException.getStatus、IndexLifecycleException.getMessage。
     *
     * @param error 本次执行捕获的异常
     * @return 带 HTTP 状态与相关业务字段的响应
     */
    @ExceptionHandler(IndexLifecycleException.class)
    public ResponseEntity<Result<Map<String, String>>> indexFailure(IndexLifecycleException error) {
        return ResponseEntity.status(error.getStatus()).body(new Result<>(error.getStatus().value(),
                error.getMessage(), Map.of("reasonCode", error.getReasonCode())));
    }
}
