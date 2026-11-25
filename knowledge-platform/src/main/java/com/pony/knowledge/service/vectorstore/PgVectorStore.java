/* 提供 PostgreSQL 向量存储实现的类型标识。 */

package com.pony.knowledge.service.vectorstore;

/**
 * PG 内向量索引实现（halfvec(2560) + HNSW cosine）—— 当前**唯一**的向量引擎。
 *
 * <p>为什么这里只有 {@link #name()}：向量列就是 {@code document_chunk.embedding}，由
 * {@code DocumentChunkServiceImpl.replaceChunks} 的 INSERT 一次写入，DELETE 一行索引项随之消失，
 * HNSW 由 PG 自动维护 —— PG 是源，不是「需要同步的副本」，所以不存在「再同步一次」这回事。</p>
 *
 * <p>2026-09-26：Milvus 副本退役，接口上的 {@code upsert} / {@code deleteByDocumentId}
 * 两个 no-op 方法已随之一并删除（它们只为喂饱副本而存在）。</p>
 *
 * <p>2026-09-29：读路径 {@code search} 也已删除。检索整条管线在「重切边界」后搬进了 Python
 * （{@code ai/app/retrieval/retriever.py}），本仓不再有第二个消费 {@code document_chunk.embedding}
 * 的地方 —— 详见 {@link VectorStore} 的类注释。留在这里的 {@code name()} 供
 * {@code ObservabilityController} 回显当前引擎。</p>
 */
public class PgVectorStore implements VectorStore {

    /** 初始化 PgVectorStore，供本文件定义的构建入口使用。 */
    public PgVectorStore() {
    }

    /**
     * 返回当前 PostgreSQL 向量存储实现的观测名称。
     * 上游为 ObservabilityController.status。
     *
     * @return 本次计算或查询得到的 String 结果
     */
    @Override
    public String name() {
        return "pgvector";
    }
}
