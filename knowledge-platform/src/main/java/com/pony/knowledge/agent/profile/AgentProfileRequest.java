/* 承载创建或更新自建 Agent 配置的接口字段。 */

package com.pony.knowledge.agent.profile;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** 创建 / 更新 Agent 配置的请求体；所有字段都只作用于当前认证用户自己的配置。 */
@Data
public class AgentProfileRequest {

    @NotBlank(message = "Agent 名称不能为空")
    @Size(max = 100, message = "Agent 名称不能超过 100 个字符")
    private String name;

    /** Optional owned chat model; overrides the user agent-purpose binding. */
    private String chatModelId;

    @Size(max = 8000, message = "systemPrompt 不能超过 8000 个字符")
    private String systemPrompt;

    /** 省略时使用 grounded，兼容已有调用方。 */
    private String answerPolicy;

    /** 省略或旧配置按 true 兼容。 */
    private Boolean memoryReadEnabled;

    private Boolean memoryWriteEnabled;

    private Boolean memorySuggestionsEnabled;

    @NotBlank(message = "kbSelectionMode 不能为空")
    private String kbSelectionMode;

    @Size(max = 200, message = "knowledgeBaseIds 数量过多")
    private List<Long> knowledgeBaseIds;

    @Size(max = 32, message = "allowedTools 数量过多")
    private List<String> allowedTools;
}
