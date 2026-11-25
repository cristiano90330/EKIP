/* 探测 Java 与 AI 服务依赖，维护供状态接口读取的能力快照。 */

package com.pony.knowledge.service.observability;

import com.pony.knowledge.dto.observability.CapabilityStatusDTO;
import com.pony.knowledge.dto.observability.ObservabilityCapabilitiesDTO;
import com.pony.knowledge.service.ai.AiServiceClient;
import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 定时检查数据库、Redis、MinIO 和 Python AI，将依赖结果组合为入库、检索、问答等能力快照。
 * 探针在有界专用池中执行，每次等待有超时；请求只读取最近一次完整替换的缓存，初始状态为 unknown。
 * 这些结果用于报告能力，不作为 Spring 启动闸门，也不证明文档处理或模型回答已成功。
 */
@Service
public class CapabilityReadinessService {
    private static final Logger log = LoggerFactory.getLogger(CapabilityReadinessService.class);
    private static final String READY = "ready";
    private static final String NOT_READY = "not_ready";
    private static final String UNKNOWN = "unknown";

    private final AiServiceClient aiServiceClient;
    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redis;
    private final MinioClient minio;
    private final String bucketName;
    private final long probeTimeoutMs;
    private final ThreadPoolExecutor probePool;
    private volatile ObservabilityCapabilitiesDTO snapshot = unknownSnapshot();

    /**
     * 保存探测 Java 与 AI 服务依赖所需的依赖和初始配置。
     * 上游为 Spring 组件装配。
     *
     * @param aiServiceClient AiServiceClient，负责装配 Java 到 AI 服务的内部 HTTP 请求，承接解析、嵌入、问答与能力检查
     * @param jdbcTemplate 负责数据库 SQL 的 JDBC 组件
     * @param redis 访问 Redis 保存和读取状态的客户端
     * @param minio 本次操作所用的 MinioClient 数据
     * @param bucketName 对象存储桶名
     * @param probeTimeoutMs 以毫秒计的时间限制
     */
    public CapabilityReadinessService(
            AiServiceClient aiServiceClient,
            JdbcTemplate jdbcTemplate,
            StringRedisTemplate redis,
            MinioClient minio,
            @Value("${minio.bucket-name}") String bucketName,
            @Value("${observability.capabilities.probe-timeout-ms:750}") long probeTimeoutMs) {
        this.aiServiceClient = aiServiceClient;
        this.jdbcTemplate = jdbcTemplate;
        this.redis = redis;
        this.minio = minio;
        this.bucketName = bucketName;
        this.probeTimeoutMs = Math.max(100L, probeTimeoutMs);
        this.probePool = new ThreadPoolExecutor(5, 5, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(5), runnable -> {
                    Thread thread = new Thread(runnable, "capability-probe");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 读取最近的依赖能力快照，供状态端点返回。
     * 上游为 ObservabilityController.capabilities。
     *
     * @return 服务能力总体状态、探测时间和分项状态，以 ObservabilityCapabilitiesDTO 返回
     */
    public ObservabilityCapabilitiesDTO snapshot() {
        return snapshot;
    }

    /**
     * 并行探测依赖能力并发布供状态端点使用的新快照。
     * 上游为 应用的定时调度器。
     * 下游为 AiServiceClient.readiness。
     */
    @Scheduled(fixedDelayString = "${observability.capabilities.refresh-interval-ms:10000}",
            initialDelayString = "${observability.capabilities.initial-delay-ms:1000}")
    public void refresh() {
        CapabilityStatusDTO database = probe(() -> jdbcTemplate.queryForObject("SELECT 1", Integer.class) == 1,
                "database_unavailable");
        CapabilityStatusDTO redisStatus = probe(() -> "PONG".equalsIgnoreCase(
                redis.execute((RedisCallback<String>) connection -> connection.ping())), "redis_unavailable");
        CapabilityStatusDTO minioStatus = probe(() -> minio.bucketExists(
                BucketExistsArgs.builder().bucket(bucketName).build()), "minio_unavailable");
        AiProbeResult aiProbe = probeValue(() -> {
            try {
                return new AiProbeResult(aiServiceClient.readiness(), null);
            } catch (RestClientResponseException e) {
                return new AiProbeResult(null, e.getStatusCode().value() == 401
                        ? "ai_internal_auth" : "ai_unavailable");
            } catch (RuntimeException e) {
                return new AiProbeResult(null, "ai_unavailable");
            }
        });
        AiServiceClient.AiReadinessResponse ai = aiProbe == null ? null : aiProbe.response();

        Map<String, CapabilityStatusDTO> aiCapabilities = ai == null
                ? unavailableAiCapabilities(aiProbe == null ? "ai_unavailable" : aiProbe.failureCode())
                : ai.getCapabilities() == null ? Map.of() : ai.getCapabilities().entrySet().stream().collect(LinkedHashMap::new,
                (map, entry) -> map.put(entry.getKey(), fromAi(entry.getValue())), Map::putAll);

        CapabilityStatusDTO backend = ready("none");
        CapabilityStatusDTO aiIngest = aiCapability(aiCapabilities, "ingest");
        CapabilityStatusDTO aiSearch = aiCapability(aiCapabilities, "search");
        CapabilityStatusDTO aiAnswer = aiCapability(aiCapabilities, "answer");
        CapabilityStatusDTO search = combine("search_unavailable", aiSearch, database);
        CapabilityStatusDTO ingest = combine("ingest_unavailable", aiIngest, minioStatus, database);
        CapabilityStatusDTO answer = combine("answer_unavailable", aiAnswer, search);
        CapabilityStatusDTO chat = combine("chat_history_unavailable", answer, redisStatus);

        Map<String, CapabilityStatusDTO> capabilities = new LinkedHashMap<>();
        capabilities.put("backend", backend);
        capabilities.put("database", database);
        capabilities.put("redis", redisStatus);
        capabilities.put("minio", minioStatus);
        capabilities.put("ingest", ingest);
        capabilities.put("search", search);
        capabilities.put("answer", answer);
        capabilities.put("chat", chat);
        capabilities.put("evidenceGate", aiCapability(aiCapabilities, "evidenceGate"));
        capabilities.put("rerank", aiCapability(aiCapabilities, "rerank"));

        ObservabilityCapabilitiesDTO next = new ObservabilityCapabilitiesDTO();
        next.setCheckedAt(Instant.now());
        next.setCapabilities(Map.copyOf(capabilities));
        next.setStatus(overallStatus(capabilities));
        snapshot = next;
    }

    /**
     * 带探测边界执行依赖检查并返回标准化状态。
     * 上游为 CapabilityReadinessService.refresh。
     * 下游为 CapabilityReadinessService.probeValue、CapabilityReadinessService.notReady。
     *
     * @param check 由此处在对应上下文或作用域内执行的 Callable&lt;Boolean&gt; 回调
     * @param unavailableCode 本方法计算或判断使用的 unavailableCode 值
     * @return 本次得到的 CapabilityStatusDTO 实例
     */
    private CapabilityStatusDTO probe(Callable<Boolean> check, String unavailableCode) {
        Boolean healthy = probeValue(check);
        if (healthy == null) {
            return notReady(unavailableCode);
        }
        return healthy ? ready("none") : notReady(unavailableCode);
    }

    /**
     * 在探测执行器中限时获取某项依赖的原始状态值。
     * 上游为 CapabilityReadinessService.refresh、CapabilityReadinessService.probe。
     *
     * @param check 由此处在对应上下文或作用域内执行的 Callable&lt;T&gt; 回调
     * @return 业务回调实际返回的结果
     */
    private <T> T probeValue(Callable<T> check) {
        Future<T> future;
        try {
            future = probePool.submit(check);
        } catch (RejectedExecutionException e) {
            return null;
        }
        try {
            return future.get(probeTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return null;
        } catch (ExecutionException | RuntimeException e) {
            return null;
        }
    }

    /**
     * 把 AI 能力响应聚合为 Java 状态接口可读的各项信息。
     * 上游为 CapabilityReadinessService.refresh。
     * 下游为 AiCapability.getStatus、CapabilityReadinessService.codeOr。
     *
     * @param capability 本次操作所用的 AiServiceClient.AiCapability 数据
     * @return 本次得到的 CapabilityStatusDTO 实例
     */
    private static CapabilityStatusDTO fromAi(AiServiceClient.AiCapability capability) {
        if (capability == null) {
            return new CapabilityStatusDTO(UNKNOWN, "probe_not_run", null, null);
        }
        String status = capability.getStatus();

        if (READY.equals(status) || "degraded".equals(status) || NOT_READY.equals(status)
                || UNKNOWN.equals(status)) {
            return new CapabilityStatusDTO(status,
                    codeOr(capability.getReasonCode(), READY.equals(status) ? "none" : status),
                    capability.getPolicyVersion(), capability.getScorer());
        }
        if (capability.getReady() == null) {
            return new CapabilityStatusDTO(UNKNOWN, "probe_not_run", capability.getPolicyVersion(),
                    capability.getScorer());
        }
        return new CapabilityStatusDTO(capability.getReady() ? READY : NOT_READY,
                codeOr(capability.getReasonCode(), capability.getReady() ? "none" : "dependency_unavailable"),
                capability.getPolicyVersion(), capability.getScorer());
    }

    /**
     * 把 AI 分项能力的状态和原因映射为 Java 状态模型。
     * 上游为 CapabilityReadinessService.refresh。
     *
     * @param ai 含当前操作所需属性的映射
     * @param key 本方法计算或判断使用的 key 值
     * @return 本次得到的 CapabilityStatusDTO 实例
     */
    private static CapabilityStatusDTO aiCapability(Map<String, CapabilityStatusDTO> ai, String key) {
        return ai.getOrDefault(key, new CapabilityStatusDTO(UNKNOWN, "probe_not_run", null, null));
    }

    /**
     * 构造 AI 依赖不可达时的能力观测数据。
     * 上游为 CapabilityReadinessService.refresh。
     * 下游为 CapabilityReadinessService.notReady、CapabilityReadinessService.codeOr。
     *
     * @param reasonCode 用于区分失败或降级路径的原因码
     * @return 由本方法收集或转换的键值映射
     */
    private static Map<String, CapabilityStatusDTO> unavailableAiCapabilities(String reasonCode) {
        Map<String, CapabilityStatusDTO> unavailable = new LinkedHashMap<>();
        for (String key : new String[]{"ingest", "search", "answer", "evidenceGate", "rerank"}) {
            unavailable.put(key, notReady(codeOr(reasonCode, "ai_unavailable")));
        }
        return unavailable;
    }

    /**
     * 将组成一项能力的多个前置依赖结果归并。
     * 上游为 CapabilityReadinessService.refresh。
     * 下游为 CapabilityReadinessService.notReady、CapabilityReadinessService.codeOr。
     *
     * @param reasonCode 用于区分失败或降级路径的原因码
     * @param dependencies 需要本方法按序读取或筛选的 CapabilityStatusDTO[] 数据
     * @return 本次得到的 CapabilityStatusDTO 实例
     */
    private static CapabilityStatusDTO combine(String reasonCode, CapabilityStatusDTO... dependencies) {
        for (CapabilityStatusDTO dependency : dependencies) {
            if (dependency != null && NOT_READY.equals(dependency.getStatus())) {
                return notReady(codeOr(dependency.getReasonCode(), reasonCode));
            }
        }
        for (CapabilityStatusDTO dependency : dependencies) {
            if (dependency == null || UNKNOWN.equals(dependency.getStatus())) {
                return unknown(dependency == null ? "probe_not_run"
                        : codeOr(dependency.getReasonCode(), "probe_not_run"));
            }
        }
        for (CapabilityStatusDTO dependency : dependencies) {
            if ("degraded".equals(dependency.getStatus())) {
                return degraded(codeOr(dependency.getReasonCode(), "dependency_degraded"));
            }
        }
        return ready("none");
    }

    /**
     * 从各项能力准备状态推导对外总体状态。
     * 上游为 CapabilityReadinessService.refresh。
     *
     * @param capabilities 模型或服务提供的能力信息
     * @return 由能力子状态推导的聚合可用状态
     */
    private static String overallStatus(Map<String, CapabilityStatusDTO> capabilities) {
        String[] required = {"backend", "database", "redis", "minio", "ingest", "search", "answer", "chat"};
        boolean allReady = true;

        for (String key : required) {
            String status = capabilities.get(key).getStatus();
            allReady &= READY.equals(status);
        }

        if (allReady) {
            return READY;
        }
        String[] operational = {"ingest", "search", "answer", "chat"};
        boolean anyOperationalCapabilityReady = false;

        for (String key : operational) {
            CapabilityStatusDTO capability = capabilities.get(key);
            anyOperationalCapabilityReady |= capability != null && READY.equals(capability.getStatus());
        }

        if (anyOperationalCapabilityReady) {
            return "degraded";
        }

        for (String key : operational) {
            CapabilityStatusDTO capability = capabilities.get(key);
            if (capability != null && NOT_READY.equals(capability.getStatus())) {
                return NOT_READY;
            }
        }
        for (String key : operational) {
            CapabilityStatusDTO capability = capabilities.get(key);
            if (capability == null || UNKNOWN.equals(capability.getStatus())) {
                return UNKNOWN;
            }
        }
        for (String key : operational) {
            CapabilityStatusDTO capability = capabilities.get(key);
            if (capability != null && "degraded".equals(capability.getStatus())) {
                return "degraded";
            }
        }
        return UNKNOWN;
    }

    /**
     * 保留已有诊断码，空缺时采用备用原因。
     * 上游为 CapabilityReadinessService.fromAi、CapabilityReadinessService.unavailableAiCapabilities。
     *
     * @param value 待校验或转换的值
     * @param fallback 原始值缺失或无效时使用的备选值
     * @return 有效的原诊断码或调用方提供的备用原因
     */
    private static String codeOr(String value, String fallback) {
        return value != null && value.matches("[a-z][a-z0-9_]{0,63}") ? value : fallback;
    }

    /**
     * 构建可用的单项依赖能力状态。
     * 上游为 CapabilityReadinessService.refresh、CapabilityReadinessService.probe。
     *
     * @param code 持久化或对外公开的状态码
     * @return 本次得到的 CapabilityStatusDTO 实例
     */
    private static CapabilityStatusDTO ready(String code) {
        return new CapabilityStatusDTO(READY, code, null, null);
    }

    /**
     * 携带原因码构建当前不可用的能力状态。
     * 上游为 CapabilityReadinessService.probe、CapabilityReadinessService.unavailableAiCapabilities。
     *
     * @param code 持久化或对外公开的状态码
     * @return 本次得到的 CapabilityStatusDTO 实例
     */
    private static CapabilityStatusDTO notReady(String code) {
        return new CapabilityStatusDTO(NOT_READY, code, null, null);
    }

    /**
     * 携带原因码构建尚未确定可用性的能力状态。
     * 上游为 CapabilityReadinessService.combine。
     *
     * @param code 持久化或对外公开的状态码
     * @return 本次得到的 CapabilityStatusDTO 实例
     */
    private static CapabilityStatusDTO unknown(String code) {
        return new CapabilityStatusDTO(UNKNOWN, code, null, null);
    }

    /**
     * 构建能力可部分使用、存在故障原因的观测状态。
     * 上游为 CapabilityReadinessService.combine。
     *
     * @param code 持久化或对外公开的状态码
     * @return 本次得到的 CapabilityStatusDTO 实例
     */
    private static CapabilityStatusDTO degraded(String code) {
        return new CapabilityStatusDTO("degraded", code, null, null);
    }

    /**
     * 为尚未执行依赖探测的服务构造初始快照。
     *
     * @return 服务能力总体状态、探测时间和分项状态，以 ObservabilityCapabilitiesDTO 返回
     */
    private static ObservabilityCapabilitiesDTO unknownSnapshot() {
        Map<String, CapabilityStatusDTO> capabilities = new LinkedHashMap<>();

        for (String key : new String[]{"backend", "database", "redis", "minio", "ingest", "search",
                "answer", "chat", "evidenceGate", "rerank"}) {
            capabilities.put(key, new CapabilityStatusDTO(UNKNOWN, "probe_not_run", null, null));
        }
        ObservabilityCapabilitiesDTO dto = new ObservabilityCapabilitiesDTO();
        dto.setStatus(UNKNOWN);
        dto.setCheckedAt(Instant.EPOCH);
        dto.setCapabilities(Map.copyOf(capabilities));
        return dto;
    }

    /**
     * 停止用于后台依赖探测的线程池。
     * 上游为 Spring 容器生命周期或应用事件。
     */
    @PreDestroy
    public void close() {
        probePool.shutdownNow();
    }

    /** 保存 AI 就绪响应，或无法取得响应时的失败原因。 */
    private record AiProbeResult(AiServiceClient.AiReadinessResponse response, String failureCode) {
    }
}
