/* 核对拟保存内容来自本轮明确用户意图，将长期记忆申请提交给确认流程。 */

package com.pony.knowledge.agent.tool.memory;

import com.pony.knowledge.agent.memory.longterm.LongTermMemoryService;
import com.pony.knowledge.agent.memory.longterm.LongTermMemoryItem;
import com.pony.knowledge.agent.model.AgentExecutionContext;
import com.pony.knowledge.agent.tool.AgentTool;
import com.pony.knowledge.agent.tool.ToolDefinition;
import com.pony.knowledge.agent.tool.ToolSchemaGenerator;
import com.pony.knowledge.agent.tool.approval.MemorySaveApproval;
import com.pony.knowledge.agent.tool.input.MemorySaveInput;
import com.pony.knowledge.agent.tool.input.ToolInput;
import com.pony.knowledge.agent.tool.output.ToolResult;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 提出长期记忆写入申请。只在用户**明确要求记住**时使用——这个约束写进了工具描述，
 * 因为一旦模型自行决定「什么值得记」，记忆库会在几轮对话里被无关内容填满，
 * 用户可在管理页纠正或删除已保存的条目。
 *
 * <p>{@code agent.memory.long-term.enabled=false} 时整个 Bean 不装配：工具从 registry 消失，
 * schema 也不再下发。比「装上了但执行时报未启用」更干净——模型不会看到一个永远失败的工具。
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent.memory.long-term.enabled", havingValue = "true", matchIfMissing = true)
public class MemorySaveTool implements AgentTool {

    private final LongTermMemoryService memoryService;

    /**
     * 登记长期记忆提议的参数类型和执行风险，供工具策略判断。
     * 上游由 {@code MemorySaveTool.execute} 调用。
     * 下游调用 {@code ToolSchemaGenerator.schemaOf}。
     *
     * @return 公开给模型或页面目录的工具定义
     */
    @Override
    public ToolDefinition definition() {
        return ToolDefinition.builder()
                .name("memory_save")
                .description("Remember a stable fact or preference about the user across sessions. "
                        + "Use ONLY when the user explicitly asks to remember something "
                        + "in the CURRENT message starting with 记住：, 请记住：, or Remember:. "
                        + "Copy content verbatim from that command. Saving needs user confirmation. "
                        + "A query about earlier memories grants no write. "
                        + "Do NOT use it for retrieval results, temporary context, "
                        + "or your own conclusions. kind must be one of: FACT, PREFERENCE, PROFILE.")
                .inputClass(MemorySaveInput.class)
                .parameters(ToolSchemaGenerator.schemaOf(MemorySaveInput.class))
                .requiresApproval(true)
                .build();
    }

    /**
     * 核对可信用户身份及当前明确的保存意图，构造长期记忆待确认申请。
     * 上游由 {@code ToolExecutor（执行 memory_save）} 调用。
     * 下游调用 {@code context.allowsTool}、{@code memoryService.isEnabled}、{@code context.requestedMemoryContent}。
     *
     * @param input 本次操作的结构化输入
     * @param context 可信执行上下文，包含身份、范围和本轮执行状态
     * @return 带载荷或可识别错误状态的工具结果
     */
    @Override
    public ToolResult execute(ToolInput input, AgentExecutionContext context) {
        if (!context.allowsTool("memory_save") || !memoryService.isEnabled()) {
            return ToolResult.builder().toolName(definition().getName()).success(false)
                    .reasonCode("memory_write_not_authorized")
                    .errorMessage("长期记忆写入需要启用开关，并以“记住：内容”明确发起本轮保存")
                    .retryable(false).build();
        }

        MemorySaveInput save = (MemorySaveInput) input;
        String content = save.getContent() == null ? "" : save.getContent().strip();
        if (content.isEmpty() || !context.requestedMemoryContent().contains(content)) {
            return ToolResult.builder().toolName(definition().getName()).success(false)
                    .reasonCode("memory_content_not_from_user")
                    .errorMessage("保存内容须逐字来自本轮记忆命令，请复制用户原文")
                    .retryable(false).build();
        }
        if (content.length() > memoryService.maxContentChars()) {
            return ToolResult.builder().toolName(definition().getName()).success(false)
                    .reasonCode("memory_content_too_long").errorMessage("内容超过长期记忆长度限制")
                    .retryable(false).build();
        }

        // 归属只来自执行上下文：即使模型传了 userId 字段（schema 里也没有），也读不到它。
        Long userId = context.getUserId();
        if (userId == null) {
            return ToolResult.builder()
                    .toolName(definition().getName())
                    .success(false)
                    .errorMessage("无法确定当前用户，未写入长期记忆")
                    .build();
        }

        return ToolResult.builder()
                .toolName(definition().getName())
                .success(false)
                .reasonCode("memory_approval_required")
                .memorySaveProposal(new MemorySaveApproval.Proposal(content, LongTermMemoryItem.normalizeKind(save.getKind())))
                .build();
    }
}
