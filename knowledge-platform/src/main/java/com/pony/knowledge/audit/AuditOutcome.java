/* 维护持久化审计记录可使用的结果码。 */

package com.pony.knowledge.audit;

/**
 * 审计结果，与 {@code audit_log.outcome} 的 CHECK 约束一一对应。
 *
 * <p>当前七类写操作在成功提交后记录 SUCCESS，在服务内拒绝或失败出口记录 DENIED / FAILED。
 * 其他枚举值保留为结果协议，不能据此推断已有对应埋点。
 */
public enum AuditOutcome {

    SUCCESS("success"),

    /** 异步操作已受理但未到终态。 */
    ACCEPTED("accepted"),

    /** 身份或权限拒绝。 */
    DENIED("denied"),

    /** 业务拒绝或依赖故障导致操作未完成。 */
    FAILED("failed"),

    /** 部分成功。 */
    PARTIAL("partial"),

    /** 人工取消。 */
    CANCELED("canceled");

    private final String code;

    /**
     * 为枚举值绑定供持久化或接口展示使用的编码。
     *
     * @param code 持久化或对外公开的状态码
     */
    AuditOutcome(String code) {
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
