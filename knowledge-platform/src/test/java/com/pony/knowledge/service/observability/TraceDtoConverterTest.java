/* 验证请求追踪及可见阶段信息转换为 API DTO 的规则。 */

package com.pony.knowledge.service.observability;

import com.pony.knowledge.observability.RagSpan;
import com.pony.knowledge.observability.RagTrace;
import com.pony.knowledge.observability.TraceStage;
import com.pony.knowledge.dto.observability.TraceDTO;
import com.pony.knowledge.dto.observability.TraceSummaryDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TraceDtoConverterTest {
    private final TraceDtoConverter converter = new TraceDtoConverter();

    /** 通过 {@code converter.toDto}、{@code converter.toSummary} 驱动该场景，核对 {@code summary.isSuccess()} 为 false。 */
    @Test
    void traceListAndDetailPreserveActualEngineAndOptionalFailureMetadata() {
        RagTrace trace = RagTrace.builder().pipelineEngine("langchain")
                .requestedPipelineEngine("langchain").requestedRetrievalVersion("hybrid-rerank-rewrite")
                .retrievalVersion("hybrid-rerank-rewrite").providerUsed("ollama")
                .evidenceScorer("model").outcome("dependency_unavailable")
                .reasonCode("generation_degraded").build();
        TraceDTO detail = converter.toDto(trace);
        TraceSummaryDTO summary = converter.toSummary(trace);
        assertThat(detail.getPipelineEngine()).isEqualTo("langchain");
        assertThat(detail.getRequestedPipelineEngine()).isEqualTo("langchain");
        assertThat(detail.getRequestedRetrievalVersion()).isEqualTo("hybrid-rerank-rewrite");
        assertThat(detail.getRetrievalVersion()).isEqualTo("hybrid-rerank-rewrite");
        assertThat(detail.getProviderUsed()).isEqualTo("ollama");
        assertThat(detail.getEvidenceScorer()).isEqualTo("model");
        assertThat(detail.getOutcome()).isEqualTo("dependency_unavailable");
        assertThat(detail.getReasonCode()).isEqualTo("generation_degraded");
        assertThat(summary.getPipelineEngine()).isEqualTo(detail.getPipelineEngine());
        assertThat(summary.getRequestedPipelineEngine()).isEqualTo(detail.getRequestedPipelineEngine());
        assertThat(summary.getRequestedRetrievalVersion()).isEqualTo(detail.getRequestedRetrievalVersion());
        assertThat(summary.getRetrievalVersion()).isEqualTo(detail.getRetrievalVersion());
        assertThat(summary.getProviderUsed()).isEqualTo(detail.getProviderUsed());
        assertThat(summary.getEvidenceScorer()).isEqualTo(detail.getEvidenceScorer());
        assertThat(summary.getOutcome()).isEqualTo(detail.getOutcome());
        assertThat(summary.getReasonCode()).isEqualTo(detail.getReasonCode());
        assertThat(summary.isSuccess()).isFalse();
        assertThat(converter.toSummary(RagTrace.builder().pipelineEngine("fixed").build()).getProviderUsed()).isNull();
    }

    /** 通过 {@code converter.toDto} 驱动该场景，核对 {@code dto.getTotalMs()} 等于 {@code 5L}、{@code dto.getStartTime()} 等于 {@code 1725440000000L}。 */
    @Test
    void toDto换算totalMs与epoch时间戳() {
        RagTrace t = RagTrace.builder()
                .traceId("t1").entrypoint("chat")
                .originalQuery("q").rewrittenQuery("q2")
                .startTime(1_000_000_000L).startTimeEpochMs(1725440000000L)
                .endTime(1_005_000_000L)
                .spans(List.of(RagSpan.builder().stage(TraceStage.RERANK).durationMs(5).success(true).build()))
                .build();
        TraceDTO dto = converter.toDto(t);
        assertThat(dto.getTotalMs()).isEqualTo(5L);
        assertThat(dto.getStartTime()).isEqualTo(1725440000000L);
        assertThat(dto.getSpans()).hasSize(1);
        assertThat(dto.getSpans().get(0).getStage()).isEqualTo("RERANK");
    }

    /** 通过 {@code converter.toSummary}、{@code s.isSuccess} 驱动该场景，核对 {@code s.isSuccess()} 为 true、{@code s.getSpanCount()} 等于 {@code 2}。 */
    @Test
    void toSummary全成功() {
        RagTrace t = RagTrace.builder()
                .traceId("t1").entrypoint("chat").originalQuery("q")
                .startTime(1_000_000_000L).startTimeEpochMs(100L).endTime(1_005_000_000L)
                .spans(List.of(
                        RagSpan.builder().stage(TraceStage.RERANK).success(true).build(),
                        RagSpan.builder().stage(TraceStage.LLM_GENERATION).success(true).build()))
                .build();
        TraceSummaryDTO s = converter.toSummary(t);
        assertThat(s.isSuccess()).isTrue();
        assertThat(s.getSpanCount()).isEqualTo(2);
    }
}
