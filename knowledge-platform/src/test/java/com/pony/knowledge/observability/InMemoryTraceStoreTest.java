/* 检查内存 Trace 存储的容量、读取顺序与筛选行为。 */

package com.pony.knowledge.observability;

import com.pony.knowledge.utils.CurrentUserUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryTraceStoreTest {

    private static final Long OWNER = 7L;
    private static final Long OTHER = 8L;

    private final CurrentUserUtil currentUserUtil = new CurrentUserUtil();

    /** 每个用例前准备夹具，调用 {@code authenticateAs}。 */
    @BeforeEach
    void authenticateOwner() {
        authenticateAs(OWNER);
    }

    /** 回收当前用例的资源和上下文，调用 {@code SecurityContextHolder.clearContext}。 */
    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    /**
     * 在测试中处理 {@code authenticateAs} 阶段，使用 {@code SecurityContextHolder.getContext}。
     * 由 {@code authenticateOwner}、{@code 他人与未知归属的记录不可见} 调用。
     * @param userId 账号 ID
     */
    private static void authenticateAs(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null, null));
    }

    /**
     * 构造 {@code RagTrace} 供测试使用，使用 {@code RagTrace.builder}。
     * 由 {@code 超过retention淘汰最旧}、{@code listRecent按时间倒序} 调用。
     * @param id 目标记录 ID
     * @param epochMs 本用例传入的{@code epochMs}
     * 返回 {@code RagTrace.builder().traceId(id).startTimeEpochMs(epochMs).build()}。
     */
    private RagTrace tr(String id, long epochMs) {
        return RagTrace.builder().traceId(id).startTimeEpochMs(epochMs).build();
    }

    /**
     * 构造 {@code InMemoryTraceStore} 供测试使用。
     * 由 {@code 超过retention淘汰最旧}、{@code listRecent按时间倒序} 调用。
     * 接收{@code retention}。
     * 返回 {@code new InMemoryTraceStore(retention, currentUserUtil)}。
     */
    private InMemoryTraceStore store(int retention) {
        return new InMemoryTraceStore(retention, currentUserUtil);
    }

    /** 验证超过retention淘汰最旧。通过 {@code store.save} 执行本例并核对输出或记录。 */
    @Test
    void 超过retention淘汰最旧() {
        InMemoryTraceStore store = store(2);
        store.save(tr("a", 100), "chat");
        store.save(tr("b", 200), "chat");
        store.save(tr("c", 300), "chat");
        assertThat(store.findByIdForOwner("a", OWNER)).isNull();
        assertThat(store.findByIdForOwner("b", OWNER)).isNotNull();
        assertThat(store.findByIdForOwner("c", OWNER)).isNotNull();
    }

    /** 验证listRecent按时间倒序。通过 {@code store.save} 执行本例并核对输出或记录。 */
    @Test
    void listRecent按时间倒序() {
        InMemoryTraceStore store = store(10);
        store.save(tr("a", 100), "chat");
        store.save(tr("b", 300), "ask");
        store.save(tr("c", 200), "chat");
        List<RagTrace> r = store.listRecentForOwner(OWNER);
        assertThat(r).extracting(RagTrace::getTraceId).containsExactly("b", "c", "a");
    }

    /** 验证save回写entrypoint。通过 {@code store.save} 执行本例并核对输出或记录。 */
    @Test
    void save回写entrypoint() {
        InMemoryTraceStore store = store(10);
        store.save(tr("a", 100), "agent_chat");
        assertThat(store.findByIdForOwner("a", OWNER).getEntrypoint()).isEqualTo("agent_chat");
    }

    /** 验证save按认证上下文补充归属。通过 {@code store.save} 执行本例并核对输出或记录。 */
    @Test
    void save按认证上下文补充归属() {
        InMemoryTraceStore store = store(10);
        store.save(tr("a", 100), "chat");

        assertThat(store.findByIdForOwner("a", OWNER).getUserId()).isEqualTo(OWNER);
    }

    /** 验证他人与未知归属的记录不可见。通过 {@code store.save} 执行本例并核对输出或记录。 */
    @Test
    void 他人与未知归属的记录不可见() {
        InMemoryTraceStore store = store(10);
        store.save(tr("mine", 100), "chat");
        authenticateAs(OTHER);
        store.save(tr("other", 200), "chat");
        SecurityContextHolder.clearContext();
        store.save(tr("orphan", 300), "chat");

        authenticateAs(OWNER);
        assertThat(store.listRecentForOwner(OWNER))
                .extracting(RagTrace::getTraceId).containsExactly("mine");
        assertThat(store.findByIdForOwner("other", OWNER)).isNull();
        assertThat(store.findByIdForOwner("orphan", OWNER)).isNull();
        assertThat(store.listRecentForOwner(null)).isEmpty();
    }
}
