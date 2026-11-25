# AI 依赖可用性

Java 在内部调用边界管理依赖可用性，问答、Agent 和文档任务各自处理失败结果。

## 源码与契约入口

- 可用性判断与配置 [AiAvailabilityGuard.java](../../../knowledge-platform/src/main/java/com/pony/knowledge/resilience/AiAvailabilityGuard.java)、[application.yml](../../../knowledge-platform/src/main/resources/application.yml)。
- AI 普通调用、RAG 流和 Agent 网关 [AiServiceClient.java](../../../knowledge-platform/src/main/java/com/pony/knowledge/service/ai/AiServiceClient.java)、[RagStreamClient.java](../../../knowledge-platform/src/main/java/com/pony/knowledge/service/rag/RagStreamClient.java)、[AgentGatewayClient.java](../../../knowledge-platform/src/main/java/com/pony/knowledge/agent/llm/AgentGatewayClient.java)。
