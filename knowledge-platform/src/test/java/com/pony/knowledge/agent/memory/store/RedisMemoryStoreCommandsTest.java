/* 验证 Agent 记忆通过产品会话读取和保存，并隔离旧会话键及存储错误。 */

package com.pony.knowledge.agent.memory.store;

import com.pony.knowledge.agent.memory.MemoryItem;
import com.pony.knowledge.agent.model.AgentMessage;
import com.pony.knowledge.agent.session.AgentSessionStore;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RedisMemoryStoreCommandsTest {
    private static final String SESSION = "as_123e4567-e89b-12d3-a456-426614174000";
    private static final String MEMORY = "managed:" + SESSION;

    private final AgentSessionStore sessions = mock(AgentSessionStore.class);
    private final RedisMemoryStore store = new RedisMemoryStore(sessions);

    /** 通过 {@code store.saveSummary} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void readsRecentMessagesAndSavesSummaryThroughProductSession() {
        List<AgentMessage> messages = List.of(AgentMessage.builder().role("user").content("q").build());
        when(sessions.countMessages(SESSION)).thenReturn(6);
        when(sessions.messages(SESSION, 4, 6)).thenReturn(messages);
        MemoryItem summary = MemoryItem.builder().content("summary").build();
        when(sessions.summary(SESSION)).thenReturn(Optional.of(summary));

        assertEquals(messages, store.loadRecentMessages(MEMORY, 2));
        assertEquals(summary, store.loadSummary(MEMORY).orElseThrow());
        store.saveSummary(MEMORY, summary);
        verify(sessions).saveSummary(SESSION, summary);
    }

    /** 通过 {@code store.loadRecentMessages} 驱动该场景，抛出 {@code IllegalArgumentException}，跳过受保护的调用。 */
    @Test
    void rejectsHistoricalMemoryKeyBeforeCallingTheStore() {
        assertThrows(IllegalArgumentException.class, () -> store.loadRecentMessages("1:agent:v2:old", 2));
        verifyNoInteractions(sessions);
    }

    /** 通过 {@code sessions.countMessages}、{@code store.loadRecentMessages} 驱动该场景，抛出 {@code RedisConnectionFailureException}。 */
    @Test
    void reportsProductSessionStorageFailure() {
        when(sessions.countMessages(SESSION)).thenThrow(new RedisConnectionFailureException("unavailable"));
        assertThrows(RedisConnectionFailureException.class, () -> store.loadRecentMessages(MEMORY, 2));
    }
}
