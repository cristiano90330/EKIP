/* 区分文档任务的临时故障和永久故障。 */

package com.pony.knowledge.service.task;

/** 任务失败分类：TRANSIENT 走自动退避重试；PERMANENT 直接终态（FAILED 等手动 retry）。 */
public enum TaskFailureKind {
    TRANSIENT,
    PERMANENT
}