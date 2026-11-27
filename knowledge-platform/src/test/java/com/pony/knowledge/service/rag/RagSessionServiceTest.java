/* 验证普通问答只在仍授权的会话范围内使用已完成历史。 */

package com.pony.knowledge.service.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pony.knowledge.exception.ForbiddenException;
import com.pony.knowledge.service.KnowledgeBaseAccessService;
import com.pony.knowledge.service.rag.query.ChatMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class RagSessionServiceTest {
    private static final long USER_ID = 7L;
    private static final UUID SESSION_ID = UUID.randomUUID();
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final KnowledgeBaseAccessService access = mock(KnowledgeBaseAccessService.class);
    private final RagSessionService sessions = new RagSessionService(jdbc, access, new ObjectMapper());

    /** 旧的全库会话无需迁移即可使用有序范围和最近三个完成回合。 */
    @Test
    void allReadableSessionResumesWithCompletedTurnsInChronologicalOrder() throws Exception {
        storedSession(null, "2,7");
        when(access.resolveReadableIds(USER_ID)).thenReturn(Set.of(7L, 2L));
        var lease = sessions.begin(USER_ID, SESSION_ID);
        assertEquals(List.of(2L, 7L), lease.scope());
        activeSession(lease, null, "2,7");
        completedHistory(List.of(
                turn(30, "第三问", "{\"outcome\":\"success\",\"answer\":\"第三答\"}"),
                turn(20, "第二问", "{\"outcome\":\"no_evidence\",\"answer\":\"不重放这段正文\"}"),
                turn(10, "第一问", "{\"outcome\":\"success\",\"answer\":\"第一答\",\"sources\":[{\"content\":\"不带入新问题的引用\"}]}")));

        var context = sessions.context(lease);

        assertEquals(List.of("user", "assistant", "user", "assistant", "user", "assistant"),
                context.stream().map(ChatMessage::getRole).toList());
        assertEquals(List.of("第一问", "第一答", "第二问", "没有足够证据回答。", "第三问", "第三答"),
                context.stream().map(ChatMessage::getContent).toList());
    }

    /** 全库范围收缩或扩张后，已认领的回合也不能读取旧会话历史。 */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void changedReadableScopeAfterClaimRejectsBeforeReadingHistory(boolean expanded) throws Exception {
        storedSession(null, "2,7");
        when(access.resolveReadableIds(USER_ID)).thenReturn(Set.of(2L, 7L),
                expanded ? Set.of(2L, 7L, 8L) : Set.of(7L));
        var lease = sessions.begin(USER_ID, SESSION_ID);
        activeSession(lease, null, "2,7");

        assertThrows(ForbiddenException.class, () -> sessions.context(lease));
        verifyHistoryWasNotRead();
    }

    /** 读取历史期间权限变化时，历史在返回给模型前被拒绝。 */
    @Test
    void revokedAccessWhileHistoryIsReadPreventsReplay() throws Exception {
        storedSession(null, "2,7");
        when(access.resolveReadableIds(USER_ID)).thenReturn(Set.of(2L, 7L), Set.of(2L, 7L), Set.of(7L));
        var lease = sessions.begin(USER_ID, SESSION_ID);
        activeSession(lease, null, "2,7");
        completedHistory(List.of(turn(1, "旧问题", "{\"outcome\":\"success\",\"answer\":\"旧答案\"}")));

        assertThrows(ForbiddenException.class, () -> sessions.context(lease));
        verify(jdbc).query(contains("FROM rag_session_turn"), any(RowMapper.class), eq(SESSION_ID));
    }

    /** 当前没有授权库时保留空范围，历史不会被读取。 */
    @Test
    void emptyScopeDoesNotReplayHistory() throws Exception {
        storedSession(null, "");
        when(access.resolveReadableIds(USER_ID)).thenReturn(Set.of());
        var lease = sessions.begin(USER_ID, SESSION_ID);
        activeSession(lease, null, "");

        assertTrue(lease.scope().isEmpty());
        assertTrue(sessions.context(lease).isEmpty());
        verifyHistoryWasNotRead();
    }

    /** 无有效租约的请求无法访问历史，包括他人会话和已经失效的令牌。 */
    @Test
    void unknownOrExpiredLeaseDoesNotReadHistory() {
        var lease = new RagSessionService.TurnLease(SESSION_ID, USER_ID, UUID.randomUUID(), List.of(2L), false);

        assertThrows(ForbiddenException.class, () -> sessions.context(lease));
        verifyNoInteractions(access);
        verifyHistoryWasNotRead();
    }

    /** 指定单库继续按该库授权检查，用户新增其他库不会改变会话范围。 */
    @Test
    void singleLibraryStillUsesItsOwnAuthorization() throws Exception {
        storedSession(7L, "7");
        when(access.canRead(USER_ID, 7L)).thenReturn(true);
        var lease = sessions.begin(USER_ID, SESSION_ID);
        activeSession(lease, 7L, "7");
        completedHistory(List.of(turn(1, "旧问题", "{\"outcome\":\"success\",\"answer\":\"旧答案\"}")));

        assertEquals(List.of(7L), lease.scope());
        assertEquals(2, sessions.context(lease).size());
        verify(access, never()).resolveReadableIds(anyLong());
    }

    /** 为已有会话准备所有权查询与认领结果。 */
    private void storedSession(Long knowledgeBaseId, String scope) throws Exception {
        ResultSet row = sessionRow(knowledgeBaseId, scope);
        when(jdbc.query(eq("SELECT * FROM rag_session WHERE id=? AND user_id=?"), any(RowMapper.class),
                eq(SESSION_ID), eq(USER_ID))).thenAnswer(call -> List.of(call.<RowMapper<?>>getArgument(1).mapRow(row, 0)));
        when(jdbc.update(contains("SET active_turn=?"), any(UUID.class), eq(SESSION_ID), eq(USER_ID))).thenReturn(1);
    }

    /** 只允许历史读取使用认领实际签发的所有者和令牌。 */
    private void activeSession(RagSessionService.TurnLease lease, Long knowledgeBaseId, String scope) throws Exception {
        ResultSet row = sessionRow(knowledgeBaseId, scope);
        when(jdbc.query(contains("active_turn=? AND active_until>=now()"), any(RowMapper.class),
                eq(SESSION_ID), eq(USER_ID), eq(lease.token())))
                .thenAnswer(call -> List.of(call.<RowMapper<?>>getArgument(1).mapRow(row, 0)));
    }

    /** 模拟数据库按时间倒序读取最近三个已持久化回合。 */
    private void completedHistory(List<ResultSet> rows) {
        when(jdbc.query(contains("ORDER BY id DESC LIMIT 3"), any(RowMapper.class), eq(SESSION_ID)))
                .thenAnswer(call -> {
                    RowMapper<?> mapper = call.getArgument(1);
                    var items = new ArrayList<>();
                    for (ResultSet row : rows) {
                        items.add(mapper.mapRow(row, items.size()));
                    }
                    return items;
                });
    }

    /** 模拟会话持久化行，由实际服务的行映射读取。 */
    private ResultSet sessionRow(Long knowledgeBaseId, String scope) throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getObject("id", UUID.class)).thenReturn(SESSION_ID);
        when(row.getObject("knowledge_base_id", Long.class)).thenReturn(knowledgeBaseId);
        when(row.getString("scope_key")).thenReturn(scope);
        when(row.getTimestamp("created_at")).thenReturn(new Timestamp(0));
        return row;
    }

    /** 构造一条已提交的问答记录供历史查询返回。 */
    private ResultSet turn(long id, String question, String response) throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getLong("id")).thenReturn(id);
        when(row.getString("question")).thenReturn(question);
        when(row.getString("response")).thenReturn(response);
        when(row.getTimestamp("created_at")).thenReturn(new Timestamp(0));
        return row;
    }

    /** 断言没有读取历史表。 */
    private void verifyHistoryWasNotRead() {
        verify(jdbc, never()).query(contains("FROM rag_session_turn"), any(RowMapper.class), eq(SESSION_ID));
    }
}
