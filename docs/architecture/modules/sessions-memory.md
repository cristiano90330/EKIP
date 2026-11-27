# 会话与记忆

Java 分别管理 RAG 会话、Agent 产品会话和 Agent 记忆。

普通 RAG 会话的全库和单库范围均可复用最近三个已完成回合。全库范围在创建时固定，读取上下文前后核对当前授权和会话记录。范围不匹配时拒绝使用旧会话，开始新会话后按当前授权查询。

## 源码与契约入口

- RAG 会话存储入口 [ConversationStore.java](../../../knowledge-platform/src/main/java/com/pony/knowledge/service/rag/ConversationStore.java)。
- 普通 RAG 会话范围、历史和回合租约 [RagSessionService.java](../../../knowledge-platform/src/main/java/com/pony/knowledge/service/rag/RagSessionService.java)。
- Agent 会话与记忆 [session/](../../../knowledge-platform/src/main/java/com/pony/knowledge/agent/session/)、[memory/](../../../knowledge-platform/src/main/java/com/pony/knowledge/agent/memory/)。
- 存储与跨服务验证 [README.md](../../../tests/README.md)。
