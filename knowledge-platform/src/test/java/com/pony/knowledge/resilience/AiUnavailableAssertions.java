/* 为 Java AI 调用方测试提供不可用异常的公共断言。 */

package com.pony.knowledge.resilience;

/**
 * 熔断相关的测试断言（供两条出口的测试共用：{@code AiServiceClient} 与 {@code AgentGatewayClient}）。
 *
 * <p>为什么不直接 {@code assertThrows(AiServiceUnavailableException.class, ...)}：
 * 「熔断打开时快速失败」这个行为要穿过各自传输层的异常包装——{@code AiServiceClient} 走
 * Spring {@code RestClient}（可能包成 {@code RestClientException}），
 * {@code AgentGatewayClient} 把传输异常包进自己的 {@code AgentGatewayException}。
 * 断言"链上能溯源到"比断言"最外层类型"更贴近被验证的契约，也不会因为框架内部改动而假红。
 */
public final class AiUnavailableAssertions {

    /** 构造 {@code AiUnavailableAssertions} 夹具，绑定本次场景的输入。 */
    private AiUnavailableAssertions() {
    }

    /** 构造 {@code boolean} 供测试使用，使用 {@code t.getCause}。接收{@code error}。返回 {@code true}。 */
    public static boolean causeChainHasUnavailable(Throwable error) {
        Throwable t = error;
        int guard = 0;
        while (t != null && guard++ < 20) {
            if (t instanceof AiServiceUnavailableException) {
                return true;
            }
            if (t == t.getCause()) {
                break;
            }
            t = t.getCause();
        }
        return false;
    }
}
