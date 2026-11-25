/* 验证 PostgreSQL 向量存储向调用方报告的实现名称。 */

package com.pony.knowledge.service.vectorstore;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PgVectorStore：当前唯一的向量引擎。2026-09-29 起本类只剩 {@code name()} ——
 * 写路径（{@code upsert} / {@code deleteByDocumentId}）随 Milvus 副本退役于 2026-09-26 删除，
 * 读路径（{@code search}）随「重切边界」于 2026-09-29 删除（检索整条管线在 Python）。
 *
 * <p>⚠️ 原来这里有一条 {@code search_pushesKbPredicateAsBoundParameter_neverInterpolated}，
 * 钉的是「KB 过滤必须以绑定参数进 SQL、WHERE 谓词不能删」这条 default-deny 防回归断言。
 * 该不变量**没有消失**，只是随 SQL 一起搬到了 Python —— 现已移植为
 * {@code ai/tests/test_rag_retrieval_authz.py} 里的同名断言。删掉 Java 实现时不留空档。</p>
 */
class PgVectorStoreTest {

    /** 构造输入与依赖返回值，实际结果与固定预期一致。 */
    @Test
    void name_isPgvector() {
        assertThat(new PgVectorStore().name()).isEqualTo("pgvector");
    }
}
