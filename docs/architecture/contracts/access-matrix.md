# 知识库授权语义与入口覆盖矩阵

本表覆盖 Java 知识库资源授权、HTTP、任务、Agent、MCP 调用链及传入 Python 的数据范围。

## 1. 当前权限语义

[KnowledgeBaseAccessService](../../../knowledge-platform/src/main/java/com/pony/knowledge/service/KnowledgeBaseAccessService.java) 是显式授权语义入口，[实现](../../../knowledge-platform/src/main/java/com/pony/knowledge/service/impl/KnowledgeBaseAccessServiceImpl.java) 只依据知识库创建者和成员关系。调用方必须传入认证上下文中的用户 ID。无效用户、无效资源 ID 返回拒绝，范围查询返回空集合。

| 操作 | 公共方法 | 创建者 | 成员 | 无关系用户 |
| --- | --- | --- | --- | --- |
| 推导全部可读库 | `resolveReadableIds(userId)` | 创建库并集成员库 | 成员库 | 空集合 |
| 读取单库及其文档/任务、单库问答 | `canRead(userId, kbId)` | 允许 | 允许 | 拒绝 |
| 上传、查询上传回执 | `canMaintainDocuments(userId, kbId)` | 允许 | 拒绝 | 拒绝 |
| 添加、移除、列出成员 | `canManageMembers(userId, kbId)` | 允许 | 拒绝 | 拒绝 |
| 单库检索质量评测 | `canEvaluate(userId, kbId)` | 允许 | 拒绝 | 拒绝 |
| 兼容已有知识库管理检查 | `canManageKnowledgeBase(userId, kbId)` | 允许 | 拒绝 | 拒绝 |
| 删除文档、手动重试 | `DocumentMapper.selectOwnedDocument(documentId, userId)` | 允许 | 拒绝 | 拒绝 |

后五项共享“知识库创建者”的规则，但操作名称分别表达意图。文档删除、重试继续在[归属 SQL](../../../knowledge-platform/src/main/java/com/pony/knowledge/mapper/DocumentMapper.java) 中联结实际文档所属知识库并绑定 `creator_id`；查不到与无权限统一拒绝，不用读取权限替换该 SQL，不移动锁和事务。读取权限使用 `canRead / resolveReadableIds`。

知识库列表为保持实体查询及排序，仍由 `KnowledgeBaseServiceImpl.list` 使用创建者条件和成员 ID 查询并去重，未为复用 ID 集合而增加一次查询；其行范围与 `resolveReadableIds` 的规则一致。

## 2. HTTP 资源入口

下列业务入口使用用户 JWT，由 Security 建立身份，再由 `CurrentUserUtil` 读取用户 ID。表中授权位置是资源层的检查，不能用入口 `authenticated` 代替。测试项用于定位用例；历史执行结果保留在授权修改计划，本轮没有运行测试，不能从这些名称推断当前验收通过。

| 方法 / 路径 | 操作与资源定位 | 授权位置 | 下游范围与副作用边界 | 测试定义 / 静态证据 |
| --- | --- | --- | --- | --- |
| `POST /knowledge-base` | 新建知识库 | `KnowledgeBaseServiceImpl.create` | 创建者取当前用户；DTO 不接受创建者 ID | 源码静态核对；真实库写入未验收 |
| `GET /knowledge-base/list` | 列出用户可读库 | `KnowledgeBaseServiceImpl.list` | 创建库与成员库实体合并去重 | `KnowledgeBaseServiceImplTest` |
| `POST /knowledge-base/{kbId}/members` | 添加目标用户的成员关系 | `KnowledgeBaseMemberServiceImpl.add → canManageMembers` | 操作者来自当前身份；授权后才查询目标用户及写关系 | `KnowledgeBaseMemberServiceImplTest`：创建者成功、拒绝时不查目标用户、不写成员 |
| `DELETE /knowledge-base/{kbId}/members/{userId}` | 移除成员关系 | `KnowledgeBaseMemberServiceImpl.remove → canManageMembers` | 路径 `userId` 是被管理对象，不是操作者；只删除指定库和用户的关系 | `KnowledgeBaseMemberServiceImplTest`：无权时无关系读写 |
| `GET /knowledge-base/{kbId}/members` | 读取成员名单 | `KnowledgeBaseMemberServiceImpl.listMembers → canManageMembers` | 当前名单也只对创建者开放；授权后读取关系和用户名 | `KnowledgeBaseMemberServiceImplTest`：成功与无权不读姓名 |
| `POST /document/upload` | 向 `knowledgeBaseId` 上传 | `DocumentServiceImpl.upload → canMaintainDocuments` | 检查先于文件读取、内容指纹、MinIO、回执和文档/任务写入 | `DocumentServiceUploadDeleteOrderTest`、`DocumentServiceUploadIdempotencyTest` |
| `GET /document/upload/receipt` | 查询 `knowledgeBaseId + requestKey` 回执 | `DocumentServiceImpl.findUploadReceipt → canMaintainDocuments` | 授权后按当前用户、库、请求键查回执；先拒绝再读文档/任务状态 | `DocumentServiceUploadDeleteOrderTest`：拒绝时所有回执与状态查询均不执行 |
| `GET /document/list` | 指定知识库的文档分页 | `DocumentServiceImpl.page → canRead` | 授权后查询该库；成员可读 | `DocumentServiceReadAccessTest`：成员成功、无关系用户不执行列表 SQL |
| `DELETE /document/{documentId}` | 删除实际所属库中的文档 | `DocumentServiceImpl.delete → selectOwnedDocument` | SQL 绑定操作者与实际所属库；数据库独立事务提交后才删对象 | `DocumentServiceUploadDeleteOrderTest`：无权不删行/对象、提交与对象删除顺序；SQL 静态核对 |
| `GET /document/{documentId}/task` | 读取文档最新任务 | `DocumentTaskServiceImpl.getTask → document → canRead` | 使用数据库中文档的 `knowledgeBaseId`；授权后读取任务 | `DocumentTaskServiceAuthorizationTest`：成员读取、跨库/文档缺失均不查任务 |
| `GET /document/{documentId}/task/stream` | 订阅文档任务状态 | `DocumentController.streamTask → getTask` | 订阅前检查，注册后补读快照再次检查；事件发送时不逐次检查成员关系 | 本轮静态核对 + `DocumentTaskServiceAuthorizationTest`；既有 `DocumentTaskStreamControllerTest` 依赖 Spring/数据库，本轮不运行 |
| `POST /document/{documentId}/retry` | 重试文档失败任务 | `DocumentTaskServiceImpl.retry → selectOwnedDocument` | 授权后才读/更新任务并投递；复用原 taskId | `DocumentTaskServiceAuthorizationTest`：无权不查/改任务、不投递，创建者按原任务重试 |
| `POST /knowledge/ask` | 当前用户全部可读库问答 | `RagService.ask → resolveReadableIds` | 仅传服务端解析 ID；空集合向下传，Python 默认拒绝召回 | `RagServiceAskTest`：只传解析范围与空范围；Python 证据见第 5 节 |
| `POST /rag/chat` | 指定 `knowledgeBaseId` 会话问答 | `RagService.chat → canRead` | 授权先于历史读取、Python 调用与历史追加；会话键含当前用户及库 | `RagServiceChatTest`：成员成功，无权不读历史、不执行管线 |
| `GET /knowledge/search` | 当前用户全部可读库纯检索 | `KnowledgeSearchController.search → resolveReadableIds` | 只将服务端 ID 传给 `retrieveOnly`；返回排序候选 | `KnowledgeSearchControllerObservabilityTest`；Controller 源码静态核对 |
| `POST /evaluation/retrieval` | 样本 `knowledgeBaseId` 检索与生成评测 | `EvaluationServiceImpl.evaluateRetrieval → canEvaluate` | 授权先于取样、模型裁判 | `EvaluationServiceAuthorizationTest`、`EvaluationServiceImplEvidenceTest` |
| `POST /evaluation/batch` | 多知识库样本批量评测 | `EvaluationServiceImpl.evaluateBatch → canEvaluate` | 全部样本先授权，再开始第一条检索/生成；每条只向 Python 传对应单库 | `EvaluationServiceAuthorizationTest`：前一库允许、后一库拒绝时零管线/裁判调用；取样范围静态核对 |

源码入口：[KnowledgeBaseController](../../../knowledge-platform/src/main/java/com/pony/knowledge/controller/KnowledgeBaseController.java)、[DocumentController](../../../knowledge-platform/src/main/java/com/pony/knowledge/controller/DocumentController.java)、[RagService](../../../knowledge-platform/src/main/java/com/pony/knowledge/service/rag/RagService.java)、[KnowledgeSearchController](../../../knowledge-platform/src/main/java/com/pony/knowledge/controller/KnowledgeSearchController.java)、[EvaluationServiceImpl](../../../knowledge-platform/src/main/java/com/pony/knowledge/service/impl/EvaluationServiceImpl.java)。

`KnowledgeBaseService.checkOwnership` 保留兼容入口，委托 `canManageKnowledgeBase`；不再作为上传、成员管理、评测之间含义模糊的公共写检查。`KnowledgeBaseServiceImplTest` 覆盖其读取可信操作者并按目标库拒绝的行为。

## 3. Agent 与任务执行

| 入口 / 执行阶段 | 凭证与身份 | 操作 / 资源 | 授权与范围传递 | 测试定义 / 未验证项 |
| --- | --- | --- | --- | --- |
| `POST /agent/chat` | JWT → `CurrentUserUtil` | 本轮可读知识库与 Agent 会话 | `AgentService.chat` 解析 `resolveReadableIds`，构造 `AgentExecutionContext`；客户端请求不定义身份/权限字段；会话键在服务端加用户前缀 | `AgentServiceTest`：可信身份、空范围保留、权限查询失败时不进模型/记忆、用户会话隔离 |
| `knowledge_search / knowledge_ask` | 只使用执行上下文，不从工具参数或工作线程的 SecurityContext 推导 | 当前授权库中的候选 / 问答证据 | 工具将 context 范围传 `PythonRagPipeline`；null 收敛为空列表；参数注入 userId/库 ID 不扩大范围 | `KnowledgeSearchToolTest`、`KnowledgeAskToolTest`、`ToolExecutorTest`：范围传递、空范围和伪造库字段 |
| `POST /evaluation/regression` | HTTP 层要求 JWT | 客户端提交的聚合指标 | 比较输入指标，不查询知识库或指定用户会话 | 源码静态核对；不增加虚构的知识库授权 |
| 已受理任务的解析、Embedding、chunk 替换、完成与恢复投递 | 进程内部任务 ID；不接收客户端当前用户身份 | 持久化任务关联的 document 与库 | 受理/手动重试入口完成授权；工作线程按持久化归属处理，锁与外键处理删除后的晚到写入，不在后台重新扩大库范围 | 本轮保持现有生命周期与 SQL；不声称每步都重新鉴权，也不声称运行中即时撤权 |

源码：[AgentService](../../../knowledge-platform/src/main/java/com/pony/knowledge/agent/service/AgentService.java)、[KnowledgeSearchTool](../../../knowledge-platform/src/main/java/com/pony/knowledge/agent/tool/knowledge/KnowledgeSearchTool.java)、[KnowledgeAskTool](../../../knowledge-platform/src/main/java/com/pony/knowledge/agent/tool/knowledge/KnowledgeAskTool.java)、[DocumentTaskServiceImpl](../../../knowledge-platform/src/main/java/com/pony/knowledge/service/impl/DocumentTaskServiceImpl.java)。当前 `EvaluationController` 仅注册 retrieval/batch/regression；旧 report/agent/memory 入口及 MemoryEvaluationExecutor 已退出源码，不在此表承诺其能力。

## 4. MCP 与密钥管理

| 入口 / 操作 | 凭证与资源 | 认证 / 授权位置 | 范围与副作用边界 | 测试定义 / 未验证项 |
| --- | --- | --- | --- | --- |
| 精确协议路径 `/mcp`（默认配置） | `Bearer ekip_mcp_...`；不接受用户 JWT 替代 | `McpApiKeyAuthFilter → McpApiKeyService.authenticate`，身份通过 `McpIdentityContextExtractor` 进入传输上下文 | 未认证不进入 MCP 传输/工具；HTTP Security 放行此精确路径不等于匿名工具执行 | `McpApiKeyAuthFilterTest`；实际代理/允许 Host 与 Origin 未验收 |
| `tools/list` 工具暴露 | 服务级 `mcp.exposed-tools` | `McpToolBridge.buildSpecifications` | 空名单不暴露；未知名称阻断装配；没有每个 Key 的独立工具能力范围 | `McpToolBridgeTest`：默认拒绝与未知工具名 |
| `tools/call` 的知识库工具 | Key 所属用户 → `McpTransportContext.USER_ID` | `McpToolBridge.call → resolveReadableIds`，再交共享 `ToolExecutor` | **每次工具调用重新解析当前可读库**，不在 session 缓存授权集合；工具参数不能替换 context 身份；空范围仍为空 | `McpToolBridgeTest`：同一会话从两库、单库到空范围；伪造 userId/范围不改变上下文；不代表 Key 吊销或连接身份绑定已做真实协议验收 |
| `POST /mcp/keys` | 用户 JWT；新 Key 归属当前用户 | `McpApiKeyController → CurrentUserUtil → issue` | 不接受客户端指定 Key 拥有者；持久化哈希，明文只在签发响应出现 | `McpApiKeyServiceImplTest` |
| `GET /mcp/keys` | 用户 JWT | `McpApiKeyServiceImpl.list` | 查询按当前 `user_id` 限定，响应不含明文/哈希 | `McpApiKeyServiceImplTest` |
| `DELETE /mcp/keys/{keyId}` | 用户 JWT；目标 Key ID | `McpApiKeyServiceImpl.revoke` | 仅 Key 所属用户可吊销，其他用户被拒绝 | `McpApiKeyServiceImplTest`：跨用户吊销拒绝 |

源码：[SecurityConfig](../../../knowledge-platform/src/main/java/com/pony/knowledge/config/SecurityConfig.java)、[McpApiKeyAuthFilter](../../../knowledge-platform/src/main/java/com/pony/knowledge/mcp/auth/McpApiKeyAuthFilter.java)、[McpToolBridge](../../../knowledge-platform/src/main/java/com/pony/knowledge/mcp/bridge/McpToolBridge.java)、[McpApiKeyServiceImpl](../../../knowledge-platform/src/main/java/com/pony/knowledge/mcp/auth/McpApiKeyServiceImpl.java)。修改 MCP endpoint 配置时，需同时核对 Security 的精确路径与过滤器匹配；本轮不改该配置。

## 5. Java → Python 的行范围

Java 通过 `AiServiceClient` 调用内部 AI；Agent 决策使用独立 `AgentGatewayClient`。`X-Internal-Token` 证明服务调用身份，不能证明用户可读哪些知识库。知识库检索范围由上述 Java 入口解析，再经 `PythonRagPipeline` 的请求体 `knowledgeBaseIds` 传递；Python 不重新查询用户所属关系。

- [prepare_query](../../../ai/app/rag/pipeline.py) 在合法参数校验后对空 `knowledgeBaseIds` 短路，检索和模型 I/O 不执行；LangChain 复用同一阶段语义。
- [retriever.py](../../../ai/app/retrieval/retriever.py) 的向量、关键词、候选与父块扩展 SQL 绑定 `knowledge_base_id = ANY(...)`，不能把空范围解释为全库，也不能让 chunk/父块 ID 绕过库条件。
- 独立只读账号只约束数据库能力，不能替代知识库行范围；Python 业务入口还需内部令牌。
- Java 范围转发的证据来自 Service/工具测试；Python 空范围、SQL 绑定证据见 [test_rag_retrieval_authz.py](../../../ai/tests/test_rag_retrieval_authz.py)。这些是隔离测试定义，不能替代真实 PostgreSQL 角色权限与跨服务运行验收。

## 6. 覆盖核查与后续边界

新增或修改入口时，按“方法/路径或工具 → 凭证 → 可信操作者 → 操作 → 资源定位 → 授权方法或归属 SQL → 下游实际范围 → 拒绝后无副作用测试”补本表。单库操作必须校验实际资源所属库，列表操作必须说明 SQL/ID 范围；HTTP 认证通过、前端隐藏按钮或内部令牌通过均不能替代资源授权。

本轮不增加角色表、租户模型、Key scope 或越权后继续放行开关。只有出现多人维护需要区分文档编辑与成员管理时，再扩展知识库级角色；外部客户端需要不同数据/工具能力时，再设计“用户当前权限 ∩ Key 获准范围/能力”。

`/observability/**` 要求登录；Trace 保存时由服务端补归属，列表和详情按当前用户过滤，未知归属不回显，越权/不存在统一 404。部署外部观测服务时，按[观测手册](../../observability.md)配置访问范围。

撤权规则已经确定：新请求按当前权限，Agent 每次工具执行前取本轮已授予范围与当前可读范围的交集，只收缩；已发模型调用尽力取消，已建 SSE 不主动断开。真实进行中撤权以及 MCP Key 吊销对既有协议会话的影响仍须分别验收，不承诺即时撤回已输入模型的上下文。运行 Store 固定 Redis，测试内存替身不构成运行选项。

## 7. 后续新增入口

| 入口 | 当前检查与范围 | 边界 |
| --- | --- | --- |
| `GET /document/{id}/chunks` | 服务读取实际文档并核对创建者/成员权限，分页省略向量字段 | 不存在/无权限统一 403；重处理期间不保证跨页快照 |
| `POST /knowledge/ask/stream`、`POST /knowledge/sessions/{id}/ask/stream` | Java 从当前用户和会话核定可读范围，固定线程池传播 SecurityContext | 支持 `fixed / langchain`。LangChain 临时正文不写历史，完成回合以终帧和持久结果为准 |
| `POST /agent/chat/stream` | 服务端会话、用户/Agent/策略指纹复核；工具前单调收缩范围 | 完成先保存再发送 done，取消半段不存为完成历史 |
| `list_documents` / `read_document` | 工具复用本轮授权范围，文档 ID 按实际所属 KB 核对；Python SQL 同时绑定 KB/documentId | 只列已完成文档；page 模式有界窗口仍走准入，游标和内容偏移不扩大范围 |

完整字段以[文档](../modules/documents.md)、[Agent](../modules/agent.md)、[RAG](../modules/rag.md)契约和生产 DTO/schema 为准。
