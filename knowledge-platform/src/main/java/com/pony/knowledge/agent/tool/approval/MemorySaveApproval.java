/* 保存一项拟写入长期记忆的会话申请及其状态。 */

package com.pony.knowledge.agent.tool.approval;

/** The exact memory write shown in the conversation and authorized by its owner. */
public record MemorySaveApproval(String id, String content, String kind, State state, long expiresAt) {
    public enum State { PENDING, PROCESSING, SAVED, DUPLICATE, REJECTED, FAILED, EXPIRED }

    public enum Decision { APPROVE, REJECT }

    public record Proposal(String content, String kind) { }
}
