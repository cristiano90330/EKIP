/* 把模型管理异常转换为带原因码的 API 响应。 */

package com.pony.knowledge.modelmanagement;

import com.pony.knowledge.common.Result;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class ModelManagementExceptionHandler {
    /**
     * 保留模型管理异常的状态与原因转换为 HTTP 响应。
     * 下游为 ModelManagementException.getStatus、ModelManagementException.getMessage。
     *
     * @param error 本次执行捕获的异常
     * @return 带 HTTP 状态与相关业务字段的响应
     */
    @ExceptionHandler(ModelManagementException.class)
    public ResponseEntity<Result<Map<String,String>>> handle(ModelManagementException error) {
        return ResponseEntity.status(error.getStatus()).contentType(MediaType.APPLICATION_JSON)
                .body(new Result<>(error.getStatus(), error.getMessage(),
                Map.of("reasonCode", error.getReasonCode())));
    }
}
