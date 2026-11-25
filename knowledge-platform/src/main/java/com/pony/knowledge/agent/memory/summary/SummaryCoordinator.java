/* 判断何时折叠会话历史，协调增量摘要、降级归档与游标更新。 */

package com.pony.knowledge.agent.memory.summary;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pony.knowledge.agent.memory.MemoryItem;
import com.pony.knowledge.agent.memory.MemoryManager;
import com.pony.knowledge.agent.memory.MemoryType;
import com.pony.knowledge.agent.model.AgentMessage;
import com.pony.knowledge.agent.observability.AgentMetrics;
import com.pony.knowledge.service.rag.generation.TokenCounter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 会话摘要协调器：回合结束后异步判断是否需要增量压缩——
 * 只折「本轮新增且未入摘要」的最旧消息（cursor 之后），与已有摘要融合后覆盖落库。
 *
 * 行为约束：
 *   - 不阻塞主 Agent Response：快照、策略、摘要都在 summaryExecutor 异步执行；
 *   - 同一 session 由 {@link SummaryLock} 串行，不同 session 并行；
 *   - 失败不导致请求失败：内部 catch 只记日志与指标，不向调用方抛；
 *   - 摘要失败**不原地打转**：写入一份降级归档（原始对话摘录）并照常推进游标，
 *     同一批消息因此不会被每回合重折一次；下次摘要成功时归档被正常摘要取代；
 *   - 不删除历史：只覆盖 summary，原始消息永不删（压缩发生在上下文装配层，不在存储层）；
 *   - 允许落后于最新消息：cursor = 已折入摘要的消息数，只折 cursor → (count - retainRecent)；
 *   - 快照不全量加载会话：条数走 LLEN，待折消息走区间读，token 估算达到阈值即早停；
 *   - snapshot.summary 可能为 null（未触发或无摘要）。
 *
 * 存/取：MemoryItem.content 保存 ConversationSummary 的 JSON（含 summarizedMessageCount 游标），
 * 兼容纯文本（无游标 → 视为 0）。
 */
@Service
public class SummaryCoordinator {

    private static final Logger log = LoggerFactory.getLogger(SummaryCoordinator.class);

    /** token 早停估算的分页下限：控制读取次数，同时不一次搬整个会话。 */
    private static final int TOKEN_ESTIMATE_MIN_PAGE = 32;

    private final MemoryManager memoryManager;

    private final SummaryService summaryService;

    private final SummaryPolicy summaryPolicy;

    private final Executor summaryExecutor;

    private final ObjectMapper objectMapper;

    private final SummaryLock summaryLock;

    private final AgentMetrics agentMetrics;

    private final long summaryTimeoutMs;

    /**
     * 用注入依赖和构造参数建立此实例执行时使用的状态。
     *
     * @param memoryManager 本次调用传入的 {@code MemoryManager}
     * @param summaryService 本次调用传入的 {@code SummaryService}
     * @param summaryPolicy 本次调用传入的 {@code SummaryPolicy}
     * @param summaryExecutor 本次调用传入的 {@code Executor}
     * @param objectMapper 负责 JSON 协议编码和解码的映射器
     * @param summaryLock 本次调用传入的 {@code SummaryLock}
     * @param agentMetrics 本次调用传入的 {@code AgentMetrics}
     * @param summaryTimeoutMs 本次调用传入的 {@code long}
     */
    public SummaryCoordinator(
            MemoryManager memoryManager,
            SummaryService summaryService,
            SummaryPolicy summaryPolicy,
            @Qualifier("summaryExecutor") Executor summaryExecutor,
            ObjectMapper objectMapper,
            SummaryLock summaryLock,
            AgentMetrics agentMetrics,
            @Value("${agent.memory.summary.timeout-ms:30000}") long summaryTimeoutMs) {
        this.memoryManager = memoryManager;
        this.summaryService = summaryService;
        this.summaryPolicy = summaryPolicy;
        this.summaryExecutor = summaryExecutor;
        this.objectMapper = objectMapper;
        this.summaryLock = summaryLock;
        this.agentMetrics = agentMetrics;
        this.summaryTimeoutMs = summaryTimeoutMs;
    }

    /**
     * 按会话、用户与用途模型安排后台摘要，执行前再次检查存储快照与折叠条件。
     * 上游由 {@code AgentService.executeTurn} 调用。
     * 下游调用 {@code CompletableFuture.runAsync}、{@code agentMetrics.countSummaryTimeout}、{@code agentMetrics.countSummaryFailure}。
     *
     * @param sessionId 受当前用户与策略约束的服务端会话 ID
     * @param ownerId 所属用户 ID
     * @param summaryModelId 本次摘要采用的模型配置 ID
     */
    public void maybeSummarize(String sessionId,Long ownerId,String summaryModelId) {
        if (sessionId == null) {
            return;
        }
        // orTimeout: 超过阈值记 timeout 指标（任务本身在异步池继续跑完并释放锁，不阻塞请求、不中断）
        CompletableFuture.runAsync(() -> summarizeGuarded(sessionId,ownerId,summaryModelId), summaryExecutor)
                .orTimeout(summaryTimeoutMs, TimeUnit.MILLISECONDS)
                .whenComplete((ignored, err) -> {
                    if (err != null) {
                        if (err instanceof TimeoutException) {
                            agentMetrics.countSummaryTimeout();
                            log.warn("session {} 摘要超时 (>{}=ms)，已记录指标", sessionId, summaryTimeoutMs);
                        } else {
                            agentMetrics.countSummaryFailure();
                        }
                    }
                });
    }

    /**
     * 获取会话摘要锁及选定模型上下文，满足策略时执行一次增量折叠。
     * 上游由 {@code SummaryCoordinator.maybeSummarize} 调用。
     * 下游调用 {@code summaryLock.lock}、{@code summaryPolicy.shouldSummarize}、{@code agentMetrics.summaryTimer}。
     *
     * @param sessionId 受当前用户与策略约束的服务端会话 ID
     * @param ownerId 所属用户 ID
     * @param summaryModelId 本次摘要采用的模型配置 ID
     */
    private void summarizeGuarded(String sessionId,Long ownerId,String summaryModelId) {
        summaryLock.lock(sessionId);
        try {
            long start = System.nanoTime();
            MemorySnapshot snapshot = snapshot(sessionId);
            snapshot.setOwnerId(ownerId);
            snapshot.setSummaryModelId(summaryModelId);
            if (summaryPolicy.shouldSummarize(snapshot.getTotalTokens(), snapshot.getMessageCount())) {
                doIncrementalSummarize(sessionId, snapshot);
            }
            agentMetrics.summaryTimer().record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        } catch (Exception e) {
            agentMetrics.countSummaryFailure();
            log.warn("session {} 摘要失败，忽略: {}", sessionId, e.getMessage());
        } finally {
            summaryLock.unlock(sessionId);
        }
    }

    /**
     * 从消息总量、既有摘要与保留窗口计算本次待折叠范围，返回可摘要批次。
     * 上游由 {@code SummaryCoordinator.summarizeGuarded} 调用。
     * 下游调用 {@code memoryManager.countMessages}、{@code loadSummary}、{@code estimateTokensWithEarlyExit}。
     *
     * @param sessionId 受当前用户与策略约束的服务端会话 ID
     * @return 摘要需要的已有摘要及待折叠消息批次
     */
    public MemorySnapshot snapshot(String sessionId) {
        ConversationSummary summary = loadSummary(sessionId);
        int cursor = summary != null ? summary.getSummarizedMessageCount() : 0;

        // 摘要游标之前已有折叠结果，最近窗口中的消息留给上下文直接读取。
        int messageCount = memoryManager.countMessages(sessionId);
        int upTo = messageCount - summaryPolicy.getRetainRecent();
        List<AgentMessage> foldBatch = upTo > cursor
                ? memoryManager.getMessages(sessionId, cursor, upTo)
                : List.of();

        return MemorySnapshot.builder()
                .sessionId(sessionId)
                .messages(foldBatch)
                .summary(summary)
                .totalTokens(estimateTokensWithEarlyExit(sessionId, messageCount))
                .messageCount(messageCount)
                .build();
    }

    /**
     * 分批估算历史令牌，到达摘要判定阈值时提前结束。
     * 上游由 {@code SummaryCoordinator.snapshot} 调用。
     * 下游调用 {@code TokenEstimator.estimateMessages}。
     *
     * @param sessionId 受当前用户与策略约束的服务端会话 ID
     * @param messageCount 可参加预算判断的会话消息条数
     * @return 按当前方法口径计算或读取的数值
     */
    private int estimateTokensWithEarlyExit(String sessionId, int messageCount) {
        int threshold = summaryPolicy.getMaxTokens();
        int pageSize = Math.max(TOKEN_ESTIMATE_MIN_PAGE, summaryPolicy.getRetainRecent());
        int total = 0;
        for (int loaded = 0; loaded < messageCount; loaded += pageSize) {
            int to = messageCount - loaded;
            int from = Math.max(0, to - pageSize);
            total += TokenEstimator.estimateMessages(memoryManager.getMessages(sessionId, from, to));
            if (total > threshold) {
                return total;
            }
        }
        return total;
    }

    /**
     * 将既有摘要与待折叠消息交给摘要模型；故障时以有界原文归档推进游标。
     * 上游由 {@code SummaryCoordinator.summarizeGuarded} 调用。
     * 下游调用 {@code snapshot.toBuilder}、{@code summaryService.summarize}、{@code agentMetrics.countSummaryFailure}。
     *
     * @param sessionId 受当前用户与策略约束的服务端会话 ID
     * @param snapshot 用于此次处理的会话或模型快照
     */
    private void doIncrementalSummarize(String sessionId, MemorySnapshot snapshot) {
        int cursor = snapshot.getSummary() != null ? snapshot.getSummary().getSummarizedMessageCount() : 0;
        int upTo = snapshot.getMessageCount() - summaryPolicy.getRetainRecent();
        List<AgentMessage> foldBatch = snapshot.getMessages();
        if (upTo <= cursor || foldBatch.isEmpty()) {
            return; // 无新增可折（摘要已覆盖；或消息太少留全部作窗口）
        }

        MemorySnapshot mergeSnapshot = snapshot.toBuilder()
                .messageCount(foldBatch.size())
                .build();

        ConversationSummary cs;
        boolean degraded = false;
        try {
            cs = summaryService.summarize(mergeSnapshot);
            if (cs == null) {
                throw new IllegalStateException("摘要服务返回 null");
            }
            cs.setSessionId(sessionId);
            cs.setSummarizedMessageCount(upTo);
        } catch (RuntimeException e) {
            // 模型不可用 / 产出不可用（空正文、被输出上限截断）：写入原始摘录，**游标照常前进**。
            // 不推进游标 = 同一批消息每回合重折一次、每次失败、每次付费，而会话永远得不到任何
            // 形式的压缩记忆。降级归档打破这个循环，代价是这批记忆比 LLM 摘要粗糙。
            cs = degradedArchive(sessionId, upTo, snapshot.getSummary(), foldBatch);
            degraded = true;
            agentMetrics.countSummaryFailure();
            agentMetrics.countSummaryDegraded();
            log.warn("session {} 摘要失败，已写降级归档（游标 {}→{}，折 {} 条）：{}",
                    sessionId, cursor, upTo, foldBatch.size(), e.getMessage());
        }

        // 防回退守卫：只前进、不回退（并发下不会被较旧的摘要覆盖更新的）
        ConversationSummary current = loadSummary(sessionId);
        if (current != null && current.getSummarizedMessageCount() > upTo) {
            log.info("session {} 已有更新摘要 (cursor={}>{})，跳过本次", sessionId,
                    current.getSummarizedMessageCount(), upTo);
            return;
        }

        saveSummary(sessionId, cs);
        int summaryTokens = TokenEstimator.estimate(cs.getSummary());
        agentMetrics.recordSummaryTokens(summaryTokens);
        // 压缩收益：折入的本批 token 与压缩后摘要 token 一起记，才能回答「摘要到底省了多少」
        // （只记摘要自身大小是回答不了的）。降级归档单独打 tag，不与非降级的收益混在一起。
        agentMetrics.recordSummaryCompaction(TokenEstimator.estimateMessages(foldBatch), summaryTokens,
                cs.isDegraded());

        if (!degraded) {
            log.info("session {} 已增量压缩：游标 {}→{} (折 {} 条，未删历史)", sessionId, cursor, upTo, foldBatch.size());
        }
    }

    /**
     * 用已有归档与新增消息构造降级摘要，并在目标折叠位置写下游标。
     * 上游由 {@code SummaryCoordinator.doIncrementalSummarize} 调用。
     * 下游调用 {@code TokenCounter.truncateToTokens}、{@code TokenEstimator.estimate}、{@code Instant.now}。
     *
     * @param sessionId 受当前用户与策略约束的服务端会话 ID
     * @param upTo 本次调用传入的 {@code int}
     * @param previous 本次调用传入的 {@code ConversationSummary}
     * @param foldBatch 本次调用传入的 {@code List<AgentMessage>}
     * @return 带折叠位置的会话摘要
     */
    private ConversationSummary degradedArchive(String sessionId, int upTo,
                                                ConversationSummary previous, List<AgentMessage> foldBatch) {
        int cap = fallbackBudgetTokens();
        String previousText = previous == null || previous.getSummary() == null ? "" : previous.getSummary();
        String previousPart = previousText.isBlank()
                ? ""
                : "[此前摘要]\n" + TokenCounter.truncateToTokens(previousText, Math.max(1, cap / 3)) + "\n\n";

        int remaining = Math.max(0, cap - TokenEstimator.estimate(previousPart));
        return ConversationSummary.builder()
                .sessionId(sessionId)
                .summary(previousPart + rawArchive(foldBatch, remaining))
                .summarizedMessageCount(upTo)
                .degraded(true)
                .updatedAt(Instant.now())
                .build();
    }

    /**
     * 按令牌预算摘取原始消息，供无法生成模型摘要时保留历史线索。
     * 上游由 {@code SummaryCoordinator.degradedArchive} 调用。
     * 下游调用 {@code TokenEstimator.estimate}、{@code TokenCounter.truncateToTokens}、{@code Collections.reverse}。
     *
     * @param messages 按照交互顺序提供的消息列表
     * @param budgetTokens 为当前内容分配的令牌预算
     * @return 供后续处理使用的文本或标识
     */
    private String rawArchive(List<AgentMessage> messages, int budgetTokens) {
        String header = "[摘要模型不可用，以下为原始对话摘录，共 " + messages.size() + " 条]";
        int used = TokenEstimator.estimate(header) + 16;   // 余量留给「更早 N 条未列出」那句
        List<String> lines = new ArrayList<>();
        int kept = 0;

        for (int i = messages.size() - 1; i >= 0; i--) {
            AgentMessage m = messages.get(i);
            String line = m.getRole() + ": " + (m.getContent() == null ? "" : m.getContent());
            int cost = TokenEstimator.estimate(line);
            if (used + cost > budgetTokens) {
                if (kept == 0) {
                    String cut = TokenCounter.truncateToTokens(line, Math.max(40, budgetTokens - used));
                    if (!cut.isBlank()) {
                        lines.add(cut);
                        kept = 1;
                    }
                }
                break;
            }
            lines.add(line);
            used += cost;
            kept++;
        }

        Collections.reverse(lines);
        StringBuilder sb = new StringBuilder(header);
        if (kept < messages.size()) {
            sb.append("\n（更早的 ").append(messages.size() - kept).append(" 条未列出）");
        }
        for (String line : lines) {
            sb.append('\n').append(line);
        }

        return sb.toString();
    }

    /**
     * 确定原文降级归档允许保留的令牌预算。
     * 上游由 {@code SummaryCoordinator.degradedArchive} 调用。
     *
     * @return 按当前方法口径计算或读取的数值
     */
    private int fallbackBudgetTokens() {
        return Math.max(200, summaryPolicy.getMaxTokens() / 4);
    }

    /**
     * 读取会话存储中的摘要记录并解码为增量摘要对象。
     * 上游由 {@code SummaryCoordinator.doIncrementalSummarize}、{@code SummaryCoordinator.snapshot} 调用。
     * 下游调用 {@code SummaryCodec.decode}。
     *
     * @param sessionId 受当前用户与策略约束的服务端会话 ID
     * @return 带折叠位置的会话摘要
     */
    private ConversationSummary loadSummary(String sessionId) {
        MemoryItem item = memoryManager.getSummary(sessionId).orElse(null);
        return SummaryCodec.decode(item, objectMapper);
    }

    /**
     * 将本次摘要和已折叠位置编码后存回会话记忆。
     * 上游由 {@code SummaryCoordinator.doIncrementalSummarize} 调用。
     * 下游调用 {@code memoryManager.updateSummary}、{@code SummaryCodec.encode}、{@code Instant.now}。
     *
     * @param sessionId 受当前用户与策略约束的服务端会话 ID
     * @param cs 本次调用传入的 {@code ConversationSummary}
     */
    private void saveSummary(String sessionId, ConversationSummary cs) {
        memoryManager.updateSummary(sessionId, MemoryItem.builder()
                .sessionId(sessionId)
                .type(MemoryType.SUMMARY)
                .content(SummaryCodec.encode(cs, objectMapper))
                .updatedAt(Instant.now())
                .build());
    }
}
