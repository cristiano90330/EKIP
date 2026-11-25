/* 验证问答消息读写、回合原子性及异常降级规则。 */

package com.pony.knowledge.service.rag;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pony.knowledge.exception.ConversationStoreUnavailableException;
import com.pony.knowledge.exception.GlobalExceptionHandler;
import com.pony.knowledge.service.rag.query.ChatMessage;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * RedisConversationStore 的两条边界。
 *
 * <p>① **3.1 P1-13**：遇到损坏条目不再静默跳过——跳过时 WARN 带 key 名，数据损坏能被发现。
 *
 * <p>② 🔴 **2026-09-30（取舍总表 A8-06）**：Redis 宕机与「单条数据损坏」是两类失败，
 * 前者必须 fail-fast。此前本类构造时内置一个 InMemoryConversationStore 兜底，
 * Redis 一挂就静默降级成「WARN 一行 + 进程内存读写」，于是同一次对话前半段在 Redis、
 * 后半段在内存里，历史缺了几轮而调用方毫无察觉——A8-06 判为
 * 「静默少几轮是所有选项里最差的那个」，故改为异常直接上抛（→ GlobalExceptionHandler → 500）。
 */
class RedisConversationStoreTest {

    private static final String KEY = "rag:conversation:%s:messages".formatted(key("c1").storageId());

    /**
     * 在该类型内部准备后续运行所需的数据。
     * @param conversationId 本次调用传入的 {@code String}
     * @return 调用方使用的 {@code ConversationKey}
     */
    private static ConversationKey key(String conversationId) {
        return new ConversationKey(42L, 7L, conversationId);
    }

    /**
     * 协调 {@code redis.opsForList} 完成当前阶段，供后续处理使用。
     * 下游调用 {@code redis.opsForList}。
     *
     * @param listOps 本次调用传入的 {@code ListOperations<String, String>}
     * @return 调用方使用的 {@code RedisConversationStore}
     */
    private RedisConversationStore newStore(ListOperations<String, String> listOps) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForList()).thenReturn(listOps);
        return new RedisConversationStore(redis, new ObjectMapper());
    }

    /**
     * 协调 {@code redis.opsForList} 完成当前阶段，供后续处理使用。
     * 下游调用 {@code redis.opsForList}。
     *
     * @return 调用方使用的 {@code RedisConversationStore}
     */
    private RedisConversationStore storeWithRedisDown() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForList()).thenThrow(new DataAccessResourceFailureException("Redis 连接失败"));
        return new RedisConversationStore(redis, new ObjectMapper());
    }

    /**
     * 返回当前计算采用的 {@code m}。
     *
     * @param role 本次调用传入的 {@code String}
     * @param content 待处理的正文内容
     * @return 当前记录中的 {@code m}
     */
    private static ChatMessage msg(String role, String content) {
        ChatMessage m = new ChatMessage();
        m.setRole(role);
        m.setContent(content);
        return m;
    }

    /** 通过 {@code store.loadHistory}、{@code storeWithRedisDown} 驱动该场景，抛出 {@code ConversationStoreUnavailableException}、{@code DataAccessResourceFailureException}。 */
    @Test
    void redisDown_loadHistoryThrows_insteadOfSilentlyReturningEmpty() {
        RedisConversationStore store = storeWithRedisDown();

        assertThatThrownBy(() -> store.loadHistory(key("c1"), 6))
                .isInstanceOf(ConversationStoreUnavailableException.class)
                .hasCauseInstanceOf(DataAccessResourceFailureException.class);
    }

    /** 通过 {@code store.append}、{@code storeWithRedisDown} 驱动该场景，抛出 {@code ConversationStoreUnavailableException}、{@code DataAccessResourceFailureException}。 */
    @Test
    void redisDown_appendThrows_insteadOfSilentlyWritingToMemory() {
        RedisConversationStore store = storeWithRedisDown();

        assertThatThrownBy(() -> store.append(key("c1"), msg("user", "问"), msg("assistant", "答")))
                .isInstanceOf(ConversationStoreUnavailableException.class)
                .hasCauseInstanceOf(DataAccessResourceFailureException.class);
    }

    /** 构造输入与依赖返回值，实际结果与固定预期一致。 */
    @Test
    void redisUnavailableMapsToServiceUnavailable() {
        ConversationStoreUnavailableException exception = new ConversationStoreUnavailableException(
                "Redis 会话历史读取失败", new DataAccessResourceFailureException("Redis 连接失败"));

        assertThat(new GlobalExceptionHandler().handleConversationStoreUnavailable(exception).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    /** 通过 {@code store.loadHistory}、{@code store.append} 驱动该场景，核对 {@code store.loadHistory(null, 6)} 为空。 */
    @Test
    void blankConversationId_shortCircuits_withoutTouchingRedis() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RedisConversationStore store = new RedisConversationStore(redis, new ObjectMapper());

        assertThat(store.loadHistory(null, 6)).isEmpty();
            store.append(null, msg("user", "问"), msg("assistant", "答"));

        verifyNoInteractions(redis);
    }

    /** 通过 {@code history.get} 驱动用例，核对 {@code history} 长度为 {@code 1}。 */
    @Test
    void corruptedEntrySkippedWithWarnLoggingKey() {
        ch.qos.logback.classic.Logger log =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(RedisConversationStore.class);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        log.addAppender(captured);
        try {
            @SuppressWarnings("unchecked")
            ListOperations<String, String> listOps = mock(ListOperations.class);
            when(listOps.range(eq(KEY), eq(-6L), eq(-1L))).thenReturn(List.of(
                    "{\"role\":\"user\",\"content\":\"正常消息\"}",
                    "这不是合法 JSON {{{"    // 损坏条目
            ));

            RedisConversationStore store = newStore(listOps);
            List<ChatMessage> history = store.loadHistory(key("c1"), 6);

            // 行为保持：损坏条目跳过，合法条目照常返回
            assertThat(history).hasSize(1);
            assertThat(history.get(0).getContent()).isEqualTo("正常消息");

            // P1-13：跳过不再静默——WARN 带 key 名
            assertThat(captured.list).anyMatch(e ->
                    e.getLevel() == Level.WARN
                            && e.getFormattedMessage().contains("损坏条目")
                            && e.getFormattedMessage().contains(KEY));
        } finally {
            log.detachAppender(captured);
        }
    }

    /** 通过 {@code captured.start}、{@code log.addAppender} 驱动该场景，核对 {@code captured.list} 长度为 {@code 2}。 */
    @Test
    void allCorruptedStillReturnsEmptyAndLogsEach() {
        ch.qos.logback.classic.Logger log =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(RedisConversationStore.class);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        log.addAppender(captured);
        try {
            @SuppressWarnings("unchecked")
            ListOperations<String, String> listOps = mock(ListOperations.class);
            when(listOps.range(eq(KEY), eq(-6L), eq(-1L))).thenReturn(List.of("坏1", "坏2"));

            RedisConversationStore store = newStore(listOps);
            assertThat(store.loadHistory(key("c1"), 6)).isEmpty();

            assertThat(captured.list).hasSize(2);
            assertThat(captured.list).allMatch(e -> e.getLevel() == Level.WARN
                    && e.getFormattedMessage().contains(KEY));
        } finally {
            log.detachAppender(captured);
        }
    }
}
