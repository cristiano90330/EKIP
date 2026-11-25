# RAG 问答

前端发起问答，Java 校验用户范围并管理会话，Python 完成检索、证据审查和生成。

## 源码与契约入口

- 浏览器请求及 Java 问答入口 [knowledge.ts](../../../frontend/src/api/knowledge.ts)、[KnowledgeChatController.java](../../../knowledge-platform/src/main/java/com/pony/knowledge/controller/KnowledgeChatController.java)、[KnowledgeChatStreamController.java](../../../knowledge-platform/src/main/java/com/pony/knowledge/controller/KnowledgeChatStreamController.java)。
- Java 编排入口与服务间传输 [rag/](../../../knowledge-platform/src/main/java/com/pony/knowledge/service/rag/)、[ai/](../../../knowledge-platform/src/main/java/com/pony/knowledge/service/ai/)。
- Python 路由、类型、引擎与审查 [router.py](../../../ai/app/rag/router.py)、[schema.py](../../../ai/app/rag/schema.py)、[rag/](../../../ai/app/rag/)。
- 使用说明与验收口径 [README.md](../../../ai/README.md)、[lightweight-acceptance.md](../contracts/lightweight-acceptance.md)。
