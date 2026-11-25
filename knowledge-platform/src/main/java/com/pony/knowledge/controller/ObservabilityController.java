/* 提供运行版本、服务能力以及当前用户可读的 RAG 轨迹接口。 */

package com.pony.knowledge.controller;

import com.pony.knowledge.common.Result;
import com.pony.knowledge.dto.observability.ObservabilityStatusDTO;
import com.pony.knowledge.dto.observability.ObservabilityCapabilitiesDTO;
import com.pony.knowledge.dto.observability.TraceDTO;
import com.pony.knowledge.dto.observability.TraceSummaryDTO;
import com.pony.knowledge.observability.RagTrace;
import com.pony.knowledge.observability.TraceStore;
import com.pony.knowledge.service.observability.TraceDtoConverter;
import com.pony.knowledge.service.observability.CapabilityReadinessService;
import com.pony.knowledge.service.vectorstore.VectorStore;
import com.pony.knowledge.utils.CurrentUserUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 暴露运行配置、缓存能力快照和 RAG trace；依赖探测与 trace 转换分别由服务层负责。 */
@RestController
@RequestMapping("/observability")
public class ObservabilityController {

    private final TraceStore traceStore;
    private final TraceDtoConverter converter;
    /** 实际装配的向量引擎。回显 name() 而非独立读配置，报告才与运行时一致。 */
    private final VectorStore vectorStore;
    private final CapabilityReadinessService capabilityReadinessService;
    /** Trace 含原始问题与阶段信息，读取入口按当前用户定界。 */
    private final CurrentUserUtil currentUserUtil;

    /**
     * 保存提供运行版本、服务能力以及当前用户可读的 RAG 轨迹接口所需的依赖和初始配置。
     * 上游为 Spring 组件装配。
     *
     * @param traceStore TraceStore，负责约定轨迹保存和按所有者读取的存储接口
     * @param converter 本次操作使用的 TraceDtoConverter 组件或记录
     * @param vectorStore VectorStore，负责约定当前向量存储实现对观测层提供的名称
     * @param capabilityReadinessService 本次操作使用的 CapabilityReadinessService 组件或记录
     * @param currentUserUtil 本次操作使用的 CurrentUserUtil 组件或记录
     */
    public ObservabilityController(TraceStore traceStore, TraceDtoConverter converter, VectorStore vectorStore,
                                   CapabilityReadinessService capabilityReadinessService,
                                   CurrentUserUtil currentUserUtil) {
        this.traceStore = traceStore;
        this.converter = converter;
        this.vectorStore = vectorStore;
        this.capabilityReadinessService = capabilityReadinessService;
        this.currentUserUtil = currentUserUtil;
    }

    @Value("${retrieval.version:baseline}") String retrievalVersion;
    @Value("${rag.pipeline.engine:fixed}") String pipelineEngine;
    @Value("${rag.compression.mode:none}")              String compressionMode;
    @Value("${observability.trace.retention:1000}")     int traceRetention;
    @Value("${git.branch:unknown}")                     String branch;
    @Value("${git.commit.id.abbrev:unknown}")           String commitSha;
    @Value("${spring.application.version:unknown}")     String appVersion;

    /**
     * 返回当前 Java 应用版本和实际检索、存储配置。
     * 上游为 HTTP GET /observability/status。
     * 下游为 VectorStore.name、Result.success。
     *
     * @return 封装应用版本、检索配置、向量存储和轨迹保留状态的业务响应
     */
    @GetMapping("/status")
    public Result<ObservabilityStatusDTO> status() {
        ObservabilityStatusDTO s = new ObservabilityStatusDTO();
        s.setAppVersion(appVersion);
        s.setBranch(branch);
        s.setCommitSha(commitSha);
        s.setRetrievalVersion(retrievalVersion);
        s.setPipelineEngine(pipelineEngine);
        s.setCompressionMode(compressionMode);
        s.setVectorStore(vectorStore.name());
        s.setTraceRetention(traceRetention);
        return Result.success(s);
    }

    /**
     * 返回最近一次依赖能力探测快照。
     * 上游为 HTTP GET /observability/capabilities。
     * 下游为 Result.success、CapabilityReadinessService.snapshot。
     *
     * @return 封装服务能力总体状态、探测时间和分项状态的业务响应
     */
    @GetMapping("/capabilities")
    public Result<ObservabilityCapabilitiesDTO> capabilities() {
        return Result.success(capabilityReadinessService.snapshot());
    }

    /**
     * 按当前访问账号的所有权读取近期问答轨迹摘要。
     * 上游为 HTTP GET /observability/trace。
     * 下游为 TraceStore.listRecentForOwner、Result.success。
     *
     * @param limit 本次最多处理或返回的项数
     * @param entrypoint 发起 RAG 请求的公开入口标识
     * @return 包含 List&lt;TraceSummaryDTO&gt; 数据的业务响应
     */
    @GetMapping("/trace")
    public Result<List<TraceSummaryDTO>> listTraces(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String entrypoint) {
        int capped = Math.min(Math.max(limit, 1), 200);
        List<TraceSummaryDTO> traces = traceStore.listRecentForOwner(currentUserUtil.getUserId()).stream()
                .filter(t -> entrypoint == null || entrypoint.isBlank() || entrypoint.equals(t.getEntrypoint()))
                .limit(capped)
                .map(converter::toSummary)
                .toList();
        return Result.success(traces);
    }

    /**
     * 按请求 ID 和账号所有权查找轨迹详情。
     * 上游为 HTTP GET /observability/trace/{traceId}。
     * 下游为 TraceStore.findByIdForOwner、Result.error。
     *
     * @param traceId 用于定位观测记录的轨迹标识
     * @return 带 HTTP 状态与相关业务字段的响应
     */
    @GetMapping("/trace/{traceId}")
    public ResponseEntity<Result<TraceDTO>> getTrace(@PathVariable String traceId) {
        RagTrace t = traceStore.findByIdForOwner(traceId, currentUserUtil.getUserId());
        if (t == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Result.error(HttpStatus.NOT_FOUND.value(), "trace 不存在: " + traceId));
        }
        return ResponseEntity.ok(Result.success(converter.toDto(t)));
    }
}
