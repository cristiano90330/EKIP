# 流式传输

浏览器共用 SSE 帧读取入口，问答、Agent 与文档任务分别负责自身的终态和取消语义。

## 源码与契约入口

- 浏览器帧读取与请求入口 [sse.ts](../../../frontend/src/api/sse.ts)、[knowledge.ts](../../../frontend/src/api/knowledge.ts)、[agent.ts](../../../frontend/src/api/agent.ts)、[library.ts](../../../frontend/src/api/library.ts)。
- 服务端入口 [RAG 问答](rag.md)、[Agent 与工具执行](agent.md)、[文档任务](tasks.md)。
- 前端流式用例 [tests/](../../../frontend/tests/)。
