/* 携带登录锁定时的剩余等待时间和认证主体信息。 */

package com.pony.knowledge.security;

/**
 * 登录限流拒绝异常，携带等待时长、用户名和来源 IP，供全局处理器返回 HTTP 429 与 Retry-After。
 * 不继承认证异常，避免限流拒绝被 Controller 再次记作一次凭证失败。
 */
public class LoginLockedException extends RuntimeException {

    private final long retryAfterSeconds;

    private final String username;

    private final String ip;

    /**
     * 创建带状态和诊断信息的失败异常，供调用方映射响应。
     *
     * @param retryAfterSeconds 以秒计的时间限制
     * @param username 登录用户名
     * @param ip 登录请求识别出的来源地址
     */
    public LoginLockedException(long retryAfterSeconds, String username, String ip) {
        super("登录失败次数过多，请在 " + retryAfterSeconds + " 秒后再试");
        this.retryAfterSeconds = retryAfterSeconds;
        this.username = username;
        this.ip = ip;
    }

    /**
     * 返回还需等待才能重试的秒数。
     * 上游为 GlobalExceptionHandler.handleLoginLocked。
     *
     * @return 还需等待才能重试的秒数
     */
    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    /**
     * 返回提交认证时使用的账号名称。
     * 上游为 GlobalExceptionHandler.handleLoginLocked。
     *
     * @return 提交认证时使用的账号名称
     */
    public String getUsername() {
        return username;
    }

    /**
     * 返回登录认证时识别的来源地址。
     * 上游为 GlobalExceptionHandler.handleLoginLocked。
     *
     * @return 登录认证时识别的来源地址
     */
    public String getIp() {
        return ip;
    }

    /**
     * 返回还需等待才能重试的分钟数。
     * 上游为 GlobalExceptionHandler.handleLoginLocked。
     *
     * @return 还需等待才能重试的分钟数
     */
    public long getRetryAfterMinutes() {
        return Math.max(1, (retryAfterSeconds + 59) / 60);
    }
}
