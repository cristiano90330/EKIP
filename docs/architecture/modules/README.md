# 模块导航

按业务职责查找源码入口。沿入口核对调用者、生产类型、配置和相关测试。对外承诺和验收口径见[使用文档](../../README.md)。

| 模块 | 职责 |
| --- | --- |
| [身份与知识库权限](access.md) | 前端会话经 Java 认证和授权，业务入口据此决定资源访问范围。 |
| [文档与文件生命周期](documents.md) | Java 协调上传回执、对象存储、入库和删除。后台任务交给文档任务模块。 |
| [文档解析与切分](parsing.md) | Java 调用 AI 服务读取原始文件，Python 解析文档并切分文本。 |
| [文档任务](tasks.md) | Java 负责解析任务的投递、执行、恢复、终态写入与状态通知。 |
| [模型管理与执行](models.md) | Java 管理模型登记、凭证和用途绑定，Python 使用授权后的模型配置执行调用。 |
| [知识索引与重建](indexes.md) | Java 管理知识库的活动索引与重建生命周期，Python 检索读取已授权的索引范围。 |
| [检索](retrieval.md) | Python 按授权范围查询向量和关键词候选，为 RAG 和 Agent 知识工具提供召回。 |
| [RAG 问答](rag.md) | 前端发起问答，Java 校验用户范围并管理会话，Python 完成检索、证据审查和生成。 |
| [Agent 与工具执行](agent.md) | Java 负责 Agent 回合编排、工具和会话，Python 网关负责模型决策与摘要调用。 |
| [会话与记忆](sessions-memory.md) | Java 分别管理 RAG 会话、Agent 产品会话和 Agent 记忆。 |
| [MCP 工具入口](mcp.md) | Java MCP 端点负责传输与 Key 认证，工具桥接复用 Agent 工具执行。 |
| [观测、追踪与审计](observability.md) | 服务记录请求阶段、依赖状态与业务审计，前端读取有权限的观测信息。 |
| [AI 依赖可用性](resilience.md) | Java 在内部调用边界管理依赖可用性，问答、Agent 和文档任务各自处理失败结果。 |
| [流式传输](streaming.md) | 浏览器共用 SSE 帧读取入口，问答、Agent 与文档任务分别负责自身的终态和取消语义。 |
| [评测](evaluation.md) | Java 接收评测请求并组织指标，Python 执行模型裁判。 |
