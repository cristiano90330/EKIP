/* 管理用户的自建 Agent 配置，并在执行前解析当前授权范围、白名单和模型。 */

package com.pony.knowledge.agent.profile;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pony.knowledge.agent.model.AgentPrompts;
import com.pony.knowledge.agent.tool.ToolRegistry;
import com.pony.knowledge.exception.BusinessException;
import com.pony.knowledge.exception.ForbiddenException;
import com.pony.knowledge.service.KnowledgeBaseAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 用户自有 Agent 配置的读写与执行期解析。
 *
 * <p>配置里的知识库 ID 与工具名只是上限：保存时验证一次，执行时再按当前可读范围与已注册工具
 * 重新求交。任何一项失权或失效都 fail-close，不静默收窄成另一个可执行范围。
 */
@Service
@RequiredArgsConstructor
public class AgentProfileService {

    private final AgentProfileMapper agentProfileMapper;

    private final KnowledgeBaseAccessService knowledgeBaseAccessService;

    private final ToolRegistry toolRegistry;

    private final ObjectMapper objectMapper;

    private com.pony.knowledge.modelmanagement.ModelRegistryService modelRegistry;

    /**
     * 注入模型登记入口，供自建 Agent 校验和解析聊天模型绑定。
     *
     * @param models 读取模型用途及执行配置的注册服务
     */
    @org.springframework.beans.factory.annotation.Autowired
    public void setModelRegistry(com.pony.knowledge.modelmanagement.ModelRegistryService models) { this.modelRegistry=models; }

    @Value("${agent.memory.long-term.enabled:true}")
    private boolean longTermMemoryEnabled = true;

    /**
     * 列出指定用户拥有的 Agent 配置并映射到公开视图。
     * 上游由 {@code AgentProfileController.list} 调用。
     * 下游调用 {@code agentProfileMapper.selectByUserId}、{@code this::toResponse}。
     *
     * @param userId 当前用户 ID
     * @return 调用方使用的 {@code List<AgentProfileResponse>}
     */
    public List<AgentProfileResponse> list(Long userId) {
        return agentProfileMapper.selectByUserId(userId).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * 查询单个归属匹配的 Agent 配置并返回公开字段。
     * 上游由 {@code AgentProfileController.get} 调用。
     * 下游调用 {@code toResponse}、{@code requireOwned}。
     *
     * @param userId 当前用户 ID
     * @param id 目标自建 Agent 配置 ID
     * @return 可由接口返回的公开配置
     */
    public AgentProfileResponse get(Long userId, Long id) {
        return toResponse(requireOwned(userId, id));
    }

    /**
     * 验证配置输入，写入归属固定的新 Agent 并返回持久化后的配置。
     * 上游由 {@code AgentProfileController.create} 调用。
     * 下游调用 {@code LocalDateTime.now}、{@code agentProfileMapper.insert}、{@code validate}。
     *
     * @param userId 当前用户 ID
     * @param request 当前入口的请求对象
     * @return 可由接口返回的公开配置
     */
    @Transactional
    public AgentProfileResponse create(Long userId, AgentProfileRequest request) {
        NormalizedProfile normalized = validate(userId, request);
        AgentProfile profile = new AgentProfile();
        profile.setUserId(userId);
        apply(profile, normalized);
        applyModel(userId, profile, request);
        profile.setMemorySuggestionsEnabled(Boolean.TRUE.equals(request.getMemorySuggestionsEnabled()));
        LocalDateTime now = LocalDateTime.now();
        profile.setCreateTime(now);
        profile.setUpdateTime(now);
        agentProfileMapper.insert(profile);
        return toResponse(profile);
    }

    /**
     * 定位用户拥有的配置，应用规范化设置并返回更新结果。
     * 上游由 {@code AgentProfileController.update} 调用。
     * 下游调用 {@code LocalDateTime.now}、{@code agentProfileMapper.updateById}、{@code requireOwned}。
     *
     * @param userId 当前用户 ID
     * @param id 目标自建 Agent 配置 ID
     * @param request 当前入口的请求对象
     * @return 可由接口返回的公开配置
     */
    @Transactional
    public AgentProfileResponse update(Long userId, Long id, AgentProfileRequest request) {
        AgentProfile profile = requireOwned(userId, id);
        NormalizedProfile normalized = validate(userId, request);
        apply(profile, normalized);
        applyModel(userId, profile, request);
        if (request.getMemorySuggestionsEnabled() != null) {
            profile.setMemorySuggestionsEnabled(request.getMemorySuggestionsEnabled());
        }

        profile.setUpdateTime(LocalDateTime.now());
        agentProfileMapper.updateById(profile);
        return toResponse(profile);
    }

    /**
     * 先确定配置归属，再按用户和配置 ID 限定删除目标。
     * 上游由 {@code AgentProfileController.delete} 调用。
     * 下游调用 {@code agentProfileMapper.delete}、{@code requireOwned}。
     *
     * @param userId 当前用户 ID
     * @param id 目标自建 Agent 配置 ID
     */
    public void delete(Long userId, Long id) {
        requireOwned(userId, id);
        int deleted = agentProfileMapper.delete(new LambdaQueryWrapper<AgentProfile>()
                .eq(AgentProfile::getId, id)
                .eq(AgentProfile::getUserId, userId));
        if (deleted == 0) {
            throw new ForbiddenException("Agent 配置不存在或无权访问");
        }
    }

    /**
     * 按所有权、最新可读库、有效工具与记忆策略解析实际执行配置。
     * 上游由 {@code AgentService.runTurn}、{@code AgentSessionService.currentPolicy} 调用。
     * 下游调用 {@code AgentKbSelectionMode.from}、{@code readable.containsAll}、{@code AgentAnswerPolicy.from}。
     *
     * @param userId 当前用户 ID
     * @param agentId 自建 Agent ID，空值表示默认 Agent
     * @param readableKnowledgeBaseIds 本次调用传入的 {@code Set<Long>}
     * @return 执行时实际生效的 Agent 配置
     */
    public ResolvedAgentProfile resolveForExecution(Long userId, Long agentId,
                                                    Set<Long> readableKnowledgeBaseIds) {
        AgentProfile profile = requireOwned(userId, agentId);
        Set<Long> readable = readableKnowledgeBaseIds == null
                ? Set.of()
                : Set.copyOf(readableKnowledgeBaseIds);
        Set<Long> configuredKbs = parseLongs(profile.getKnowledgeBaseIdsJson());
        Set<String> configuredTools = parseStrings(profile.getAllowedToolsJson());

        Set<Long> effectiveKbs = switch (AgentKbSelectionMode.from(profile.getKbSelectionMode())) {
            case ALL -> readable;
            case NONE -> Set.of();
            case SELECTED -> {
                if (configuredKbs.isEmpty()) {
                    throw new BusinessException("Agent 配置未选择任何知识库，请重新保存配置");
                }
                if (!readable.containsAll(configuredKbs)) {
                    throw new ForbiddenException("Agent 配置中的知识库已不可访问，请重新选择");
                }
                yield Set.copyOf(configuredKbs);
            }
        };

        for (String tool : configuredTools) {
            if (!toolRegistry.contains(tool) && !(!longTermMemoryEnabled
                    && ("memory_save".equals(tool) || "memory_search".equals(tool)))) {
                throw new BusinessException("Agent 配置引用了不可用的工具: " + tool);
            }
        }

        String systemPrompt = profile.getSystemPrompt() == null || profile.getSystemPrompt().isBlank()
                ? AgentPrompts.KNOWLEDGE_ASSISTANT_SYSTEM
                : profile.getSystemPrompt();
        return new ResolvedAgentProfile(profile.getId(), systemPrompt, effectiveKbs,
                Set.copyOf(configuredTools), AgentAnswerPolicy.from(profile.getAnswerPolicy()).value(),
                !Boolean.FALSE.equals(profile.getMemoryReadEnabled()), !Boolean.FALSE.equals(profile.getMemoryWriteEnabled()),
                profile.getChatModelId(), Boolean.TRUE.equals(profile.getMemorySuggestionsEnabled()));
    }

    /**
     * 对自建 Agent 的聊天模型配置应用选定设置，供后续执行解析。
     * 上游由 {@code AgentProfileService.create}、{@code AgentProfileService.update} 调用。
     * 下游调用 {@code modelRegistry.validateAgentModel}。
     *
     * @param owner 所属用户标识
     * @param profile 自建 Agent 的配置与执行策略
     * @param request 当前入口的请求对象
     */
    private void applyModel(Long owner, AgentProfile profile, AgentProfileRequest request) {
        String id=request.getChatModelId();
        if (id != null && id.isBlank()) id=null;
        if (modelRegistry != null) modelRegistry.validateAgentModel(owner,id);
        profile.setChatModelId(id);
    }

    /**
     * 检查指定配置属于当前用户并返回配置实体。
     * 上游由 {@code AgentProfileService.delete}、{@code AgentProfileService.get}、{@code AgentProfileService.resolveForExecution} 调用。
     * 下游调用 {@code agentProfileMapper.selectOwned}。
     *
     * @param userId 当前用户 ID
     * @param id 目标自建 Agent 配置 ID
     * @return 数据库持久化的自建 Agent 配置实体
     */
    private AgentProfile requireOwned(Long userId, Long id) {
        if (id == null) {
            throw new BusinessException("Agent 配置 ID 不能为空");
        }
        AgentProfile profile = agentProfileMapper.selectOwned(id, userId);
        if (profile == null) {
            throw new ForbiddenException("Agent 配置不存在或无权访问");
        }
        return profile;
    }

    /**
     * 校验选库模式、工具白名单、回答策略与模型等请求输入，产出规范化配置。
     * 上游由 {@code AgentProfileService.create}、{@code AgentProfileService.update} 调用。
     * 下游调用 {@code AgentKbSelectionMode.from}、{@code knowledgeBaseAccessService.canRead}、{@code AgentAnswerPolicy.from}。
     *
     * @param userId 当前用户 ID
     * @param request 当前入口的请求对象
     * @return 调用方使用的 {@code NormalizedProfile}
     */
    private NormalizedProfile validate(Long userId, AgentProfileRequest request) {
        String name = request.getName() == null ? "" : request.getName().trim();
        if (name.isEmpty()) {
            throw new BusinessException("Agent 名称不能为空");
        }

        AgentKbSelectionMode mode = AgentKbSelectionMode.from(request.getKbSelectionMode());

        Set<Long> requestedKbs = new LinkedHashSet<>();
        if (request.getKnowledgeBaseIds() != null) {
            for (Long kbId : request.getKnowledgeBaseIds()) {
                if (kbId == null || kbId <= 0) {
                    throw new BusinessException("knowledgeBaseIds 含非法 ID");
                }
                requestedKbs.add(kbId);
            }
        }

        switch (mode) {
            case SELECTED -> {
                if (requestedKbs.isEmpty()) {
                    throw new BusinessException("selected 模式至少选择 1 个知识库");
                }
                for (Long kbId : requestedKbs) {
                    if (!knowledgeBaseAccessService.canRead(userId, kbId)) {
                        throw new ForbiddenException("无权使用知识库: " + kbId);
                    }
                }
            }
            case ALL -> {
                if (!requestedKbs.isEmpty()) {
                    throw new BusinessException("all 模式不能指定 knowledgeBaseIds");
                }
            }
            case NONE -> {
                if (!requestedKbs.isEmpty()) {
                    throw new BusinessException("none 模式不能指定 knowledgeBaseIds");
                }
            }
        }

        Set<String> requestedTools = new LinkedHashSet<>();
        if (request.getAllowedTools() != null) {
            for (String tool : request.getAllowedTools()) {
                String toolName = tool == null ? "" : tool.trim();
                if (toolName.isEmpty()) {
                    throw new BusinessException("allowedTools 含空工具名");
                }
                if (!toolRegistry.contains(toolName)) {
                    throw new BusinessException("未知工具: " + toolName);
                }
                requestedTools.add(toolName);
            }
        }

        String prompt = request.getSystemPrompt() == null || request.getSystemPrompt().isBlank()
                ? null
                : request.getSystemPrompt().trim();
        return new NormalizedProfile(name, prompt, mode, requestedKbs, requestedTools,
                AgentAnswerPolicy.from(request.getAnswerPolicy()),
                !Boolean.FALSE.equals(request.getMemoryReadEnabled()), !Boolean.FALSE.equals(request.getMemoryWriteEnabled()));
    }

    /**
     * 把经校验的业务设置写入配置实体，供新增或更新操作存储。
     * 上游由 {@code AgentProfileService.create}、{@code AgentProfileService.update} 调用。
     * 下游调用 {@code normalized.name}、{@code normalized.systemPrompt}、{@code normalized.answerPolicy}。
     *
     * @param profile 自建 Agent 的配置与执行策略
     * @param normalized 本次调用传入的 {@code NormalizedProfile}
     */
    private void apply(AgentProfile profile, NormalizedProfile normalized) {
        profile.setName(normalized.name());
        profile.setSystemPrompt(normalized.systemPrompt());
        profile.setAnswerPolicy(normalized.answerPolicy().value());
        profile.setMemoryReadEnabled(normalized.memoryReadEnabled());
        profile.setMemoryWriteEnabled(normalized.memoryWriteEnabled());
        profile.setKbSelectionMode(normalized.mode().value());
        profile.setKnowledgeBaseIdsJson(writeJson(normalized.knowledgeBaseIds()));
        profile.setAllowedToolsJson(writeJson(normalized.allowedTools()));
    }

    /**
     * 把数据库中的知识库范围、工具和记忆开关统一映射为公开配置。
     * 上游由 {@code AgentProfileService.create}、{@code AgentProfileService.get}、{@code AgentProfileService.list} 调用。
     * 下游调用 {@code AgentAnswerPolicy.from}、{@code parseLongs}、{@code parseStrings}。
     *
     * @param profile 自建 Agent 的配置与执行策略
     * @return 可由接口返回的公开配置
     */
    private AgentProfileResponse toResponse(AgentProfile profile) {
        return AgentProfileResponse.builder()
                .id(profile.getId())
                .name(profile.getName())
                .chatModelId(profile.getChatModelId())
                .systemPrompt(profile.getSystemPrompt())
                .answerPolicy(AgentAnswerPolicy.from(profile.getAnswerPolicy()).value())
                .memoryReadEnabled(!Boolean.FALSE.equals(profile.getMemoryReadEnabled()))
                .memoryWriteEnabled(!Boolean.FALSE.equals(profile.getMemoryWriteEnabled()))
                .memorySuggestionsEnabled(Boolean.TRUE.equals(profile.getMemorySuggestionsEnabled()))
                .kbSelectionMode(profile.getKbSelectionMode())
                .knowledgeBaseIds(List.copyOf(parseLongs(profile.getKnowledgeBaseIdsJson())))
                .allowedTools(List.copyOf(parseStrings(profile.getAllowedToolsJson())))
                .createTime(profile.getCreateTime())
                .updateTime(profile.getUpdateTime())
                .build();
    }

    /**
     * 将知识库或工具列表转换成持久化字段需要的 JSON。
     * 上游由 {@code AgentProfileService.apply} 调用。
     * 下游调用 {@code objectMapper.writeValueAsString}。
     *
     * @param values 本次调用传入的 {@code Set<?>}
     * @return 供后续处理使用的文本或标识
     */
    private String writeJson(Set<?> values) {
        try {
            return objectMapper.writeValueAsString(new ArrayList<>(values));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Agent 配置序列化失败", e);
        }
    }

    /**
     * 从持久化字段还原知识库 ID 列表。
     * 上游由 {@code AgentProfileService.resolveForExecution}、{@code AgentProfileService.toResponse} 调用。
     * 下游调用 {@code objectMapper.readValue}。
     *
     * @param json 本次调用传入的 {@code String}
     * @return 调用方使用的 {@code Set<Long>}
     */
    private Set<Long> parseLongs(String json) {
        if (json == null || json.isBlank()) {
            return Set.of();
        }
        try {
            List<Long> values = objectMapper.readValue(json, new TypeReference<List<Long>>() { });
            Set<Long> result = new LinkedHashSet<>();
            for (Long value : values) {
                if (value != null) {
                    result.add(value);
                }
            }
            return result;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Agent 配置中的知识库列表无法解析", e);
        }
    }

    /**
     * 从持久化字段还原允许的工具名称列表。
     * 上游由 {@code AgentProfileService.resolveForExecution}、{@code AgentProfileService.toResponse} 调用。
     * 下游调用 {@code objectMapper.readValue}。
     *
     * @param json 本次调用传入的 {@code String}
     * @return 调用方使用的 {@code Set<String>}
     */
    private Set<String> parseStrings(String json) {
        if (json == null || json.isBlank()) {
            return Set.of();
        }
        try {
            List<String> values = objectMapper.readValue(json, new TypeReference<List<String>>() { });
            return new LinkedHashSet<>(values.stream().filter(Objects::nonNull).toList());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Agent 配置中的工具列表无法解析", e);
        }
    }

    private record NormalizedProfile(
            String name,
            String systemPrompt,
            AgentKbSelectionMode mode,
            Set<Long> knowledgeBaseIds,
            Set<String> allowedTools,
            AgentAnswerPolicy answerPolicy,
            boolean memoryReadEnabled,
            boolean memoryWriteEnabled) {
    }
}
