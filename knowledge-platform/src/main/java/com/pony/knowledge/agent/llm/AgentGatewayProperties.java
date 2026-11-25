/* 绑定模型网关的基础地址、内部令牌和连接时限。 */

package com.pony.knowledge.agent.llm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Agent 决策走 Python 治理层（{@code agent.llm.mode=python}）的连接配置。
 *
 * <p>base-url 与 {@code ai.client.base-url} 是同一个键，不另起 agent 专属地址：
 * AI 服务只有一个进程，两个地址等于两处真相。
 * 超时则单独配置两个键：Agent 决策的等待上限与 parse/generate 不是一个量级，
 * 共用 {@code ai.client.read-timeout-ms} 会破坏「外层必须等内层」的级联约束。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentGatewayProperties {

    private String baseUrl;

    /** 连接超时（ms）：AI 服务没起时应在毫秒级失败，不等读超时。 */
    @Builder.Default
    private int connectTimeoutMs = 3000;

    /**
     * 读超时（ms）。必须大于 Python 侧 agent 档的 timeout_s（默认 20s）：
     * 外层先断会造出 Java 报超时、Python 仍在等模型、钱花了却没有答案的假超时。
     */
    @Builder.Default
    private int readTimeoutMs = 25000;
}
