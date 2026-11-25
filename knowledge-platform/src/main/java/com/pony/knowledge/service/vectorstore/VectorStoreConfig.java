/* 根据应用配置装配 PgVector 向量存储组件。 */

package com.pony.knowledge.service.vectorstore;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * VectorStore 的唯一装配点。
 *
 * <p>改造前 vector.store 被多个类各自 @Value 读取，且默认值不统一
 * （MilvusVectorStoreImpl / DocumentChunkServiceImpl 是 milvus，
 * ObservabilityController 是 pgvector）。现在判定收敛到这里一处。</p>
 *
 * <p>🔴 2026-09-26 减法：{@code milvusVectorStore} 这个条件 bean **已删除** ——
 * Milvus 副本及旧工具已退役。退役依据：
 * 两引擎命中集合 16/16 相同、顺序 16/16 相同、逐条 distance 最大差 1.7e-05，
 * 评测指标完全相同（{@code eval/reports/engine_parity_probe_20260916_193627.txt}）；
 * 且装配期连接失败是**致命**的（历史以 milvus 配置启动 10 次失败 4 次）——
 * 一个零收益的副本换来一个能拖垮整个上下文启动的失败模式。</p>
 *
 * <p>⚠️ 随之而来的行为变化：{@code vector.store} 现在只认 {@code pgvector}。
 * 设成 {@code milvus}（或任何其它值）时下面的条件 bean 不匹配 → <b>容器里没有 VectorStore bean</b>
 * → 依赖它的 ObservabilityController 装配失败，上下文起不来。
 * 这是**有意的**：静默落回 pgvector 会让「我明明切了引擎」变成一个看不见的谎言
 * （旧 run_eval.py 对 --vector-stores 采取白名单拒绝，理由相同）。</p>
 */
@Configuration
public class VectorStoreConfig {

    /**
     * 装配 PostgreSQL 向量存储的实现组件。
     * 上游为 Spring 容器组件装配。
     *
     * @return 当前向量存储实现对观测层提供的名称，以 VectorStore 返回
     */
    @Bean
    @ConditionalOnProperty(name = "vector.store", havingValue = "pgvector", matchIfMissing = true)
    public VectorStore pgVectorStore() {
        return new PgVectorStore();
    }
}
