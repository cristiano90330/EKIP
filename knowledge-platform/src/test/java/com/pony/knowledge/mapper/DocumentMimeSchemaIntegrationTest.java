/* 验证实际数据库结构能保存当前接受的 Office MIME 类型。 */

package com.pony.knowledge.mapper;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.*;

/** New disposable schema only; no account/model fixtures or existing development database. */
@EnabledIfEnvironmentVariable(named = "EKIP_INDEX_TEST_ISOLATED", matches = "true")
class DocumentMimeSchemaIntegrationTest {
    private static JdbcTemplate jdbc;
    private static Long kb;

    /** 初始化本组用例共用依赖，调用 {@code url.matches}、{@code jdbc.queryForObject}。 */
    @BeforeAll static void database() {
        String url = System.getenv("EKIP_INDEX_TEST_JDBC_URL");
        assertNotNull(url);
        assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/ekip_index_test_mime_[a-z0-9]+"),
                "Only a dedicated disposable MIME test database may be migrated");
        DriverManagerDataSource data = new DriverManagerDataSource(url,
                System.getenv("EKIP_INDEX_TEST_DB_USER"), System.getenv("EKIP_INDEX_TEST_DB_PASSWORD"));
        jdbc = new JdbcTemplate(data);
        assertTrue(jdbc.queryForObject("SELECT current_database()", String.class).startsWith("ekip_index_test_mime_"));
        Flyway.configure().dataSource(data).locations("classpath:db/migration").cleanDisabled(true).load().migrate();
        kb = jdbc.queryForObject("INSERT INTO knowledge_base(name,creator_id) VALUES ('mime fixture',1) RETURNING id", Long.class);
    }

    /** 通过 {@code jdbc.queryForObject} 执行本例并核对输出或记录。接收{@code mime}。 */
    @ParameterizedTest
    @ValueSource(strings = {
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    })
    void standardOfficeMimeIsStoredWithoutTruncation(String mime) {
        Long document = jdbc.queryForObject("INSERT INTO document(knowledge_base_id,file_name,file_type) VALUES (?,?,?) RETURNING id",
                Long.class, kb, "fixture.office", mime);
        assertEquals(mime, jdbc.queryForObject("SELECT file_type FROM document WHERE id=?", String.class, document));
    }
}
