/* 保存服务端 Agent 会话身份、策略指纹和生命周期元数据。 */

package com.pony.knowledge.agent.session;

/** 内部会话元数据；fingerprint/owner 不作为 API 字段公开。 */
public record AgentSession(String id, Long userId, Long agentId, String fingerprint, String answerPolicy, String title,
                           long createdAt, long updatedAt, long expiresAt) {
    public static final String PUBLIC_PREFIX = "as_";
    public static final String PUBLIC_ID_PATTERN = PUBLIC_PREFIX + "[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}";
    public static final String MEMORY_PREFIX = "managed:";

    /**
     * 构造与服务端产品会话关联的内部记忆键。
     * 上游由 {@code MemorySaveApprovalService.decidePending} 调用。
     *
     * @return 供后续处理使用的文本或标识
     */
    public String memoryId() { return MEMORY_PREFIX + id; }

    /**
     * 更新当前会话的标题、活动时间及有效期，同时保留归属和既有策略。
     * 上游由 {@code AgentSessionService.complete} 调用。
     *
     * @param nextTitle 本次调用传入的 {@code String}
     * @param now 本次调用传入的 {@code long}
     * @param expiry 本次调用传入的 {@code long}
     * @return 带所有者和策略的会话元数据
     */
    public AgentSession updated(String nextTitle, long now, long expiry) {
        return new AgentSession(id, userId, agentId, fingerprint, answerPolicy, nextTitle, createdAt, now, expiry);
    }

    /**
     * 从会话元数据构造可返回目录或会话页面的视图。
     * 上游由 {@code AgentSessionService.create}、{@code AgentSessionService.history}、{@code AgentSessionService.list} 调用。
     *
     * @return 调用方使用的 {@code View}
     */
    public View view() { return new View(id, agentId, answerPolicy, title, createdAt, updatedAt, expiresAt); }

    public record View(String id, Long agentId, String answerPolicy, String title, long createdAt, long updatedAt, long expiresAt) { }
}
