/* 检查 HTTP、传输和业务错误如何归类为 AI 依赖失败。 */

package com.pony.knowledge.resilience;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 评审 B3 / C-2 的判据边界：**哪些失败算「AI 服务不可达」**。
 *
 * <p>这组用例的价值全在**反例**上——把不该算的算进去（尤其读超时），
 * 后果是熔断器会被"服务活着只是慢"的场景打开，把健康调用一起拒掉。
 * 这是本项最容易写错、且写错后最难归因的地方（症状是"偶发全量 503"，与故障现场无关）。
 */
class AiFailureClassifierTest {

    /** 核对 {@code AiFailureClassifier.isUnreachable(new ConnectException("Connection refused"))} 为真。 */
    @Test
    void connectRefused_isUnreachable() {
        // 进程不在 / 端口关闭：评审 B3 里"Python 不可用"的典型形态
        assertTrue(AiFailureClassifier.isUnreachable(new ConnectException("Connection refused")));
    }

    /** 核对 {@code AiFailureClassifier.isUnreachable} 为真。 */
    @Test
    void connectTimeout_isUnreachable() {
        assertTrue(AiFailureClassifier.isUnreachable(new HttpConnectTimeoutException("connect timed out")));
    }

    /** 核对 {@code AiFailureClassifier.isUnreachable(new UnknownHostException("ekip-ai"))} 为真。 */
    @Test
    void unknownHost_andNoRoute_isUnreachable() {
        assertTrue(AiFailureClassifier.isUnreachable(new UnknownHostException("ekip-ai")));
        assertTrue(AiFailureClassifier.isUnreachable(new NoRouteToHostException("no route")));
    }

    /**
     * 🔴 本组最重要的一条：读超时**不**算不可达。
     *
     * <p>{@code HttpConnectTimeoutException} 是 {@code HttpTimeoutException} 的**子类**，
     * 所以判定必须用子类做 instanceof。若写成父类，一次 rerank 冷启动
     * （RERANK_SERVER_TIMEOUT_S=30 打满，实测可达 38.9s）就会打开熔断器——
     * 而那一刻 AI 服务是完全健康的。
     */
    /** 覆盖超时的相关边界。通过 {@code AiFailureClassifier.isUnreachable} 执行本例并核对输出或记录。 */
    @Test
    void readTimeout_isNotUnreachable() {
        assertFalse(AiFailureClassifier.isUnreachable(new HttpTimeoutException("request timed out")),
                "读超时不是不可达：TCP 已建连，服务活着只是这一单慢");
    }

    /** 核对 {@code AiFailureClassifier.isUnreachable(new IOException("connection reset by peer"))} 为假。 */
    @Test
    void otherIoException_isNotUnreachable() {
        assertFalse(AiFailureClassifier.isUnreachable(new IOException("connection reset by peer")));
        assertFalse(AiFailureClassifier.isUnreachable(new RuntimeException("boom")));
        assertFalse(AiFailureClassifier.isUnreachable(null));
    }

    /**
     * 必须沿 cause 链找：两条出口的包法不同——{@code AgentGatewayClient} 包进
     * {@code AgentGatewayException}，{@code AiServiceClient} 走 Spring {@code ResourceAccessException}。
     * 只看最外层会漏判，症状是「熔断器永远不打开」。
     */
    /** 核对 {@code AiFailureClassifier.isUnreachable(wrapped)} 为真。 */
    @Test
    void unreachableCause_isFoundThroughWrapperChain() {
        Throwable wrapped = new RuntimeException("外层包装",
                new IllegalStateException("再包一层", new ConnectException("Connection refused")));
        assertTrue(AiFailureClassifier.isUnreachable(wrapped));
    }

    /** 核对 {@code AiFailureClassifier.isUnreachable(selfRef)} 为假。 */
    @Test
    void selfReferencingCause_doesNotLoopForever() {
        RuntimeException selfRef = new RuntimeException("self") {
            /** 读取夹具字段。返回 {@code this}。 */
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };
        assertFalse(AiFailureClassifier.isUnreachable(selfRef));
    }
}
