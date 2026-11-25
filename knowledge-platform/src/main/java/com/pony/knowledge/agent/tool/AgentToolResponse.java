/* 承载工具执行返回的内容与来源，以及可选诊断状态。 */

package com.pony.knowledge.agent.tool;

/** 配置页面可读的工具元数据；不公开 Java 类型和模型参数 Schema。 */
public record AgentToolResponse(
        String name,
        String description,
        boolean requiresApproval) {
}
