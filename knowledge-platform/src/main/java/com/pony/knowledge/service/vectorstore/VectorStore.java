/* 约定当前向量存储实现对观测层提供的名称。 */

package com.pony.knowledge.service.vectorstore;

/**
 * 向量引擎的标识接缝：实现由 {@code VectorStoreConfig} 按 {@code vector.store} 条件装配，
 * 当前**只有一个实现** {@link PgVectorStore}（PG 内 halfvec(2560) + HNSW）。
 *
 * <p>本接口现在只剩 {@link #name()} 一个成员，用途是让 {@code /observability/status}
 * 回显「这次跑的是哪个引擎」——把「我切了引擎」从口头声明变成可读的运行时事实。</p>
 *
 * <p>🔴 2026-09-26 减法：Milvus 运行时副本退役 —— {@code MilvusVectorStore} /
 * {@code MilvusVectorStoreImpl} / {@code VectorStoreConfig.milvusVectorStore} 均已删除，
 * 连带删掉了本接口的两个**写方法**（{@code upsert} / {@code deleteByDocumentId}）与
 * {@code ChunkVector} 记录。写路径的理由是：它们唯一的存在理由是「把 PG 的向量同步进副本」，
 * 而 PgVectorStore 的实现**两个都是 no-op** —— embedding 随 {@code DocumentChunkServiceImpl}
 * 的 INSERT 一次落库，DELETE 一行索引项随之消失，HNSW 由 PG 自动维护。删除依据见
 * {@code eval/reports/engine_parity_probe_20260916_193627.txt} —— 两引擎命中集合 16/16 相同、
 * 顺序 16/16 相同、逐条 distance 最大差 1.7e-05，评测指标完全相同。</p>
 *
 * <p>🔴 2026-09-29 减法：<b>读方法 {@code search} 也已删除</b>。它在本仓已无生产调用方——
 * 「重切边界」（决策文档 §4 第 5 项）把整条检索管线搬进了 Python，向量检索现在是
 * {@code ai/app/retrieval/retriever.py} 的 {@code vector_retrieve}（同样的
 * {@code embedding <=> %s::halfvec} + {@code WHERE knowledge_base_id = ANY(%s)} + HNSW）。
 * 留着一个没人调、SQL 却与 Python 侧并存待漂移的 Java 实现，只会让人以为还存在第二条读路径；
 * 「KB 过滤必须以绑定参数进 SQL」这条防回归断言已随之移植到 Python 侧
 * （{@code ai/tests/test_rag_retrieval_authz.py}）。</p>
 *
 * <p>Milvus 迁移和对照的旧脚本见 {@code docs/archive/2026-11-19-retired-tools/README.md}。</p>
 */
public interface VectorStore {

    /**
     * 声明向状态接口提供当前向量存储实现的名称。
     * 上游为 ObservabilityController.status。
     *
     * @return 本次计算或查询得到的 String 结果
     */
    String name();
}
