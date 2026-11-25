/* 映射自建 Agent 的所有者、配置、知识库范围、模型和工具白名单。 */

package com.pony.knowledge.agent.profile;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户自有的 Agent 配置。知识库 ID 与工具名以 JSON 文本保存，只表示配置上限；
 * 执行时会按当前可读范围与已注册工具重新核验。
 */
@Data
@TableName("agent_profile")
public class AgentProfile {

    private Long id;

    private Long userId;

    private String name;

    /** Optional owned chat model; overrides the user agent-purpose binding. */
    @TableField(updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
    private String chatModelId;

    private String systemPrompt;

    private String answerPolicy;

    /** 省略或旧配置按 true 兼容。 */
    private Boolean memoryReadEnabled;

    private Boolean memoryWriteEnabled;

    private Boolean memorySuggestionsEnabled;

    private String kbSelectionMode;

    @TableField("knowledge_base_ids")
    private String knowledgeBaseIdsJson;

    @TableField("allowed_tools")
    private String allowedToolsJson;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
