/* 维护审计事件的稳定操作码，供业务写入和审计查询使用。 */

package com.pony.knowledge.audit;

/**
 * 审计动作名，点分命名 {@code <area>.<event>}（与本项目其他枚举一样保留 code，不写 ordinal）。
 *
 * <p>只登记**当前真实埋点**的动作。新增埋点时同时改这里和 {@code audit_log} 的查询习惯，
 * 不要预先铺一堆没人写的枚举值 —— 枚举里存在但永远不出现的动作，会让「查不到」被误判成「没发生」。
 */
public enum AuditAction {

    ACCOUNT_STATUS_CHANGED("account.status_changed"),

    /** 知识库创建操作，outcome 区分提交成功与拒绝/失败。 */
    KB_CREATED("kb.created"),

    KB_UPDATED("kb.updated"),

    KB_DELETED("kb.deleted"),

    /** 知识库成员新增。 */
    KB_MEMBER_ADDED("rbac.member_added"),

    /** 知识库成员移除。 */
    KB_MEMBER_REMOVED("rbac.member_removed"),

    /**
     * 文档上传受理操作；success 表示新建文档记录已提交，幂等复用不重复记录。
     *
     * <p>2026-10-23 由 {@code knowledge.created} 改为 {@code document.uploaded}：旧名与
     * {@link #KB_CREATED}（{@code kb.created}）在按动作名聚合时几乎无法区分，而
     * {@code target_type=document} 才是真正的判据。**改名前的历史行仍为 {@code knowledge.created}**，
     * 追溯查询要同时匹配两个值（审计表 append-only，不回写历史）。
     */
    DOCUMENT_UPLOADED("document.uploaded"),

    /** 文档删除操作；success 表示数据库删除已提交。 */
    KNOWLEDGE_DELETED("knowledge.deleted"),

    /** MCP API Key 签发（成功只记 id、公开名称与显示前缀，密钥正文不入审计）。 */
    API_KEY_CREATED("system.api_key_created"),

    /** MCP API Key 吊销。 */
    API_KEY_REVOKED("system.api_key_revoked"),

    AGENT_MEMORY_APPROVAL_DECIDED("agent.memory_approval_decided");

    private final String code;

    /**
     * 为枚举值绑定供持久化或接口展示使用的编码。
     *
     * @param code 持久化或对外公开的状态码
     */
    AuditAction(String code) {
        this.code = code;
    }

    /**
     * 返回持久化或对外的状态码。
     * 上游为 AuditLogService.record。
     *
     * @return 持久化或对外的状态码
     */
    public String getCode() {
        return code;
    }
}
