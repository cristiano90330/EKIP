/* 承载供 API 和后台重试识别的 AI 依赖不可用异常。 */

package com.pony.knowledge.resilience;

/**
 * JVM → Python 的这一跳**不可达**时抛出的异常（评审 B3 项：快速失败）。
 *
 * <p>语义边界（重要，别当成 Universal 的"AI 调用失败"）：
 * <ul>
 *   <li><b>只表示"连不上"</b>——连接被拒 / 连接超时 / 主机不可达，或熔断器已打开而**根本没发请求**
 *       （{@link AiAvailabilityGuard#acquire()}）。</li>
 *   <li><b>不表示</b>"Python 回了错"（4xx/5xx 各自有原有异常）、也<b>不表示</b>"读超时"——
 *       进程 accept 了 TCP 却不应答属于"卡住"，那是 `ai.client.read-timeout-ms` 的事，
 *       不是本异常要解决的（见 {@link AiAvailabilityGuard} 的类注释）。</li>
 * </ul>
 *
 * <p>对外状态由各业务边界映射；不在异常类型上固定 HTTP 状态，也不向客户端暴露内部类名。
 */
public class AiServiceUnavailableException extends RuntimeException {

    /**
     * 构建断路器开启导致 AI 调用被拒绝的异常。
     * 上游为 AiAvailabilityGuard.acquire。
     *
     * @param retryAfterMs 以毫秒计的时间限制
     * @return 供 API 和后台重试识别的 AI 依赖不可用异常，以 AiServiceUnavailableException 返回
     */
    public static AiServiceUnavailableException circuitOpen(long retryAfterMs) {
        return new AiServiceUnavailableException(
                "AI 服务不可达：熔断器已打开，本次调用未发出（约 " + retryAfterMs + "ms 后进入半开探测）",
                null);
    }

    /**
     * 创建用于向调用方传递当前错误的异常。
     *
     * @param message 用于构建结果或异常的说明文字
     * @param cause 当前流程捕获的 Throwable 异常
     */
    public AiServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
