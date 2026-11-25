/* 关联记忆申请与已完成回合，并原子消费用户确认，记录处理结果。 */

package com.pony.knowledge.agent.tool.approval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pony.knowledge.agent.memory.longterm.LongTermMemoryService;
import com.pony.knowledge.agent.session.AgentSession;
import com.pony.knowledge.agent.session.AgentSessionService;
import com.pony.knowledge.agent.tool.approval.MemorySaveApproval.Decision;
import com.pony.knowledge.agent.tool.approval.MemorySaveApproval.State;
import com.pony.knowledge.audit.AuditAction;
import com.pony.knowledge.audit.AuditLogService;
import com.pony.knowledge.audit.AuditOutcome;
import com.pony.knowledge.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class MemorySaveApprovalService {
    private static final Logger log = LoggerFactory.getLogger(MemorySaveApprovalService.class);
    private static final DefaultRedisScript<Long> TRANSITION = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
            redis.call('PSETEX', KEYS[1], ARGV[3], ARGV[2])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final AgentSessionService sessions;
    private final LongTermMemoryService memory;
    private final AuditLogService audit;
    private final Duration pendingTtl;

    /**
     * 用注入依赖和构造参数建立此实例执行时使用的状态。
     * 下游调用 {@code Duration.ofMinutes}。
     *
     * @param redis 当前模块使用的 Redis 客户端
     * @param json 本次调用传入的 {@code ObjectMapper}
     * @param sessions 本次调用传入的 {@code AgentSessionService}
     * @param memory 本次调用传入的 {@code LongTermMemoryService}
     * @param audit 本次调用传入的 {@code AuditLogService}
     * @param ttlMinutes 本次调用传入的 {@code long}
     */
    public MemorySaveApprovalService(StringRedisTemplate redis, ObjectMapper json, AgentSessionService sessions,
                                     LongTermMemoryService memory, AuditLogService audit,
                                     @Value("${agent.tool.approval.ttl-minutes:30}") long ttlMinutes) {
        this.redis = redis;
        this.json = json;
        this.sessions = sessions;
        this.memory = memory;
        this.audit = audit;
        this.pendingTtl = Duration.ofMinutes(Math.max(1, ttlMinutes));
    }

    /**
     * 将待保存内容写入与会话、有效期绑定的申请，并返回页面视图。
     * 上游由 {@code AgentService.executeTurn} 调用。
     * 下游调用 {@code UUID.randomUUID}、{@code pendingTtl.toMillis}、{@code proposal.content}。
     *
     * @param session 会话元数据及归属信息
     * @param proposal 尚待用户确认的长期记忆建议
     * @return 长期记忆申请及其当前状态
     */
    public MemorySaveApproval create(AgentSession session, MemorySaveApproval.Proposal proposal) {
        String id = UUID.randomUUID().toString();
        long expiresAt = System.currentTimeMillis() + pendingTtl.toMillis();
        Entry entry = new Entry(proposal.content(), proposal.kind(), State.PENDING, expiresAt);
        redis.opsForValue().set(key(session, id), serialize(entry), pendingTtl);
        return entry.view(id);
    }

    /**
     * 复核申请所属会话的读取权限，按申请 ID 返回当前状态。
     * 上游由 {@code MemorySaveApprovalController.get} 调用。
     * 下游调用 {@code sessions.requireAuthorizedSession}、{@code redis.opsForValue}、{@code view}。
     *
     * @param actor 执行审批或管理操作的认证用户 ID
     * @param sessionId 受当前用户与策略约束的服务端会话 ID
     * @param id 本次调用传入的 {@code UUID}
     * @return 长期记忆申请及其当前状态
     */
    public MemorySaveApproval get(Long actor, String sessionId, UUID id) {
        AgentSession session = sessions.requireAuthorizedSession(actor, sessionId);
        return view(id.toString(), read(redis.opsForValue().get(key(session, id.toString()))));
    }

    /**
     * 复核会话并获取回合槽后提交用户决定，防止审批与会话删除或新回合交错执行。
     * 上游由 {@code MemorySaveApprovalController.decide} 调用。
     * 下游调用 {@code AgentSessionService.requireAuthorizedSession}、{@code AgentSessionService.begin}、{@code MemorySaveApprovalService.decidePending}。
     *
     * @param actor 执行审批或管理操作的认证用户 ID
     * @param sessionId 受当前用户与策略约束的服务端会话 ID
     * @param approvalId 目标审批申请 ID
     * @param decision 待处理的决策及其终态信息
     * @return 长期记忆申请及其当前状态
     */
    public MemorySaveApproval decide(Long actor, String sessionId, UUID approvalId, Decision decision) {
        Objects.requireNonNull(decision);
        AgentSession session = sessions.requireAuthorizedSession(actor, sessionId);
        String id = approvalId.toString();
        String key = key(session, id);
        Entry existing = read(redis.opsForValue().get(key));
        if (existing == null || existing.state() != State.PENDING) return view(id, existing);

        // Use the session lock to serialize against other turns and session deletion.
        AgentSessionService.TurnHandle handle = sessions.begin(actor, sessionId, session.agentId(), session.fingerprint());
        try {
            sessions.requireAuthorizedSession(actor, sessionId);
            return decidePending(handle.session(), id, key, decision);
        } finally {
            sessions.end(handle);
        }
    }

    /**
     * 通过 Redis 原子状态迁移认领待处理申请，批准后写入长期记忆并记录最终决定。
     * 上游由 {@code MemorySaveApprovalService.decide} 调用。
     * 下游调用 {@code redis.opsForValue}、{@code entry.state}、{@code memory.isEnabled}。
     *
     * @param session 会话元数据及归属信息
     * @param id 本次调用传入的 {@code String}
     * @param key 用于访问当前数据的存储键
     * @param decision 待处理的决策及其终态信息
     * @return 长期记忆申请及其当前状态
     */
    private MemorySaveApproval decidePending(AgentSession session, String id, String key, Decision decision) {
        String original = redis.opsForValue().get(key);
        Entry entry = read(original);
        if (entry == null || entry.state() != State.PENDING) return view(id, entry);
        if (decision == Decision.APPROVE && (!memory.isEnabled() || entry.content().length() > memory.maxContentChars())) {
            throw new BusinessException("长期记忆设置已变化，无法保存本次内容");
        }

        // A claimed request has no writable payload. Duplicate decisions can only read its status.
        State claimed = decision == Decision.REJECT ? State.REJECTED : State.PROCESSING;
        Entry locked = entry.withState(claimed);
        String lockedValue = serialize(locked);
        long resultTtl = Math.max(1, session.expiresAt() - System.currentTimeMillis());
        if (!transition(key, original, lockedValue, resultTtl)) {
            return view(id, read(redis.opsForValue().get(key)));
        }
        if (claimed == State.REJECTED) {
            recordDecision(id, decision, State.REJECTED, AuditOutcome.DENIED);
            return locked.view(id);
        }

        // 只有成功抢占待处理状态的一方可进入实际记忆写入。
        State result;
        try {
            boolean saved = memory.remember(session.userId(), entry.content(), entry.kind(), session.memoryId());
            result = saved ? State.SAVED : State.DUPLICATE;
        } catch (RuntimeException failure) {
            // The Redis write may have completed before a connection error. The approval stays consumed.
            log.warn("Agent memory write outcome could not be confirmed; approvalId={}", id);
            result = State.FAILED;
        }


        // 记忆写入后记录业务审计，再把审批项过渡到可查询的最终状态。
        Entry completed = entry.withState(result);
        recordDecision(id, decision, result, result == State.FAILED ? AuditOutcome.FAILED : AuditOutcome.SUCCESS);
        transition(key, lockedValue, serialize(completed), resultTtl);
        return completed.view(id);
    }

    /**
     * 将申请标识、用户决定与处理状态写入审计记录。
     * 上游由 {@code MemorySaveApprovalService.decidePending} 调用。
     * 下游调用 {@code audit.record}、{@code AuditLogService.details}、{@code decision.name}。
     *
     * @param id 本次调用传入的 {@code String}
     * @param decision 待处理的决策及其终态信息
     * @param result 此次工具或管线的结果
     * @param outcome 问答或检索的业务终态
     */
    private void recordDecision(String id, Decision decision, State result, AuditOutcome outcome) {
        audit.record(AuditAction.AGENT_MEMORY_APPROVAL_DECIDED, "agent_memory_approval", id, outcome,
                AuditLogService.details("tool", "memory_save", "decision", decision.name(), "result", result.name()));
    }

    /**
     * 按原序列化状态比较并替换 Redis 记录，阻止重复消费。
     * 上游由 {@code MemorySaveApprovalService.decidePending} 调用。
     * 下游调用 {@code redis.execute}。
     *
     * @param key 用于访问当前数据的存储键
     * @param original 本次调用传入的 {@code String}
     * @param target 本次调用传入的 {@code String}
     * @param ttlMs 保存记录的存活时长，单位毫秒
     * @return 条件成立时为 true，其他状态按 false 处理
     */
    private boolean transition(String key, String original, String target, long ttlMs) {
        return Long.valueOf(1L).equals(redis.execute(TRANSITION, List.of(key), original, target, Long.toString(ttlMs)));
    }

    /**
     * 将审批状态及其数据编码为 Redis 可比较的原始 JSON。
     * 上游由 {@code MemorySaveApprovalService.create}、{@code MemorySaveApprovalService.decidePending} 调用。
     * 下游调用 {@code json.writeValueAsString}。
     *
     * @param entry 本次调用传入的 {@code Entry}
     * @return 供后续处理使用的文本或标识
     */
    private String serialize(Entry entry) {
        try {
            return json.writeValueAsString(entry);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法编码长期记忆审批状态");
        }
    }

    /**
     * 将 Redis 中的审批状态解码为申请记录，缺失时返回空值。
     * 上游由 {@code MemorySaveApprovalService.decide}、{@code MemorySaveApprovalService.decidePending}、{@code MemorySaveApprovalService.get} 调用。
     * 下游调用 {@code json.readValue}。
     *
     * @param value 本次调用传入的 {@code String}
     * @return 调用方使用的 {@code Entry}
     */
    private Entry read(String value) {
        if (value == null) return null;
        try {
            return json.readValue(value, Entry.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法读取长期记忆审批状态");
        }
    }

    /**
     * 根据会话归属和申请标识构造隔离的审批键。
     * 上游由 {@code MemorySaveApprovalService.create}、{@code MemorySaveApprovalService.decide}、{@code MemorySaveApprovalService.get} 调用。
     * 下游调用 {@code session.userId}、{@code session.id}。
     *
     * @param session 会话元数据及归属信息
     * @param approvalId 目标审批申请 ID
     * @return 供后续处理使用的文本或标识
     */
    private static String key(AgentSession session, String approvalId) {
        return "agent:memory:approval:" + session.userId() + ":" + session.id() + ":" + approvalId;
    }

    /**
     * 将内部记录转换成页面可读申请，缺失记录用过期状态表达。
     * 上游由 {@code MemorySaveApprovalService.decide}、{@code MemorySaveApprovalService.decidePending}、{@code MemorySaveApprovalService.get} 调用。
     * 下游调用 {@code entry.view}。
     *
     * @param id 本次调用传入的 {@code String}
     * @param entry 本次调用传入的 {@code Entry}
     * @return 长期记忆申请及其当前状态
     */
    private static MemorySaveApproval view(String id, Entry entry) {
        return entry == null ? new MemorySaveApproval(id, null, null, State.EXPIRED, 0) : entry.view(id);
    }

    private record Entry(String content, String kind, State state, long expiresAt) {
        /**
         * 把目标申请 ID 及当前状态包装成 API 返回视图。
         * 上游由 {@code MemorySaveApprovalService.create}、{@code MemorySaveApprovalService.decidePending}、{@code MemorySaveApprovalService.view} 调用。
         *
         * @param id 本次调用传入的 {@code String}
         * @return 长期记忆申请及其当前状态
         */
        MemorySaveApproval view(String id) { return new MemorySaveApproval(id, content, kind, state, expiresAt); }

        /**
         * 按新的审批状态生成记录副本，并应用正文保留规则。
         * 上游由 {@code MemorySaveApprovalService.decidePending} 调用。
         *
         * @param next 本次调用传入的 {@code State}
         * @return 调用方使用的 {@code Entry}
         */
        Entry withState(State next) { return new Entry(null, null, next, expiresAt); }
    }
}
