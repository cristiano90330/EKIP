/* 管理 Agent 会话授权、策略复核、回合互斥、历史与提交。 */

package com.pony.knowledge.agent.session;

import com.pony.knowledge.agent.model.AgentMessage;
import com.pony.knowledge.agent.model.AgentResponse;
import com.pony.knowledge.agent.model.StopReason;
import com.pony.knowledge.agent.model.ToolSteps;
import com.pony.knowledge.agent.profile.AgentProfileService;
import com.pony.knowledge.agent.profile.ResolvedAgentProfile;
import com.pony.knowledge.exception.BusinessException;
import com.pony.knowledge.exception.ForbiddenException;
import com.pony.knowledge.service.KnowledgeBaseAccessService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/** 认证边界由 Controller/AgentService 提供；读历史与续聊都重新计算权限和策略。 */
@Service
public class AgentSessionService {
    private final AgentSessionStore store;
    private final AgentProfileService profiles;
    private final KnowledgeBaseAccessService access;
    private final long retentionMs;

    @Value("${agent.memory.long-term.enabled:true}")
    private boolean longTermMemoryEnabled = true;

    /**
     * 用注入依赖和构造参数建立此实例执行时使用的状态。
     * 下游调用 {@code Duration.ofDays}。
     *
     * @param store 负责持久化当前实体的存储接口
     * @param profiles 本次调用传入的 {@code AgentProfileService}
     * @param access 本次调用传入的 {@code KnowledgeBaseAccessService}
     * @param retentionDays 会话或状态保留天数
     */
    public AgentSessionService(AgentSessionStore store, AgentProfileService profiles,
                               KnowledgeBaseAccessService access,
                               @Value("${agent.memory.session-retention-days:30}") int retentionDays) {
        if (retentionDays < 1 || retentionDays > 365) throw new IllegalArgumentException("会话保留天数必须在 1 到 365 之间");
        this.store = store; this.profiles = profiles; this.access = access;
        this.retentionMs = Duration.ofDays(retentionDays).toMillis();
    }

    /**
     * 根据用户与 Agent 当前策略签发带策略指纹及有效期的服务端会话，返回目录视图。
     * 上游由 {@code AgentSessionController.create} 调用。
     * 下游调用 {@code UUID.randomUUID}、{@code policy.fingerprint}、{@code policy.answerPolicy}。
     *
     * @param userId 当前用户 ID
     * @param agentId 自建 Agent ID，空值表示默认 Agent
     * @return 会话的客户端视图
     */
    public AgentSession.View create(Long userId, Long agentId) {
        PolicyBinding policy = currentPolicy(userId, agentId, AgentPolicyFingerprint.DEFAULT_ANSWER_POLICY);

        // 为会话统一生成创建时间和到期时间，并把当前策略固化到元数据。
        long now = System.currentTimeMillis();
        AgentSession session = new AgentSession(AgentSession.PUBLIC_PREFIX + UUID.randomUUID(), userId,
                agentId, policy.fingerprint(), policy.answerPolicy(), "新会话", now, now, now + retentionMs);
        store.create(session);
        return session.view();
    }

    /**
     * 从用户专属目录分页读取会话，复核每项的当前策略后返回可见条目和下页位置。
     * 上游由 {@code AgentSessionController.list} 调用。
     * 下游调用 {@code store.list}、{@code session.view}、{@code bounds}。
     *
     * @param userId 当前用户 ID
     * @param offset 从结果边界起算的分页偏移
     * @param limit 最多返回或保留的条数
     * @return 当前分页中的会话及后续偏移
     */
    public SessionPage list(Long userId, int offset, int limit) {
        bounds(offset, limit);
        List<AgentSession> candidates = store.list(userId, offset, limit);
        List<AgentSession.View> items = new ArrayList<>();
        for (AgentSession session : candidates) {
            try { validatePolicy(session); items.add(session.view()); }
            catch (BusinessException ignored) { /* 失权历史不在目录暴露标题；仍可按 ID 删除。 */ }
        }
        return new SessionPage(items, offset + candidates.size(), candidates.size() == limit);
    }

    /**
     * 先复核会话归属与策略，再倒序定位近端页，返回按会话顺序保存的回合。
     * 上游由 {@code AgentSessionController.history} 调用。
     * 下游调用 {@code store.countTurns}、{@code store.history}、{@code session.view}。
     *
     * @param userId 当前用户 ID
     * @param id 服务端签发的产品会话 ID
     * @param offset 从结果边界起算的分页偏移
     * @param limit 最多返回或保留的条数
     * @return 指定会话的回合记录和后续分页位置
     */
    public HistoryPage history(Long userId, String id, int offset, int limit) {
        bounds(offset, limit);
        AgentSession session = requireAuthorizedSession(userId, id);

        // 根据回合总数将近端偏移换成存储使用的起点，再按消息顺序取页。
        int total = store.countTurns(id);
        int end = Math.max(0, total - offset);
        int start = Math.max(0, end - limit);
        List<AgentSessionTurn> turns = store.history(id, start, end - start);
        return new HistoryPage(session.view(), turns, offset + turns.size(), start > 0);
    }

    /**
     * 校验归属后删除会话及关联数据；执行中的回合导致删除失败。
     * 上游由 {@code AgentSessionController.delete} 调用。
     * 下游调用 {@code store.delete}、{@code owned}。
     *
     * @param userId 当前用户 ID
     * @param id 服务端签发的产品会话 ID
     */
    public void delete(Long userId, String id) {
        owned(userId, id);
        if (!store.delete(id, userId)) throw new BusinessException("会话仍有正在执行的回合，请停止并等待结束后删除");
    }

    /**
     * 校验归属、Agent 与策略指纹，并用随机令牌获取该会话的单回合占用权。
     * 下游调用 {@code begin}、{@code owned}。
     *
     * @param userId 当前用户 ID
     * @param id 服务端签发的产品会话 ID
     * @param agentId 自建 Agent ID，空值表示默认 Agent
     * @param fingerprint 用于检测模型、权限或策略变化的绑定指纹
     * @return 会话回合占用句柄
     */
    public TurnHandle begin(Long userId, String id, Long agentId, String fingerprint) {
        return begin(owned(userId, id), agentId, fingerprint);
    }

    /**
     * 以会话保存的回答策略重算本轮配置，返回互斥回合句柄及实际执行策略。
     * 上游由 {@code AgentService.runTurn} 调用。
     * 下游调用 {@code resolvePolicy.apply}、{@code session.answerPolicy}、{@code policy.fingerprint}。
     *
     * @param userId 当前用户 ID
     * @param id 服务端签发的产品会话 ID
     * @param agentId 自建 Agent ID，空值表示默认 Agent
     * @param resolvePolicy 本次调用传入的 {@code Function<String, AgentPolicyFingerprint.Policy>}
     * @return 回合句柄和实际执行策略
     */
    public ExecutionTurn beginExecution(Long userId, String id, Long agentId,
                                        Function<String, AgentPolicyFingerprint.Policy> resolvePolicy) {
        AgentSession session = owned(userId, id);
        AgentPolicyFingerprint.Policy policy = resolvePolicy.apply(session.answerPolicy());
        return new ExecutionTurn(begin(session, agentId, policy.fingerprint()), policy);
    }

    /**
     * 校验归属、Agent 与策略指纹，并用随机令牌获取该会话的单回合占用权。
     * 下游调用 {@code session.agentId}、{@code session.fingerprint}、{@code UUID.randomUUID}。
     *
     * @param session 会话元数据及归属信息
     * @param agentId 自建 Agent ID，空值表示默认 Agent
     * @param fingerprint 用于检测模型、权限或策略变化的绑定指纹
     * @return 会话回合占用句柄
     */
    private TurnHandle begin(AgentSession session, Long agentId, String fingerprint) {
        if (!Objects.equals(agentId, session.agentId()) || !fingerprint.equals(session.fingerprint())) {
            throw new ForbiddenException("Agent 配置或知识库权限已变化，请创建新会话");
        }

        // 为本轮生成独立占用令牌，释放时可以核对持有者。
        String token = UUID.randomUUID().toString();
        if (!store.beginTurn(session.id(), token)) throw new BusinessException("会话已有执行中的回合或已过期，请稍后重试");
        return new TurnHandle(session, token);
    }

    /**
     * 将确认终态、批准引用、工具摘要及消息一起保存；缺少终态或已取消时结束提交。
     * 上游由 {@code AgentService.executeTurn} 调用。
     * 下游调用 {@code handle.session}、{@code session.title}、{@code UUID.randomUUID}。
     *
     * @param handle 持有本轮占用令牌的会话句柄
     * @param question 本轮用户提问
     * @param response 本次调用传入的 {@code AgentResponse}
     * @param messages 按照交互顺序提供的消息列表
     */
    public void complete(TurnHandle handle, String question, AgentResponse response, List<AgentMessage> messages) {
        // 只持久化 runtime 确认的终态；取消/断流不形成 user/assistant 历史。
        if (response.getStopReason() == null || response.getStopReason() == StopReason.CANCELLED) return;
        long now = System.currentTimeMillis();
        AgentSession session = handle.session();
        String title = "新会话".equals(session.title()) ? title(question) : session.title();

        // 带记忆申请时复用审批标识，让历史回合能关联页面中的待确认申请。
        String turnId = response.getMemoryApproval() == null ? UUID.randomUUID().toString() : response.getMemoryApproval().id();
        AgentSessionTurn turn = new AgentSessionTurn(turnId, question, response.getAnswer(),
                ToolSteps.from(response.getToolInvocations()), response.getSources() == null ? List.of() : response.getSources(),
                response.getOutcome(), response.getReasonCode(), response.getStopReason(), response.isDegraded(),
                response.getDegradeReason(), response.isSynthesized(), response.getIterations(), response.getToolCalls(),
                response.getPromptTokens(), response.getCompletionTokens(), now);
        store.completeTurn(session.updated(title, now, now + retentionMs), handle.token(), turn, messages);
    }

    /**
     * 用本轮持有的令牌释放会话回合槽，避免旧请求释放新的执行。
     * 上游由 {@code AgentService.runTurn}、{@code MemorySaveApprovalService.decide} 调用。
     * 下游调用 {@code store.endTurn}、{@code handle.session}、{@code handle.token}。
     *
     * @param handle 持有本轮占用令牌的会话句柄
     */
    public void end(TurnHandle handle) { store.endTurn(handle.session().id(), handle.token()); }

    /**
     * 联合校验会话归属和当前策略，返回可继续读取或执行的会话。
     * 上游由 {@code AgentSessionService.history}、{@code MemorySaveApprovalService.decide}、{@code MemorySaveApprovalService.get} 调用。
     * 下游调用 {@code owned}、{@code validatePolicy}。
     *
     * @param userId 当前用户 ID
     * @param id 服务端签发的产品会话 ID
     * @return 带所有者和策略的会话元数据
     */
    public AgentSession requireAuthorizedSession(Long userId, String id) {
        AgentSession session = owned(userId, id);
        validatePolicy(session);
        return session;
    }

    /**
     * 校验服务端会话标识及当前用户归属，返回会话元数据。
     * 上游由 {@code AgentSessionService.begin}、{@code AgentSessionService.beginExecution}、{@code AgentSessionService.delete} 调用。
     * 下游调用 {@code id.matches}、{@code session.userId}。
     *
     * @param userId 当前用户 ID
     * @param id 服务端签发的产品会话 ID
     * @return 带所有者和策略的会话元数据
     */
    private AgentSession owned(Long userId, String id) {
        if (id == null || !id.matches(AgentSession.PUBLIC_ID_PATTERN)) throw new ForbiddenException("会话不存在或无权访问");
        AgentSession session = store.get(id).orElseThrow(() -> new ForbiddenException("会话不存在或无权访问"));
        if (!userId.equals(session.userId())) throw new ForbiddenException("会话不存在或无权访问");
        return session;
    }

    /**
     * 按当前知识权限、模型及记忆开关重算会话指纹，变化时要求建立新会话。
     * 上游由 {@code AgentSessionService.list}、{@code AgentSessionService.requireAuthorizedSession} 调用。
     * 下游调用 {@code session.fingerprint}、{@code session.userId}、{@code session.agentId}。
     *
     * @param session 会话元数据及归属信息
     */
    private void validatePolicy(AgentSession session) {
        if (!session.fingerprint().equals(currentPolicy(session.userId(), session.agentId(), session.answerPolicy()).fingerprint())) {
            throw new ForbiddenException("Agent 配置或知识库权限已变化，请创建新会话");
        }
    }

    private com.pony.knowledge.modelmanagement.ModelRegistryService modelRegistry;

    /**
     * 注入模型注册服务，供生成和校验会话指纹时读取模型身份。
     *
     * @param models 读取模型用途及执行配置的注册服务
     */
    @org.springframework.beans.factory.annotation.Autowired
    public void setModelRegistry(com.pony.knowledge.modelmanagement.ModelRegistryService models) { this.modelRegistry=models; }

    /**
     * 解析当前 Agent、可读库和模型身份所对应的会话策略绑定。
     * 上游由 {@code AgentSessionService.create}、{@code AgentSessionService.validatePolicy} 调用。
     * 下游调用 {@code access.resolveReadableIds}、{@code profiles.resolveForExecution}、{@code AgentPolicyFingerprint.resolve}。
     *
     * @param userId 当前用户 ID
     * @param agentId 自建 Agent ID，空值表示默认 Agent
     * @param sessionAnswerPolicy 本次调用传入的 {@code String}
     * @return 调用方使用的 {@code PolicyBinding}
     */
    private PolicyBinding currentPolicy(Long userId, Long agentId, String sessionAnswerPolicy) {
        Set<Long> readable = access.resolveReadableIds(userId);
        ResolvedAgentProfile profile = agentId == null ? null : profiles.resolveForExecution(userId, agentId, readable);
        AgentPolicyFingerprint.Policy policy = AgentPolicyFingerprint.resolve(
                userId, profile, readable, longTermMemoryEnabled, sessionAnswerPolicy);
        if (modelRegistry != null) policy=AgentPolicyFingerprint.bindModel(policy,modelRegistry.agentIdentity(userId,profile == null ? null : profile.chatModelId()));
        return new PolicyBinding(policy.fingerprint(), policy.answerPolicy());
    }

    /**
     * 按首个问题的归一化文本生成有长度限制的会话标题。
     * 上游由 {@code AgentSessionService.complete} 调用。
     * 下游调用 {@code question.codePointCount}、{@code question.length}、{@code question.offsetByCodePoints}。
     *
     * @param question 本轮用户提问
     * @return 供后续处理使用的文本或标识
     */
    private static String title(String question) {
        if (question == null || question.isBlank()) return "新会话";
        int count = Math.min(question.codePointCount(0, question.length()), 40);
        return question.substring(0, question.offsetByCodePoints(0, count));
    }

    /**
     * 检查历史或目录分页的 offset 和 limit，非法范围直接抛出业务异常。
     * 上游由 {@code AgentSessionService.history}、{@code AgentSessionService.list} 调用。
     *
     * @param offset 从结果边界起算的分页偏移
     * @param limit 最多返回或保留的条数
     */
    private static void bounds(int offset, int limit) {
        if (offset < 0 || offset > 1000000 || limit < 1 || limit > 50) throw new BusinessException("分页参数超出范围");
    }

    public record TurnHandle(AgentSession session, String token) { }
    public record ExecutionTurn(TurnHandle handle, AgentPolicyFingerprint.Policy policy) { }
    private record PolicyBinding(String fingerprint, String answerPolicy) { }
    public record SessionPage(List<AgentSession.View> items, int nextOffset, boolean hasMore) { }
    public record HistoryPage(AgentSession.View session, List<AgentSessionTurn> items, int nextOffset, boolean hasMore) { }
}
