/* 将内部 RAG 轨迹及阶段信息映射为详情和列表响应。 */

package com.pony.knowledge.service.observability;

import com.pony.knowledge.observability.RagTrace;
import com.pony.knowledge.observability.RagSpan;
import com.pony.knowledge.dto.observability.TraceDTO;
import com.pony.knowledge.dto.observability.TraceSpanDTO;
import com.pony.knowledge.dto.observability.TraceSummaryDTO;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class TraceDtoConverter {

    /**
     * 把内部轨迹和阶段映射为有权读取轨迹详情的响应。
     * 上游为 ObservabilityController.getTrace。
     * 下游为 RagTrace.getTraceId、RagTrace.getEntrypoint。
     *
     * @param t 本次操作使用的 RagTrace 组件或记录
     * @return 单条 RAG 轨迹及各阶段详细记录，以 TraceDTO 返回
     */
    public TraceDTO toDto(RagTrace t) {
        TraceDTO d = new TraceDTO();
        d.setTraceId(t.getTraceId());
        d.setEntrypoint(t.getEntrypoint());
        d.setRequestedPipelineEngine(t.getRequestedPipelineEngine());
        d.setRequestedRetrievalVersion(t.getRequestedRetrievalVersion());
        d.setPipelineEngine(t.getPipelineEngine());
        d.setRetrievalVersion(t.getRetrievalVersion());
        d.setProviderUsed(t.getProviderUsed());
        d.setEvidenceScorer(t.getEvidenceScorer());
        d.setOutcome(t.getOutcome());
        d.setReasonCode(t.getReasonCode());
        d.setOriginalQuery(t.getOriginalQuery());
        d.setRewrittenQuery(t.getRewrittenQuery());
        d.setStartTime(t.getStartTimeEpochMs());
        d.setTotalMs(Math.max(0L, (t.getEndTime() - t.getStartTime()) / 1_000_000L));
        d.setSpans(t.getSpans() == null ? List.of()
                : t.getSpans().stream().map(this::spanToDto).toList());
        return d;
    }

    /**
     * 从已完成轨迹提取列表展示所需的摘要。
     * 下游为 RagTrace.getTraceId、RagTrace.getEntrypoint。
     *
     * @param t 本次操作使用的 RagTrace 组件或记录
     * @return 轨迹列表的执行信息和耗时摘要，以 TraceSummaryDTO 返回
     */
    public TraceSummaryDTO toSummary(RagTrace t) {
        TraceSummaryDTO s = new TraceSummaryDTO();
        s.setTraceId(t.getTraceId());
        s.setEntrypoint(t.getEntrypoint());
        s.setRequestedPipelineEngine(t.getRequestedPipelineEngine());
        s.setRequestedRetrievalVersion(t.getRequestedRetrievalVersion());
        s.setPipelineEngine(t.getPipelineEngine());
        s.setRetrievalVersion(t.getRetrievalVersion());
        s.setProviderUsed(t.getProviderUsed());
        s.setEvidenceScorer(t.getEvidenceScorer());
        s.setOutcome(t.getOutcome());
        s.setReasonCode(t.getReasonCode());
        s.setOriginalQuery(t.getOriginalQuery());
        s.setStartTime(t.getStartTimeEpochMs());
        s.setTotalMs(Math.max(0L, (t.getEndTime() - t.getStartTime()) / 1_000_000L));
        s.setSpanCount(t.getSpans() == null ? 0 : t.getSpans().size());
        s.setSuccess((t.getOutcome() == null || "success".equals(t.getOutcome())
                || "no_evidence".equals(t.getOutcome()))
                && (t.getSpans() == null || t.getSpans().stream().allMatch(RagSpan::isSuccess)));
        return s;
    }

    /**
     * 把内部阶段耗时、令牌、成本和错误字段转换为响应字段。
     * 下游为 RagSpan.isSuccess。
     *
     * @param sp 本次操作使用的 RagSpan 组件或记录
     * @return 单个 RAG 阶段的耗时、用量和执行结果，以 TraceSpanDTO 返回
     */
    private TraceSpanDTO spanToDto(RagSpan sp) {
        TraceSpanDTO d = new TraceSpanDTO();
        d.setStage(sp.getStage() == null ? null : sp.getStage().name());
        d.setDurationMs(sp.getDurationMs());
        d.setTotalTokens(sp.getTotalTokens());
        d.setCostUsd(sp.getCostUsd());
        d.setInputTokens(sp.getInputTokens());
        d.setOutputTokens(sp.getOutputTokens());
        d.setSuccess(sp.isSuccess());
        d.setErrorMessage(sp.getErrorMessage());
        return d;
    }
}
