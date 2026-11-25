/* 保存单次 Agent 运行实际采用的提示词、模型、知识库范围和记忆策略。 */

package com.pony.knowledge.agent.profile;

import java.util.Set;

/** 一次 Agent 回合实际生效的配置快照；知识库与工具均已按当前权限和注册表收敛。 */
public record ResolvedAgentProfile(
        Long id,
        String systemPrompt,
        Set<Long> knowledgeBaseIds,
        Set<String> allowedToolNames,
        String answerPolicy,
        boolean memoryReadEnabled,
        boolean memoryWriteEnabled,
        String chatModelId,
        boolean memorySuggestionsEnabled) {

    /**
     * 将当前参数交给共有构造入口，并补齐这组重载省略的设置。
     *
     * @param id 目标自建 Agent 配置 ID
     * @param prompt 本次调用传入的 {@code String}
     * @param kbs 本次调用传入的 {@code Set<Long>}
     * @param tools 本次调用传入的 {@code Set<String>}
     * @param policy 本轮使用的授权、回答与记忆策略
     * @param read 本次调用传入的 {@code boolean}
     * @param write 本次调用传入的 {@code boolean}
     * @param chatModelId 指定的聊天模型配置 ID
     */
    public ResolvedAgentProfile(Long id, String prompt, Set<Long> kbs, Set<String> tools, String policy,
                                boolean read, boolean write, String chatModelId) {
        this(id, prompt, kbs, tools, policy, read, write, chatModelId, false);
    }

    /**
     * 将当前参数交给共有构造入口，并补齐这组重载省略的设置。
     *
     * @param id 目标自建 Agent 配置 ID
     * @param prompt 本次调用传入的 {@code String}
     * @param kbs 本次调用传入的 {@code Set<Long>}
     * @param tools 本次调用传入的 {@code Set<String>}
     * @param policy 本轮使用的授权、回答与记忆策略
     * @param read 本次调用传入的 {@code boolean}
     * @param write 本次调用传入的 {@code boolean}
     */
    public ResolvedAgentProfile(Long id, String prompt, Set<Long> kbs, Set<String> tools, String policy, boolean read, boolean write) {
        this(id,prompt,kbs,tools,policy,read,write,null);
    }

    /**
     * 用注入依赖和构造参数建立此实例执行时使用的状态。
     * 下游调用 {@code AgentAnswerPolicy.from}。
     *
     * @param id 目标自建 Agent 配置 ID
     * @param systemPrompt 本次调用传入的 {@code String}
     * @param knowledgeBaseIds 本次调用传入的 {@code Set<Long>}
     * @param allowedToolNames 当前 Agent 策略允许提供给模型的工具名
     * @param answerPolicy 本次调用传入的 {@code String}
     * @param memoryReadEnabled 本次调用传入的 {@code boolean}
     * @param memoryWriteEnabled 本次调用传入的 {@code boolean}
     * @param chatModelId 指定的聊天模型配置 ID
     * @param memorySuggestionsEnabled 本次调用传入的 {@code boolean}
     */
    public ResolvedAgentProfile {
        answerPolicy = AgentAnswerPolicy.from(answerPolicy).value();
    }

    /**
     * 将当前参数交给共有构造入口，并补齐这组重载省略的设置。
     *
     * @param id 目标自建 Agent 配置 ID
     * @param systemPrompt 本次调用传入的 {@code String}
     * @param knowledgeBaseIds 本次调用传入的 {@code Set<Long>}
     * @param allowedToolNames 当前 Agent 策略允许提供给模型的工具名
     * @param answerPolicy 本次调用传入的 {@code String}
     */
    public ResolvedAgentProfile(Long id, String systemPrompt, Set<Long> knowledgeBaseIds,
                                Set<String> allowedToolNames, String answerPolicy) {
        this(id, systemPrompt, knowledgeBaseIds, allowedToolNames, answerPolicy, true, true);
    }

    /**
     * 将当前参数交给共有构造入口，并补齐这组重载省略的设置。
     * 下游调用 {@code AgentAnswerPolicy.GROUNDED.value}。
     *
     * @param id 目标自建 Agent 配置 ID
     * @param systemPrompt 本次调用传入的 {@code String}
     * @param knowledgeBaseIds 本次调用传入的 {@code Set<Long>}
     * @param allowedToolNames 当前 Agent 策略允许提供给模型的工具名
     */
    public ResolvedAgentProfile(Long id, String systemPrompt, Set<Long> knowledgeBaseIds,
                                Set<String> allowedToolNames) {
        this(id, systemPrompt, knowledgeBaseIds, allowedToolNames, AgentAnswerPolicy.GROUNDED.value());
    }
}
