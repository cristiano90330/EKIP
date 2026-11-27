# 流式传输

浏览器共用 SSE 帧读取入口，问答、Agent 与文档任务分别负责自身的终态和取消语义。

两种问答引擎共用单轮和会话 SSE 入口。LangChain 在正文生成期间发送临时增量，最终正文及引用由 `done` 确认。会话记录只保存成功或正常拒答的完成回合。页面在失败、超时或取消时清除 LangChain 的临时正文。

会话 SSE 在响应头 `X-Rag-Turn-Id` 返回本轮标识。前端停止或离页时调用 `POST /knowledge/sessions/{id}/turns/{turnId}/cancel`，收到取消确认后可在同一会话发起新回合。取消只作用于当前账号指定的旧回合。连接断开时，SSE 注释心跳协助服务端尽快发现断连并释放执行权。

Python 的 SSE 消费和模型工作线程共享断连信号。模型已返回 HTTP 响应头时，取消会主动中断仍在等待正文的连接。连接仍在建立或等待模型响应头时，网络阶段受原有超时约束。

## 源码与契约入口

- 浏览器帧读取与请求入口 [sse.ts](../../../frontend/src/api/sse.ts)、[knowledge.ts](../../../frontend/src/api/knowledge.ts)、[agent.ts](../../../frontend/src/api/agent.ts)、[library.ts](../../../frontend/src/api/library.ts)。
- 服务端入口 [RAG 问答](rag.md)、[Agent 与工具执行](agent.md)、[文档任务](tasks.md)。
- 前端流式用例 [tests/](../../../frontend/tests/)。
