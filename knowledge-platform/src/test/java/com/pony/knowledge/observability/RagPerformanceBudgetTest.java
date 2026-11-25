/* 检查 RAG 性能统计的预算、单位和汇总规则。 */

package com.pony.knowledge.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RagPerformanceBudgetTest {

    private SimpleMeterRegistry registry;
    private RagPerformanceBudget budget;

    /** 每个用例前准备夹具。 */
    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        RagMetrics metrics = new RagMetrics(registry, "hybrid");
        // 所有阶段耗时上限 100ms、token 上限 10、cost 上限 0.01
        budget = new RagPerformanceBudget(metrics, 100L, 100L, 100L, 100L, 100L, 10L, 0.01);
    }

    /** 核对 {@code overrun("retrieval")} 与预期 {@code 1.0} 一致。调用路径经过 {@code budget.checkElapsed}。 */
    @Test
    void elapsedOverLimitIncrementsOverrun() {
        budget.checkElapsed("retrieval", 500L);
        assertEquals(1.0, overrun("retrieval"));
    }

    /** 通过 {@code budget.checkElapsed} 执行本例并核对输出或记录。 */
    @Test
    void elapsedWithinLimitDoesNotIncrement() {
        budget.checkElapsed("retrieval", 50L);
        assertNull(registry.find("rag.budget.overrun").tag("stage", "retrieval").counter());
    }

    /** 核对 {@code overrun("generation")} 与预期 {@code 1.0} 一致。调用路径经过 {@code budget.checkTokens}。 */
    @Test
    void tokensOverLimitIncrementsOverrun() {
        budget.checkTokens("generation", 20L);
        assertEquals(1.0, overrun("generation"));
    }

    /** 核对 {@code registry.find("rag.budget.overrun").counter()} 为空。调用路径经过 {@code budget.checkElapsed}。 */
    @Test
    void unknownStageIsIgnored() {
        budget.checkElapsed("unknown", 99999L);
        assertNull(registry.find("rag.budget.overrun").counter());
    }

    /**
     * 构造 {@code double} 供测试使用，使用 {@code registry.find}、{@code c.count}。
     * 由 {@code elapsedOverLimitIncrementsOverrun}、{@code tokensOverLimitIncrementsOverrun} 调用。
     * @param stage 本用例传入的{@code stage}
     * 返回 {@code c == null ? 0.0 : c.count()}。
     */
    private double overrun(String stage) {
        Counter c = registry.find("rag.budget.overrun").tag("stage", stage).counter();
        return c == null ? 0.0 : c.count();
    }
}
