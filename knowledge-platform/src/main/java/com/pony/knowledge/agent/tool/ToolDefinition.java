/* 记录工具名称、介绍、输入类型和风险属性。 */

package com.pony.knowledge.agent.tool;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * Tool 统一 Definition：name + description + 输入类型 + JSON-schema 参数。
 * parameters 由 ToolSchemaGenerator 从 inputClass 派生，供 LLM 提示词使用。
 * inputClass 只声明 LLM 能控制的字段 → 身份/权限字段天然不在 schema 里。
 */
@Data
@Builder
public class ToolDefinition {

    private String name;

    private String description;

    private Class<?> inputClass;

    /** JSON-schema 风格参数定义（type/properties/required） */
    private Map<String, Object> parameters;

    /**
     * 是否需人工审批（HITL）：true 的工具在执行前必须有一份精确匹配本次参数的审批
     * （ToolApprovalService.isApproved），否则 ToolExecutor fail-close 拒绝执行。
     * 只读工具（knowledge_search / knowledge_ask）为 false；接入外部 MCP 写操作时置 true。
     */
    @Builder.Default
    private boolean requiresApproval = false;
}
