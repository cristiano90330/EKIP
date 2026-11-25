/* 验证 AI 依赖、超时、限流和输入错误对应的重试分类及诊断。 */

package com.pony.knowledge.service.task;

import com.pony.knowledge.resilience.AiServiceUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class TaskFailureClassifierTest {

    private final TaskFailureClassifier classifier = new TaskFailureClassifier();

    /**
     * 在该类型内部准备后续运行所需的数据。
     * @param status 本次调用传入的 {@code int}
     * @return 调用方使用的 {@code RestClientResponseException}
     */
    private static RestClientResponseException httpError(int status) {
        return new RestClientResponseException("msg", status, "status", null,
                new byte[0], StandardCharsets.UTF_8);
    }

    /** 通过 {@code classifier.classify} 驱动该场景，实际结果与固定预期一致。 */
    @Test
    void networkAndTimeoutAreTransient() {
        assertThat(classifier.classify(
                new ResourceAccessException("connect timeout"))).isEqualTo(TaskFailureKind.TRANSIENT);
        assertThat(classifier.classify(
                new ResourceAccessException("Connection refused"))).isEqualTo(TaskFailureKind.TRANSIENT);
    }

    /** 通过 {@code AiServiceUnavailableException.circuitOpen}、{@code classifier.classify} 驱动该场景，核对 {@code classifier.classify(openCircuit)} 等于 {@code TaskFailureKind.TRANSIENT}。 */
    @Test
    void openAiCircuitIsTransientAndGetsStableReasonCode() {
        AiServiceUnavailableException openCircuit = AiServiceUnavailableException.circuitOpen(5000L);

        assertThat(classifier.classify(openCircuit)).isEqualTo(TaskFailureKind.TRANSIENT);
        assertThat(classifier.reasonCode(openCircuit)).isEqualTo("AI_UNAVAILABLE");
    }

    /** 通过 {@code classifier.classify}、{@code httpError} 驱动该场景，核对 {@code classifier.classify(httpError(500))} 等于 {@code TaskFailureKind.TRANSIENT}、{@code classifier.classify(httpError(502))} 等于 {@code TaskFailureKind.TRANSIENT}。 */
    @Test
    void serverErrorsAreTransient() {
        assertThat(classifier.classify(httpError(500))).isEqualTo(TaskFailureKind.TRANSIENT);
        assertThat(classifier.classify(httpError(502))).isEqualTo(TaskFailureKind.TRANSIENT);
        assertThat(classifier.classify(httpError(503))).isEqualTo(TaskFailureKind.TRANSIENT);
    }

    /** 通过 {@code classifier.classify}、{@code classifier.reasonCode} 驱动该场景，核对 {@code classifier.classify(httpError(400))} 等于 {@code TaskFailureKind.PERMANENT}、{@code classifier.classify(httpError(404))} 等于 {@code TaskFailureKind.PERMANENT}。 */
    @Test
    void clientErrorsArePermanent() {
        assertThat(classifier.classify(httpError(400))).isEqualTo(TaskFailureKind.PERMANENT);
        assertThat(classifier.classify(httpError(404))).isEqualTo(TaskFailureKind.PERMANENT);
        assertThat(classifier.classify(httpError(422))).isEqualTo(TaskFailureKind.PERMANENT);
        assertThat(classifier.classify(httpError(401))).isEqualTo(TaskFailureKind.PERMANENT);
        assertThat(classifier.reasonCode(httpError(401))).isEqualTo("AI_INTERNAL_AUTH");
    }

    /** 通过 {@code classifier.classify} 驱动该场景，实际结果与固定预期一致。 */
    @Test
    void unknownFailuresArePermanent() {
        assertThat(classifier.classify(new IllegalStateException("向量数不符"))).isEqualTo(TaskFailureKind.PERMANENT);
        assertThat(classifier.classify(new RuntimeException("boom"))).isEqualTo(TaskFailureKind.PERMANENT);
    }
}
