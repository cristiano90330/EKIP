/* 验证能力就绪检查如何汇总外部依赖状态及诊断结果。 */

package com.pony.knowledge.service.observability;

import com.pony.knowledge.dto.observability.ObservabilityCapabilitiesDTO;
import com.pony.knowledge.service.ai.AiServiceClient;
import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CapabilityReadinessServiceTest {

    /**
     * 协调 {@code service} 完成当前阶段，供后续处理使用。
     * 下游调用 {@code service}。
     *
     * @param ingest 本次调用传入的 {@code boolean}
     * @param search 本次调用传入的 {@code boolean}
     * @param answer 本次调用传入的 {@code boolean}
     * @return 调用方使用的 {@code CapabilityReadinessService}
     */
    private CapabilityReadinessService service(boolean ingest, boolean search, boolean answer) {
        return service(ingest, search, answer, true, true);
    }

    /**
     * 协调 {@code service} 完成当前阶段，供后续处理使用。
     * 下游调用 {@code service}、{@code capability}。
     *
     * @param ingest 本次调用传入的 {@code boolean}
     * @param search 本次调用传入的 {@code boolean}
     * @param answer 本次调用传入的 {@code boolean}
     * @param databaseReady 本次调用传入的 {@code boolean}
     * @param redisReady 本次调用传入的 {@code boolean}
     * @return 调用方使用的 {@code CapabilityReadinessService}
     */
    private CapabilityReadinessService service(boolean ingest, boolean search, boolean answer,
            boolean databaseReady, boolean redisReady) {
        return service(capability(ingest), capability(search), capability(answer), databaseReady, redisReady);
    }

    /**
     * 协调 {@code service} 完成当前阶段，供后续处理使用。
     * 下游调用 {@code service}、{@code capability}。
     *
     * @param ingest 本次调用传入的 {@code String}
     * @param search 本次调用传入的 {@code String}
     * @param answer 本次调用传入的 {@code String}
     * @return 调用方使用的 {@code CapabilityReadinessService}
     */
    private CapabilityReadinessService service(String ingest, String search, String answer) {
        return service(capability(ingest, null), capability(search, null), capability(answer, null), true, true);
    }

    /**
     * 协调 {@code aiClient.readiness} 完成当前阶段，供后续处理使用。
     * 下游调用 {@code aiClient.readiness}、{@code jdbc.queryForObject}、{@code redis.execute}。
     *
     * @param ingest 本次调用传入的 {@code AiServiceClient.AiCapability}
     * @param search 本次调用传入的 {@code AiServiceClient.AiCapability}
     * @param answer 本次调用传入的 {@code AiServiceClient.AiCapability}
     * @param databaseReady 本次调用传入的 {@code boolean}
     * @param redisReady 本次调用传入的 {@code boolean}
     * @return 调用方使用的 {@code CapabilityReadinessService}
     */
    private CapabilityReadinessService service(AiServiceClient.AiCapability ingest,
            AiServiceClient.AiCapability search, AiServiceClient.AiCapability answer,
            boolean databaseReady, boolean redisReady) {
        AiServiceClient aiClient = mock(AiServiceClient.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        MinioClient minio = mock(MinioClient.class);

        AiServiceClient.AiReadinessResponse ai = new AiServiceClient.AiReadinessResponse();
        ai.setCapabilities(Map.of(
                "ingest", ingest,
                "search", search,
                "answer", answer,
                "evidenceGate", capability("degraded", false),
                "rerank", capability("unknown", true)));
        when(aiClient.readiness()).thenReturn(ai);
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(databaseReady ? 1 : 0);
        when(redis.execute(any(RedisCallback.class))).thenReturn(redisReady ? "PONG" : null);
        try {
            when(minio.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
        } catch (Exception e) {
            throw new AssertionError(e);
        }

        return new CapabilityReadinessService(aiClient, jdbc, redis, minio, "bucket", 200);
    }

    /**
     * 返回当前计算采用的 {@code capability}。
     *
     * @param ready 本次调用传入的 {@code boolean}
     * @return 当前记录中的 {@code capability}
     */
    private static AiServiceClient.AiCapability capability(boolean ready) {
        AiServiceClient.AiCapability capability = new AiServiceClient.AiCapability();
        capability.setReady(ready);
        capability.setReasonCode(ready ? "none" : "model_unavailable");
        return capability;
    }

    /**
     * 返回当前计算采用的 {@code capability}。
     *
     * @param status 本次调用传入的 {@code String}
     * @param ready 本次调用传入的 {@code Boolean}
     * @return 当前记录中的 {@code capability}
     */
    private static AiServiceClient.AiCapability capability(String status, Boolean ready) {
        AiServiceClient.AiCapability capability = new AiServiceClient.AiCapability();
        capability.setStatus(status);
        capability.setReady(ready);
        capability.setReasonCode("capability_" + status);
        return capability;
    }

    /** 通过 {@code service.refresh}、{@code service.snapshot} 驱动该场景，实际结果与固定预期一致。 */
    @Test
    void reachableInfrastructureWithNoOperationalCapabilityIsNotReady() {
        CapabilityReadinessService service = service(false, false, false);
        try {
            service.refresh();
            ObservabilityCapabilitiesDTO snapshot = service.snapshot();
            assertThat(snapshot.getCapabilities().get("backend").getStatus()).isEqualTo("ready");
            assertThat(snapshot.getCapabilities().get("database").getStatus()).isEqualTo("ready");
            assertThat(snapshot.getCapabilities().get("redis").getStatus()).isEqualTo("ready");
            assertThat(snapshot.getCapabilities().get("minio").getStatus()).isEqualTo("ready");
            assertThat(snapshot.getCapabilities().get("ingest").getStatus()).isEqualTo("not_ready");
            assertThat(snapshot.getCapabilities().get("search").getStatus()).isEqualTo("not_ready");
            assertThat(snapshot.getCapabilities().get("evidenceGate").getStatus()).isEqualTo("degraded");
            assertThat(snapshot.getCapabilities().get("rerank").getStatus()).isEqualTo("unknown");
            assertThat(snapshot.getStatus()).isEqualTo("not_ready");
        } finally {
            service.close();
        }
    }

    /** 通过 {@code notProbed.snapshot}、{@code notProbed.close} 驱动该场景，实际结果与固定预期一致。 */
    @Test
    void readinessIsUnknownBeforeFirstProbeAndWhenAllBusinessCapabilitiesAreUnknown() {
        CapabilityReadinessService notProbed = service(false, false, false);
        try {
            assertThat(notProbed.snapshot().getStatus()).isEqualTo("unknown");
        } finally {
            notProbed.close();
        }

        CapabilityReadinessService allUnknown = service("unknown", "unknown", "unknown");
        try {
            allUnknown.refresh();
            assertThat(allUnknown.snapshot().getCapabilities().get("ingest").getStatus()).isEqualTo("unknown");
            assertThat(allUnknown.snapshot().getCapabilities().get("search").getStatus()).isEqualTo("unknown");
            assertThat(allUnknown.snapshot().getStatus()).isEqualTo("unknown");
            assertThat(allUnknown.snapshot().getCapabilities().get("rerank").getStatus()).isEqualTo("unknown");
        } finally {
            allUnknown.close();
        }
    }

    /** 通过 {@code unknownOverDegraded.refresh}、{@code unknownOverDegraded.snapshot} 驱动该场景，实际结果与固定预期一致。 */
    @Test
    void overallUsesFailureThenUnknownThenDegradedPriorityWhenNothingIsReady() {
        CapabilityReadinessService unknownOverDegraded = service("unknown", "degraded", "degraded");
        try {
            unknownOverDegraded.refresh();
            assertThat(unknownOverDegraded.snapshot().getStatus()).isEqualTo("unknown");
        } finally {
            unknownOverDegraded.close();
        }

        CapabilityReadinessService failureOverUnknown = service("not_ready", "unknown", "degraded");
        try {
            failureOverUnknown.refresh();
            assertThat(failureOverUnknown.snapshot().getStatus()).isEqualTo("not_ready");
        } finally {
            failureOverUnknown.close();
        }
    }

    /** 通过 {@code service.refresh}、{@code service.snapshot} 驱动该场景，实际结果与固定预期一致。 */
    @Test
    void partialOperationalAvailabilityIsDegraded() {
        CapabilityReadinessService service = service(false, true, false);
        try {
            service.refresh();
            assertThat(service.snapshot().getCapabilities().get("search").getStatus()).isEqualTo("ready");
            assertThat(service.snapshot().getStatus()).isEqualTo("degraded");
        } finally {
            service.close();
        }
    }

    /** 通过 {@code service.refresh}、{@code service.snapshot} 驱动该场景，实际结果与固定预期一致。 */
    @Test
    void databaseOutageMakesIngestUnavailableAndAllBusinessUnavailableNotReady() {
        CapabilityReadinessService service = service(true, false, false, false, true);
        try {
            service.refresh();
            assertThat(service.snapshot().getCapabilities().get("database").getStatus()).isEqualTo("not_ready");
            assertThat(service.snapshot().getCapabilities().get("ingest").getStatus()).isEqualTo("not_ready");
            assertThat(service.snapshot().getStatus()).isEqualTo("not_ready");
        } finally {
            service.close();
        }
    }

    /** 通过 {@code service.refresh}、{@code service.snapshot} 驱动该场景，实际结果与固定预期一致。 */
    @Test
    void redisOutageWithOtherOperationsReadyIsDegraded() {
        CapabilityReadinessService service = service(true, true, true, true, false);
        try {
            service.refresh();
            assertThat(service.snapshot().getCapabilities().get("ingest").getStatus()).isEqualTo("ready");
            assertThat(service.snapshot().getCapabilities().get("search").getStatus()).isEqualTo("ready");
            assertThat(service.snapshot().getCapabilities().get("answer").getStatus()).isEqualTo("ready");
            assertThat(service.snapshot().getCapabilities().get("chat").getStatus()).isEqualTo("not_ready");
            assertThat(service.snapshot().getStatus()).isEqualTo("degraded");
        } finally {
            service.close();
        }
    }

}
