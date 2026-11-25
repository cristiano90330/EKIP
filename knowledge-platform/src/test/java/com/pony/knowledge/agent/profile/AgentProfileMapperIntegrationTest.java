/* 通过数据库查询验证自建 Agent 配置的知识库和工具列表映射。 */

package com.pony.knowledge.agent.profile;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises both annotated queries against a connection-local table, rolled back on close. */
@EnabledIfEnvironmentVariable(named = "EKIP_AGENT_PROFILE_TEST_ENABLED", matches = "true")
class AgentProfileMapperIntegrationTest {

    /** 通过 {@code statement.execute} 驱动用例，布尔状态符合断言，返回的数据、边界或顺序符合断言。 */
    @Test
    void annotatedQueriesRestoreKnowledgeAndToolLists() throws Exception {
        String url = System.getenv("EKIP_AGENT_PROFILE_TEST_JDBC_URL");
        assertTrue(url != null && url.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/[A-Za-z0-9_]+"));
        UnpooledDataSource data = new UnpooledDataSource("org.postgresql.Driver", url,
                System.getenv("EKIP_AGENT_PROFILE_TEST_DB_USER"),
                System.getenv("EKIP_AGENT_PROFILE_TEST_DB_PASSWORD"));
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("agent-profile-test", new JdbcTransactionFactory(), data));
        configuration.addMapper(AgentProfileMapper.class);

        try (SqlSession session = new MybatisSqlSessionFactoryBuilder().build(configuration).openSession(false)) {
            try (var statement = session.getConnection().createStatement()) {
                statement.execute("""
                        CREATE TEMP TABLE agent_profile (
                            id bigint PRIMARY KEY, user_id bigint, name text, chat_model_id text,
                            system_prompt text, kb_selection_mode text, knowledge_base_ids text,
                            allowed_tools text, answer_policy text, memory_read_enabled boolean,
                            memory_write_enabled boolean, create_time timestamp, update_time timestamp
                        ) ON COMMIT DROP
                        """);
                statement.execute("""
                        INSERT INTO pg_temp.agent_profile VALUES (
                            901, 902, 'profile-mapping', NULL, NULL, 'selected', '[5]',
                            '["knowledge_search","read_document"]', 'grounded', true, false,
                            CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                        )
                        """);
            }
            AgentProfileMapper mapper = session.getMapper(AgentProfileMapper.class);
            for (AgentProfile profile : new AgentProfile[] {
                    mapper.selectOwned(901L, 902L), mapper.selectByUserId(902L).get(0)}) {
                assertEquals("[5]", profile.getKnowledgeBaseIdsJson());
                assertEquals("[\"knowledge_search\",\"read_document\"]", profile.getAllowedToolsJson());
                assertEquals("selected", profile.getKbSelectionMode());
                assertEquals("grounded", profile.getAnswerPolicy());
                assertTrue(profile.getMemoryReadEnabled());
                assertFalse(profile.getMemoryWriteEnabled());
            }
            assertNull(mapper.selectOwned(901L, 903L));
            assertTrue(mapper.selectByUserId(903L).isEmpty());
            session.rollback();
        }
    }
}
