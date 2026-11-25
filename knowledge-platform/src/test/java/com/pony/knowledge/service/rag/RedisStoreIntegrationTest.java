/* 验证真实 Redis 的会话存储、隔离、原子更新及过期处理。 */

package com.pony.knowledge.service.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pony.knowledge.agent.memory.MemoryItem;
import com.pony.knowledge.agent.memory.longterm.LongTermMemoryItem;
import com.pony.knowledge.agent.memory.longterm.LongTermMemoryService;
import com.pony.knowledge.agent.memory.longterm.RedisLongTermMemoryStore;
import com.pony.knowledge.agent.model.AgentMessage;
import com.pony.knowledge.agent.model.StopReason;
import com.pony.knowledge.agent.session.*;
import com.pony.knowledge.agent.profile.AgentProfileService;
import com.pony.knowledge.agent.tool.approval.MemorySaveApproval;
import com.pony.knowledge.agent.tool.approval.MemorySaveApprovalService;
import com.pony.knowledge.audit.AuditLogService;
import com.pony.knowledge.exception.ForbiddenException;
import com.pony.knowledge.service.KnowledgeBaseAccessService;
import com.pony.knowledge.service.rag.query.ChatMessage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Exercises the real Redis commands/Lua; run only through the disposable Redis runner. */
@EnabledIfEnvironmentVariable(named = "EKIP_REDIS_TEST_ISOLATED", matches = "true")
class RedisStoreIntegrationTest {
    private LettuceConnectionFactory connection;
    private StringRedisTemplate redis;
    private ObjectMapper mapper;
    private RedisAgentSessionStore sessions;

    /** 准备本组用例共享的测试数据和隔离环境，通过 {@code Integer.parseInt}、{@code owner.matches} 初始化依赖。 */
    @BeforeEach void connectToOwnedRedis() {
        int port = Integer.parseInt(System.getenv("EKIP_REDIS_TEST_PORT"));
        String owner = System.getenv("EKIP_REDIS_TEST_RUN_ID");
        assertTrue(port > 1024 && port != 6379 && port != 16379);
        assertTrue(owner != null && owner.matches("[a-f0-9]{12}"));
        connection = new LettuceConnectionFactory("127.0.0.1", port);
        connection.setDatabase(15);
        connection.afterPropertiesSet();
        redis = new StringRedisTemplate(connection);
        redis.afterPropertiesSet();
        assertEquals(owner, redis.opsForValue().get("ekip:test:owner"));
        mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        sessions = new RedisAgentSessionStore(redis, mapper, 60000, 180000);
    }

    /** 在用例结束后释放该用例创建的资源。 */
    @AfterEach void disconnect() { if (connection != null) connection.destroy(); }

    /** 在真实 Redis 中提交回合，核对合法租约是写入前提且相关键到期同步更新。 */
    @Test void completedTurnRequiresLeaseAndRefreshesAllExpiries() {
        AgentSession session = session(1L);
        sessions.create(session);
        assertTrue(sessions.beginTurn(session.id(), "first"));
        assertFalse(sessions.beginTurn(session.id(), "second"));
        assertFalse(sessions.delete(session.id(), 1L));
        assertThrows(IllegalStateException.class, () -> complete(session, "wrong"));
        assertEquals(0, sessions.countTurns(session.id()));
        assertEquals(0, sessions.countMessages(session.id()));
        sessions.saveSummary(session.id(), MemoryItem.builder().content("summary").build());
        AgentSession renewed = session.updated("next", System.currentTimeMillis(), System.currentTimeMillis() + 120000);
        complete(renewed, "first");
        assertEquals("answer", sessions.history(session.id(), 0, 10).get(0).answer());
        assertEquals(2, sessions.countMessages(session.id()));
        assertEquals("summary", sessions.summary(session.id()).orElseThrow().getContent());
        for (String suffix : List.of("meta", "turns", "messages", "summary")) {
            long ttl = redis.getExpire("agent:session:" + session.id() + ":" + suffix, TimeUnit.MILLISECONDS);
            assertTrue(ttl > 110000 && ttl <= 120000, suffix + " must share the renewed expiry");
        }

        sessions.endTurn(session.id(), "wrong");
        assertFalse(sessions.beginTurn(session.id(), "second"));
        sessions.endTurn(session.id(), "first");
        assertTrue(sessions.delete(session.id(), 1L));
    }

    /** 删除受归属保护的会话后尝试写入晚到摘要，确认不会恢复已删除数据。 */
    @Test void sessionOwnershipDeletionAndLateSummaryDoNotReviveData() {
        AgentSession session = session(2L);
        sessions.create(session);
        assertTrue(sessions.list(1L, 0, 100).stream().noneMatch(s -> s.id().equals(session.id())));
        assertTrue(sessions.delete(session.id(), 1L)); // no-op for another owner
        assertTrue(sessions.get(session.id()).isPresent());
        assertTrue(sessions.delete(session.id(), 2L));
        sessions.saveSummary(session.id(), MemoryItem.builder().content("late").build());
        assertTrue(sessions.get(session.id()).isEmpty());
        assertTrue(sessions.summary(session.id()).isEmpty());
        assertFalse(sessions.beginTurn(session.id(), "late"));
        assertThrows(IllegalStateException.class, () -> complete(session, "late"));
        assertEquals(0, sessions.countTurns(session.id()));
    }

    /** 通过 Redis 验证长期记忆按用户隔离，并将容量和修正处理作为原子更新。 */
    @Test void longTermMemoryUsesAtomicCapacityCorrectionAndUserIsolation() {
        var store = new RedisLongTermMemoryStore(redis, mapper);
        long user = Math.abs(UUID.randomUUID().getMostSignificantBits()) / 2;
        for (int i = 0; i < 3; i++) store.add(user, LongTermMemoryItem.builder()
                .id("m" + i).userId(user).kind("FACT").content("value" + i)
                .createdAt(Instant.now()).build(), 2);
        assertEquals(List.of("m2", "m1"), store.list(user, 10).stream().map(LongTermMemoryItem::getId).toList());
        assertTrue(store.update(user + 1, "m2", "wrong", "FACT", Instant.now()).isEmpty());
        assertFalse(store.delete(user + 1, "m2"));
        assertEquals("corrected", store.update(user, "m2", "corrected", "PREFERENCE", Instant.now())
                .orElseThrow().getContent());
        assertTrue(store.delete(user, "m2"));
        assertEquals(List.of("m1"), new RedisLongTermMemoryStore(redis, mapper).list(user, 10)
                .stream().map(LongTermMemoryItem::getId).toList());
    }

    /** 分别尝试越权和重复审批，确认只有会话所有者能消费一次记忆申请。 */
    @Test void memoryApprovalIsOwnedAndConsumedOnce() {
        var sessionService = new AgentSessionService(sessions, mock(AgentProfileService.class),
                mock(KnowledgeBaseAccessService.class), 30);
        var memory = new LongTermMemoryService(new RedisLongTermMemoryStore(redis, mapper), true, 50, 5, 500);
        var approvals = new MemorySaveApprovalService(redis, mapper, sessionService, memory, mock(AuditLogService.class), 30);
        long owner = (UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE) / 2;
        String sessionId = sessionService.create(owner, null).id();
        AgentSession session = sessionService.requireAuthorizedSession(owner, sessionId);
        String content = "评审记录统一使用中文";
        var proposal = new MemorySaveApproval.Proposal(content, "PREFERENCE");
        UUID rejectedId = UUID.fromString(approvals.create(session, proposal).id());
        assertTrue(memory.list(owner).isEmpty());
        assertThrows(ForbiddenException.class, () -> approvals.get(owner + 1, sessionId, rejectedId));
        assertEquals(MemorySaveApproval.State.REJECTED,
                approvals.decide(owner, sessionId, rejectedId, MemorySaveApproval.Decision.REJECT).state());
        assertEquals(MemorySaveApproval.State.REJECTED,
                approvals.decide(owner, sessionId, rejectedId, MemorySaveApproval.Decision.APPROVE).state());
        assertTrue(memory.list(owner).isEmpty());

        UUID acceptedId = UUID.fromString(approvals.create(session, proposal).id());
        assertEquals(content, approvals.get(owner, sessionId, acceptedId).content());
        assertEquals(MemorySaveApproval.State.SAVED,
                approvals.decide(owner, sessionId, acceptedId, MemorySaveApproval.Decision.APPROVE).state());
        assertEquals(MemorySaveApproval.State.SAVED,
                approvals.decide(owner, sessionId, acceptedId, MemorySaveApproval.Decision.APPROVE).state());
        assertEquals(List.of(content), memory.list(owner).stream().map(LongTermMemoryItem::getContent).toList());
        assertNull(approvals.get(owner, sessionId, acceptedId).content());
        assertEquals(MemorySaveApproval.State.EXPIRED,
                approvals.decide(owner, sessionId, UUID.randomUUID(), MemorySaveApproval.Decision.APPROVE).state());
    }

    /** 通过 {@code UUID.randomUUID}、{@code store.append} 驱动该场景，布尔状态符合断言，返回的数据、边界或顺序符合断言。 */
    @Test void ragHistorySurvivesStoreRecreationAndIsolatesUserAndKnowledgeBase() {
        var store = new RedisConversationStore(redis, mapper);
        var key = new ConversationKey(1, 1, UUID.randomUUID().toString());
        ChatMessage user = new ChatMessage(); user.setRole("user"); user.setContent("question");
        ChatMessage assistant = new ChatMessage(); assistant.setRole("assistant"); assistant.setContent("answer");
        store.append(key, user, assistant);
        assertEquals(List.of("question", "answer"), new RedisConversationStore(redis, mapper)
                .loadHistory(key, 10).stream().map(ChatMessage::getContent).toList());
        assertEquals("answer", store.loadHistory(key, 1).get(0).getContent());
        assertTrue(store.loadHistory(new ConversationKey(2, 1, key.conversationId()), 10).isEmpty());
        assertTrue(store.loadHistory(new ConversationKey(1, 2, key.conversationId()), 10).isEmpty());
    }

    /** 通过 {@code sessions.create}、{@code sessions.saveSummary} 驱动用例，抛出 {@code IllegalStateException}，布尔状态符合断言。 */
    @Test void sessionDataActuallyExpiresAndLateWritesCannotRestoreIt() throws Exception {
        AgentSession original = session(101L);
        long now = System.currentTimeMillis();
        AgentSession expiring = original.updated("expires", now, now + 1200);
        sessions.create(expiring);
        sessions.saveSummary(expiring.id(), MemoryItem.builder().content("summary").build());
        assertTrue(sessions.beginTurn(expiring.id(), "expiry"));
        complete(expiring, "expiry");
        sessions.endTurn(expiring.id(), "expiry");
        Thread.sleep(1500);
        assertTrue(sessions.get(expiring.id()).isEmpty());
        assertTrue(sessions.list(expiring.userId(), 0, 10).isEmpty());
        assertEquals(0, sessions.countTurns(expiring.id()));
        assertEquals(0, sessions.countMessages(expiring.id()));
        assertTrue(sessions.summary(expiring.id()).isEmpty());
        sessions.saveSummary(expiring.id(), MemoryItem.builder().content("late").build());
        assertFalse(sessions.beginTurn(expiring.id(), "late"));
        assertThrows(IllegalStateException.class, () -> complete(expiring, "late"));
        for (String suffix : List.of("meta", "turns", "messages", "summary")) {
            assertFalse(Boolean.TRUE.equals(redis.hasKey("agent:session:" + expiring.id() + ":" + suffix)));
        }
    }

    /** 为同一用户创建不同有效期的会话，确认短会话到期时长会话目录仍可读取。 */
    @Test void shorterSessionCannotExpireTheCatalogOfALongerSession() {
        AgentSession longer = session(102L);
        sessions.create(longer);
        long now = System.currentTimeMillis();
        AgentSession shorter = session(102L).updated("short", now, now + 10000);
        sessions.create(shorter);
        String catalog = "agent:session:user:" + longer.userId();
        assertTrue(redis.getExpire(catalog, TimeUnit.MILLISECONDS) > 50000,
                "creating a shorter session must preserve the longer session's catalog");
        assertTrue(sessions.beginTurn(shorter.id(), "short"));
        complete(shorter, "short");
        sessions.endTurn(shorter.id(), "short");
        assertTrue(redis.getExpire(catalog, TimeUnit.MILLISECONDS) > 50000,
                "completing a shorter session must preserve the longer session's catalog");
        assertEquals(2, sessions.list(longer.userId(), 0, 10).size());
    }

    /** 用相互独立的 JVM 竞争同一会话，核对第二个节点能读取已提交的完整回合。 */
    @Test void independentJvmsCoordinateTheLeaseAndReadCommittedHistory() throws Exception {
        AgentSession session = session(103L);
        sessions.create(session);
        String gate = "ekip:test:gate:" + session.id();
        Process first = worker(session.id(), "begin", "first", gate);
        Process second = worker(session.id(), "begin", "second", gate);
        try {
            long deadline = System.currentTimeMillis() + 15000;
            while (redis.opsForSet().size(gate + ":ready") < 2 && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(2L, redis.opsForSet().size(gate + ":ready"));
            redis.opsForValue().set(gate + ":go", "1");
            boolean firstWon = output(first).contains("ACQUIRED true");
            boolean secondWon = output(second).contains("ACQUIRED true");
            assertNotEquals(firstWon, secondWon, "exactly one JVM must own the active turn");
            String winner = firstWon ? "first" : "second";
            sessions.endTurn(session.id(), firstWon ? "second" : "first");
            assertFalse(sessions.beginTurn(session.id(), "third"));
            assertFalse(sessions.delete(session.id(), session.userId()));
            sessions.saveSummary(session.id(), MemoryItem.builder().content("summary").build());
            complete(session, winner);
            Process reader = worker(session.id(), "read", "unused", gate);
            try {
                assertTrue(output(reader).contains("VISIBLE 1 2 true"));
            } finally {
                if (reader.isAlive()) reader.destroyForcibly();
            }
            sessions.endTurn(session.id(), winner);
            assertTrue(sessions.delete(session.id(), session.userId()));
        } finally {
            if (first.isAlive()) first.destroyForcibly();
            if (second.isAlive()) second.destroyForcibly();
        }
    }

    /**
     * 在该类型内部准备后续运行所需的数据。
     *
     * @param id 本次调用传入的 {@code String}
     * @param operation 本次调用传入的 {@code String}
     * @param token 当前操作的令牌
     * @param gate 本次调用传入的 {@code String}
     * @return 调用方使用的 {@code Process}
     */
    private Process worker(String id, String operation, String token, String gate) throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-cp", classpath, SessionWorker.class.getName(), id, operation, token, gate)
                .redirectErrorStream(true).start();
    }

    /** 通过 {@code process.waitFor}、{@code process.exitValue} 驱动该场景，布尔状态符合断言，实际结果与固定预期一致。 */
    private String output(Process process) throws Exception {
        assertTrue(process.waitFor(20, TimeUnit.SECONDS), "isolated JVM did not finish");
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
        return output;
    }

    public static class SessionWorker {
        /**
         * 协调 {@code test.connectToOwnedRedis} 完成当前阶段，供后续处理使用。
         * @param args 本次调用传入的 {@code String[]}
         */
        public static void main(String[] args) throws Exception {
            var test = new RedisStoreIntegrationTest();
            try {
                test.connectToOwnedRedis();
                if ("begin".equals(args[1])) {
                    test.redis.opsForSet().add(args[3] + ":ready", args[2]);
                    long deadline = System.currentTimeMillis() + 20000;
                    while (!Boolean.TRUE.equals(test.redis.hasKey(args[3] + ":go"))) {
                        if (System.currentTimeMillis() >= deadline) throw new IllegalStateException("barrier expired");
                        Thread.sleep(10);
                    }
                    System.out.println("ACQUIRED " + test.sessions.beginTurn(args[0], args[2]));
                } else if ("read".equals(args[1])) {
                    System.out.println("VISIBLE " + test.sessions.countTurns(args[0]) + " "
                            + test.sessions.countMessages(args[0]) + " " + test.sessions.summary(args[0]).isPresent());
                } else {
                    throw new IllegalArgumentException("unknown worker operation");
                }
            } finally {
                test.disconnect();
            }
        }
    }

    /**
     * 协调 {@code UUID.randomUUID} 完成当前阶段，供后续处理使用。
     * @param user 本次调用传入的 {@code Long}
     * @return 带所有者和策略的会话元数据
     */
    private AgentSession session(Long user) {
        long now = System.currentTimeMillis();
        return new AgentSession("as_" + UUID.randomUUID(), user, 1L, "policy", "grounded", "title", now, now, now + 60000);
    }

    /**
     * 协调 {@code sessions.completeTurn} 完成当前阶段，供后续处理使用。
     * @param session 会话元数据及归属信息
     * @param token 当前操作的令牌
     */
    private void complete(AgentSession session, String token) {
        sessions.completeTurn(session, token, new AgentSessionTurn(UUID.randomUUID().toString(), "question", "answer",
                List.of(), List.of(), "success", null, StopReason.FINAL_ANSWER, false, null, false, 1, 0, 0, 0,
                System.currentTimeMillis()), List.of(AgentMessage.builder().role("user").content("question").build(),
                AgentMessage.builder().role("assistant").content("answer").build()));
    }
}
