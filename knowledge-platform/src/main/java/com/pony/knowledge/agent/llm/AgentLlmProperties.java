/* 保存 Agent 直接模型传输所需的地址、模型和请求参数配置。 */

package com.pony.knowledge.agent.llm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Agent 直连 LLM 的配置：每个 OpenAI 兼容供应商一套（OpenAI / DeepSeek / Qwen(DashScope) / 智谱）。
 * 由 AgentLlmConfig 从「OS 环境变量 → 根 .env」解析填充（镜像 Python ai/app/config.py），
 * 默认对齐 ai/.env.example（DeepSeek）。真实环境用换 Qwen(DashScope) 的 base-url/model。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentLlmProperties {

    private String apiKey;

    private String baseUrl;

    private String model;

    /** LLM 调用连接超时（ms） */
    @Builder.Default
    private int connectTimeoutMs = 3000;

    /** LLM 调用读超时（ms），应小于 agent.budget-time-ms */
    @Builder.Default
    private int readTimeoutMs = 20000;
}
