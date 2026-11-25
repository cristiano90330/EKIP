# 会话与记忆

Java 分别管理 RAG 会话、Agent 产品会话和 Agent 记忆。

## 源码与契约入口

- RAG 会话存储入口 [ConversationStore.java](../../../knowledge-platform/src/main/java/com/pony/knowledge/service/rag/ConversationStore.java)。
- Agent 会话与记忆 [session/](../../../knowledge-platform/src/main/java/com/pony/knowledge/agent/session/)、[memory/](../../../knowledge-platform/src/main/java/com/pony/knowledge/agent/memory/)。
- 存储与跨服务验证 [README.md](../../../tests/README.md)。
