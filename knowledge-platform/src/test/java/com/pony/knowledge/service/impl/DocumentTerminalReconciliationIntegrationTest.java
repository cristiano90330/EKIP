/* 验证文档处理中断后的数据库任务终态和补偿规则。 */

package com.pony.knowledge.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs the deployment reconciliation SQL on isolated historical positive/negative fixtures. */
@SpringBootTest(properties = "task.reliability.recovery-enabled=false")
class DocumentTerminalReconciliationIntegrationTest {
    @Autowired private DataSource dataSource;
    private Connection connection;
    private String schema;

    /** 准备本组用例共享的测试数据和隔离环境，通过 {@code UUID.randomUUID}、{@code execute} 初始化依赖。 */
    @BeforeEach
    void isolateHistory() throws Exception {
        connection = dataSource.getConnection();
        schema = "terminal_history_" + UUID.randomUUID().toString().replace("-", "");
        execute("CREATE SCHEMA " + schema);
        connection.setSchema(schema);
        execute("CREATE TABLE document(id bigint PRIMARY KEY, status integer)");
        execute("CREATE TABLE document_task(id bigint PRIMARY KEY, document_id bigint, status integer)");
    }

    /** 在用例结束后释放该用例创建的资源。 */
    @AfterEach
    void cleanup() throws Exception {
        if (connection != null) {
            connection.setSchema("public");
            execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            connection.close();
        }
    }

    /** 通过 {@code execute}、{@code applyReconciliation} 驱动该场景，核对 {@code statusOf(1)} 等于 {@code 2}、{@code statusOf(2)} 等于 {@code 1}。 */
    @Test
    void completedLatestTaskRepairsOnlyItsStaleProcessingDocument() throws Exception {
        execute("INSERT INTO document VALUES (1, 1), (2, 1)");
        execute("INSERT INTO document_task VALUES (11, 1, 2), (12, 2, 1)");

        applyReconciliation();

        assertThat(statusOf(1)).isEqualTo(2);
        assertThat(statusOf(2)).isEqualTo(1);
    }

    /** 准备较早的成功任务和更晚的失败或活动任务，检查补偿逻辑只采纳实际最新任务。 */
    @Test
    void olderSuccessCannotOverrideLaterFailureOrActiveTask() throws Exception {
        execute("INSERT INTO document VALUES (1, 1), (2, 1), (3, 1)");
        execute("INSERT INTO document_task VALUES (11, 1, 2), (12, 1, 3), "
                + "(21, 2, 2), (22, 2, 0), (31, 3, 2), (32, 3, 1)");

        applyReconciliation();

        assertThat(statusOf(1)).isEqualTo(1);
        assertThat(statusOf(2)).isEqualTo(1);
        assertThat(statusOf(3)).isEqualTo(1);
    }

    /** 通过 {@code execute}、{@code applyReconciliation} 驱动该场景，核对 {@code statusOf(1)} 等于 {@code 1}、{@code statusOf(2)} 等于 {@code 1}。 */
    @Test
    void activeHistoricalTaskAndMissingTaskRemainProcessingForRecovery() throws Exception {
        execute("INSERT INTO document VALUES (1, 1), (2, 1), (3, 1)");
        execute("INSERT INTO document_task VALUES (11, 1, 0), (12, 1, 2), (21, 2, 1), (22, 2, 2)");

        applyReconciliation();

        assertThat(statusOf(1)).isEqualTo(1);
        assertThat(statusOf(2)).isEqualTo(1);
        assertThat(statusOf(3)).isEqualTo(1);
    }

    /** 通过 {@code connection.createStatement}、{@code statement.executeQuery} 驱动该场景，核对 {@code statusOf(1)} 等于 {@code 0}、{@code statusOf(2)} 等于 {@code 2}。 */
    @Test
    void reconciliationLeavesOtherDocumentStatusesAndTasksIntactAndCanRepeat() throws Exception {
        execute("INSERT INTO document VALUES (1, 0), (2, 2), (3, 3), (4, 1)");
        execute("INSERT INTO document_task VALUES (11, 1, 2), (21, 2, 2), (31, 3, 2), "
                + "(41, 4, 3), (42, 4, 2)");

        applyReconciliation();
        applyReconciliation();

        assertThat(statusOf(1)).isEqualTo(0);
        assertThat(statusOf(2)).isEqualTo(2);
        assertThat(statusOf(3)).isEqualTo(3);
        assertThat(statusOf(4)).isEqualTo(2);
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT string_agg(id || ':' || status, ',' ORDER BY id) FROM document_task")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo("11:2,21:2,31:2,41:3,42:2");
        }
    }

    /** 通过 {@code input.readAllBytes} 驱动用例，核对 {@code migration.exists()} 为 true。 */
    private void applyReconciliation() throws Exception {
        ClassPathResource migration = new ClassPathResource("db/migration/V10__reconcile_completed_document_status.sql");
        assertThat(migration.exists()).isTrue();
        // PostgreSQL Flyway migrations are transactional; the reconciliation lock must
        // live in the same transaction as its UPDATE, including in this isolated schema.
        connection.setAutoCommit(false);
        try {
            try (var input = migration.getInputStream()) {
                execute(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
            connection.commit();
        } finally {
            connection.rollback();
            connection.setAutoCommit(true);
        }
    }

    /** 通过 {@code connection.prepareStatement}、{@code statement.executeQuery} 驱动该场景，核对 {@code rows.next()} 为 true。 */
    private int statusOf(long documentId) throws Exception {
        try (var statement = connection.prepareStatement("SELECT status FROM document WHERE id=?")) {
            statement.setLong(1, documentId);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getInt(1);
            }
        }
    }

    /**
     * 通过 {@code connection.createStatement} 执行当前方法封装的业务操作。
     * @param sql 本次调用传入的 {@code String}
     */
    private void execute(String sql) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
