/* 验证切片向量、全文分词及父子块写入的事务边界。 */

package com.pony.knowledge.service.impl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.pony.knowledge.service.ai.AiServiceClient;
import com.pony.knowledge.modelmanagement.index.KnowledgeIndexService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DocumentChunkServiceImplTest {

    /**
     * 协调 {@code embeddingOf} 完成当前阶段，供后续处理使用。
     * @param dim 本次调用传入的 {@code int}
     * @return 调用方使用的 {@code AiServiceClient.EmbeddingResponse}
     */
    private static AiServiceClient.EmbeddingResponse embeddingOf(int dim) {
        return embeddingOf(dim, 1);
    }

    /**
     * 返回当前计算采用的 {@code resp}。
     *
     * @param dim 本次调用传入的 {@code int}
     * @param count 当前批次涉及的条目数量
     * @return 当前记录中的 {@code resp}
     */
    private static AiServiceClient.EmbeddingResponse embeddingOf(int dim, int count) {
        List<Double> v = new java.util.ArrayList<>();
        for (int i = 0; i < dim; i++) {
            v.add(0.1);
        }

        List<List<Double>> list = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(v);
        }

        AiServiceClient.EmbeddingResponse resp = new AiServiceClient.EmbeddingResponse();
        resp.setEmbeddings(list);
        return resp;
    }

    /**
     * 返回当前计算采用的 {@code tx}。
     *
     * @return 当前记录中的 {@code tx}
     */
    private static PlatformTransactionManager txManager() {
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        return tx;
    }

    /**
     * 协调 {@code newSvc} 完成当前阶段，供后续处理使用。
     * 下游调用 {@code newSvc}。
     *
     * @param jdbc 本次调用传入的 {@code JdbcTemplate}
     * @param ai 本次调用传入的 {@code AiServiceClient}
     * @param tx 本次调用传入的 {@code PlatformTransactionManager}
     * @return 调用方使用的 {@code DocumentChunkServiceImpl}
     */
    private static DocumentChunkServiceImpl newSvc(JdbcTemplate jdbc, AiServiceClient ai,
                                                   PlatformTransactionManager tx) {
        return newSvc(jdbc, ai, tx, false);
    }

    /**
     * 协调 {@code jdbc.queryForList} 完成当前阶段，供后续处理使用。
     * 下游调用 {@code jdbc.queryForList}、{@code indexes.embeddingExecution}、{@code lenient}。
     *
     * @param jdbc 本次调用传入的 {@code JdbcTemplate}
     * @param ai 本次调用传入的 {@code AiServiceClient}
     * @param tx 本次调用传入的 {@code PlatformTransactionManager}
     * @param smallToBig 本次调用传入的 {@code boolean}
     * @return 当前记录中的 {@code service}
     */
    private static DocumentChunkServiceImpl newSvc(JdbcTemplate jdbc, AiServiceClient ai,
                                                   PlatformTransactionManager tx, boolean smallToBig) {
        lenient().when(jdbc.queryForList(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(List.of(1L));
        DocumentChunkServiceImpl service = new DocumentChunkServiceImpl(jdbc, ai, tx, smallToBig, 4096);
        KnowledgeIndexService indexes = mock(KnowledgeIndexService.class);
        when(indexes.embeddingExecution(anyLong(), eq(120000))).thenReturn(Map.of("schemaVersion", 1));
        service.setKnowledgeIndexService(indexes);
        return service;
    }

    /** 通过 {@code ai.embed}、{@code jdbc.queryForObject} 驱动该场景，实际结果与固定预期一致。 */
    @Test
    void insertIncludesMetadataColumns() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AiServiceClient ai = mock(AiServiceClient.class);
        when(ai.embed(any(), anyMap())).thenReturn(embeddingOf(3));
        // INSERT ... RETURNING id
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(42L);

        DocumentChunkServiceImpl svc = newSvc(jdbc, ai, txManager());

        AiServiceClient.ChunkResult c = new AiServiceClient.ChunkResult();
        c.setChunkIndex(0);
        c.setContent("内容");
        c.setSearchText("内 容");
        c.setSourceType("table");
        c.setPageNo(3);
        c.setHeadingPath("A > B");
        c.setTableId("s1");
        c.setRowIndex(2);

        svc.replaceChunks(1L, 7L, List.of(c));

        verify(jdbc).update(eq("DELETE FROM document_chunk WHERE document_id = ?"), eq(1L));

        // INSERT 走 queryForObject(RETURNING id)，参数顺序与 SQL 占位符一一对应
        // （覆盖 5 个新增元数据列：source_type/page_no/heading_path/table_id/row_index）
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForObject(anyString(), eq(Long.class), args.capture());
        Object[] p = args.getValue();
        assertEquals(1L, p[0]);          // document_id
        assertEquals(7L, p[1]);          // knowledge_base_id
        assertEquals(0, p[2]);           // chunk_index
        assertEquals("内容", p[3]);       // content
        assertEquals(2, p[4]);           // char_count
        assertEquals("内 容", p[5]);      // searchText
        assertEquals("table", p[6]);     // source_type
        assertEquals(3, p[7]);           // page_no
        assertEquals("A > B", p[8]);     // heading_path
        assertEquals("s1", p[9]);       // table_id
        assertEquals(2, p[10]);          // row_index
    }

    /** 为一次入库配置指定索引快照，核对 Embedding 与向量持久化共用该快照。 */
    @Test
    void embeddingAndVectorPersistenceUseTheSameExplicitIndexSnapshot() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AiServiceClient ai = mock(AiServiceClient.class);
        KnowledgeIndexService indexes = mock(KnowledgeIndexService.class);
        PlatformTransactionManager tx = txManager();
        Map<String, Object> execution = Map.of("schemaVersion", 1, "indexScopes", List.of(Map.of(
                "knowledgeBaseId", 7L, "generationId", "g1", "dimension", 1024, "spaceId", "s1")));
        when(indexes.embeddingExecution(7L, 120000)).thenReturn(execution);
        when(jdbc.queryForList(anyString(), eq(Long.class), any(Object[].class))).thenReturn(List.of(1L));
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(42L);
        AiServiceClient.EmbeddingResponse embedded = embeddingOf(1024);
        when(ai.embed(List.of("content"), execution)).thenReturn(embedded);
        DocumentChunkServiceImpl service = new DocumentChunkServiceImpl(jdbc, ai, tx, false, 4096);
        service.setKnowledgeIndexService(indexes);
        AiServiceClient.ChunkResult chunk = new AiServiceClient.ChunkResult();
        chunk.setChunkIndex(0); chunk.setContent("content"); chunk.setSearchText("content");

        service.replaceChunks(1L, 7L, List.of(chunk));

        InOrder order = inOrder(ai, indexes, tx);
        order.verify(indexes).embeddingExecution(7L, 120000);
        order.verify(ai).embed(List.of("content"), execution);
        order.verify(tx).getTransaction(any());
        order.verify(indexes).acquireWriteBarrier(7L);
        order.verify(indexes).writeDocumentVectors(7L, execution, List.of(42L), embedded.getEmbeddings());
        order.verify(tx).commit(any());
    }

    // ---------- 设计 §3.3 的写路径契约：PG 事务只包 DELETE+INSERT；嵌入在事务之外 ----------

    /** 通过 {@code ai.embed}、{@code jdbc.queryForObject} 驱动该场景，跳过受保护的调用。 */
    @Test
    void pgWritesAreInsideOneTransaction_deleteThenInsertThenCommit() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AiServiceClient ai = mock(AiServiceClient.class);
        PlatformTransactionManager tx = txManager();
        when(ai.embed(any(), anyMap())).thenReturn(embeddingOf(3));
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(42L);

        AiServiceClient.ChunkResult c = new AiServiceClient.ChunkResult();
        c.setChunkIndex(0);
        c.setContent("内容");
        newSvc(jdbc, ai, tx).replaceChunks(1L, 7L, List.of(c));

        InOrder inOrder = inOrder(tx, jdbc);
        inOrder.verify(tx).getTransaction(any());
        inOrder.verify(jdbc).queryForList(
                eq("SELECT id FROM document WHERE id = ? FOR UPDATE"), eq(Long.class), eq(1L));
        inOrder.verify(jdbc).update(eq("DELETE FROM document_chunk WHERE document_id = ?"), eq(1L));
        inOrder.verify(jdbc).queryForObject(anyString(), eq(Long.class), any(Object[].class));
        inOrder.verify(tx).commit(any());
        verify(tx, never()).rollback(any());
    }

    /** 通过 {@code svc.replaceChunks}、{@code newSvc} 驱动该场景，跳过受保护的调用。 */
    @Test
    void emptyChunks_stillDeletesOldRows_andSkipsInsert() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AiServiceClient ai = mock(AiServiceClient.class);
        DocumentChunkServiceImpl svc = newSvc(jdbc, ai, txManager());

        assertDoesNotThrow(() -> svc.replaceChunks(1L, 1L, List.of()));

        verify(jdbc).update(eq("DELETE FROM document_chunk WHERE document_id = ?"), eq(1L));
        verify(jdbc, never()).queryForObject(anyString(), eq(Long.class), any(Object[].class));
    }

    /** 在切片准备完成后让文档不再存在，验证事务跳过晚到的切片写入。 */
    @Test
    void deletedParentPreventsLateChunkWrite() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AiServiceClient ai = mock(AiServiceClient.class);
        when(ai.embed(any(), anyMap())).thenReturn(embeddingOf(3));
        AiServiceClient.ChunkResult chunk = new AiServiceClient.ChunkResult();
        chunk.setChunkIndex(0);
        chunk.setContent("late content");
        DocumentChunkServiceImpl service = newSvc(jdbc, ai, txManager());
        when(jdbc.queryForList(anyString(), eq(Long.class), any(Object[].class))).thenReturn(List.of());

        service.replaceChunks(999L, 7L, List.of(chunk));

        verify(jdbc, never()).update(eq("DELETE FROM document_chunk WHERE document_id = ?"), any(Object[].class));
        verify(jdbc, never()).queryForObject(anyString(), eq(Long.class), any(Object[].class));
    }

    /** 通过 {@code ai.embed}、{@code jdbc.queryForObject} 驱动该场景，抛出 {@code RuntimeException}，跳过受保护的调用。 */
    @Test
    void pgInsertFailure_rollsBack() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AiServiceClient ai = mock(AiServiceClient.class);
        PlatformTransactionManager tx = txManager();
        when(ai.embed(any(), anyMap())).thenReturn(embeddingOf(3));
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenThrow(new RuntimeException("PG 挂了"));

        AiServiceClient.ChunkResult c = new AiServiceClient.ChunkResult();
        c.setChunkIndex(0);
        c.setContent("内容");

        assertThrows(RuntimeException.class,
                () -> newSvc(jdbc, ai, tx).replaceChunks(1L, 7L, List.of(c)));

        verify(tx).rollback(any());
        verify(tx, never()).commit(any());
    }

    /** 通过 {@code ai.embed}、{@code txManager} 驱动该场景，抛出 {@code RuntimeException}，跳过受保护的调用。 */
    @Test
    void embedFailure_happensBeforeAnyPgWrite() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AiServiceClient ai = mock(AiServiceClient.class);
        PlatformTransactionManager tx = txManager();
        when(ai.embed(any(), anyMap())).thenThrow(new RuntimeException("Ollama 不可达"));

        AiServiceClient.ChunkResult c = new AiServiceClient.ChunkResult();
        c.setChunkIndex(0);
        c.setContent("内容");

        assertThrows(RuntimeException.class,
                () -> newSvc(jdbc, ai, tx).replaceChunks(1L, 7L, List.of(c)));

        verify(tx, never()).getTransaction(any());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    /** 让嵌入返回向量数与切片数不符，验证持久化阶段执行前直接拒绝。 */
    @Test
    void vectorCountMismatch_isRejectedBeforeAnyWrite() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AiServiceClient ai = mock(AiServiceClient.class);
        PlatformTransactionManager tx = txManager();
        when(ai.embed(any(), anyMap())).thenReturn(embeddingOf(3));   // 返回 1 条，但下面给 2 个 chunk

        AiServiceClient.ChunkResult c1 = new AiServiceClient.ChunkResult();
        c1.setChunkIndex(0);
        c1.setContent("内容1");
        AiServiceClient.ChunkResult c2 = new AiServiceClient.ChunkResult();
        c2.setChunkIndex(1);
        c2.setContent("内容2");

        assertThrows(IllegalStateException.class,
                () -> newSvc(jdbc, ai, tx).replaceChunks(1L, 7L, List.of(c1, c2)));

        verify(tx, never()).getTransaction(any());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    /** 通过 {@code ai.embed}、{@code jdbc.queryForObject} 驱动该场景，布尔状态符合断言，实际结果与固定预期一致。 */
    @Test
    void searchTextBlank_fallsBackToContent_andLogsWarn() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AiServiceClient ai = mock(AiServiceClient.class);
        when(ai.embed(any(), anyMap())).thenReturn(embeddingOf(3));
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(42L);

        AiServiceClient.ChunkResult c = new AiServiceClient.ChunkResult();
        c.setChunkIndex(2);
        c.setContent("未分词的中文内容");
        // searchText 不设置 → null → 走回退分支

        ch.qos.logback.classic.Logger log =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(DocumentChunkServiceImpl.class);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        log.addAppender(captured);
        try {
            newSvc(jdbc, ai, txManager()).replaceChunks(1L, 7L, List.of(c));

            // 回退行为保持：INSERT 的 searchText 参数 = content
            ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
            verify(jdbc).queryForObject(anyString(), eq(Long.class), args.capture());
            assertEquals("未分词的中文内容", args.getValue()[5]);

            // P0-5：回退必须可见——WARN 日志带上 documentId / chunkIndex
            assertTrue(captured.list.stream().anyMatch(e ->
                    e.getLevel() == Level.WARN && e.getFormattedMessage().contains("searchText 为空")
                            && e.getFormattedMessage().contains("documentId=1")
                            && e.getFormattedMessage().contains("chunkIndex=2")));
        } finally {
            log.detachAppender(captured);
        }
    }

    /** 通过 {@code ai.embed}、{@code jdbc.queryForObject} 驱动该场景，布尔状态符合断言。 */
    @Test
    void smallToBigEnabled_createsParentAndLinksChildren() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AiServiceClient ai = mock(AiServiceClient.class);
        when(ai.embed(any(), anyMap())).thenReturn(embeddingOf(3, 2));
        // 两个子块 INSERT 依次返回 id 42、43；父块 INSERT 返回 id 100
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(42L, 43L, 100L);

        AiServiceClient.ChunkResult c1 = new AiServiceClient.ChunkResult();
        c1.setChunkIndex(0);
        c1.setContent("第一条内容");
        c1.setSearchText("s1");
        c1.setHeadingPath("1.1");
        AiServiceClient.ChunkResult c2 = new AiServiceClient.ChunkResult();
        c2.setChunkIndex(1);
        c2.setContent("第二条内容");
        c2.setSearchText("s2");
        c2.setHeadingPath("1.1");

        newSvc(jdbc, ai, txManager(), true).replaceChunks(1L, 7L, List.of(c1, c2));

        // 共 3 次 INSERT（2 子块 + 1 父块），其中一次是 source_type='parent'
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(3)).queryForObject(sql.capture(), eq(Long.class), any(Object[].class));
        assertTrue(sql.getAllValues().stream().anyMatch(s -> s.contains("'parent'")));
        // 两个子块都回填 parent_chunk_id = 100（父块 id）
        verify(jdbc, times(2)).update(
                eq("UPDATE document_chunk SET parent_chunk_id = ? WHERE id = ?"), eq(100L), anyLong());
    }

    /** 通过 {@code ai.embed}、{@code jdbc.queryForObject} 驱动该场景，跳过受保护的调用，布尔状态符合断言。 */
    @Test
    void smallToBigDisabled_doesNotCreateParent() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AiServiceClient ai = mock(AiServiceClient.class);
        when(ai.embed(any(), anyMap())).thenReturn(embeddingOf(3));
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(42L);

        AiServiceClient.ChunkResult c = new AiServiceClient.ChunkResult();
        c.setChunkIndex(0);
        c.setContent("内容");
        c.setSearchText("s");
        c.setHeadingPath("1.1");

        newSvc(jdbc, ai, txManager()).replaceChunks(1L, 7L, List.of(c));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(1)).queryForObject(sql.capture(), eq(Long.class), any(Object[].class));
        assertTrue(sql.getAllValues().stream().noneMatch(s -> s.contains("'parent'")));
        verify(jdbc, never()).update(eq("UPDATE document_chunk SET parent_chunk_id = ? WHERE id = ?"), anyLong(), anyLong());
    }

    /** 构造合并后含分隔符的切片，验证父块预算计算准确且保留全部子块正文。 */
    @ParameterizedTest
    @CsvSource({"2048,2048,2", "2047,2047,1", "2048,2047,2"})
    void parentBudgetIncludesSeparatorAndKeepsAllChildText(int firstLength, int secondLength, int expectedParents) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AiServiceClient ai = mock(AiServiceClient.class);
        when(ai.embed(any(), anyMap())).thenReturn(embeddingOf(3, 2));
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(42L, 43L, 100L, 101L);
        AiServiceClient.ChunkResult first = new AiServiceClient.ChunkResult();
        first.setChunkIndex(0); first.setContent("a".repeat(firstLength)); first.setSearchText("first");
        AiServiceClient.ChunkResult second = new AiServiceClient.ChunkResult();
        second.setChunkIndex(1); second.setContent("b".repeat(secondLength)); second.setSearchText("second");

        newSvc(jdbc, ai, txManager(), true).replaceChunks(1L, 7L, List.of(first, second));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, atLeastOnce()).queryForObject(sql.capture(), eq(Long.class), args.capture());
        List<String> parents = new java.util.ArrayList<>();
        for (int index = 0; index < sql.getAllValues().size(); index++) {
            if (sql.getAllValues().get(index).contains("'parent'")) {
                Object[] values = args.getAllValues().get(index);
                String content = (String) values[3];
                assertTrue(content.length() <= 4096, "parent exceeds budget: " + content.length());
                assertEquals(content.length(), values[4]);
                parents.add(content);
            }
        }

        assertEquals(expectedParents, parents.size());
        assertEquals(first.getContent() + second.getContent(), String.join("", parents).replace("\n\n", ""));
        verify(jdbc, times(2)).update(eq("UPDATE document_chunk SET parent_chunk_id = ? WHERE id = ?"), anyLong(), anyLong());
    }
}
