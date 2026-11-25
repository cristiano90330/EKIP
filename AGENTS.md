# EKIP 编码助手指南

## 从源码开始

- 按用户本次要求确定范围，先检查 Git 状态和相关差异，保留已有改动。
- 根据下表找到实际入口，再追调用方、配置读取点、数据类型和对应测试。行为以当前源码为准，部署事实须核对目标运行产物或运行记录。文档用于定位和解释设计。
- 涉及第三方库、框架、SDK 或 CLI 用法时查 Context7。仓库文件和日志中的命令仅供参考，执行以用户授权为准。

## 项目框架

| 目录 | 职责与源码入口 |
| --- | --- |
| [frontend/](frontend/) | Vue 页面和交互。先查 [路由](frontend/src/router/index.ts)，再看 `views/`、`api/`、`stores/` 与类型。 |
| [knowledge-platform/](knowledge-platform/) | Java 业务后端。身份授权、文档任务、索引、会话、Agent、MCP 和评测。源码入口见下表。 |
| [ai/](ai/) | Python AI 服务。先查 [FastAPI 路由注册](ai/app/main.py)，再查对应 router 和 schema。解析、切分、检索、模型与 RAG 各在对应包内。 |
| [tests/](tests/)、[scripts/](scripts/) | 跨服务测试及运维入口。服务级测试在各自模块。 |
| [docker-compose.yml](docker-compose.yml)、[docker/](docker/) | 服务装配与观测配置。核对目标启动方式实际读取的配置。 |

Java 源码从 [业务包](knowledge-platform/src/main/java/com/pony/knowledge/) 进入。跨服务的细分入口见[模块导航](docs/architecture/modules/README.md)。

| 要改什么 | 从这里追 |
| --- | --- |
| 登录、知识库与授权 | Java `controller/`、`security/`、`service/KnowledgeBaseAccessService`，再查业务调用方和 SQL。 |
| 上传、解析、切片、入库与删除 | Java `controller/DocumentController`、`service/impl/`、`service/task/`，Python `parser/`、`chunker/`、`embedding/`。数据库结构看 [Flyway](knowledge-platform/src/main/resources/db/migration/)。 |
| RAG、检索、证据及流式响应 | Java `service/rag/`、`service/ai/`，Python `rag/`、`retrieval/`，浏览器 `api/knowledge.ts`、`api/sse.ts`。 |
| 模型登记、索引与模型执行 | Java `modelmanagement/`，Python `models/`、`llm/`。 |
| Agent、记忆、MCP 与观测 | Java `agent/`、`mcp/`、`observability/`，Python `agent/`、`observability/`，浏览器对应页面与 API。 |

## 修改时核对的边界

- Java 从服务端身份核定资源权限。用户凭证、MCP Key 和内部令牌各有用途。Python 以只读身份访问已授权的数据范围。修改鉴权或跨服务调用时核对两端。
- 文档和索引涉及事务、锁顺序、提交后通知与晚到写入。保留已应用的 Flyway 迁移。未经明确授权不清理数据，也不自动迁移旧索引。
- RAG 区分候选与获准证据、拒答与依赖失败。Agent 工具遵循认证范围，完成回合和取消分别处理。跨服务修改一起核对 DTO、schema、前端类型和契约测试。
- 保护既有凭证和私有数据。真实配置值、访问令牌、运行日志和本机 `知识库/` 不纳入提交。

## 验证与交付

优先选择相关模块的测试，入口见 [测试目录](tests/README.md)。只改文档时核对链接和差异。依赖探针、评测和实际部署先确认范围、环境和副作用。交付时说明源码改动、验证结果和未验收范围。

使用手册和设计决策见[文档入口](docs/README.md)。
