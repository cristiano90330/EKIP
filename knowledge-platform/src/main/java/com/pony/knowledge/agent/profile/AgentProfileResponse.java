/* 承载自建 Agent 配置的公开返回字段。 */

package com.pony.knowledge.agent.profile;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** Agent 配置的对外视图；不包含用户 ID 等身份字段。 */
@Data
@Builder
public class AgentProfileResponse {

    private Long id;

    private String name;

    /** Optional owned chat model; overrides the user agent-purpose binding. */
    private String chatModelId;

    private String systemPrompt;

    private String answerPolicy;

    /** 省略或旧配置按 true 兼容。 */
    private Boolean memoryReadEnabled;

    private Boolean memoryWriteEnabled;

    private boolean memorySuggestionsEnabled;

    private String kbSelectionMode;

    private List<Long> knowledgeBaseIds;

    private List<String> allowedTools;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
