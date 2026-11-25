/* 把业务、参数校验、鉴权和上游错误转换为 HTTP 响应。 */

package com.pony.knowledge.exception;

import com.pony.knowledge.common.Result;
import com.pony.knowledge.dto.RagFailureDTO;
import com.pony.knowledge.security.LoginLockedException;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.AccountStatusException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.util.DisconnectedClientHelper;

import java.util.stream.Collectors;

/**
 * 业务异常的 MVC 响应出口：将权限、输入、上传冲突和问答失败映射为对应 HTTP 状态。
 * 普通错误使用 Result，问答依赖故障另保留 outcome 等业务状态；过滤链中的提前拒绝由其自身返回。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 将 InvalidRagPipelineException 映射为对外 HTTP 状态与业务错误响应。
     * 下游为 GlobalExceptionHandler.respond、InvalidRagPipelineException.getMessage。
     *
     * @param e 本次执行捕获的异常
     * @return 带 HTTP 状态与相关业务字段的响应
     */
    @ExceptionHandler(InvalidRagPipelineException.class)
    public ResponseEntity<Result<String>> handleInvalidRagPipeline(InvalidRagPipelineException e) {
        return respond(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /**
     * 将 ResponseStatusException 映射为对外 HTTP 状态与业务错误响应。
     * 下游为 GlobalExceptionHandler.safeMessage。
     *
     * @param e 本次执行捕获的异常
     * @return 带 HTTP 状态与相关业务字段的响应
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Result<String>> handleResponseStatus(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).headers(e.getHeaders())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new Result<>(e.getStatusCode().value(), safeMessage(e.getReason()), null));
    }

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // 未预期异常的对外文案：内部细节只进日志，不返回给前端
    private static final String GENERIC_MESSAGE = "系统繁忙，请稍后重试";

    // 无效登录凭证使用统一对外文案。
    private static final String BAD_CREDENTIALS_MESSAGE = "用户名或密码错误";

    // 账号状态异常的对外文案
    private static final String ACCOUNT_DISABLED_MESSAGE = "账号已被禁用，请联系管理员";

    // 未认证（含 JWT 之外的认证信息缺失）：HTTP 401
    /**
     * 将 UnauthorizedException 映射为对外 HTTP 状态与业务错误响应。
     * 下游为 GlobalExceptionHandler.respond、UnauthorizedException.getMessage。
     *
     * @param e 本次执行捕获的异常
     * @return 带 HTTP 状态与相关业务字段的响应
     */
    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<Result<String>> handleUnauthorized(UnauthorizedException e) {
        return respond(HttpStatus.UNAUTHORIZED, e.getMessage());
    }

    /**
     * 将 AuthenticationException 映射为对外 HTTP 状态与业务错误响应。
     * 下游为 GlobalExceptionHandler.respond。
     *
     * @param e 本次执行捕获的异常
     * @return 带 HTTP 状态与相关业务字段的响应
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Result<String>> handleAuthentication(AuthenticationException e) {
        log.debug("登录认证失败({}): {}", e.getClass().getSimpleName(), e.getMessage());
        return respond(HttpStatus.UNAUTHORIZED, BAD_CREDENTIALS_MESSAGE);
    }

    // 越权/无权限：HTTP 403
    /**
     * 将 ForbiddenException 映射为对外 HTTP 状态与业务错误响应。
     * 下游为 GlobalExceptionHandler.respond、ForbiddenException.getMessage。
     *
     * @param e 本次执行捕获的异常
     * @return 带 HTTP 状态与相关业务字段的响应
     */
    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Result<String>> handleForbidden(ForbiddenException e) {
        return respond(HttpStatus.FORBIDDEN, e.getMessage());
    }

    /**
     * 将 RagQueryFailureException 映射为对外 HTTP 状态与业务错误响应。
     * 下游为 RagQueryFailureException.getOutcome、RagQueryFailureException.getReasonCode。
     *
     * @param e 本次执行捕获的异常
     * @return 带 HTTP 状态与相关业务字段的响应
     */
    @ExceptionHandler(RagQueryFailureException.class)
    public ResponseEntity<Result<RagFailureDTO>> handleRagQueryFailure(RagQueryFailureException e) {
        log.warn("RAG 边界失败 outcome={} reasonCode={} failedStage={} requestId={}",
                e.getOutcome(), e.getReasonCode(), e.getFailedStage(), e.getRequestId(), e);
        return ResponseEntity.status(e.getStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new Result<>(e.getStatus().value(), e.getMessage(),
                        new RagFailureDTO(e.getOutcome(), e.getReasonCode(), e.getFailedStage(), e.getRequestId())));
    }

    /**
     * 将 ConversationStoreUnavailableException 映射为对外 HTTP 状态与业务错误响应。
     *
     * @param e 本次执行捕获的异常
     * @return 带 HTTP 状态与相关业务字段的响应
     */
    @ExceptionHandler(ConversationStoreUnavailableException.class)
    public ResponseEntity<Result<RagFailureDTO>> handleConversationStoreUnavailable(
            ConversationStoreUnavailableException e) {
        log.warn("RAG 会话历史依赖不可用", e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new Result<>(HttpStatus.SERVICE_UNAVAILABLE.value(), "会话历史暂时不可用，请稍后重试",
                        new RagFailureDTO("dependency_unavailable", "redis_unavailable", "chat.history", null)));
    }

    /**
     * 将 RestClientResponseException 映射为对外 HTTP 状态与业务错误响应。
     *
     * @param e 本次执行捕获的异常
     * @return 带 HTTP 状态与相关业务字段的响应
     */
    @ExceptionHandler(RestClientResponseException.class)
    public ResponseEntity<Result<RagFailureDTO>> handleUpstreamResponse(RestClientResponseException e) {
        if (e.getStatusCode().value() == HttpStatus.UNAUTHORIZED.value()) {
            log.error("AI 服务间鉴权失败，拒绝将其解释为用户登录失效");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new Result<>(HttpStatus.SERVICE_UNAVAILABLE.value(), "AI 服务暂时不可用，请稍后重试",
                            new RagFailureDTO("dependency_unavailable", "ai_internal_auth", "ai_boundary", null)));
        }
        log.error("上游服务返回非预期 HTTP 状态 {}", e.getStatusCode(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new Result<>(HttpStatus.INTERNAL_SERVER_ERROR.value(), GENERIC_MESSAGE, null));
    }

    /**
     * 将 AccountStatusException 映射为对外 HTTP 状态与业务错误响应。
     * 下游为 GlobalExceptionHandler.respond。
     *
     * @param e 本次执行捕获的异常
     * @return 带 HTTP 状态与相关业务字段的响应
     */
    @ExceptionHandler({DisabledException.class, LockedException.class})
    public ResponseEntity<Result<String>> handleAccountStatus(AccountStatusException e) {
        log.warn("登录被拒绝（账号状态异常）: {} - {}", e.getClass().getSimpleName(), e.getMessage());
        return respond(HttpStatus.FORBIDDEN, ACCOUNT_DISABLED_MESSAGE);
    }

    /**
     * 将 LoginLockedException 映射为对外 HTTP 状态与业务错误响应。
     * 下游为 LoginLockedException.getUsername、LoginLockedException.getIp。
     *
     * @param e 本次执行捕获的异常
     * @return 带 HTTP 状态与相关业务字段的响应
     */
    @ExceptionHandler(LoginLockedException.class)
    public ResponseEntity<Result<String>> handleLoginLocked(LoginLockedException e) {
        log.warn("登录被限流: username={}, ip={}, retryAfterSeconds={}",
                e.getUsername(), e.getIp(), e.getRetryAfterSeconds());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfterSeconds()))
                .body(new Result<>(HttpStatus.TOO_MANY_REQUESTS.value(),
                        "登录失败次数过多，请 " + e.getRetryAfterMinutes() + " 分钟后再试", null));
    }

    // 可预期的业务失败：HTTP 422，message 是给人看的业务文案，原样返回
    /**
     * 将 BusinessException 映射为对外 HTTP 状态与业务错误响应。
     * 下游为 GlobalExceptionHandler.respond、BusinessException.getMessage。
     *
     * @param e 本次执行捕获的异常
     * @return 带 HTTP 状态与相关业务字段的响应
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<String>> handleBusinessException(BusinessException e) {
        return respond(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    /**
     * 将 UploadIdempotencyConflictException 映射为对外 HTTP 状态与业务错误响应。
     * 下游为 GlobalExceptionHandler.respond、UploadIdempotencyConflictException.getMessage。
     *
     * @param e 本次执行捕获的异常
     * @return 带 HTTP 状态与相关业务字段的响应
     */
    @ExceptionHandler(UploadIdempotencyConflictException.class)
    public ResponseEntity<Result<String>> handleUploadIdempotencyConflict(UploadIdempotencyConflictException e) {
        return respond(HttpStatus.CONFLICT, e.getMessage());
    }

    /**
     * 将 DuplicateKeyException 映射为对外 HTTP 状态与业务错误响应。
     * 下游为 GlobalExceptionHandler.respond。
     *
     * @param e 本次执行捕获的异常
     * @return 带 HTTP 状态与相关业务字段的响应
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Result<String>> handleDuplicateKey(DuplicateKeyException e) {
        log.warn("唯一键冲突: {}", e.getMessage());
        return respond(HttpStatus.UNPROCESSABLE_ENTITY, "用户名已存在");
    }

    // 参数绑定/校验失败（@Valid 抛的 MethodArgumentNotValidException 是 BindException 子类）：HTTP 400
    /**
     * 将 BindException 映射为对外 HTTP 状态与业务错误响应。
     * 下游为 GlobalExceptionHandler.respond。
     *
     * @param e 本次执行捕获的异常
     * @return 带 HTTP 状态与相关业务字段的响应
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<Result<String>> handleValidation(BindException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getDefaultMessage())
                .collect(Collectors.joining("；"));
        return respond(HttpStatus.BAD_REQUEST, msg);
    }

    /**
     * 将 HandlerMethodValidationException 映射为对外 HTTP 状态与业务错误响应。
     * 下游为 GlobalExceptionHandler.respond。
     *
     * @param e 本次执行捕获的异常
     * @return 带 HTTP 状态与相关业务字段的响应
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Result<String>> handleMethodValidation(HandlerMethodValidationException e) {
        if (e.isForReturnValue()) {
            log.error("Controller 返回值校验失败", e);
            return respond(HttpStatus.INTERNAL_SERVER_ERROR, GENERIC_MESSAGE);
        }
        return respond(HttpStatus.BAD_REQUEST, "请求参数校验失败");
    }

    /**
     * 将 MissingServletRequestParameterException 映射为对外 HTTP 状态与业务错误响应。
     * 下游为 GlobalExceptionHandler.respond。
     *
     * @param e 本次执行捕获的异常
     * @return 带 HTTP 状态与相关业务字段的响应
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<String>> handleMissingParameter(MissingServletRequestParameterException e) {
        return respond(HttpStatus.BAD_REQUEST, "缺少请求参数: " + e.getParameterName());
    }

    /**
     * 将 HttpMessageNotReadableException 映射为对外 HTTP 状态与业务错误响应。
     * 下游为 GlobalExceptionHandler.respond。
     *
     * @param e 本次执行捕获的异常
     * @return 带 HTTP 状态与相关业务字段的响应
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<String>> handleUnreadableBody(HttpMessageNotReadableException e) {
        return respond(HttpStatus.BAD_REQUEST, "请求体格式不正确");
    }

    // 查询串/路径变量类型转换失败（如 ?size=abc）：属客户端错误，HTTP 400，不打 error 日志
    /**
     * 将 MethodArgumentTypeMismatchException 映射为对外 HTTP 状态与业务错误响应。
     * 下游为 GlobalExceptionHandler.respond。
     *
     * @param e 本次执行捕获的异常
     * @return 带 HTTP 状态与相关业务字段的响应
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<String>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return respond(HttpStatus.BAD_REQUEST, "参数格式不正确: " + e.getName());
    }

    // 上传超限：HTTP 413。框架原文 message 是英文且带内部限制值，换成业务文案
    /**
     * 将 MaxUploadSizeExceededException 映射为对外 HTTP 状态与业务错误响应。
     * 下游为 GlobalExceptionHandler.respond。
     *
     * @param e 本次执行捕获的异常
     * @return 带 HTTP 状态与相关业务字段的响应
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<String>> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        log.warn("上传文件超出大小限制: {}", e.getMessage());
        return respond(HttpStatus.PAYLOAD_TOO_LARGE, "上传文件超过大小限制，请压缩后重试");
    }

    /**
     * 将 Exception 映射为对外 HTTP 状态与业务错误响应。
     * 下游为 GlobalExceptionHandler.respond。
     *
     * @param e 本次执行捕获的异常
     * @param response 即将交给客户端的 HTTP 响应
     * @return 带 HTTP 状态与相关业务字段的响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<String>> handleUnexpected(Exception e, HttpServletResponse response) {
        String contentType = response.getContentType();
        boolean eventStream = contentType != null && contentType.startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);

        if (e instanceof AsyncRequestNotUsableException
                || eventStream && DisconnectedClientHelper.isClientDisconnectedException(e)) {
            log.debug("流式客户端已断开，结束异常分派");
            return null;
        }
        if (response.isCommitted()) {
            // SSE 正文已提交，不能再切换媒体类型或追加 Result。
            log.warn("响应已提交，保留原响应并结束异常分派", e);
            return null;
        }
        log.error("未处理异常", e);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, GENERIC_MESSAGE);
    }

    // body 里的 code 与 HTTP 状态码保持一致，前端两个字段任取其一都能判错
    /**
     * 用 HTTP 状态和业务消息构建通用错误响应。
     * 上游为 GlobalExceptionHandler.handleInvalidRagPipeline、GlobalExceptionHandler.handleUnauthorized。
     * 下游为 GlobalExceptionHandler.safeMessage。
     *
     * @param status 要读取或写入的状态
     * @param message 用于构建结果或异常的说明文字
     * @return 带 HTTP 状态与相关业务字段的响应
     */
    private static ResponseEntity<Result<String>> respond(HttpStatusCode status, String message) {
        return ResponseEntity.status(status)
                // SSE-only Accept 不能把建连前的 JSON 错误变成空响应或媒体协商失败。
                .contentType(MediaType.APPLICATION_JSON)
                .body(new Result<>(status.value(), safeMessage(message), null));
    }

    /**
     * 为没有具体说明的业务异常提供可对外展示的消息。
     * 上游为 GlobalExceptionHandler.handleResponseStatus、GlobalExceptionHandler.respond。
     *
     * @param message 用于构建结果或异常的说明文字
     * @return 本次计算或查询得到的 String 结果
     */
    private static String safeMessage(String message) {
        return (message == null || message.isBlank()) ? GENERIC_MESSAGE : message;
    }
}
