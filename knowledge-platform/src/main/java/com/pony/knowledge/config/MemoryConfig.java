/* 装配 Agent 摘要策略和模型上下文预算。 */

package com.pony.knowledge.config;

import com.pony.knowledge.agent.context.ContextBudget;
import com.pony.knowledge.agent.memory.summary.SummaryPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Memory 摘要策略 + Context 预算装配：从 application.yml 读 agent.memory.summary.* / agent.context.*。
 *
 * <p>两者的数值关系不是各自独立可调的——{@link AgentBudgetGuard} 在启动时校验：
 * 摘要阈值必须低于可用提示词预算，工具结果上限也必须低于它。
 */
@Configuration
public class MemoryConfig {

    /**
     * 从部署配置装配 Agent 摘要触发和大小约束。
     * 上游为 Spring 容器组件装配。
     *
     * @param maxTokens 允许使用的最大令牌数
     * @param retainRecent 本方法计算或判断使用的 retainRecent 值
     * @return 本次计算或查询得到的 SummaryPolicy 结果
     */
    @Bean
    public SummaryPolicy summaryPolicy(
            @Value("${agent.memory.summary.max-tokens:3000}") int maxTokens,
            @Value("${agent.memory.summary.retain-recent:6}") int retainRecent) {
        return new SummaryPolicy(maxTokens, retainRecent);
    }

    /**
     * 从部署配置装配 Agent 模型上下文的令牌预算。
     * 上游为 Spring 容器组件装配。
     * 下游为 ContextBudget.builder。
     *
     * @param maxTokens 允许使用的最大令牌数
     * @param reservedOutputTokens 本方法计算或判断使用的 reservedOutputTokens 值
     * @param reservedToolTokens 本方法计算或判断使用的 reservedToolTokens 值
     * @return 本次计算或查询得到的 ContextBudget 结果
     */
    @Bean
    public ContextBudget contextBudget(
            @Value("${agent.context.max-tokens:8192}") int maxTokens,
            @Value("${agent.context.reserved-output-tokens:1200}") int reservedOutputTokens,
            @Value("${agent.context.reserved-tool-tokens:400}") int reservedToolTokens) {
        return ContextBudget.builder()
                .maxTokens(maxTokens)
                .reservedOutputTokens(reservedOutputTokens)
                .reservedToolTokens(reservedToolTokens)
                .build();
    }
}
