/* 从客户端异常链和响应状态识别 AI 依赖不可达故障。 */

package com.pony.knowledge.resilience;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;

/**
 * Java → Python 同步及流式客户端共用的连接故障分类。
 * ConnectException、连接超时、DNS 和路由错误计入熔断失败；沿 cause 链检查包装异常。
 * 读超时、取消和其它 I/O 错误不证明服务不可达，不计入失败率。
 * HTTP 5xx 由客户端记为失败，4xx 忽略，2xx/3xx 记为成功。
 */
public final class AiFailureClassifier {

    /** 初始化 AiFailureClassifier，供本文件定义的构建入口使用。 */
    private AiFailureClassifier() {
    }

    /**
     * 检查异常链及 HTTP 状态是否表明 AI 依赖不可用。
     * 上游为 AgentGatewayClient.post、AgentGatewayClient.postSse。
     *
     * @param error 本次执行捕获的异常
     * @return 条件是否成立（检查异常链及 HTTP 状态是否表明 AI 依赖不可用）
     */
    public static boolean isUnreachable(Throwable error) {
        Throwable t = error;
        // 自环保护：理论上 cause 链不会成环，但排障期手造的测试异常可能，死循环比漏判更难查
        int guard = 0;

        while (t != null && guard++ < 20) {
            if (t instanceof ConnectException
                    || t instanceof HttpConnectTimeoutException
                    || t instanceof UnknownHostException
                    || t instanceof NoRouteToHostException) {
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
