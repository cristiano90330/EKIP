# Agent 与工具执行

Java 负责 Agent 回合编排、工具和会话，Python 网关负责模型决策与摘要调用。

新建默认 Agent 会话允许通用回答。日常对话可以直接回答，涉及企业内部事实时，默认提示词仍要求先检索可读知识库。旧默认会话沿用创建时的回答策略，自建 Agent 按各自的配置执行。

## 源码与契约入口

- 前端、Java 会话入口与运行时 [agent.ts](../../../frontend/src/api/agent.ts)、[AgentController.java](../../../knowledge-platform/src/main/java/com/pony/knowledge/agent/controller/AgentController.java)、[runtime/](../../../knowledge-platform/src/main/java/com/pony/knowledge/agent/runtime/)。
- 工具注册和执行 [tool/](../../../knowledge-platform/src/main/java/com/pony/knowledge/agent/tool/)。
- 模型调用边界 [AgentGatewayClient.java](../../../knowledge-platform/src/main/java/com/pony/knowledge/agent/llm/AgentGatewayClient.java)、[router.py](../../../ai/app/agent/router.py)。
