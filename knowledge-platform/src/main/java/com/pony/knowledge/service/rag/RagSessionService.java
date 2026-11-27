/* 管理服务端问答会话与回合租用，保存有权限读取的历史。 */

package com.pony.knowledge.service.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pony.knowledge.exception.ForbiddenException;
import com.pony.knowledge.service.KnowledgeBaseAccessService;
import com.pony.knowledge.service.rag.query.ChatMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RagSessionService {
    private static final int PAGE_SIZE = 30;
    private final JdbcTemplate jdbc;
    private final KnowledgeBaseAccessService access;
    private final ObjectMapper json;

    /**
     * 保存当前用户的问答会话及所选知识库范围。
     * 上游为 RagSessionController.create。
     * 下游为 RagSessionService.resolveScope、RagSessionService.scopeKey。
     *
     * @param userId 用户 ID
     * @param knowledgeBaseId 知识库 ID
     * @return 本次计算或查询得到的 SessionView 结果
     */
    public SessionView create(long userId, Long knowledgeBaseId) {
        List<Long> scope = resolveScope(userId, knowledgeBaseId);
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO rag_session(id,user_id,knowledge_base_id,scope_key) VALUES (?,?,?,?)",
                id, userId, knowledgeBaseId, scopeKey(scope));
        return owned(userId, id).view(true);
    }

    /**
     * 分页返回账号拥有的问答会话及当前可用性。
     * 上游为 RagSessionController.list。
     * 下游为 RagSessionService.invalidPage、RagSessionService.resolveScope。
     *
     * @param userId 用户 ID
     * @param offset 从窗口起点计算的偏移
     * @return 本次计算或查询得到的 SessionPage 结果
     */
    public SessionPage list(long userId, int offset) {
        if (offset < 0 || offset > 1_000_000) throw invalidPage();
        List<Long> readable = resolveScope(userId, null);
        List<Session> matches = jdbc.query("""
                SELECT * FROM rag_session WHERE user_id=? ORDER BY updated_at DESC, id DESC LIMIT ? OFFSET ?
                """, (rs, row) -> session(rs), userId, PAGE_SIZE + 1, offset);
        List<SessionView> items = matches.stream().limit(PAGE_SIZE)
                .map(item -> item.view(item.knowledgeBaseId() == null ? item.scopeKey().equals(scopeKey(readable))
                        : readable.contains(item.knowledgeBaseId()))).toList();
        return new SessionPage(items, matches.size() > PAGE_SIZE ? offset + PAGE_SIZE : null);
    }

    /**
     * 核对历史访问权限，按回合游标读取并返回已提交记录。
     * 上游为 RagSessionController.history。
     * 下游为 RagSessionService.authorized、RagSessionService.invalidPage。
     *
     * @param userId 用户 ID
     * @param id 问答会话 ID
     * @param beforeId 要定位的关联标识
     * @return 按查询游标读取的会话回合和可用的下一页位置
     */
    public TurnPage history(long userId, UUID id, Long beforeId) {
        authorized(userId, id);
        if (beforeId != null && beforeId <= 0) throw invalidPage();
        List<Turn> matches = jdbc.query("""
                SELECT id,question,response,created_at FROM rag_session_turn
                WHERE session_id=? AND id<? ORDER BY id DESC LIMIT ?
                """, (rs, row) -> new Turn(rs.getLong("id"), rs.getString("question"),
                parse(rs.getString("response")), rs.getTimestamp("created_at").getTime()),
                id, beforeId == null ? Long.MAX_VALUE : beforeId, PAGE_SIZE + 1);
        List<Turn> items = new java.util.ArrayList<>(matches.stream().limit(PAGE_SIZE).toList());
        Long nextBeforeId = matches.size() > PAGE_SIZE ? items.get(items.size() - 1).id() : null;
        Collections.reverse(items);
        return new TurnPage(items, nextBeforeId);
    }

    /**
     * 核对会话所有权后删除会话记录。
     * 上游为 RagSessionController.delete。
     * 下游为 RagSessionService.owned、RagSessionService.busy。
     *
     * @param userId 用户 ID
     * @param id 问答会话 ID
     */
    public void delete(long userId, UUID id) {
        owned(userId, id);
        if (jdbc.update("""
                DELETE FROM rag_session WHERE id=? AND user_id=? AND (active_until IS NULL OR active_until<now())
                """, id, userId) != 1) throw busy();
    }

    /**
     * 锁定会话并核对最新权限，取得可用于写入的回合租约。
     * 上游为 KnowledgeChatStreamController.streamSession、RagSessionController.ask。
     * 下游为 RagSessionService.owned、RagSessionService.resolveScope。
     *
     * @param userId 用户 ID
     * @param id 问答会话 ID
     * @return 携带会话标识、用户权限和回合令牌的租约
     */
    public TurnLease begin(long userId, UUID id) {
        Session session = owned(userId, id);
        List<Long> scope = resolveScope(userId, session.knowledgeBaseId());
        if (!session.scopeKey().equals(scopeKey(scope))) throw denied();
        UUID token = UUID.randomUUID();

        if (jdbc.update("""
                UPDATE rag_session SET active_turn=?, active_until=now() + interval '12 minutes'
                WHERE id=? AND user_id=? AND (active_until IS NULL OR active_until<now())
                """, token, id, userId) != 1) throw busy();
        return new TurnLease(id, userId, token, scope, session.knowledgeBaseId() != null);
    }

    /**
     * 在已授权的会话范围内取最近三个完成回合，供单库和全库追问使用。
     * 上游为 KnowledgeChatStreamController.streamSession、RagSessionController.ask。
     * 下游为 RagSessionService.requireCurrentScope、RagSessionService.contextAnswer。
     *
     * @param lease 本次取得的处理或问答回合租约
     * @return 与过滤条件匹配的 List&lt;ChatMessage&gt; 数据集合
     */
    public List<ChatMessage> context(TurnLease lease) {
        Session session = jdbc.query("""
                SELECT * FROM rag_session WHERE id=? AND user_id=? AND active_turn=? AND active_until>=now()
                """, (rs, row) -> session(rs), lease.id(), lease.userId(), lease.token())
                .stream().findFirst().orElseThrow(RagSessionService::denied);
        if (!session.scopeKey().equals(scopeKey(lease.scope()))) throw denied();
        requireCurrentScope(lease.userId(), session);
        if (lease.scope().isEmpty()) return List.of();

        List<Turn> recent = jdbc.query("""
                SELECT id,question,response,created_at FROM rag_session_turn
                WHERE session_id=? ORDER BY id DESC LIMIT 3
                """, (rs, row) -> new Turn(rs.getLong("id"), rs.getString("question"),
                parse(rs.getString("response")), rs.getTimestamp("created_at").getTime()), lease.id());

        // 读取历史期间授权可能发生变化。向调用方返回历史前，再对比会话范围快照。
        requireCurrentScope(lease.userId(), session);
        Collections.reverse(recent);
        return recent.stream().flatMap(turn -> java.util.stream.Stream.of(
                message("user", turn.question()),
                message("assistant", contextAnswer(turn.response())))).toList();
    }

    /**
     * 从历史响应中提取可再次传给问答模型的答案文本。
     * 上游为 RagSessionService.context。
     *
     * @param response 当前调用对应的响应对象
     * @return 可用于后续生成的有效答案或保留的缺证提示
     */
    private static String contextAnswer(JsonNode response) {
        String answer = response.path("answer").asText("");
        return "success".equals(response.path("outcome").asText()) && !answer.isBlank()
                ? answer : "没有足够证据回答。";
    }

    /**
     * 在权限和租约仍有效时提交本次有效问答。
     * 上游为 KnowledgeChatStreamController.openStream、RagSessionController.ask。
     * 下游为 TurnLease.userId、TurnLease.id。
     *
     * @param lease 本次取得的处理或问答回合租约
     * @param question 用户问题正文
     * @param response 当前调用对应的响应对象
     */
    @Transactional
    public void complete(TurnLease lease, String question, JsonNode response) {
        if (!List.of("success", "no_evidence").contains(response.path("outcome").asText())) return;
        authorized(lease.userId(), lease.id());
        String title = question.substring(0, question.offsetByCodePoints(0, Math.min(36, question.codePointCount(0, question.length()))));

        // 先用仍有效的回合令牌归还租约，再在同一事务写入历史，避免旧回合将迟到的答案存入会话。
        if (jdbc.update("""
                UPDATE rag_session SET title=CASE WHEN title='新会话' THEN ? ELSE title END,
                    updated_at=now(), active_turn=NULL, active_until=NULL
                WHERE id=? AND user_id=? AND active_turn=? AND active_until>=now()
                """, title, lease.id(), lease.userId(), lease.token()) != 1) throw busy();
        jdbc.update("INSERT INTO rag_session_turn(session_id,question,response) VALUES (?,?, CAST(? AS jsonb))",
                lease.id(), question, response.toString());
    }

    /**
     * 释放指定会话回合仍由本次调用持有的租约。
     * 上游为 KnowledgeChatStreamController.releaseLease、RagSessionController.ask。
     * 下游为 TurnLease.id、TurnLease.userId。
     *
     * @param lease 本次取得的处理或问答回合租约
     */
    public void end(TurnLease lease) {
        end(lease.userId(), lease.id(), lease.token());
    }

    /** 仅释放指定账号、会话和回合的执行权。重复调用不会影响下一轮。 */
    public void end(long userId, UUID sessionId, UUID turnId) {
        jdbc.update("UPDATE rag_session SET active_turn=NULL,active_until=NULL WHERE id=? AND user_id=? AND active_turn=?",
                sessionId, userId, turnId);
    }

    /**
     * 读取用户会话并再次确认当前知识库范围是否有权访问。
     * 上游为 RagSessionService.history、RagSessionService.complete。
     * 下游为 RagSessionService.owned、RagSessionService.scopeKey。
     *
     * @param userId 用户 ID
     * @param id 问答会话 ID
     * @return 本次计算或查询得到的 Session 结果
     */
    private Session authorized(long userId, UUID id) {
        Session session = owned(userId, id);
        requireCurrentScope(userId, session);
        return session;
    }

    /**
     * 对比当前可读范围和会话创建时的范围，变化时拒绝使用该会话。
     * 上游为 authorized、context，下游为 resolveScope。
     *
     * @param userId 会话账号 ID
     * @param session 含有固定范围快照的会话
     */
    private void requireCurrentScope(long userId, Session session) {
        if (!session.scopeKey().equals(scopeKey(resolveScope(userId, session.knowledgeBaseId())))) throw denied();
    }

    /**
     * 按会话 ID 和所有者查询会话记录，缺失时按拒绝访问处理。
     * 上游为 RagSessionService.create、RagSessionService.delete。
     * 下游为 RagSessionService.session。
     *
     * @param userId 用户 ID
     * @param id 问答会话 ID
     * @return 本次计算或查询得到的 Session 结果
     */
    private Session owned(long userId, UUID id) {
        return jdbc.query("SELECT * FROM rag_session WHERE id=? AND user_id=?",
                (rs, row) -> session(rs), id, userId).stream().findFirst().orElseThrow(RagSessionService::denied);
    }

    /**
     * 按单库或全部授权库模式解析当前可用的知识库列表。
     * 上游为 RagSessionService.create、RagSessionService.list。
     * 下游为 KnowledgeBaseAccessService.resolveReadableIds、KnowledgeBaseAccessService.canRead。
     *
     * @param userId 用户 ID
     * @param knowledgeBaseId 知识库 ID
     * @return 与过滤条件匹配的 List&lt;Long&gt; 数据集合
     */
    private List<Long> resolveScope(long userId, Long knowledgeBaseId) {
        if (knowledgeBaseId == null) return access.resolveReadableIds(userId).stream().sorted().toList();
        if (!access.canRead(userId, knowledgeBaseId)) throw denied();
        return List.of(knowledgeBaseId);
    }

    /**
     * 对知识库列表排序和拼接，生成可比对的会话范围键。
     * 上游为 RagSessionService.create、RagSessionService.list。
     *
     * @param scope 需要本方法按序读取或筛选的 List&lt;Long&gt; 数据
     * @return 由排序后的知识库 ID 组成的权限范围标识
     */
    private static String scopeKey(List<Long> scope) {
        return scope.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    /**
     * 从持久化问答 JSON 解析显示与历史上下文需要的内容。
     * 上游为 RagSessionService.history、RagSessionService.context。
     *
     * @param value 待校验或转换的值
     * @return 本次计算或查询得到的 JsonNode 结果
     */
    private JsonNode parse(String value) {
        try { return json.readTree(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("会话响应无法读取", e); }
    }

    /**
     * 从数据库行读取问答会话的持久化字段。
     * 上游为 RagSessionService.list、RagSessionService.owned。
     *
     * @param rs 本次操作所用的 ResultSet 数据
     * @return 本次计算或查询得到的 Session 结果
     */
    private static Session session(ResultSet rs) throws SQLException {
        return new Session(rs.getObject("id", UUID.class), rs.getObject("knowledge_base_id", Long.class),
                rs.getString("scope_key"), rs.getString("title"), rs.getTimestamp("created_at").getTime());
    }

    /**
     * 构造供问答历史上下文传给 RAG 的角色和正文。
     * 上游为 RagSessionService.context。
     *
     * @param role 本方法计算或判断使用的 role 值
     * @param content 待处理的正文内容
     * @return 问答历史中的消息角色和正文，以 ChatMessage 返回
     */
    private static ChatMessage message(String role, String content) {
        ChatMessage message = new ChatMessage();
        message.setRole(role);
        message.setContent(content.substring(0, content.offsetByCodePoints(0,
                Math.min(4000, content.codePointCount(0, content.length())))));
        return message;
    }

    /**
     * 返回没有会话访问或写入权限时使用的异常。
     * 上游为 RagSessionService.begin、RagSessionService.authorized。
     *
     * @return 本次得到的 ForbiddenException 实例
     */
    private static ForbiddenException denied() { return new ForbiddenException("会话不存在或知识库权限已变化"); }
    /**
     * 返回已有其他回合正在处理会话时使用的异常。
     * 上游为 RagSessionService.delete、RagSessionService.begin。
     *
     * @return 本次计算或查询得到的 ResponseStatusException 结果
     */
    private static ResponseStatusException busy() { return new ResponseStatusException(HttpStatus.CONFLICT, "会话当前正在处理，请稍后重试"); }
    /**
     * 返回历史分页位置无效时使用的异常。
     * 上游为 RagSessionService.list、RagSessionService.history。
     *
     * @return 本次计算或查询得到的 ResponseStatusException 结果
     */
    private static ResponseStatusException invalidPage() { return new ResponseStatusException(HttpStatus.BAD_REQUEST, "会话分页参数无效"); }

    private record Session(UUID id, Long knowledgeBaseId, String scopeKey, String title, long createdAt) {
        /**
         * 将会话内容与本次权限检查的可用标记组装成前端视图。
         *
         * @param available 本方法计算或判断使用的 available 值
         * @return 本次计算或查询得到的 SessionView 结果
         */
        SessionView view(boolean available) {
            return new SessionView(id, available ? knowledgeBaseId : null,
                    available ? title : "知识库权限已变化", createdAt, available);
        }
    }
    public record SessionView(UUID id, Long knowledgeBaseId, String title, long createdAt, boolean available) { }
    public record SessionPage(List<SessionView> items, Integer nextOffset) { }
    public record Turn(long id, String question, JsonNode response, long createdAt) { }
    public record TurnPage(List<Turn> items, Long nextBeforeId) { }
    public record TurnLease(UUID id, long userId, UUID token, List<Long> scope, boolean scoped) {
        public TurnLease {
            scope = List.copyOf(scope);
        }
    }
}
