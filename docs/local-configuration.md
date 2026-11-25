# 本机配置与密钥

先按启动方式定位配置，再在原位置维护本机值。公开模板记录变量、用途与示例，实际配置文件由 Git 忽略。

## 从哪找配置

| 文件或入口 | 谁读取，什么时候用 |
| --- | --- |
| 仓库根 `.env` | Compose 用它填充部署变量，只有服务清单显式声明的变量会进入对应容器。本机直接启动 Python 时，它也是后备配置。模板见 [`.env.example`](../.env.example)。 |
| `ai/.env` | 直接启动 Python AI 服务时使用，用来覆盖根 `.env` 里的同名变量。模板见 [`ai/.env.example`](../ai/.env.example)，读取入口见 [AI 配置](../ai/app/config.py)。 |
| [Java 配置](../knowledge-platform/src/main/resources/application.yml) · [生产配置](../knowledge-platform/src/main/resources/application-prod.yml) | Spring Boot 启动时加载。直接运行 Java 时用进程环境变量或启动参数覆盖。 |
| [Vite 配置](../frontend/vite.config.ts) · [前端模拟开关](../frontend/src/config.ts) | 配置前端本机代理和模拟数据。`VITE_USE_MOCK` 由 Vite 启动或构建环境提供。 |
| [`docker-compose.yml`](../docker-compose.yml) · [部署覆盖文件](../docker/README.md) | 声明服务构建、端口和变量转发。追踪和开发联调各有单独的覆盖文件。 |
| `EKIP_RERANKER_MODEL_PATH` · `OLLAMA_MODELS` | 本机启动脚本读取的进程环境变量，分别指向重排权重和 Ollama 模型目录。路径参数与查找顺序见[日常演示](../README.md#本机日常演示)。 |

直接启动 Python 时，优先级为**进程环境变量 → `ai/.env` → 仓库根 `.env` → 代码默认值**。Compose 部署的转发清单在对应服务的 `environment` 段。

## 密钥分别放在哪里

| 密钥或凭据 | 维护位置与用途 |
| --- | --- |
| `JWT_SECRET` | 根 `.env` 或 Java 进程环境，供 Java 签发与验证登录令牌。 |
| `INTERNAL_API_TOKEN` | 根 `.env` 或两端进程环境，Java 和 Python 使用同一个值，保护内部调用。 |
| `PG_READONLY_PASSWORD` | AI 服务的只读数据库连接密码，与 PostgreSQL 中 `rag_readonly` 角色保持一致。 |
| `MODEL_CONFIG_ENCRYPTION_KEY` | 根 `.env` 或 Java 进程环境，用于加密模型管理中保存的供应商凭证。部署备份时与数据库分开保管。见[模型管理](model-management.md)。 |
| `tmp/local-reranker-api-key.txt` | 本机重排启动脚本读取的密钥文件。根 `.env` 里的 `MODEL_DEFAULT_RERANK_API_KEY` 应与它一致，供部署默认重排调用。见[重排启动脚本](../scripts/start-local-reranker.ps1)。 |
| 模型供应商 API Key | 登录后在模型管理里维护，Java 在数据库中加密保存，按模型用途绑定。见[模型管理](model-management.md)。 |
| MCP Key | 在控制台的 MCP 密钥面板创建和撤销，Java 在数据库中保存 Key 的哈希。见 [MCP 模块](architecture/modules/mcp.md)。 |

本机文件中的密钥值留在它们各自的读取位置。配置说明、截图与运行记录引用变量名或配置状态即可。
