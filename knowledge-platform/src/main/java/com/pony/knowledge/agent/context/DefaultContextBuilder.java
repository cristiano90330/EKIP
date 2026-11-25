/* 按优先级拼装 Agent 消息，压缩历史与工具输出，保留必需的正文证据。 */

package com.pony.knowledge.agent.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pony.knowledge.agent.memory.MemoryManager;
import com.pony.knowledge.agent.llm.AgentInputTokens;
import com.pony.knowledge.agent.memory.longterm.LongTermMemoryItem;
import com.pony.knowledge.agent.memory.longterm.LongTermMemoryService;
import com.pony.knowledge.agent.memory.summary.ConversationSummary;
import com.pony.knowledge.agent.memory.summary.SummaryCodec;
import com.pony.knowledge.agent.memory.summary.TokenEstimator;
import com.pony.knowledge.agent.model.AgentMessage;
import com.pony.knowledge.agent.model.ToolStep;
import com.pony.knowledge.service.rag.generation.TokenCounter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Context 装配器实现。
 *
 * <p>装配顺序（Required 恒不裁）：system → summary → 最近窗口(时间序) → currentQuery → 工具往返。
 * 最近窗口里的 assistant 消息若带工具步骤（跨轮落库的痕迹），会在它后面插入一条
 * 「该轮工具轨迹」system 消息——这是模型知道「上一轮查过什么」的唯一来源。
 *
 * <p>最近窗口的起点是摘要的游标（压缩点），不是「最后 N 条」：摘要已覆盖的消息不再以原文
 * 重复出现，避免同一段内容既进摘要又进窗口。
 *
 * <p>预算裁剪按三级优先级进行，先动最不重要的：
 * <ol>
 *   <li>最近窗口最旧的消息（保持时间序，一次一条）；</li>
 *   <li>重复工具往返、较早的非证据工具往返（整组进出）；</li>
 *   <li>非证据工具结果可缩短；带引用映射的获准原文恒不裁；</li>
 *   <li>必需内容仍超限时如实返回 overBudgetTokens，由调用前门禁拒绝。</li>
 * </ol>
 * system / summary / currentQuery 恒不裁。
 *
 * <p>「整组进出」是协议要求，不是风格偏好：OpenAI 兼容端点要求每条 role=tool 消息都能对应到
 * 前一条 assistant 里的 tool_calls，反之 assistant 带 tool_calls 也必须有其应答。
 * 逐条裁会造出孤儿消息——严格实现的端点直接 400，宽松实现（如本地 Ollama）则静默丢弃证据，
 * 后者更糟，因为症状看起来像「模型没查到」。
 *
 * <p>截断而非丢弃是第二层保护：单条工具结果超过可用预算时丢掉它等于本轮检索白做。
 * 截断保留头部（命中摘要与首段证据通常在前），并在尾部留标记，让模型自己知道证据不完整，
 * 而不是把它当成全部。
 *
 * <p>只依赖 MemoryManager 读取摘要与窗口；工具结果、currentQuery 均由调用方传入，
 * 本类不执行 Tool、不访问 Redis。截断产生的是新对象，不改写调用方传入的消息
 * （同一批工具消息会在本回合的后续轮次被重复装配，原地改写会造成逐轮累积退化）。
 */
@Component
public class DefaultContextBuilder implements ContextBuilder {

    /** 单条工具结果截断后的最小保留量：低于这个长度连「查到了什么」都读不出来，截断就失去意义。 */
    private static final int MIN_TOOL_CONTENT_TOKENS = 160;

    /** 截断标记：让模型知道这段证据不完整。 */
    private static final String TRUNCATE_MARKER = "…[证据超预算，已截断]";

    /** 标记自身占用的 token，计算目标长度时先扣掉，否则截完仍然超预算。 */
    private static final int MARKER_TOKENS = TokenCounter.count(TRUNCATE_MARKER);

    /**
     * 长期记忆注入块的 token 上限。
     *
     * <p>记忆是辅助素材，不该与「本轮检索到的证据」抢预算：证据丢了答案就错，
     * 记忆少了只是回答不够个性化。因此给一个与证据量级明显有差距的固定上限。
     */
    private static final int LONG_TERM_MAX_TOKENS = 400;

    private final MemoryManager memoryManager;

    private final ObjectMapper objectMapper;

    /** 长期记忆（可为 null → 不注入；测试与离线装配不需要它）。 */
    private final LongTermMemoryService longTermMemory;

    /**
     * 用注入依赖和构造参数建立此实例执行时使用的状态。
     *
     * @param memoryManager 本次调用传入的 {@code MemoryManager}
     * @param objectMapper 负责 JSON 协议编码和解码的映射器
     * @param longTermMemory 本次调用传入的 {@code LongTermMemoryService}
     */
    @Autowired
    public DefaultContextBuilder(MemoryManager memoryManager, ObjectMapper objectMapper,
                                 LongTermMemoryService longTermMemory) {
        this.memoryManager = memoryManager;
        this.objectMapper = objectMapper;
        this.longTermMemory = longTermMemory;
    }

    /**
     * 将当前参数交给共有构造入口，并补齐这组重载省略的设置。
     *
     * @param memoryManager 本次调用传入的 {@code MemoryManager}
     * @param objectMapper 负责 JSON 协议编码和解码的映射器
     */
    public DefaultContextBuilder(MemoryManager memoryManager, ObjectMapper objectMapper) {
        this(memoryManager, objectMapper, null);
    }

    /**
     * 按系统提示、摘要、最近消息、工具往返、当前提问与必需证据构建模型消息，并记录裁减结果。
     * 上游由 {@code DefaultContextBudgetManager} 调用。
     * 下游调用 {@code budget.allowedTokens}、{@code AgentInputTokens.messages}、{@code java.util.stream.Collectors.joining}。
     *
     * @param request 身份、会话、提示词、当前问题及可选工具结果与预算
     * @return 模型消息及其用量和裁减诊断
     */
    @Override
    public ContextBuildResult build(ContextBuildRequest request) {
        boolean defaultedSession = request.getSessionId() == null;
        String key = defaultedSession ? "" : request.getSessionId();

        // 摘要先读：它同时提供「已折入多少条」（压缩点）与要注入的文本。
        ConversationSummary summary = loadSummary(request.getSessionId());

        // 1. 各素材段
        List<AgentMessage> systemMsgs = List.of(system(request.getSystemPrompt()));
        List<AgentMessage> summaryMsgs = summaryMessage(summary);
        List<AgentMessage> longTermMsgs = longTermBlock(request);
        List<AgentMessage> recentMsgs = new ArrayList<>(withToolTraces(loadWindow(
                key, summary == null ? 0 : summary.getSummarizedMessageCount(), request.getRecentWindow())));
        List<AgentMessage> queryMsgs = List.of(user(request.getCurrentQuery()));
        List<ToolGroup> toolGroups = groupIntoRoundTrips(nonNull(request.getToolMessages()));

        ContextBudget budget = request.getBudget();
        int allowed = budget == null ? Integer.MAX_VALUE : budget.allowedTokens();

        int total = AgentInputTokens.messages(systemMsgs)
                + AgentInputTokens.messages(summaryMsgs)
                + AgentInputTokens.messages(longTermMsgs)
                + AgentInputTokens.messages(recentMsgs)
                + AgentInputTokens.messages(queryMsgs)
                + toolGroupTokens(toolGroups);
        int tokensBefore = total;

        int dropped = 0;
        int truncated = 0;

        // 2. 超预算裁剪 —— 优先级 ①：最近窗口最旧消息
        while (total > allowed && !recentMsgs.isEmpty()) {
            total -= AgentInputTokens.messages(List.of(recentMsgs.remove(0)));
            dropped++;
        }

        // Remove duplicate tool results as complete protocol groups. A source mapping
        // makes a group required: never clip or evict admitted evidence to make it fit.
        java.util.Set<String> seenGroups = new java.util.HashSet<>();
        for (int i = toolGroups.size() - 1; total > allowed && i >= 0; i--) {
            ToolGroup group = toolGroups.get(i);
            String signature = group.messages.stream().filter(m -> "tool".equals(m.getRole()))
                    .map(AgentMessage::getContent).collect(java.util.stream.Collectors.joining("\n"));
            if (!signature.isBlank() && !seenGroups.add(signature)) {
                toolGroups.remove(i);
                total -= group.tokens;
                dropped += group.messages.size();
            }
        }
        while (total > allowed && toolGroups.size() > 1) {
            int optional = IntStream.range(0, toolGroups.size() - 1)
                    .filter(i -> !toolGroups.get(i).hasEvidence()).findFirst().orElse(-1);
            if (optional < 0) break;
            ToolGroup evicted = toolGroups.remove(optional);
            total -= evicted.tokens;
            dropped += evicted.messages.size();
        }

        // Optional verbose tool results can be shortened; admitted evidence is immutable.
        for (ToolGroup group : toolGroups) {
            if (total <= allowed) break;
            if (!group.hasEvidence()) {
                Shrink shrink = shrink(group, total - allowed);
                total -= shrink.savedTokens();
                truncated += shrink.changedMessages();
            }
        }

        // 兜底：证据已截到最小保留量仍装不下（预算小到连一条结果都盛不了）→ 整组丢弃。
        // 优先保证「不超预算」而不是「留下半条证据」：超出模型窗口的后果是提示词被供应商截断或
        // 整轮降级，两者都比「少一条证据」更糟；丢掉的条数会进 agent.context.dropped 指标。
        if (total > allowed && toolGroups.size() == 1 && !toolGroups.get(0).hasEvidence()) {
            ToolGroup evicted = toolGroups.remove(0);
            total -= evicted.tokens;
            dropped += evicted.messages.size();
        }

        // 3. 固定顺序装配
        List<AgentMessage> finalMessages = new ArrayList<>();
        finalMessages.addAll(systemMsgs);
        finalMessages.addAll(summaryMsgs);
        finalMessages.addAll(longTermMsgs);
        finalMessages.addAll(recentMsgs);
        finalMessages.addAll(queryMsgs);
        for (ToolGroup group : toolGroups) {
            finalMessages.addAll(group.messages);
        }

        // 汇总用装配后的真实值重算一遍，避免循环中的增量记账误差。
        int used = AgentInputTokens.messages(finalMessages);

        // Required 段自身超预算、或单条证据已截到下限仍装不下时，把超额量显式量化交给上层打指标
        // （本类保持纯装配，不打点）。
        int overBudget = budget == null ? 0 : Math.max(0, used - allowed);

        return ContextBuildResult.builder()
                .messages(finalMessages)
                .tokensBefore(tokensBefore)
                .tokensUsed(used)
                .trimmed(dropped > 0 || truncated > 0)
                .droppedMessages(dropped)
                .truncatedMessages(truncated)
                .overBudgetTokens(overBudget)
                .build();
    }

    /**
     * 将 assistant 工具调用和所属工具回复聚成同一裁减单元，保持协议完整。
     * 上游由 {@code DefaultContextBuilder.build} 调用。
     *
     * @param in 本次调用传入的 {@code List<AgentMessage>}
     * @return 当前记录中的 {@code out}
     */
    private static List<ToolGroup> groupIntoRoundTrips(List<AgentMessage> in) {
        List<ToolGroup> out = new ArrayList<>();
        ToolGroup current = null;
        for (AgentMessage m : in) {
            if (current == null || "assistant".equals(m.getRole())) {
                current = new ToolGroup();
                out.add(current);
            }
            current.add(m);
        }
        return out;
    }

    /**
     * 对工具往返中的正文做有界裁减，返回节省的令牌及改变的消息数。
     * 上游由 {@code DefaultContextBuilder.build} 调用。
     * 下游调用 {@code IntStream.range}、{@code Comparator.comparingInt}、{@code TokenEstimator.estimate}。
     *
     * @param group 本次调用传入的 {@code ToolGroup}
     * @param needTokens 本次调用传入的 {@code int}
     * @return 调用方使用的 {@code Shrink}
     */
    private Shrink shrink(ToolGroup group, int needTokens) {
        if (needTokens <= 0) {
            return Shrink.NONE;
        }

        List<Integer> order = IntStream.range(0, group.messages.size())
                .filter(i -> "tool".equals(group.messages.get(i).getRole())
                        && group.messages.get(i).getContent() != null)
                .boxed()
                .sorted(Comparator.comparingInt(
                        (Integer i) -> TokenEstimator.estimate(group.messages.get(i).getContent())).reversed())
                .toList();

        int remaining = needTokens;
        int saved = 0;
        int changed = 0;
        for (int idx : order) {
            if (remaining <= 0) {
                break;
            }
            AgentMessage msg = group.messages.get(idx);
            int currentTokens = TokenCounter.count(msg.getContent());
            int reducible = currentTokens - MIN_TOOL_CONTENT_TOKENS;
            if (reducible <= 0) {
                continue;
            }
            int target = Math.max(MIN_TOOL_CONTENT_TOKENS,
                    currentTokens - Math.min(reducible, remaining) - MARKER_TOKENS);
            // Capacity uses raw conservative tokens, matching Python ModelGate.
            int rawTarget = Math.max(1, target);
            String shorter = TokenCounter.truncateToTokens(msg.getContent(), rawTarget) + TRUNCATE_MARKER;
            int afterTokens = TokenCounter.count(shorter);
            if (afterTokens >= currentTokens) {
                continue; // 已到下限 / 标记抵消了收益，换下一条
            }
            group.replace(idx, copyWith(msg, shorter));
            saved += currentTokens - afterTokens;
            remaining -= currentTokens - afterTokens;
            changed++;
        }

        return new Shrink(saved, changed);
    }

    /**
     * 复制消息元数据并替换裁减后的正文，保留原消息供其他路径使用。
     * 上游由 {@code DefaultContextBuilder.shrink} 调用。
     *
     * @param src 本次调用传入的 {@code AgentMessage}
     * @param content 待处理的正文内容
     * @return 指定角色和内容的模型消息
     */
    private static AgentMessage copyWith(AgentMessage src, String content) {
        return AgentMessage.builder()
                .role(src.getRole())
                .content(content)
                .toolCallId(src.getToolCallId())
                .toolName(src.getToolName())
                .toolCalls(src.getToolCalls())
                .timestamp(src.getTimestamp())
                .build();
    }

    /**
     * 按可信用户身份和当前问题读取可注入的长期记忆，生成受记忆开关限制的提示块。
     * 上游由 {@code DefaultContextBuilder.build} 调用。
     * 下游调用 {@code request.isMemoryReadEnabled}、{@code longTermMemory.injectLimit}、{@code longTermMemory.recall}。
     *
     * @param request 身份、会话、提示词、当前问题及可选工具结果与预算
     * @return 按当前约定排列的模型消息列表
     */
    private List<AgentMessage> longTermBlock(ContextBuildRequest request) {
        if (longTermMemory == null || !request.isMemoryReadEnabled() || request.getUserId() == null) {
            return List.of();
        }
        int limit = longTermMemory.injectLimit();
        if (limit <= 0) {
            return List.of();
        }
        List<LongTermMemoryItem> items = longTermMemory.recall(
                request.getUserId(), request.getCurrentQuery(), limit);
        while (!items.isEmpty()) {
            String block = longTermMemory.renderBlock(items);
            if (!block.isBlank() && TokenEstimator.estimate(block) <= LONG_TERM_MAX_TOKENS) {
                return List.of(system(block));
            }
            items = items.subList(0, items.size() - 1);
        }
        return List.of();
    }

    /**
     * 按会话键读取并解析摘要，旧格式或无摘要时按当前兼容逻辑返回。
     * 上游由 {@code DefaultContextBuilder.build} 调用。
     * 下游调用 {@code SummaryCodec.decode}。
     *
     * @param sessionId 受当前用户与策略约束的服务端会话 ID
     * @return 带折叠位置的会话摘要
     */
    private ConversationSummary loadSummary(String sessionId) {
        return SummaryCodec.decode(memoryManager.getSummary(sessionId).orElse(null), objectMapper);
    }

    /**
     * 优先从摘要游标之后读取消息窗口，摘要位置失效时采用最近窗口。
     * 上游由 {@code DefaultContextBuilder.build} 调用。
     * 下游调用 {@code memoryManager.countMessages}。
     *
     * @param key 用于访问当前数据的存储键
     * @param cursor 本次调用传入的 {@code int}
     * @param recentWindow 本次调用传入的 {@code int}
     * @return 按当前约定排列的模型消息列表
     */
    private List<AgentMessage> loadWindow(String key, int cursor, int recentWindow) {
        int window = Math.max(1, recentWindow);
        if (cursor <= 0) {
            return memoryManager.getRecentMessages(key, window);
        }
        int total = memoryManager.countMessages(key);
        if (cursor > total) {
            return memoryManager.getRecentMessages(key, window);
        }
        if (cursor == total) {
            return List.of();
        }
        return memoryManager.getMessages(key, Math.max(cursor, total - window), total);
    }

    /**
     * 将当前摘要状态包装为模型消息，标明正常摘要或降级归档。
     * 上游由 {@code DefaultContextBuilder.build} 调用。
     * 下游调用 {@code summary.isDegraded}、{@code system}。
     *
     * @param summary 本次调用传入的 {@code ConversationSummary}
     * @return 按当前约定排列的模型消息列表
     */
    private List<AgentMessage> summaryMessage(ConversationSummary summary) {
        if (summary == null || summary.getSummary() == null || summary.getSummary().isBlank()) {
            return List.of();
        }
        // 降级摘要要标注出来：它可能是原始对话摘录（摘要模型当时不可用），
        // 不标的话模型会把摘录当成已经整理过的结论直接引用。
        String label = summary.isDegraded()
                ? "[会话摘要（降级：非整理结论，仅为原始对话摘录）] "
                : "[会话摘要] ";
        return List.of(system(label + summary.getSummary()));
    }

    /**
     * 把已保存的工具步骤摘要追加到相应会话消息的上下文表达。
     * 上游由 {@code DefaultContextBuilder.build} 调用。
     * 下游调用 {@code toolTraceOf}、{@code system}。
     *
     * @param recent 本次调用传入的 {@code List<AgentMessage>}
     * @return 按当前约定排列的模型消息列表
     */
    private List<AgentMessage> withToolTraces(List<AgentMessage> recent) {
        if (recent == null || recent.isEmpty()) {
            return List.of();
        }
        List<AgentMessage> out = new ArrayList<>(recent.size() + 2);
        for (AgentMessage m : recent) {
            out.add(m);
            String trace = toolTraceOf(m);
            if (trace != null) {
                out.add(system(trace));
            }
        }
        return out;
    }

    /**
     * 将一条历史消息对应的工具步骤压成模型可读的轨迹文本。
     * 上游由 {@code DefaultContextBuilder.withToolTraces} 调用。
     * 下游调用 {@code sb.append}、{@code step.isOk}。
     *
     * @param m 本次调用传入的 {@code AgentMessage}
     * @return 供后续处理使用的文本或标识
     */
    private String toolTraceOf(AgentMessage m) {
        if (m == null || !"assistant".equals(m.getRole())
                || m.getSteps() == null || m.getSteps().isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("[该轮工具轨迹]");
        for (ToolStep step : m.getSteps()) {
            sb.append("\n- ").append(step.getTool()).append(step.isOk() ? "(ok)" : "(失败)");
            if (step.getSummary() != null && !step.getSummary().isBlank()) {
                sb.append(": ").append(step.getSummary());
            }
        }
        return sb.toString();
    }

    /**
     * 汇总工具调用往返分组占用的令牌总数。
     * 上游由 {@code DefaultContextBuilder.build} 调用。
     *
     * @param groups 本次调用传入的 {@code List<ToolGroup>}
     * @return 当前记录中的 {@code sum}
     */
    private static int toolGroupTokens(List<ToolGroup> groups) {
        int sum = 0;
        for (ToolGroup group : groups) {
            sum += group.tokens;
        }
        return sum;
    }

    /**
     * 将缺省的消息列表归一化为空列表，方便后续装配。
     * 上游由 {@code DefaultContextBuilder.build} 调用。
     *
     * @param in 本次调用传入的 {@code List<AgentMessage>}
     * @return 按当前约定排列的模型消息列表
     */
    private List<AgentMessage> nonNull(List<AgentMessage> in) {
        return in == null ? List.of() : in;
    }

    /**
     * 用给定正文创建系统角色消息。
     * 上游由 {@code DefaultContextBuilder.build}、{@code DefaultContextBuilder.longTermBlock}、{@code DefaultContextBuilder.summaryMessage} 调用。
     *
     * @param content 待处理的正文内容
     * @return 指定角色和内容的模型消息
     */
    private AgentMessage system(String content) {
        return AgentMessage.builder().role("system").content(content).build();
    }

    /**
     * 将输入问题包装为用户角色消息。
     * 上游由 {@code DefaultContextBuilder.build} 调用。
     *
     * @param content 待处理的正文内容
     * @return 指定角色和内容的模型消息
     */
    private AgentMessage user(String content) {
        return AgentMessage.builder().role("user").content(content).build();
    }

    /** 一次裁剪省下的量与被截断的消息条数。 */
    private record Shrink(int savedTokens, int changedMessages) {

        static final Shrink NONE = new Shrink(0, 0);
    }

    /** 一次工具往返的消息组：裁剪的最小单位。 */
    private static final class ToolGroup {

        private final List<AgentMessage> messages = new ArrayList<>();

        private int tokens;

        /**
         * 判断分组中是否包含构建时必须保留的证据消息。
         * 上游由 {@code DefaultContextBuilder.build} 调用。
         *
         * @return 条件成立时为 true，其他状态按 false 处理
         */
        boolean hasEvidence() {
            return messages.stream().anyMatch(m -> "tool".equals(m.getRole())
                    && m.getContent() != null && m.getContent().contains("source_reference=[[cite:"));
        }

        /**
         * 把同一轮中的子消息加入工具往返并更新令牌估算。
         * 上游由 {@code DefaultContextBuilder.groupIntoRoundTrips} 调用。
         * 下游调用 {@code AgentInputTokens.messages}。
         *
         * @param message 待处理的消息或对外提示
         */
        void add(AgentMessage message) {
            messages.add(message);
            tokens += AgentInputTokens.messages(List.of(message));
        }

        /**
         * 将某个索引的工具消息换成裁减版本，并同步分组计数。
         * 上游由 {@code DefaultContextBuilder.shrink} 调用。
         * 下游调用 {@code AgentInputTokens.messages}。
         *
         * @param index 本次调用传入的 {@code int}
         * @param message 待处理的消息或对外提示
         */
        void replace(int index, AgentMessage message) {
            tokens -= AgentInputTokens.messages(List.of(messages.get(index)));
            messages.set(index, message);
            tokens += AgentInputTokens.messages(List.of(message));
        }
    }
}
