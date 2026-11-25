/* 比较各阶段耗时、令牌和成本是否超过观测预算。 */

package com.pony.knowledge.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * RAG 阶段性能预算：各阶段耗时上限 + token/cost 上限（按次调用判断）。
 * 超限只记 Counter + warn 日志 + span tag（调用方），不中断流程（不熔断）。
 */
@Component
public class RagPerformanceBudget {

    private static final Logger log = LoggerFactory.getLogger(RagPerformanceBudget.class);

    private final RagMetrics ragMetrics;

    private final long rewriteMs;
    private final long retrievalMs;
    private final long rerankMs;
    private final long compressionMs;
    private final long generationMs;
    private final long tokenLimit;
    private final double costLimitUsd;

    /**
     * 保存比较各阶段耗时、令牌和成本是否超过观测预算所需的依赖和初始配置。
     * 上游为 Spring 组件装配。
     *
     * @param ragMetrics 本次操作使用的 RagMetrics 组件或记录
     * @param rewriteMs 以毫秒计的时间限制
     * @param retrievalMs 以毫秒计的时间限制
     * @param rerankMs 以毫秒计的时间限制
     * @param compressionMs 以毫秒计的时间限制
     * @param generationMs 以毫秒计的时间限制
     * @param tokenLimit 初始化或更新同名字段的值
     * @param costLimitUsd 初始化或更新同名字段的值
     */
    public RagPerformanceBudget(
            RagMetrics ragMetrics,
            @Value("${rag.budget.rewrite-ms:3000}") long rewriteMs,
            @Value("${rag.budget.retrieval-ms:2000}") long retrievalMs,
            @Value("${rag.budget.rerank-ms:3000}") long rerankMs,
            @Value("${rag.budget.compression-ms:2000}") long compressionMs,
            @Value("${rag.budget.generation-ms:30000}") long generationMs,
            @Value("${rag.budget.tokens:4000}") long tokenLimit,
            @Value("${rag.budget.cost-usd:0.01}") double costLimitUsd) {
        this.ragMetrics = ragMetrics;
        this.rewriteMs = rewriteMs;
        this.retrievalMs = retrievalMs;
        this.rerankMs = rerankMs;
        this.compressionMs = compressionMs;
        this.generationMs = generationMs;
        this.tokenLimit = tokenLimit;
        this.costLimitUsd = costLimitUsd;
    }

    /**
     * 比较 RAG 阶段实际耗时与对应观察门槛。
     * 上游为 RagStageTracer.observe。
     * 下游为 RagMetrics.countBudgetOverrun。
     *
     * @param stage 执行阶段标识
     * @param elapsedMs 已用时间（毫秒）
     */
    public void checkElapsed(String stage, long elapsedMs) {
        long limit = limitFor(stage);

        if (limit <= 0 || elapsedMs <= limit) {
            return;
        }
        ragMetrics.countBudgetOverrun(stage);
        log.warn("RAG budget overrun [elapsed]: stage={}, elapsedMs={}, limitMs={}", stage, elapsedMs, limit);
    }

    /**
     * 比较实际模型用量与令牌观察门槛。
     * 上游为 RagStageTracer.recordStageUsage。
     * 下游为 RagMetrics.countBudgetOverrun。
     *
     * @param stage 执行阶段标识
     * @param tokens 本阶段需汇总的令牌数量
     */
    public void checkTokens(String stage, long tokens) {
        if (tokenLimit <= 0 || tokens <= 0 || tokens <= tokenLimit) {
            return;
        }
        ragMetrics.countBudgetOverrun(stage);
        log.warn("RAG budget overrun [tokens]: stage={}, tokens={}, limit={}", stage, tokens, tokenLimit);
    }

    /**
     * 比较估算费用与成本门槛并记录超限。
     * 上游为 RagStageTracer.recordStageUsage。
     * 下游为 RagMetrics.countBudgetOverrun。
     *
     * @param stage 执行阶段标识
     * @param usd 本方法计算或判断使用的 usd 值
     */
    public void checkCost(String stage, double usd) {
        if (costLimitUsd <= 0 || usd <= 0 || usd <= costLimitUsd) {
            return;
        }
        ragMetrics.countBudgetOverrun(stage);
        log.warn("RAG budget overrun [cost]: stage={}, usd={}, limitUsd={}", stage, usd, costLimitUsd);
    }

    /**
     * 为指定阶段选择已配置的耗时限制。
     * 上游为 RagPerformanceBudget.checkElapsed。
     *
     * @param stage 执行阶段标识
     * @return 阶段对应的耗时门槛（毫秒）
     */
    private long limitFor(String stage) {
        return switch (stage) {
            case "rewrite" -> rewriteMs;
            case "retrieval" -> retrievalMs;
            case "rerank" -> rerankMs;
            case "compression" -> compressionMs;
            case "generation" -> generationMs;
            default -> -1;
        };
    }
}
