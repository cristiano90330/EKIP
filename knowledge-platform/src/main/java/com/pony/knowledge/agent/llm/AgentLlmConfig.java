/* 装配生产 Agent 决策和摘要所用的 Python 网关客户端。 */

package com.pony.knowledge.agent.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pony.knowledge.resilience.AiAvailabilityGuard;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Agent model selection belongs to the registry; all execution uses the governed Python gateway. */
@Configuration
public class AgentLlmConfig {
    /**
     * 用内部服务地址和连接时限构造 Agent 网关配置。
     *
     * @param baseUrl 本次调用传入的 {@code String}
     * @param connectTimeoutMs 本次调用传入的 {@code int}
     * @param readTimeoutMs 本次调用传入的 {@code int}
     * @return 调用方使用的 {@code AgentGatewayProperties}
     */
    @Bean
    public AgentGatewayProperties agentGatewayProperties(
            @Value("${ai.client.base-url:http://127.0.0.1:8000}") String baseUrl,
            @Value("${agent.llm.gateway.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${agent.llm.gateway.read-timeout-ms:35000}") int readTimeoutMs) {
        return AgentGatewayProperties.builder().baseUrl(baseUrl).connectTimeoutMs(connectTimeoutMs)
                .readTimeoutMs(readTimeoutMs).build();
    }

    /**
     * 为运行时注册使用内部 GatewayAgentLlm 的决策入口。
     *
     * @param gatewayClient 本次调用传入的 {@code AgentGatewayClient}
     * @return 调用方使用的 {@code AgentLlm}
     */
    @Bean
    public AgentLlm agentLlm(AgentGatewayClient gatewayClient) { return new GatewayAgentLlm(gatewayClient); }

    /**
     * 将连接配置、内部鉴权和观测组件注入 Agent 网关客户端。
     *
     * @param properties 本次调用传入的 {@code AgentGatewayProperties}
     * @param mapper 负责 JSON 协议编码和解码的映射器
     * @param internalToken 本次调用传入的 {@code String}
     * @param tracer 本次调用传入的 {@code ObjectProvider<Tracer>}
     * @param guard 本次调用传入的 {@code AiAvailabilityGuard}
     * @return 调用方使用的 {@code AgentGatewayClient}
     */
    @Bean
    public AgentGatewayClient agentGatewayClient(AgentGatewayProperties properties, ObjectMapper mapper,
            @Value("${ai.client.internal-token}") String internalToken,
            ObjectProvider<Tracer> tracer, AiAvailabilityGuard guard) {
        return new AgentGatewayClient(properties, mapper, internalToken, tracer.getIfAvailable(), guard);
    }
}
