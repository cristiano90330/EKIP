/* 验证删除文档时数据库清理关联切片、任务和回执。 */

package com.pony.knowledge.service.impl;

import com.pony.knowledge.service.DocumentService;
import com.pony.knowledge.service.storage.MinioService;
import com.pony.knowledge.utils.CurrentUserUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Exercises the service and real Flyway schema, including rollback of a rejected cascade. */
@SpringBootTest(properties = "task.reliability.recovery-enabled=false")
class DocumentDeleteLifecycleIntegrationTest {
    @Autowired private DocumentService service;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private MinioService minio;
    @MockitoBean private CurrentUserUtil currentUser;

    private Long kbId;
    private Long documentId;
    private Long taskId;
    private String guardTable;

    /** 准备本组用例共享的测试数据和隔离环境，通过 {@code jdbc.queryForObject}、{@code UUID.randomUUID} 初始化依赖。 */
    @BeforeEach
    void seed() {
        when(currentUser.getUserId()).thenReturn(999_997L);
        kbId = jdbc.queryForObject(
                "INSERT INTO knowledge_base(name, creator_id) VALUES (?, ?) RETURNING id",
                Long.class, "delete-lifecycle-" + UUID.randomUUID(), 999_997L);
        documentId = jdbc.queryForObject("""
                INSERT INTO document(knowledge_base_id, file_name, file_path, status)
                VALUES (?, 'delete.pdf', 'delete-lifecycle.pdf', 1) RETURNING id
                """, Long.class, kbId);
        taskId = jdbc.queryForObject(
                "INSERT INTO document_task(document_id, status) VALUES (?, 1) RETURNING id",
                Long.class, documentId);
        Long parentId = jdbc.queryForObject("""
                INSERT INTO document_chunk(document_id, knowledge_base_id, chunk_index, content, source_type)
                VALUES (?, ?, 1, 'parent evidence', 'parent') RETURNING id
                """, Long.class, documentId, kbId);
        jdbc.update("""
                INSERT INTO document_chunk(document_id, knowledge_base_id, chunk_index, content, parent_chunk_id)
                VALUES (?, ?, 0, 'child evidence', ?)
                """, documentId, kbId, parentId);
        jdbc.update("""
                INSERT INTO document_upload_receipt(user_id, knowledge_base_id, request_key,
                                                    content_sha256, document_id, task_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """, 999_997L, kbId, UUID.randomUUID().toString(), "a".repeat(64), documentId, taskId);
    }

    /** 在用例结束后释放该用例创建的资源。 */
    @AfterEach
    void cleanup() {
        if (guardTable != null) jdbc.execute("DROP TABLE IF EXISTS " + guardTable);
        // Explicit cleanup also works when the regression is run against the pre-V9 schema.
        jdbc.update("DELETE FROM document_upload_receipt WHERE document_id = ?", documentId);
        jdbc.update("DELETE FROM document_chunk WHERE document_id = ?", documentId);
        jdbc.update("DELETE FROM document_task WHERE document_id = ?", documentId);
        jdbc.update("DELETE FROM document WHERE id = ?", documentId);
        jdbc.update("DELETE FROM knowledge_base WHERE id = ?", kbId);
    }

    /** 删除有完整关联数据的文档，核对数据库已提交清理后才调用对象存储。 */
    @Test
    void deleteCommitsAllDependenciesBeforeRemovingObject() throws Exception {
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertRows(0);
            return null;
        }).when(minio).delete("delete-lifecycle.pdf");

        service.delete(documentId);

        assertRows(0);
        verify(minio).delete("delete-lifecycle.pdf");
    }

    /** 模拟任务级联删除失败，核对文档、切片与原对象均得到保留。 */
    @Test
    void rejectedTaskCascadeRollsBackDocumentAndChunksAndKeepsObject() {
        guardTable = "delete_guard_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE TABLE " + guardTable
                + " (task_id bigint REFERENCES document_task(id) ON DELETE RESTRICT)");
        jdbc.update("INSERT INTO " + guardTable + " VALUES (?)", taskId);

        assertThatThrownBy(() -> service.delete(documentId)).hasMessageContaining("文档删除失败");

        assertRows(1);
        verifyNoInteractions(minio);
    }

    /** 通过 {@code count} 驱动该场景，实际结果与固定预期一致。 */
    private void assertRows(int expected) {
        assertThat(count("document", "id")).isEqualTo(expected);
        assertThat(count("document_task", "document_id")).isEqualTo(expected);
        assertThat(count("document_chunk", "document_id")).isEqualTo(expected * 2);
        assertThat(count("document_upload_receipt", "document_id")).isEqualTo(expected);
    }

    /**
     * 通过 {@code jdbc.queryForObject} 记录或统计符合当前条件的数量。
     * @param table 本次调用传入的 {@code String}
     * @param column 本次调用传入的 {@code String}
     * @return 按当前方法口径计算或读取的数值
     */
    private int count(String table, String column) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + column + " = ?",
                Integer.class, documentId);
    }
}
