/* 验证会话发放、指纹变化、历史分页、过期和管理权限。 */

package com.pony.knowledge.agent.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pony.knowledge.agent.memory.MemoryItem;
import com.pony.knowledge.agent.memory.store.InMemoryMemoryStore;
import com.pony.knowledge.agent.model.AgentMessage;
import com.pony.knowledge.agent.model.AgentResponse;
import com.pony.knowledge.agent.model.StopReason;
import com.pony.knowledge.agent.profile.AgentProfileService;
import com.pony.knowledge.agent.profile.ResolvedAgentProfile;
import com.pony.knowledge.dto.SourceDTO;
import com.pony.knowledge.exception.BusinessException;
import com.pony.knowledge.exception.ForbiddenException;
import com.pony.knowledge.service.KnowledgeBaseAccessService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 产品会话安全/生命周期的定向验证；不访问 Redis、数据库或模型。 */
class AgentSessionServiceTest {

    /** 通过 {@code AgentPolicyFingerprint.resolve}、{@code policy.memoryReadEnabled} 驱动该场景，布尔状态符合断言。 */
    @Test
    void resolvedMemoryFlagsAreVisibleInTheModelPrompt() {
        var policy = AgentPolicyFingerprint.resolve(1L,
                new ResolvedAgentProfile(9L, "prompt", Set.of(), Set.of(), "general", false, true),
                Set.of(), true);
        assertFalse(policy.memoryReadEnabled());
        assertTrue(policy.memoryWriteEnabled());
        assertTrue(policy.systemPrompt().contains("读取=false，写入=true"));
    }
    private final InMemoryAgentSessionStore store = new InMemoryAgentSessionStore();
    private final AgentProfileService profiles = mock(AgentProfileService.class);
    private final KnowledgeBaseAccessService access = mock(KnowledgeBaseAccessService.class);
    private final AgentSessionService service = new AgentSessionService(store, profiles, access, 30);

    /**
     * 通过 {@code access.resolveReadableIds} 创建当前入口需要的对象或持久化记录。
     * @return 会话的客户端视图
     */
    private AgentSession.View create() {
        when(access.resolveReadableIds(1L)).thenReturn(Set.of(2L));
        return service.create(1L, null);
    }

    /**
     * 协调 {@code service.begin} 完成当前阶段，供后续处理使用。
     * @param session 会话元数据及归属信息
     * @return 调用方使用的 {@code AgentSessionService.TurnHandle}
     */
    private AgentSessionService.TurnHandle begin(AgentSession.View session) {
        return service.begin(1L, session.id(), session.agentId(), store.get(session.id()).orElseThrow().fingerprint());
    }

    /** 提供按当前约定排列的模型消息列表。 */
    private List<AgentMessage> messages() {
        return List.of(AgentMessage.builder().role("user").content("q").build(),
                AgentMessage.builder().role("assistant").content("a").build());
    }

    /** 通过 {@code service.delete}、{@code service.history} 驱动用例，抛出 {@code BusinessException}、{@code ForbiddenException}，布尔状态符合断言。 */
    @Test
    void completedTurnRestoresOutcomeSourcesAndBoundedSteps_andDeletesAllMemory() throws Exception {
        AgentSession.View session = create();
        AgentSessionService.TurnHandle turn = begin(session);
        assertEquals("general", session.answerPolicy());
        assertThrows(BusinessException.class, () -> service.delete(1L, session.id()));
        assertThrows(BusinessException.class, () -> begin(session));

        SourceDTO source = new SourceDTO();
        source.setCitationId("S3_5"); source.setDocumentId(3L); source.setChunkId(5L);
        source.setKnowledgeBaseId(2L); source.setContent("获准内容");
        AgentResponse response = AgentResponse.builder().answer("答案 [[cite:S3_5]]")
                .outcome("dependency_unavailable").reasonCode("partial_failure")
                .stopReason(StopReason.LLM_DEGRADED).degraded(true).degradeReason("provider unavailable")
                .sources(List.of(source)).toolInvocations(List.of(AgentResponse.ToolInvocation.builder()
                        .toolName("knowledge_search").success(true).outcome("no_evidence")
                        .reasonCode("below_threshold").dataSummary("未获准").build())).build();
        service.complete(turn, "测试问题", response, messages());
        service.end(turn);

        AgentSessionService.HistoryPage page = service.history(1L, session.id(), 0, 20);
        assertEquals(1, page.items().size());
        AgentSessionTurn restored = page.items().get(0);
        assertEquals("S3_5", restored.sources().get(0).getCitationId());
        assertEquals("dependency_unavailable", restored.outcome());
        assertEquals("partial_failure", restored.reasonCode());
        assertEquals(StopReason.LLM_DEGRADED, restored.stopReason());
        assertEquals("no_evidence", restored.steps().get(0).getOutcome());
        // Redis 持久化会用同样的 JSON 契约恢复 record、步骤和公开来源字段。
        ObjectMapper mapper = new ObjectMapper();
        AgentSessionTurn decoded = mapper.readValue(mapper.writeValueAsString(restored), AgentSessionTurn.class);
        assertEquals("S3_5", decoded.sources().get(0).getCitationId());

        InMemoryMemoryStore memory = new InMemoryMemoryStore(store);
        assertEquals(2, memory.countMessages(turn.session().memoryId()));
        assertFalse(page.hasMore());
        assertTrue(service.history(1L, session.id(), 500, 20).items().isEmpty());
        service.delete(1L, session.id());
        memory.saveSummary(turn.session().memoryId(), MemoryItem.builder().content("晚到摘要").build());
        assertEquals(0, memory.countMessages(turn.session().memoryId()));
        assertTrue(memory.loadSummary(turn.session().memoryId()).isEmpty());
        assertTrue(service.list(1L, 0, 20).items().isEmpty());
        assertThrows(ForbiddenException.class, () -> service.history(1L, session.id(), 0, 20));
    }

    /** 通过 {@code service.history}、{@code service.delete} 驱动用例，抛出 {@code ForbiddenException}，布尔状态符合断言。 */
    @Test
    void userOwnershipAndCurrentPolicyAreRecheckedBeforeLoadingOrContinuing() {
        AgentSession.View session = create();
        assertThrows(ForbiddenException.class, () -> service.history(7L, session.id(), 0, 20));
        assertThrows(ForbiddenException.class, () -> service.delete(7L, session.id()));
        assertThrows(ForbiddenException.class, () -> service.begin(7L, session.id(), null, "forged"));
        assertThrows(ForbiddenException.class, () -> service.begin(1L, session.id(), 4L,
                store.get(session.id()).orElseThrow().fingerprint()));
        when(access.resolveReadableIds(1L)).thenReturn(Set.of());
        assertThrows(ForbiddenException.class, () -> service.history(1L, session.id(), 0, 20));
        assertTrue(service.list(1L, 0, 20).items().isEmpty());
        // 失权会话仍能由所属用户删除，不能要求用户重新获得知识库权限才能清理。
        service.delete(1L, session.id());
    }

    /** 更改回答策略并生成取消回合，检查旧历史不再可续读，取消结果也未写入新历史。 */
    @Test
    void answerPolicyChangesInvalidateExistingHistory_andCancelledTurnsAreNotSaved() {
        when(access.resolveReadableIds(1L)).thenReturn(Set.of(2L));
        ResolvedAgentProfile grounded = new ResolvedAgentProfile(9L, "prompt", Set.of(2L), Set.of(), "grounded");
        when(profiles.resolveForExecution(1L, 9L, Set.of(2L))).thenReturn(grounded);
        AgentSession.View session = service.create(1L, 9L);
        AgentSessionService.TurnHandle turn = begin(session);
        service.complete(turn, "取消", AgentResponse.builder().stopReason(StopReason.CANCELLED).build(), messages());
        service.end(turn);
        assertTrue(service.history(1L, session.id(), 0, 20).items().isEmpty());
        assertEquals(0, store.countMessages(session.id()));

        when(profiles.resolveForExecution(1L, 9L, Set.of(2L)))
                .thenReturn(new ResolvedAgentProfile(9L, "prompt", Set.of(2L), Set.of(), "general"));
        assertThrows(ForbiddenException.class, () -> service.history(1L, session.id(), 0, 20));
        assertNotEquals(store.get(session.id()).orElseThrow().fingerprint(),
                AgentPolicyFingerprint.fingerprint(1L, new ResolvedAgentProfile(9L, "prompt", Set.of(2L), Set.of(), "general"),
                        Set.of(2L), Set.of(), AgentPolicyFingerprint.systemPrompt(
                                new ResolvedAgentProfile(9L, "prompt", Set.of(2L), Set.of(), "general"))));
        assertThrows(ForbiddenException.class, () -> service.begin(1L, null, null, "unused"));
        assertThrows(ForbiddenException.class, () -> service.begin(1L, "legacy-client-id", null, "unused"));
    }

    /** 通过 {@code service.history}、{@code store.get} 驱动用例，抛出 {@code ForbiddenException}，布尔状态符合断言。 */
    @Test
    void memoryPolicyChangesInvalidateHistory_includingGlobalSwitchForDefaultAgent() {
        AgentSession.View defaultSession = create();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "longTermMemoryEnabled", false);
        assertThrows(ForbiddenException.class, () -> service.history(1L, defaultSession.id(), 0, 20));
        AgentPolicyFingerprint.Policy off = AgentPolicyFingerprint.resolve(1L, null, Set.of(2L), false);
        assertFalse(off.memoryReadEnabled());
        assertFalse(off.memoryWriteEnabled());
        assertNotEquals(store.get(defaultSession.id()).orElseThrow().fingerprint(), off.fingerprint());
        service.delete(1L, defaultSession.id());

        org.springframework.test.util.ReflectionTestUtils.setField(service, "longTermMemoryEnabled", true);
        when(profiles.resolveForExecution(1L, 9L, Set.of(2L)))
                .thenReturn(new ResolvedAgentProfile(9L, "p", Set.of(2L), Set.of(), "general", true, true));
        AgentSession.View custom = service.create(1L, 9L);
        when(profiles.resolveForExecution(1L, 9L, Set.of(2L)))
                .thenReturn(new ResolvedAgentProfile(9L, "p", Set.of(2L), Set.of(), "general", false, true));
        assertThrows(ForbiddenException.class, () -> service.history(1L, custom.id(), 0, 20));
        assertNotEquals(AgentPolicyFingerprint.resolve(1L,
                        new ResolvedAgentProfile(9L, "p", Set.of(2L), Set.of(), "general", false, true), Set.of(2L), true).fingerprint(),
                AgentPolicyFingerprint.resolve(1L,
                        new ResolvedAgentProfile(9L, "p", Set.of(2L), Set.of(), "general", false, false), Set.of(2L), true).fingerprint());
    }

    /** 让会话过期后读取历史并尝试保存晚到摘要，核对数据按到期约束处理且分页有界。 */
    @Test
    void expiredSessionDropsMessagesAndLateSummary_andHistoryReadsAreBounded() {
        AgentSession.View session = create();
        AgentSession original = store.get(session.id()).orElseThrow();
        AgentSession expired = new AgentSession("as_00000000-0000-0000-0000-000000000000", 1L, null,
                original.fingerprint(), original.answerPolicy(), "过期", 1, 1, 2);
        store.create(expired);
        store.saveSummary(expired.id(), MemoryItem.builder().content("late").build());
        assertTrue(store.get(expired.id()).isEmpty());
        assertTrue(store.summary(expired.id()).isEmpty());
        assertThrows(BusinessException.class, () -> service.history(1L, session.id(), 0, 51));
        assertThrows(BusinessException.class, () -> service.list(1L, -1, 20));
        assertEquals(1, service.list(1L, 0, 20).items().size());
    }
}
