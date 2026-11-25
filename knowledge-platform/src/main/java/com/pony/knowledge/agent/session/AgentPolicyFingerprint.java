/* 把模型、范围、回答策略与工具白名单压成会话绑定，用于续聊前比较。 */

package com.pony.knowledge.agent.session;

import com.pony.knowledge.agent.model.AgentPrompts;
import com.pony.knowledge.agent.profile.AgentAnswerPolicy;
import com.pony.knowledge.agent.profile.ResolvedAgentProfile;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

/** 会话创建、恢复及续聊共用的生效策略编码。 */
public final class AgentPolicyFingerprint {
    static final String DEFAULT_ANSWER_POLICY = AgentAnswerPolicy.GENERAL.value();

    /** 保留私有构造入口，以便通过静态方法使用此类型。 */
    private AgentPolicyFingerprint() { }

    /**
     * 根据自建提示词和会话回答策略选择实际发送给模型的系统提示词。
     * 下游调用 {@code profile.answerPolicy}、{@code systemPrompt}。
     *
     * @param profile 自建 Agent 的配置与执行策略
     * @return 供后续处理使用的文本或标识
     */
    public static String systemPrompt(ResolvedAgentProfile profile) {
        return systemPrompt(profile, profile == null ? DEFAULT_ANSWER_POLICY : profile.answerPolicy());
    }

    /**
     * 根据自建提示词和会话回答策略选择实际发送给模型的系统提示词。
     * 下游调用 {@code AgentAnswerPolicy.GROUNDED.value}、{@code profile.systemPrompt}。
     *
     * @param profile 自建 Agent 的配置与执行策略
     * @param answerPolicy 本次调用传入的 {@code String}
     * @return 供后续处理使用的文本或标识
     */
    private static String systemPrompt(ResolvedAgentProfile profile, String answerPolicy) {
        boolean grounded = AgentAnswerPolicy.GROUNDED.value().equals(answerPolicy);
        String basePrompt = profile != null ? profile.systemPrompt()
                : grounded ? AgentPrompts.KNOWLEDGE_ASSISTANT_SYSTEM : AgentPrompts.GENERAL_ASSISTANT_SYSTEM;
        return basePrompt
                + "\n" + AgentPrompts.CITATION_INSTRUCTION
                + "\n" + AgentPrompts.MEMORY_INSTRUCTION
                + (grounded ? "\n" + AgentPrompts.GROUNDED_INSTRUCTION : "");
    }

    /**
     * 将用户、授权范围、工具及执行策略规范化后计算会话比较指纹。
     * 下游调用 {@code profile.answerPolicy}、{@code fingerprint}。
     *
     * @param userId 当前用户 ID
     * @param profile 自建 Agent 的配置与执行策略
     * @param knowledgeBaseIds 本次调用传入的 {@code Set<Long>}
     * @param allowedTools 本次调用传入的 {@code Set<String>}
     * @param systemPrompt 本次调用传入的 {@code String}
     * @return 供后续处理使用的文本或标识
     */
    public static String fingerprint(Long userId, ResolvedAgentProfile profile, Set<Long> knowledgeBaseIds,
                                     Set<String> allowedTools, String systemPrompt) {
        return fingerprint(userId, profile, knowledgeBaseIds, allowedTools, systemPrompt, true,
                profile == null ? DEFAULT_ANSWER_POLICY : profile.answerPolicy());
    }

    /**
     * 计算本轮的回答、知识库、工具与记忆规则，返回可以绑定到会话的执行策略。
     * 下游调用 {@code resolve}。
     *
     * @param userId 当前用户 ID
     * @param profile 自建 Agent 的配置与执行策略
     * @param readable 本次调用传入的 {@code Set<Long>}
     * @param longTermMemoryEnabled 本次调用传入的 {@code boolean}
     * @return 调用方使用的 {@code Policy}
     */
    public static Policy resolve(Long userId, ResolvedAgentProfile profile, Set<Long> readable,
                                 boolean longTermMemoryEnabled) {
        return resolve(userId, profile, readable, longTermMemoryEnabled, DEFAULT_ANSWER_POLICY);
    }

    /**
     * 计算本轮的回答、知识库、工具与记忆规则，返回可以绑定到会话的执行策略。
     * 下游调用 {@code AgentAnswerPolicy.from}、{@code profile.answerPolicy}、{@code profile.knowledgeBaseIds}。
     *
     * @param userId 当前用户 ID
     * @param profile 自建 Agent 的配置与执行策略
     * @param readable 本次调用传入的 {@code Set<Long>}
     * @param longTermMemoryEnabled 本次调用传入的 {@code boolean}
     * @param sessionAnswerPolicy 本次调用传入的 {@code String}
     * @return 调用方使用的 {@code Policy}
     */
    public static Policy resolve(Long userId, ResolvedAgentProfile profile, Set<Long> readable,
                                 boolean longTermMemoryEnabled, String sessionAnswerPolicy) {
        String answerPolicy = profile == null ? AgentAnswerPolicy.from(sessionAnswerPolicy).value() : profile.answerPolicy();
        Set<Long> knowledgeBaseIds = profile == null ? readable : profile.knowledgeBaseIds();
        Set<String> allowedTools = profile == null ? null : profile.allowedToolNames();
        boolean memoryReadEnabled = longTermMemoryEnabled && (profile == null || profile.memoryReadEnabled());
        boolean memoryWriteEnabled = longTermMemoryEnabled && (profile == null || profile.memoryWriteEnabled());
        boolean memorySuggestionsEnabled = memoryWriteEnabled && profile != null && profile.memorySuggestionsEnabled();
        String prompt = systemPrompt(profile, answerPolicy) + "\n当前长期记忆开关：读取=" + memoryReadEnabled
                + "，写入=" + memoryWriteEnabled + "。读取关闭时明确说明无法查询记忆。"
                + "保存确认必须来自本轮 memory_save 的成功执行，写入关闭时明确说明无法保存。"
                + "\n" + AgentPrompts.DOCUMENT_READING_INSTRUCTION;
        return new Policy(knowledgeBaseIds, allowedTools, prompt, answerPolicy,
                memoryReadEnabled,
                memoryWriteEnabled,
                memorySuggestionsEnabled,
                fingerprint(userId, profile, knowledgeBaseIds, allowedTools, prompt, longTermMemoryEnabled, answerPolicy));
    }

    /**
     * 将用户、授权范围、工具及执行策略规范化后计算会话比较指纹。
     * 下游调用 {@code profile.id}、{@code profile.memoryReadEnabled}、{@code profile.memoryWriteEnabled}。
     *
     * @param userId 当前用户 ID
     * @param profile 自建 Agent 的配置与执行策略
     * @param knowledgeBaseIds 本次调用传入的 {@code Set<Long>}
     * @param allowedTools 本次调用传入的 {@code Set<String>}
     * @param systemPrompt 本次调用传入的 {@code String}
     * @param longTermMemoryEnabled 本次调用传入的 {@code boolean}
     * @param answerPolicy 本次调用传入的 {@code String}
     * @return 供后续处理使用的文本或标识
     */
    private static String fingerprint(Long userId, ResolvedAgentProfile profile, Set<Long> knowledgeBaseIds,
                                      Set<String> allowedTools, String systemPrompt, boolean longTermMemoryEnabled,
                                      String answerPolicy) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            field(digest, "agent-session-v3");
            field(digest, String.valueOf(userId));
            field(digest, profile == null ? "default" : "profile:" + profile.id());
            field(digest, systemPrompt);
            field(digest, answerPolicy);
            field(digest, "memory-global:" + longTermMemoryEnabled);
            field(digest, "memory-read:" + (profile == null || profile.memoryReadEnabled()));
            field(digest, "memory-write:" + (profile == null || profile.memoryWriteEnabled()));
            if (profile != null && longTermMemoryEnabled && profile.memoryWriteEnabled() && profile.memorySuggestionsEnabled()) {
                field(digest, "memory-suggestions:true");
            }
            field(digest, "kb:" + knowledgeBaseIds.size());
            knowledgeBaseIds.stream().sorted().forEach(id -> field(digest, String.valueOf(id)));
            field(digest, allowedTools == null ? "tools:unrestricted" : "tools:" + allowedTools.size());
            if (allowedTools != null) allowedTools.stream().sorted().forEach(name -> field(digest, name));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public record Policy(Set<Long> knowledgeBaseIds, Set<String> allowedTools, String systemPrompt,
                         String answerPolicy, boolean memoryReadEnabled, boolean memoryWriteEnabled,
                         boolean memorySuggestionsEnabled, String fingerprint) { }

    /**
     * 把当前模型身份加入策略指纹，使模型配置变化可在续聊时识别。
     * 上游由 {@code AgentService.runTurn}、{@code AgentSessionService.currentPolicy} 调用。
     * 下游调用 {@code policy.fingerprint}、{@code policy.knowledgeBaseIds}、{@code policy.allowedTools}。
     *
     * @param policy 本轮使用的授权、回答与记忆策略
     * @param identity 本次调用传入的 {@code String}
     * @return 调用方使用的 {@code Policy}
     */
    public static Policy bindModel(Policy policy,String identity) {
        if (identity == null) return policy;
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            field(digest,policy.fingerprint()); field(digest,"model:"+identity);
            return new Policy(policy.knowledgeBaseIds(),policy.allowedTools(),policy.systemPrompt(),policy.answerPolicy(),
                    policy.memoryReadEnabled(),policy.memoryWriteEnabled(),policy.memorySuggestionsEnabled(),
                    HexFormat.of().formatHex(digest.digest()));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable",e); }
    }

    /**
     * 把一项规范化字段按独立边界写入摘要，避免不同输入拼接出现同形。
     * 上游由 {@code AgentPolicyFingerprint.bindModel}、{@code AgentPolicyFingerprint.fingerprint} 调用。
     * 下游调用 {@code digest.update}、{@code ByteBuffer.allocate}。
     *
     * @param digest 本次调用传入的 {@code MessageDigest}
     * @param value 本次调用传入的 {@code String}
     */
    private static void field(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
