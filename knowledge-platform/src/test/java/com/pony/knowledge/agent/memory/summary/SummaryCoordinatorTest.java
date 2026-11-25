/* 验证增量摘要游标、失败归档、并发隔离和压缩指标。 */

package com.pony.knowledge.agent.memory.summary;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pony.knowledge.agent.memory.DefaultMemoryManager;
import com.pony.knowledge.agent.memory.MemoryItem;
import com.pony.knowledge.agent.memory.MemoryManager;
import com.pony.knowledge.agent.memory.MemoryStore;
import com.pony.knowledge.agent.memory.MemoryType;
import com.pony.knowledge.agent.memory.store.InMemoryMemoryStore;
import com.pony.knowledge.agent.model.AgentMessage;
import com.pony.knowledge.agent.observability.AgentMetrics;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 3.8c：SummaryCoordinator 异步增量压缩。
 *   只折「cursor 之后、retainRecent 之前」的最旧新增消息；与已有摘要融合；覆盖落库；
 *   **不删除历史**（验收：不删除历史）。
 * mock SummaryService（返回固定摘要），executor 用 Runnable::run（同步）便于断言。
 */
class SummaryCoordinatorTest {

    private MemoryManager mem;
    private InMemoryMemoryStore history;

    private SummaryService summaryService;

    private SummaryCoordinator coord;

    /** 在该类型内部准备后续运行所需的数据。 */
    private ObjectMapper mapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /**
     * 协调 {@code summaryService.summarize} 完成当前阶段，供后续处理使用。
     * 下游调用 {@code summaryService.summarize}、{@code Runnable::run}、{@code mapper}。
     *
     * @param maxTokens 允许使用的最大令牌数
     * @param retainRecent 本次调用传入的 {@code int}
     */
    private void setup(int maxTokens, int retainRecent) {
        history = new InMemoryMemoryStore();
        mem = new DefaultMemoryManager(history);
        summaryService = mock(SummaryService.class);
        when(summaryService.summarize(any())).thenAnswer(inv -> {
            MemorySnapshot s = inv.getArgument(0);
            return ConversationSummary.builder()
                    .sessionId(s.getSessionId())
                    .summary("摘要:" + s.getMessageCount())
                    .build();
        });
        coord = new SummaryCoordinator(mem, summaryService,
                new SummaryPolicy(maxTokens, retainRecent), Runnable::run, mapper(),
                new SummaryLock(), new AgentMetrics(new SimpleMeterRegistry()), 30000L);
    }

    /**
     * 协调 {@code Instant.now} 完成当前阶段，供后续处理使用。
     * @param c 本次调用传入的 {@code String}
     * @return 指定角色和内容的模型消息
     */
    private AgentMessage msg(String c) {
        return AgentMessage.builder().role("user").content(c).timestamp(Instant.now()).build();
    }

    /** 协调 {@code history.appendMessage} 完成当前阶段，供后续处理使用。 */
    private void store5Messages() {
        for (int i = 1; i <= 5; i++) {
            history.appendMessage("A", msg("hello"));
        }
    }

    /** 通过 {@code history.appendMessage}、{@code coord.maybeSummarize} 驱动该场景，布尔状态符合断言，返回的数据、边界或顺序符合断言。 */
    @Test
    void belowThreshold_noSummarize_keepsAll() {
        setup(1000, 5);
        for (int i = 0; i < 3; i++) {
            history.appendMessage("A", msg("hi"));
        }
        coord.maybeSummarize("A", null, null);

        assertTrue(mem.getSummary("A").isEmpty(), "未超阈值不应生成摘要");
        assertEquals(3, mem.getRecentMessages("A", 100).size(), "消息全保留");
    }

    /** 通过 {@code coord.maybeSummarize}、{@code coord.snapshot} 驱动该场景，实际结果与固定预期一致。 */
    @Test
    void exceedsThreshold_summarizesIncrementally_andKeepsHistory() {
        setup(5, 2);
        store5Messages();
        coord.maybeSummarize("A", null, null);

        MemorySnapshot after = coord.snapshot("A");
        assertEquals("摘要:3", after.getSummary().getSummary(), "折 3 条最旧消息成摘要");
        assertEquals(3, after.getSummary().getSummarizedMessageCount(), "游标前进到 3");
        assertEquals(5, mem.getRecentMessages("A", 100).size(), "不删除历史：消息仍全保留");
    }

    /** 通过 {@code coord.maybeSummarize}、{@code coord.snapshot} 驱动该场景，实际结果与固定预期一致。 */
    @Test
    void noNewMessagesAfterSummary_skipsSecondRound() {
        setup(5, 2);
        store5Messages();
        coord.maybeSummarize("A", null, null);                              // 第一次折 3，游标=3
        assertEquals(1, summarizeCalls(), "第一次应调一次摘要");

        coord.maybeSummarize("A", null, null);                              // 第二次：cursor=3, upTo=3 → 无可折 → 跳过
        assertEquals(1, summarizeCalls(), "无可折新增 → 不重复调摘要");
        assertEquals(3, coord.snapshot("A").getSummary().getSummarizedMessageCount(), "游标停在 3");
        assertEquals("摘要:3", coord.snapshot("A").getSummary().getSummary());
        assertEquals(5, mem.getRecentMessages("A", 100).size(), "历史未删");
    }

    /**
     * 通过 {@code org.mockito.Mockito.mockingDetails} 根据输入产生保留业务要点的摘要。
     * @return 按当前方法口径计算或读取的数值
     */
    private int summarizeCalls() {
        return org.mockito.Mockito.mockingDetails(summaryService).getInvocations().size();
    }

    /** 通过 {@code mem.updateSummary}、{@code ArgumentCaptor.forClass} 驱动该场景，实际结果与固定预期一致。 */
    @Test
    void mergesExistingSummary_intoFoldBatch() {
        setup(5, 2);
        // 纯文本旧摘要（无游标 → 视为 0）
        mem.updateSummary("A", MemoryItem.builder().sessionId("A").type(MemoryType.SUMMARY).content("旧摘要").build());
        store5Messages();

        ArgumentCaptor<MemorySnapshot> cap = ArgumentCaptor.forClass(MemorySnapshot.class);
        coord.maybeSummarize("A", null, null);
        verify(summaryService).summarize(cap.capture());

        MemorySnapshot snap = cap.getValue();
        assertEquals("旧摘要", snap.getSummary().getSummary(), "摘要应带上已有摘要做融合");
        assertEquals(3, snap.getMessageCount(), "只把 3 条新增最旧消息折入本次摘要");
    }

    /** 通过 {@code history.appendMessage}、{@code coord.maybeSummarize} 驱动该场景，跳过受保护的调用，布尔状态符合断言。 */
    @Test
    void sessionA_isolatedFromB() {
        setup(5, 2);
        history.appendMessage("A", msg("hello"));
        history.appendMessage("B", msg("world"));
        coord.maybeSummarize("A", null, null);
        coord.maybeSummarize("B", null, null);

        assertTrue(mem.getSummary("A").isEmpty());
        assertTrue(mem.getSummary("B").isEmpty());
        verify(summaryService, never()).summarize(any());
    }

    /** 通过 {@code history.appendMessage}、{@code coord.snapshot} 驱动该场景，核对输出和依赖调用。 */
    @Test
    void snapshot_ofNoSummary_isNull() {
        setup(5, 2);
        history.appendMessage("A", msg("hi"));
        assertNull(coord.snapshot("A").getSummary(), "无摘要时 summary 应为 null");
    }

    /** 通过 {@code badService.summarize} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void summarizeFailure_doesNotPropagate_agentUnaffected() {
        // 独立构造：摘要服务一调就抛（模拟 LLM 超时/异常），确认协调器吞掉、历史不受影响
        InMemoryMemoryStore history2 = new InMemoryMemoryStore();
        MemoryManager mem2 = new DefaultMemoryManager(history2);
        for (int i = 0; i < 5; i++) {
            history2.appendMessage("A", msg("hello"));
        }

        SummaryService badService = mock(SummaryService.class);
        when(badService.summarize(any())).thenThrow(new RuntimeException("LLM 挂了"));
        SummaryCoordinator coord2 = new SummaryCoordinator(mem2, badService,
                new SummaryPolicy(5, 2), Runnable::run, mapper(),
                new SummaryLock(), new AgentMetrics(new SimpleMeterRegistry()), 30000L);

        // 不应抛出（异步任务吞异常）
        coord2.maybeSummarize("A", null, null);
        // 历史仍完整保留（摘要失败不影响消息）
        assertEquals(5, mem2.getRecentMessages("A", 100).size(), "摘要失败不影响历史消息");
    }

    // ---------- P1-1：摘要失败不再原地打转 ----------

    /**
     * 协调 {@code summaryService.summarize} 完成当前阶段，供后续处理使用。
     * 下游调用 {@code summaryService.summarize}、{@code Runnable::run}、{@code mapper}。
     */
    private void setupFailingOnce() {
        history = new InMemoryMemoryStore();
        mem = new DefaultMemoryManager(history);
        summaryService = mock(SummaryService.class);
        when(summaryService.summarize(any()))
                .thenThrow(new RuntimeException("LLM 超时"))
                .thenAnswer(inv -> ConversationSummary.builder()
                        .summary("恢复后的摘要")
                        .build());
        coord = new SummaryCoordinator(mem, summaryService,
                new SummaryPolicy(5, 2), Runnable::run, mapper(),
                new SummaryLock(), new AgentMetrics(new SimpleMeterRegistry()), 30000L);
    }

    /** 令模型摘要失败，读回已保存的降级归档并验证折叠游标前移。 */
    @Test
    void summarizeFailure_writesDegradedArchive_andAdvancesCursor() {
        setupFailingOnce();
        store5Messages();

        coord.maybeSummarize("A", null, null);

        ConversationSummary cs = coord.snapshot("A").getSummary();
        assertNotNull(cs, "失败也要留下一条归档，否则游标无处推进");
        assertTrue(cs.isDegraded(), "必须标记为降级，ContextBuilder 据此改变注入标注");
        assertEquals(3, cs.getSummarizedMessageCount(), "游标必须前进到 upTo");
        assertTrue(cs.getSummary().contains("原始对话摘录"), "归档要自述形态，实际=" + cs.getSummary());
        assertTrue(cs.getSummary().contains("hello"), "归档里要真有原始内容");
        assertEquals(5, mem.getRecentMessages("A", 100).size(), "仍不删除历史");
    }

    /** 完成一次降级折叠后检查相同历史未重复进入下一次摘要批次。 */
    @Test
    void afterDegraded_sameBatchIsNotReprocessed() {
        setupFailingOnce();
        store5Messages();

        coord.maybeSummarize("A", null, null);
        coord.maybeSummarize("A", null, null);

        assertEquals(1, invocationsOf(summaryService), "同一批消息不得被折第二次");
        assertEquals(3, coord.snapshot("A").getSummary().getSummarizedMessageCount());
    }

    /** 通过 {@code coord.maybeSummarize}、{@code history.appendMessage} 驱动该场景，布尔状态符合断言，实际结果与固定预期一致。 */
    @Test
    void recoveredSummarizer_replacesDegradedArchive() {
        setupFailingOnce();
        store5Messages();
        coord.maybeSummarize("A", null, null);                 // 失败 → 归档，游标 3

        history.appendMessage("A", msg("hello"));      // count=6, upTo=4
        history.appendMessage("A", msg("hello"));      // count=7, upTo=5
        coord.maybeSummarize("A", null, null);

        ConversationSummary cs = coord.snapshot("A").getSummary();
        assertEquals("恢复后的摘要", cs.getSummary(), "恢复正常后覆盖归档");
        assertFalse(cs.isDegraded(), "degraded 标记必须清掉");
        assertEquals(5, cs.getSummarizedMessageCount(), "游标继续前进到 5");
    }

    /** 多次触发概要生成异常，验证持续追加的降级归档仍符合长度预算。 */
    @Test
    void degradedArchive_staysBoundedAcrossRepeatedFailures() {
        history = new InMemoryMemoryStore();
        mem = new DefaultMemoryManager(history);
        SummaryService alwaysFailing = mock(SummaryService.class);
        when(alwaysFailing.summarize(any())).thenThrow(new RuntimeException("down"));
        coord = new SummaryCoordinator(mem, alwaysFailing,
                new SummaryPolicy(1000, 2), Runnable::run, mapper(),
                new SummaryLock(), new AgentMetrics(new SimpleMeterRegistry()), 30000L);

        for (int round = 0; round < 6; round++) {
            for (int i = 0; i < 8; i++) {
                history.appendMessage("A", msg("这是一条比较长的历史消息，用来把归档预算顶满"));
            }
            coord.maybeSummarize("A", null, null);
        }

        int archived = TokenEstimator.estimate(coord.snapshot("A").getSummary().getSummary());
        int cap = 1000 / 4;                                  // fallbackBudgetTokens 的口径
        assertTrue(archived <= cap + 120,
                "归档不得超过摘要阈值的 1/4（+切分余量），实际 " + archived + " > " + cap);
    }

    // ---------- P2-3：压缩收益（folded vs freed） ----------

    /** 通过 {@code ok.summarize}、{@code bad.summarize} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void recordsCompactionGain_splitByMode() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        history = new InMemoryMemoryStore();
        mem = new DefaultMemoryManager(history);
        SummaryService ok = mock(SummaryService.class);
        when(ok.summarize(any())).thenReturn(ConversationSummary.builder().summary("短摘要").build());
        SummaryCoordinator healthy = new SummaryCoordinator(mem, ok, new SummaryPolicy(5, 2), Runnable::run,
                mapper(), new SummaryLock(), new AgentMetrics(registry), 30000L);
        store5Messages();
        healthy.maybeSummarize("A", null, null);

        // 3 条 "hello"（各 2 token）= 6；摘要 "短摘要" = 3 → 比值 0.5（净收益一半）
        assertEquals(6.0, registry.counter("agent.summary.folded_tokens", "mode", "normal").count(), 1e-9,
                "折入的是本批消息的 token，不是整个会话");
        assertEquals(3.0, registry.counter("agent.summary.compressed_tokens", "mode", "normal").count(), 1e-9);
        DistributionSummary ratio = registry.find("agent.summary.compression_ratio")
                .tag("mode", "normal").summary();
        assertNotNull(ratio, "收益要单独可查");
        assertEquals(1, ratio.count());
        assertEquals(0.5, ratio.totalAmount(), 1e-9, "压缩后 ÷ 折入");

        // 降级归档走另一个 tag：它的「收益」来自原始摘录，不代表 LLM 摘要有效
        InMemoryMemoryStore historyB = new InMemoryMemoryStore();
        MemoryManager memB = new DefaultMemoryManager(historyB);
        SummaryService bad = mock(SummaryService.class);
        when(bad.summarize(any())).thenThrow(new RuntimeException("down"));
        SummaryCoordinator degradedCoord = new SummaryCoordinator(memB, bad, new SummaryPolicy(5, 2),
                Runnable::run, mapper(), new SummaryLock(), new AgentMetrics(registry), 30000L);
        for (int i = 0; i < 5; i++) {
            historyB.appendMessage("B", msg("hello"));
        }

        degradedCoord.maybeSummarize("B", null, null);

        assertEquals(6.0, registry.counter("agent.summary.folded_tokens", "mode", "degraded").count(), 1e-9);
        assertEquals(1, registry.find("agent.summary.compression_ratio")
                .tag("mode", "degraded").summary().count(), "两种模式分开计数");
        assertEquals(6.0, registry.counter("agent.summary.folded_tokens", "mode", "normal").count(), 1e-9,
                "降级档不得污染正常档");
    }

    /** 通过 {@code service.summarize} 驱动用例，布尔状态符合断言，实际结果与固定预期一致。 */
    @Test
    void steadyState_afterSummary_doesNotReloadWholeSession() {
        RecordingMemoryStore store = new RecordingMemoryStore();
        MemoryManager memoryManager = new DefaultMemoryManager(store);
        SummaryService service = mock(SummaryService.class);
        when(service.summarize(any())).thenAnswer(inv -> {
            MemorySnapshot s = inv.getArgument(0);
            return ConversationSummary.builder().summary("摘要:" + s.getMessageCount()).build();
        });
        SummaryCoordinator coordinator = new SummaryCoordinator(memoryManager, service,
                new SummaryPolicy(5, 2), Runnable::run, mapper(),
                new SummaryLock(), new AgentMetrics(new SimpleMeterRegistry()), 30000L);
        for (int i = 0; i < 200; i++) {
            store.appendMessage("A", msg("hello"));
        }

        coordinator.maybeSummarize("A", null, null);                   // 第一次：折 198 条，游标前进到 198
        assertEquals(1, invocationsOf(service));

        store.reset();
        coordinator.maybeSummarize("A", null, null);                   // 第二次：无新增可折

        assertEquals(0, store.fullLoadCalls, "不再走 loadRecentMessages 全量加载会话");
        assertEquals(1, invocationsOf(service), "无可折新增时不该再调一次 LLM 摘要然后丢掉");
        assertTrue(store.materializedMessages <= 32,
                "token 估算应只在尾部读一页就早停，实际搬运 " + store.materializedMessages + " 条");
        assertEquals(200, memoryManager.getRecentMessages("A", 300).size(), "历史一条不少");
    }

    /** 通过 {@code coord.snapshot}、{@code store5Messages} 驱动该场景，实际结果与固定预期一致。 */
    @Test
    void snapshot_messagesAreTheFoldBatchOnly() {
        setup(5, 2);
        store5Messages();

        MemorySnapshot snap = coord.snapshot("A");

        assertEquals(5, snap.getMessageCount(), "messageCount 仍是会话总条数");
        assertEquals(3, snap.getMessages().size(), "只有 cursor→upTo 这段最旧消息进快照（5-2=3）");
    }

    /**
     * 协调 {@code org.mockito.Mockito.mockingDetails} 完成当前阶段，供后续处理使用。
     * @param service 本次调用传入的 {@code SummaryService}
     * @return 按当前方法口径计算或读取的数值
     */
    private int invocationsOf(SummaryService service) {
        return org.mockito.Mockito.mockingDetails(service).getInvocations().size();
    }

    /** 记录「搬进内存多少条消息」的 MemoryStore 包装（P1-7 的守卫用它证明读取是分段且有界的）。 */
    static class RecordingMemoryStore implements MemoryStore {

        private final InMemoryMemoryStore delegate = new InMemoryMemoryStore();

        private int fullLoadCalls;

        private int materializedMessages;

        /** 在该类型内部准备后续运行所需的数据。 */
        void reset() {
            fullLoadCalls = 0;
            materializedMessages = 0;
        }

        /**
         * 读取持久化状态。
         * 下游调用 {@code delegate.loadRecentMessages}。
         *
         * @param sessionId 受当前用户与策略约束的服务端会话 ID
         * @param limit 最多返回或保留的条数
         * @return 按当前约定排列的模型消息列表
         */
        @Override
        public List<AgentMessage> loadRecentMessages(String sessionId, int limit) {
            fullLoadCalls++;
            List<AgentMessage> out = delegate.loadRecentMessages(sessionId, limit);
            materializedMessages += out.size();
            return out;
        }

        /**
         * 通过 {@code delegate.countMessages} 记录或统计符合当前条件的数量。
         * @param sessionId 受当前用户与策略约束的服务端会话 ID
         * @return 按当前方法口径计算或读取的数值
         */
        @Override
        public int countMessages(String sessionId) {
            return delegate.countMessages(sessionId);
        }

        /**
         * 读取持久化状态。
         * 下游调用 {@code delegate.loadMessages}。
         *
         * @param sessionId 受当前用户与策略约束的服务端会话 ID
         * @param from 包含的起始位置
         * @param to 目标区间的结束位置
         * @return 按当前约定排列的模型消息列表
         */
        @Override
        public List<AgentMessage> loadMessages(String sessionId, int from, int to) {
            List<AgentMessage> out = delegate.loadMessages(sessionId, from, to);
            materializedMessages += out.size();
            return out;
        }

        /**
         * 通过 {@code delegate.appendMessage} 把新消息追加到当前序列。
         * @param sessionId 受当前用户与策略约束的服务端会话 ID
         * @param message 待处理的消息或对外提示
         */
        public void appendMessage(String sessionId, AgentMessage message) {
            delegate.appendMessage(sessionId, message);
        }

        /**
         * 通过 {@code delegate.loadSummary} 读取持久化状态。
         * @param sessionId 受当前用户与策略约束的服务端会话 ID
         * @return 调用方使用的 {@code java.util.Optional<MemoryItem>}
         */
        @Override
        public java.util.Optional<MemoryItem> loadSummary(String sessionId) {
            return delegate.loadSummary(sessionId);
        }

        /**
         * 通过 {@code delegate.saveSummary} 保存当前计算结果供下一次读取。
         * @param sessionId 受当前用户与策略约束的服务端会话 ID
         * @param summary 本次调用传入的 {@code MemoryItem}
         */
        @Override
        public void saveSummary(String sessionId, MemoryItem summary) {
            delegate.saveSummary(sessionId, summary);
        }
    }
}
