/* 核对 RAG 指标的采样、标签与失败分类。 */

package com.pony.knowledge.observability;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RagMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final RagMetrics metrics = new RagMetrics(registry, "hybrid");

    /**
     * 核对 {@code registry.get("rag.llm.tokens.input").counter().count()} 与预期 {@code 10.0} 一致。
     * 调用路径经过 {@code metrics.countRequest}。
     */
    @Test
    void countersIncrementWithTags() {
        metrics.countRequest("ask");
        metrics.countError("retrieval");
        metrics.countBudgetOverrun("rerank");
        metrics.countDegraded("rerank", "server_unreachable");
        metrics.countDegraded("rerank", "server_unreachable");
        metrics.countDegraded("compression", "guard_degraded");
        metrics.countPartial("rerank", 3);
        metrics.countPartial("rerank", 0);      // 0 增量不该产生序列
        metrics.recordInputTokens(10);
        metrics.recordOutputTokens(20);
        metrics.recordStageTokens("rewrite", 5);
        metrics.recordCost("generation", 0.001);

        assertEquals(1.0, registry.get("rag.requests.total").tag("endpoint", "ask").counter().count());
        assertEquals(1.0, registry.get("rag.errors.total").tag("stage", "retrieval").counter().count());
        assertEquals(1.0, registry.get("rag.budget.overrun").tag("stage", "rerank").counter().count());
        assertEquals(10.0, registry.get("rag.llm.tokens.input").counter().count());
        assertEquals(20.0, registry.get("rag.llm.tokens.output").counter().count());
        assertEquals(5.0, registry.get("rag.llm.tokens.consumed").tag("stage", "rewrite").counter().count());
        assertEquals(0.001, registry.get("rag.cost.usd").tag("stage", "generation").counter().count(), 1e-9);
        assertEquals(2.0, registry.get("rag.stage.degraded")
                .tag("stage", "rerank").tag("reason", "server_unreachable").counter().count());
        assertEquals(1.0, registry.get("rag.stage.degraded")
                .tag("stage", "compression").tag("reason", "guard_degraded").counter().count());
        assertEquals(3.0, registry.get("rag.stage.partial_degraded")
                .tag("stage", "rerank").counter().count());
    }

    /** 核对 {@code got.count()} 与预期 {@code 1} 一致。调用路径经过 {@code metrics.timer}。 */
    @Test
    void timerRegistersWithLatencySuffix() {
        Timer timer = metrics.timer("rerank");
        timer.record(1200, TimeUnit.MILLISECONDS);
        Timer got = registry.get("rag.rerank.latency").timer();
        assertEquals(1, got.count());
        assertEquals(1200, got.totalTime(TimeUnit.MILLISECONDS), 1e-6);
    }
}
