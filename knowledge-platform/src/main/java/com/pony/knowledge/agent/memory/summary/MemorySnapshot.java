/* 承载一次摘要所需的消息批次、前次摘要和会话位置。 */

package com.pony.knowledge.agent.memory.summary;

import com.pony.knowledge.agent.model.AgentMessage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 会话在某一时刻的快照：把当前要处理的最近消息窗口、已有摘要与 token 估算
 * 收敛成一个不可变描述，供 SummaryService 摘要、SummaryPolicy 决策、ContextBuilder 渲染。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class MemorySnapshot {

    private String sessionId;

    /** Explicit non-secret model ownership for background execution. */
    private Long ownerId;

    private String summaryModelId;

    /** 本快照承载的消息窗口（待摘要或待渲染） */
    private List<AgentMessage> messages;

    /** 已落库的运行中摘要（可为 null） */
    private ConversationSummary summary;

    /** 本快照 token 估算（消息 + 摘要） */
    private int totalTokens;

    /** 消息条数 */
    private int messageCount;
}
