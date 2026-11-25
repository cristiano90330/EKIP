/* 验证自建 Agent 配置的所有权、范围与工具白名单校验。 */

package com.pony.knowledge.agent.profile;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pony.knowledge.agent.model.AgentExecutionContext;
import com.pony.knowledge.agent.model.AgentPrompts;
import com.pony.knowledge.agent.tool.AgentTool;
import com.pony.knowledge.agent.tool.ToolDefinition;
import com.pony.knowledge.agent.tool.ToolRegistry;
import com.pony.knowledge.agent.tool.input.ToolInput;
import com.pony.knowledge.agent.tool.output.ToolResult;
import com.pony.knowledge.exception.BusinessException;
import com.pony.knowledge.exception.ForbiddenException;
import com.pony.knowledge.service.KnowledgeBaseAccessService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** B 工作包：Agent 配置的归属校验、保存期校验与执行期 fail-close。 */
class AgentProfileServiceTest {

    private final AgentProfileMapper mapper = mock(AgentProfileMapper.class);

    private final KnowledgeBaseAccessService accessService = mock(KnowledgeBaseAccessService.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ToolRegistry toolRegistry = new ToolRegistry(
            List.of(new NamedTool("knowledge_search"), new NamedTool("knowledge_ask")));

    private final AgentProfileService service =
            new AgentProfileService(mapper, accessService, toolRegistry, objectMapper);

    /**
     * 返回当前计算采用的 {@code request}。
     *
     * @param mode 本次调用传入的 {@code String}
     * @param kbIds 本次调用传入的 {@code List<Long>}
     * @param tools 本次调用传入的 {@code List<String>}
     * @return 当前记录中的 {@code request}
     */
    private AgentProfileRequest request(String mode, List<Long> kbIds, List<String> tools) {
        AgentProfileRequest request = new AgentProfileRequest();
        request.setName("我的 Agent");
        request.setKbSelectionMode(mode);
        request.setKnowledgeBaseIds(kbIds);
        request.setAllowedTools(tools);
        return request;
    }

    /**
     * 返回当前计算采用的 {@code profile}。
     *
     * @param id 目标自建 Agent 配置 ID
     * @param userId 当前用户 ID
     * @param mode 本次调用传入的 {@code String}
     * @param kbIdsJson 本次调用传入的 {@code String}
     * @param toolsJson 本次调用传入的 {@code String}
     * @param prompt 本次调用传入的 {@code String}
     * @return 数据库持久化的自建 Agent 配置实体
     */
    private AgentProfile profile(Long id, Long userId, String mode, String kbIdsJson,
                                 String toolsJson, String prompt) {
        AgentProfile profile = new AgentProfile();
        profile.setId(id);
        profile.setUserId(userId);
        profile.setName("配置");
        profile.setKbSelectionMode(mode);
        profile.setKnowledgeBaseIdsJson(kbIdsJson);
        profile.setAllowedToolsJson(toolsJson);
        profile.setSystemPrompt(prompt);
        return profile;
    }

    /** 通过 {@code service.create}、{@code objectMapper.readValue} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void create_selectedProfile_validatesEveryKb_andPersistsNormalizedJson() throws Exception {
        when(accessService.canRead(1L, 1L)).thenReturn(true);
        when(accessService.canRead(1L, 2L)).thenReturn(true);

        AgentProfileResponse response = service.create(1L,
                request("selected", List.of(1L, 2L, 2L), List.of("knowledge_search")));

        ArgumentCaptor<AgentProfile> inserted = ArgumentCaptor.forClass(AgentProfile.class);
        verify(mapper).insert(inserted.capture());
        AgentProfile entity = inserted.getValue();
        assertEquals(1L, entity.getUserId(), "归属取当前认证用户，不接受客户端指定");
        assertEquals("selected", entity.getKbSelectionMode());
        assertEquals("grounded", entity.getAnswerPolicy());
        assertEquals("grounded", response.getAnswerPolicy());
        assertEquals(List.of(1L, 2L),
                objectMapper.readValue(entity.getKnowledgeBaseIdsJson(), new TypeReference<List<Long>>() { }));
        assertEquals(List.of("knowledge_search"),
                objectMapper.readValue(entity.getAllowedToolsJson(), new TypeReference<List<String>>() { }));
        assertEquals(2, response.getKnowledgeBaseIds().size());
    }

    /** 通过 {@code service.create} 驱动用例，抛出 {@code BusinessException}，跳过受保护的调用。 */
    @Test
    void create_selectedProfile_withoutKb_isRejected() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.create(1L, request("selected", List.of(), List.of())));

        assertTrue(error.getMessage().contains("至少选择"));
        verify(mapper, never()).insert(any(AgentProfile.class));
    }

    /** 通过 {@code service.create} 驱动用例，抛出 {@code ForbiddenException}，跳过受保护的调用。 */
    @Test
    void create_selectedProfile_withUnreadableKb_isForbidden_andDoesNotInsert() {
        when(accessService.canRead(1L, 1L)).thenReturn(true);
        when(accessService.canRead(1L, 2L)).thenReturn(false);

        assertThrows(ForbiddenException.class,
                () -> service.create(1L, request("selected", List.of(1L, 2L), List.of())));

        verify(mapper, never()).insert(any(AgentProfile.class));
    }

    /** 通过 {@code service.create} 驱动用例，抛出 {@code BusinessException}，跳过受保护的调用。 */
    @Test
    void create_unknownTool_isRejected() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.create(1L, request("all", List.of(), List.of("shell_exec"))));

        assertTrue(error.getMessage().contains("未知工具"));
        verify(mapper, never()).insert(any(AgentProfile.class));
    }

    /** 通过 {@code service.create} 驱动用例，抛出 {@code BusinessException}，跳过受保护的调用。 */
    @Test
    void create_allModeWithExplicitKbs_isRejected() {
        assertThrows(BusinessException.class,
                () -> service.create(1L, request("all", List.of(1L), List.of())));

        verify(mapper, never()).insert(any(AgentProfile.class));
    }

    /** 通过 {@code service.update} 驱动用例，抛出 {@code ForbiddenException}，跳过受保护的调用。 */
    @Test
    void update_profileOfAnotherUser_isForbidden_withoutWriting() {
        when(mapper.selectOwned(9L, 1L)).thenReturn(null);

        assertThrows(ForbiddenException.class,
                () -> service.update(1L, 9L, request("all", List.of(), List.of())));

        verify(mapper, never()).updateById(any(AgentProfile.class));
    }

    /** 通过 {@code mapper.selectOwned}、{@code service.resolveForExecution} 驱动该场景，抛出 {@code ForbiddenException}。 */
    @Test
    void resolve_selectedProfile_afterKbAccessLost_failsClosed() {
        when(mapper.selectOwned(9L, 1L)).thenReturn(
                profile(9L, 1L, "selected", "[1,2]", "[\"knowledge_search\"]", null));

        assertThrows(ForbiddenException.class,
                () -> service.resolveForExecution(1L, 9L, Set.of(1L)));
    }

    /** 通过 {@code mapper.selectOwned}、{@code service.resolveForExecution} 驱动该场景，实际结果与固定预期一致。 */
    @Test
    void resolve_allMode_usesCurrentReadableSet() {
        when(mapper.selectOwned(9L, 1L)).thenReturn(
                profile(9L, 1L, "all", "[]", "[\"knowledge_search\"]", null));

        ResolvedAgentProfile resolved = service.resolveForExecution(1L, 9L, Set.of(1L, 3L));

        assertEquals(Set.of(1L, 3L), resolved.knowledgeBaseIds());
        assertEquals(Set.of("knowledge_search"), resolved.allowedToolNames());
    }

    /** 通过 {@code mapper.selectOwned}、{@code service.resolveForExecution} 驱动该场景，布尔状态符合断言，返回的数据、边界或顺序符合断言。 */
    @Test
    void resolve_noneMode_returnsEmptyScope() {
        when(mapper.selectOwned(9L, 1L)).thenReturn(
                profile(9L, 1L, "none", "[]", "[]", null));

        ResolvedAgentProfile resolved = service.resolveForExecution(1L, 9L, Set.of(1L, 3L));

        assertTrue(resolved.knowledgeBaseIds().isEmpty());
        assertTrue(resolved.allowedToolNames().isEmpty());
    }

    /** 通过 {@code mapper.selectOwned}、{@code service.resolveForExecution} 驱动该场景，抛出 {@code BusinessException}，布尔状态符合断言。 */
    @Test
    void resolve_profileWithUnregisteredTool_failsClosed() {
        when(mapper.selectOwned(9L, 1L)).thenReturn(
                profile(9L, 1L, "all", "[]", "[\"retired_tool\"]", null));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.resolveForExecution(1L, 9L, Set.of(1L)));

        assertTrue(error.getMessage().contains("不可用"));
    }

    /** 通过 {@code mapper.selectOwned}、{@code service.resolveForExecution} 驱动该场景，抛出 {@code ForbiddenException}。 */
    @Test
    void resolve_otherUsersProfile_isForbidden() {
        when(mapper.selectOwned(9L, 1L)).thenReturn(null);

        assertThrows(ForbiddenException.class,
                () -> service.resolveForExecution(1L, 9L, Set.of(1L)));
    }

    /** 通过 {@code mapper.selectOwned}、{@code service.resolveForExecution} 驱动该场景，实际结果与固定预期一致。 */
    @Test
    void resolve_blankPrompt_fallsBackToPlatformDefault() {
        when(mapper.selectOwned(9L, 1L)).thenReturn(
                profile(9L, 1L, "all", "[]", "[]", "  "));

        ResolvedAgentProfile resolved = service.resolveForExecution(1L, 9L, Set.of(1L));

        assertEquals(AgentPrompts.KNOWLEDGE_ASSISTANT_SYSTEM, resolved.systemPrompt());
    }

    /** 通过 {@code service.list}、{@code responses.get} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void list_isScopedToCurrentUser() {
        when(mapper.selectByUserId(1L)).thenReturn(List.of(
                profile(9L, 1L, "all", "[]", "[]", null)));

        List<AgentProfileResponse> responses = service.list(1L);

        assertEquals(1, responses.size());
        assertEquals(9L, responses.get(0).getId());
        verify(mapper).selectByUserId(1L);
    }

    /** 通过 {@code mapper.delete}、{@code service.delete} 驱动用例，核对输出和依赖调用。 */
    @Test
    void delete_ownedProfile_removesByUserScope() {
        when(mapper.selectOwned(9L, 1L)).thenReturn(profile(9L, 1L, "all", "[]", "[]", null));
        when(mapper.delete(any(Wrapper.class))).thenReturn(1);

        service.delete(1L, 9L);

        verify(mapper).delete(any(Wrapper.class));
    }

    /** 通过 {@code service.delete} 驱动用例，抛出 {@code ForbiddenException}，跳过受保护的调用。 */
    @Test
    void delete_profileOfAnotherUser_isForbidden() {
        when(mapper.selectOwned(9L, 1L)).thenReturn(null);

        assertThrows(ForbiddenException.class, () -> service.delete(1L, 9L));

        verify(mapper, never()).delete(any(Wrapper.class));
    }

    /** 通过 {@code service.create} 驱动用例，抛出 {@code BusinessException}，实际结果与固定预期一致。 */
    @Test
    void answerPolicy_generalRoundTrips_andUnknownPolicyIsRejected() {
        AgentProfileRequest request = request("none", List.of(), List.of());
        request.setAnswerPolicy("general");
        assertEquals("general", service.create(1L, request).getAnswerPolicy());
        AgentProfile stored = profile(9L, 1L, "none", "[]", "[]", null);
        stored.setAnswerPolicy("general");
        when(mapper.selectOwned(9L, 1L)).thenReturn(stored);
        assertEquals("general", service.resolveForExecution(1L, 9L, Set.of()).answerPolicy());
        request.setAnswerPolicy("guess");
        assertThrows(BusinessException.class, () -> service.create(1L, request));
    }

    /** 通过 {@code service.create} 驱动用例，布尔状态符合断言。 */
    @Test
    void memoryPolicies_defaultTrue_andExplicitFalseRoundTrip() {
        AgentProfileRequest request = request("none", List.of(), List.of());
        AgentProfileResponse defaults = service.create(1L, request);
        assertTrue(defaults.getMemoryReadEnabled());
        assertTrue(defaults.getMemoryWriteEnabled());
        request.setMemoryReadEnabled(false);
        request.setMemoryWriteEnabled(false);
        AgentProfileResponse saved = service.create(1L, request);
        org.junit.jupiter.api.Assertions.assertFalse(saved.getMemoryReadEnabled());
        org.junit.jupiter.api.Assertions.assertFalse(saved.getMemoryWriteEnabled());
        AgentProfile stored = profile(9L, 1L, "none", "[]", "[]", null);
        stored.setMemoryReadEnabled(false);
        stored.setMemoryWriteEnabled(false);
        when(mapper.selectOwned(9L, 1L)).thenReturn(stored);
        ResolvedAgentProfile resolved = service.resolveForExecution(1L, 9L, Set.of());
        org.junit.jupiter.api.Assertions.assertFalse(resolved.memoryReadEnabled());
        org.junit.jupiter.api.Assertions.assertFalse(resolved.memoryWriteEnabled());
        assertTrue(new ResolvedAgentProfile(9L, "p", Set.of(), Set.of()).memoryReadEnabled());
        assertTrue(new ResolvedAgentProfile(9L, "p", Set.of(), Set.of(), "general").memoryWriteEnabled());
    }

    /** 只暴露名字的工具。 */
    private record NamedTool(String name) implements AgentTool {

        /** 声明此工具对模型公开的属性，包括用途与输入类型。 */
        @Override
        public ToolDefinition definition() {
            return ToolDefinition.builder().name(name)
                    .parameters(Map.of("type", "object", "properties", Map.of()))
                    .build();
        }

        /**
         * 执行此工具所定义的业务操作，使用上下文中的身份与访问范围。
         * @param input 本次操作的结构化输入
         * @param context 可信执行上下文，包含身份、范围和本轮执行状态
         * @return 带载荷或可识别错误状态的工具结果
         */
        @Override
        public ToolResult execute(ToolInput input, AgentExecutionContext context) {
            throw new UnsupportedOperationException("仅用于注册表测试");
        }
    }
}
