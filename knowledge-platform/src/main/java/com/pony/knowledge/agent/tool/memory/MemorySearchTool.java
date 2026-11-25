/* 按可信用户身份查询长期记忆并返回匹配条目。 */

package com.pony.knowledge.agent.tool.memory;

import com.pony.knowledge.agent.memory.longterm.LongTermMemoryItem;
import com.pony.knowledge.agent.memory.longterm.LongTermMemoryService;
import com.pony.knowledge.agent.model.AgentExecutionContext;
import com.pony.knowledge.agent.tool.AgentTool;
import com.pony.knowledge.agent.tool.ToolDefinition;
import com.pony.knowledge.agent.tool.ToolSchemaGenerator;
import com.pony.knowledge.agent.tool.input.MemorySearchInput;
import com.pony.knowledge.agent.tool.input.ToolInput;
import com.pony.knowledge.agent.tool.output.MemorySearchOutput;
import com.pony.knowledge.agent.tool.output.ToolResult;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 检索长期记忆（当前用户自己的条目）。
 *
 * <p>与 {@code knowledge_search} 的分工写在描述里：前者查「用户是谁、有什么偏好」，
 * 后者查「企业知识库里有什么」。混用会让模型用一条旧偏好替代制度检索。
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent.memory.long-term.enabled", havingValue = "true", matchIfMissing = true)
public class MemorySearchTool implements AgentTool {

    private static final int DEFAULT_LIMIT = 5;

    private final LongTermMemoryService memoryService;

    /**
     * 登记允许模型选择的记忆查询文本及最大结果条数。
     * 上游由 {@code MemorySearchTool.execute} 调用。
     * 下游调用 {@code ToolSchemaGenerator.schemaOf}。
     *
     * @return 公开给模型或页面目录的工具定义
     */
    @Override
    public ToolDefinition definition() {
        return ToolDefinition.builder()
                .name("memory_search")
                .description("Search the user's own long-term memory (facts and preferences they asked "
                        + "to remember in earlier sessions). Use it for user profile questions; "
                        + "For recall questions repeat matching item.content verbatim, preserving identifiers and numbers. "
                        + "Items have no knowledge citation IDs; do not invent [[cite:...]] markers. "
                        + "These are user-reported statements and may be outdated; prefer knowledge-base evidence on conflicts. "
                        + "For enterprise documents use knowledge_search instead.")
                .inputClass(MemorySearchInput.class)
                .parameters(ToolSchemaGenerator.schemaOf(MemorySearchInput.class))
                .build();
    }

    /**
     * 按本轮可信用户身份查询记忆，返回匹配条目及可直接呈现的答案。
     * 上游由 {@code ToolExecutor（执行 memory_search）} 调用。
     * 下游调用 {@code context.isMemoryReadEnabled}、{@code memoryService.isEnabled}、{@code memoryService.recall}。
     *
     * @param input 本次操作的结构化输入
     * @param context 可信执行上下文，包含身份、范围和本轮执行状态
     * @return 带载荷或可识别错误状态的工具结果
     */
    @Override
    public ToolResult execute(ToolInput input, AgentExecutionContext context) {
        if (!context.isMemoryReadEnabled() || !memoryService.isEnabled()) {
            return ToolResult.builder().toolName(definition().getName()).success(false)
                    .errorMessage("长期记忆读取已停用").retryable(false).build();
        }

        MemorySearchInput search = (MemorySearchInput) input;
        int limit = search.getLimit() == null ? DEFAULT_LIMIT : search.getLimit();

        List<LongTermMemoryItem> found = memoryService.recall(context.getUserId(), search.getQuery(), limit);
        List<MemorySearchOutput.Item> items = new ArrayList<>(found.size());
        for (LongTermMemoryItem item : found) {
            items.add(MemorySearchOutput.Item.builder()
                    .kind(item.getKind())
                    .content(item.getContent())
                    .build());
        }

        return ToolResult.builder()
                .toolName(definition().getName())
                .success(true)
                .data(MemorySearchOutput.builder().query(search.getQuery()).items(items).build())
                .directAnswer(items.isEmpty() ? "没有找到匹配的长期记忆。"
                        : "查询到的用户自述（可能已过期）：\n" + items.stream()
                                .map(item -> "- " + item.getContent()).collect(Collectors.joining("\n")))
                .build();
    }
}
