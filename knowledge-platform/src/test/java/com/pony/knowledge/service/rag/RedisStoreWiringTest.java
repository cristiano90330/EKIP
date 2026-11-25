/* 验证会话、任务和 Agent 采用当前 Redis 存储组件的装配方式。 */

package com.pony.knowledge.service.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pony.knowledge.agent.memory.MemoryStore;
import com.pony.knowledge.agent.memory.store.RedisMemoryStore;
import com.pony.knowledge.agent.memory.longterm.LongTermMemoryStore;
import com.pony.knowledge.agent.memory.longterm.RedisLongTermMemoryStore;
import com.pony.knowledge.agent.session.AgentSessionStore;
import com.pony.knowledge.agent.session.RedisAgentSessionStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RedisStoreWiringTest {
    /** 构造输入与依赖返回值，核对输出和依赖调用。 */
    @Test void runtimeAlwaysWiresExactlyOneRedisStoreForEachLifecycle() {
        new ApplicationContextRunner()
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
                .withUserConfiguration(RedisConversationStore.class, RedisMemoryStore.class,
                        RedisAgentSessionStore.class, RedisLongTermMemoryStore.class)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ConversationStore.class)
                            .hasSingleBean(MemoryStore.class).hasSingleBean(AgentSessionStore.class)
                            .hasSingleBean(LongTermMemoryStore.class);
                    assertThat(context.getBean(ConversationStore.class)).isInstanceOf(RedisConversationStore.class);
                    assertThat(context.getBean(MemoryStore.class)).isInstanceOf(RedisMemoryStore.class);
                    assertThat(context.getBean(AgentSessionStore.class)).isInstanceOf(RedisAgentSessionStore.class);
                    assertThat(context.getBean(LongTermMemoryStore.class)).isInstanceOf(RedisLongTermMemoryStore.class);
                });
    }
}
