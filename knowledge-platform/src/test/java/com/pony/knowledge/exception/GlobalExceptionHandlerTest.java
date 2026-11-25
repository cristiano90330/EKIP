/* 核对认证、参数、上传、业务与依赖异常的 HTTP 和业务错误响应。 */

package com.pony.knowledge.exception;

import com.pony.knowledge.common.Result;
import com.pony.knowledge.security.LoginLockedException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 错误响应契约（升级项 P0-1 / P0-2 + 本轮认证主干加固）：
 * 每类异常走真实 HTTP 状态码，body 的 code 与状态码一致；
 * 可预期的业务异常保留原始文案，未预期异常一律换成统一文案且不泄露内部信息。
 *
 * 本轮新增的四档认证契约：
 *   401「用户名或密码错误」—— 用户不存在与密码错误对外不可区分（枚举通道关闭）
 *   403「账号已被禁用，请联系管理员」—— 此前 user.status 在代码里没有读取点，功能不存在
 *   429 + Retry-After —— 登录限流，本轮新增的一档状态码
 *   422「用户名已存在」—— 并发注册撞 username 唯一键，此前落到 500 兜底
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    /**
     * 构造 {@code String} 供测试使用。
     * 由 {@code forbiddenMapsTo403AndKeepsMessage}、{@code businessExceptionMapsTo422AndKeepsMessage} 调用。
     * @param response 测试响应
     * 返回 {@code response.getBody().getMessage()}。
     */
    private static String messageOf(ResponseEntity<Result<String>> response) {
        return response.getBody().getMessage();
    }

    /** 通过 {@code handler.handleForbidden} 执行本例并核对输出或记录。 */
    @Test
    void forbiddenMapsTo403AndKeepsMessage() {
        ResponseEntity<Result<String>> r = handler.handleForbidden(new ForbiddenException("无权操作该知识库"));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(r.getBody().getCode()).isEqualTo(403);
        assertThat(messageOf(r)).isEqualTo("无权操作该知识库");
    }

    /** 覆盖SSE的相关边界。通过 {@code handler.handleBusinessException} 执行本例并核对输出或记录。 */
    @Test
    void businessExceptionMapsTo422AndKeepsMessage() {
        ResponseEntity<Result<String>> r = handler.handleBusinessException(new BusinessException("用户名已存在"));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(r.getBody().getCode()).isEqualTo(422);
        assertThat(messageOf(r)).isEqualTo("用户名已存在");
    }

    /** 覆盖SSE的相关边界。通过 {@code handler.handleUnauthorized} 执行本例并核对输出或记录。 */
    @Test
    void unauthorizedSubclassWinsOverBusinessExceptionHandler() {
        // UnauthorizedException 是 BusinessException 子类，但必须映射 401 而不是 422
        ResponseEntity<Result<String>> r = handler.handleUnauthorized(new UnauthorizedException("未认证或登录已过期"));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** 通过 {@code binding.addError} 执行本例并核对输出或记录。 */
    @Test
    void validationMapsTo400AndJoinsFieldErrors() {
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "dto");
        binding.addError(new FieldError("dto", "name", "知识库名称不能为空"));
        binding.addError(new FieldError("dto", "description", "描述不能超过500个字符"));

        ResponseEntity<Result<String>> r = handler.handleValidation(new BindException(binding));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(messageOf(r)).isEqualTo("知识库名称不能为空；描述不能超过500个字符");
    }

    /** 覆盖无效的相关边界。通过 {@code handler.handleMethodValidation} 执行本例并核对输出或记录。 */
    @Test
    void methodInputValidationIs400ButInvalidReturnValueRemains500() {
        HandlerMethodValidationException inputFailure = mock(HandlerMethodValidationException.class);
        ResponseEntity<Result<String>> inputResponse = handler.handleMethodValidation(inputFailure);
        assertThat(inputResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(inputResponse.getBody().getCode()).isEqualTo(400);

        HandlerMethodValidationException returnFailure = mock(HandlerMethodValidationException.class);
        when(returnFailure.isForReturnValue()).thenReturn(true);
        ResponseEntity<Result<String>> returnResponse = handler.handleMethodValidation(returnFailure);
        assertThat(returnResponse.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(returnResponse.getBody().getCode()).isEqualTo(500);
    }

    /** 覆盖缺失的相关边界。通过 {@code handler.handleMissingParameter} 执行本例并核对输出或记录。 */
    @Test
    void missingQueryParameterIs400() {
        ResponseEntity<Result<String>> response = handler.handleMissingParameter(
                new MissingServletRequestParameterException("query", "String"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getCode()).isEqualTo(400);
        assertThat(messageOf(response)).isEqualTo("缺少请求参数: query");
    }

    /** 覆盖畸形的相关边界。通过 {@code handler.handleUnreadableBody} 执行本例并核对输出或记录。 */
    @Test
    void malformedBodyIs400WithoutEchoingParserOrPayloadDetails() {
        ResponseEntity<Result<String>> response = handler.handleUnreadableBody(
                new HttpMessageNotReadableException("private-document-content; internal.ParserType",
                        new MockHttpInputMessage(new byte[0])));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getCode()).isEqualTo(400);
        assertThat(messageOf(response)).isEqualTo("请求体格式不正确");
        assertThat(messageOf(response)).doesNotContain("private-document-content", "ParserType");
    }

    /** 覆盖停用的相关边界。通过 {@code handler.handleAccountStatus} 执行本例并核对输出或记录。 */
    @Test
    void disabledAccountMapsTo403WithUnifiedMessage() {
        // 框架的 DefaultPreAuthenticationChecks 抛的异常，message 是英文 "User is disabled"，
        // 不能原样透给前端
        ResponseEntity<Result<String>> r = handler.handleAccountStatus(new DisabledException("User is disabled"));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(r.getBody().getCode()).isEqualTo(403);
        assertThat(messageOf(r)).isEqualTo("账号已被禁用，请联系管理员");
        assertThat(messageOf(r)).doesNotContain("User is disabled");
    }

    /** 通过 {@code handler.handleAccountStatus} 执行本例并核对输出或记录。 */
    @Test
    void lockedAccountMapsTo403() {
        ResponseEntity<Result<String>> r = handler.handleAccountStatus(new LockedException("User account is locked"));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(messageOf(r)).isEqualTo("账号已被禁用，请联系管理员");
    }

    /** 通过 {@code handler.handleAuthentication} 执行本例并核对输出或记录。 */
    @Test
    void badCredentialsMapsTo401WithUnifiedMessage() {
        ResponseEntity<Result<String>> r =
                handler.handleAuthentication(new BadCredentialsException("Bad credentials"));

        // 改造前是 422 + 两句不同文案（「用户不存在」/「密码错误」）→ 用户名枚举通道全开
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(r.getBody().getCode()).isEqualTo(401);
        assertThat(messageOf(r)).isEqualTo("用户名或密码错误");
    }

    /** 通过 {@code handler.handleAuthentication} 执行本例并核对输出或记录。 */
    @Test
    void usernameNotFoundAndBadCredentialsAreIndistinguishableFromOutside() {
        // hideUserNotFoundExceptions=true 时框架已经转成 BadCredentialsException；
        // 万一有 UsernameNotFoundException 漏出来，handler 也必须给出同一句话
        String forUnknownUser = messageOf(
                handler.handleAuthentication(new UsernameNotFoundException("用户不存在: nobody")));
        String forWrongPassword = messageOf(
                handler.handleAuthentication(new BadCredentialsException("Bad credentials")));

        assertThat(forUnknownUser).isEqualTo(forWrongPassword);
        assertThat(forUnknownUser).doesNotContain("nobody");
    }

    /** 覆盖重试、响应头的相关边界。通过 {@code handler.handleLoginLocked} 执行本例并核对输出或记录。 */
    @Test
    void loginLockedMapsTo429WithRetryAfterHeader() {
        ResponseEntity<Result<String>> r =
                handler.handleLoginLocked(new LoginLockedException(900L, "alice", "203.0.113.7"));

        // 429 是本轮新增的一档，不在原有状态码表（400/401/403/413/422/500）里
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(r.getBody().getCode()).isEqualTo(429);
        assertThat(r.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("900");
        assertThat(messageOf(r)).isEqualTo("登录失败次数过多，请 15 分钟后再试");
    }

    /** 覆盖重试的相关边界。通过 {@code handler.handleLoginLocked} 执行本例并核对输出或记录。 */
    @Test
    void loginLockedRoundsRetryAfterUpToWholeMinutes() {
        // 89 秒不能显示成「1 分钟」，否则用户等不够就重试又被拒
        ResponseEntity<Result<String>> r =
                handler.handleLoginLocked(new LoginLockedException(89L, "alice", "203.0.113.7"));

        assertThat(messageOf(r)).isEqualTo("登录失败次数过多，请 2 分钟后再试");
        assertThat(r.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("89");
    }

    /** 覆盖重复的相关边界。通过 {@code handler.handleDuplicateKey} 执行本例并核对输出或记录。 */
    @Test
    void duplicateKeyMapsTo422InsteadOfFallingThroughTo500() {
        // register 是「先查后插」，并发同名请求会双双通过存在性检查，第二个撞 username 唯一键
        ResponseEntity<Result<String>> r = handler.handleDuplicateKey(
                new DuplicateKeyException("Duplicate entry 'alice' for key 'user.username'"));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(r.getBody().getCode()).isEqualTo(422);
        assertThat(messageOf(r)).isEqualTo("用户名已存在");
        // 框架原文带着库名与键名，不能透给前端
        assertThat(messageOf(r)).doesNotContain("Duplicate entry");
    }

    /** 通过 {@code handler.handleMaxUploadSize} 执行本例并核对输出或记录。 */
    @Test
    void maxUploadSizeMapsTo413WithoutFrameworkEnglishText() {
        ResponseEntity<Result<String>> r = handler.handleMaxUploadSize(
                new MaxUploadSizeExceededException(1_048_576L));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(messageOf(r)).doesNotContain("Maximum upload size");
    }

    /** 通过 {@code handler.handleUnexpected} 执行本例并核对输出或记录。 */
    @Test
    void unexpectedExceptionMapsTo500AndDoesNotLeakInternalMessage() {
        // 模拟真实泄露场景：SQL 异常文案里带表名、类型转换异常带 Java 类名
        ResponseEntity<Result<String>> r = handler.handleUnexpected(
                new RuntimeException("BadSqlGrammarException: select * from user where ..."),
                new MockHttpServletResponse());

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(r.getBody().getCode()).isEqualTo(500);
        assertThat(messageOf(r)).isEqualTo("系统繁忙，请稍后重试");
        assertThat(messageOf(r)).doesNotContain("user");
    }

    /** 覆盖空值的相关边界。通过 {@code handler.handleUnexpected} 执行本例并核对输出或记录。 */
    @Test
    void nullPointerExceptionGetsGenericMessageInsteadOfNull() {
        ResponseEntity<Result<String>> r = handler.handleUnexpected(new NullPointerException(),
                new MockHttpServletResponse());

        // 改造前 NPE 会让前端收到 {"code":500,"message":null}
        assertThat(messageOf(r)).isNotBlank();
    }
}
