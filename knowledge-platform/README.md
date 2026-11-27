# EKIP 后端使用手册

企业智能知识管理平台的 Java 业务后端，提供用户认证、知识库与成员管理、文档入库任务、检索问答、Agent、MCP、评测和运行观测接口。默认监听 `http://localhost:8080`。

Java 管理身份、访问范围、业务数据、文件和任务状态；独立的 Python AI 服务负责文档解析、Embedding、检索编排与模型调用。本文以当前源码和配置为依据，配置表中的数值均为仓库默认值；部署时的覆盖值需以实际进程配置为准。

项目整体入口见[根 README](../README.md)，AI 服务说明见[AI README](../ai/README.md)。

## 目录

- [1. 后端组成与数据存储](#1-后端组成与数据存储)
- [2. 安装与启动](#2-安装与启动)
- [3. 配置参考](#3-配置参考)
- [4. 认证与访问权限](#4-认证与访问权限)
- [5. API 约定与基础操作](#5-api-约定与基础操作)
- [6. 文档上传与任务管理](#6-文档上传与任务管理)
- [7. 检索与 RAG 问答](#7-检索与-rag-问答)
- [8. Agent 与 MCP](#8-agent-与-mcp)
- [9. 评测接口](#9-评测接口)
- [10. 运行观测与排错](#10-运行观测与排错)
- [11. 源码导航](#11-源码导航)

## 1. 后端组成与数据存储

### 1.1 技术栈与职责

| 组成 | 当前实现 | 主要职责 |
| --- | --- | --- |
| 运行时 | Java 17、Spring Boot 3.5.16 | HTTP 服务、依赖装配、配置与生命周期 |
| Web | Spring MVC、Jakarta Validation | JSON / multipart 接口、参数校验、SSE |
| 认证 | Spring Security、jjwt 0.11.5、BCrypt | 登录、访问令牌、刷新与登出 |
| 数据访问 | MyBatis-Plus 3.5.7、JdbcTemplate | 业务实体、分页、chunk 原生 SQL 与事务 |
| 数据库 | PostgreSQL 17 + pgvector | 业务表、任务、文档切片、全文索引与向量 |
| Schema | Flyway | 启动时执行 `src/main/resources/db/migration/` 中的迁移 |
| 对象存储 | MinIO，SDK 8.5.17 | 上传文件与对象回收 |
| Redis | Redis 7 | 登录计数、刷新票据、RAG 历史、Agent 记忆 |
| AI 边界 | RestClient、HTTP/1.1 | 调用 Python AI 服务，传递内部令牌与请求标识 |
| MCP | MCP Java SDK 0.18.4 | Streamable HTTP 知识工具服务 |
| 观测 | Actuator、Micrometer、业务 Trace | 健康、指标、依赖能力与管线追踪 |

主要调用关系：

```text
HTTP 请求
  → Security 认证
  → Controller
  → Service：权限、会话、业务与任务编排
      ├─ MyBatis-Plus / JdbcTemplate → PostgreSQL
      ├─ MinIO → 文件对象
      ├─ Redis → 认证状态、历史与记忆
      └─ AiServiceClient / AgentGatewayClient → Python AI 服务
```

### 1.2 数据与访问边界

业务数据和向量数据使用同一个 `spring.datasource`，默认数据库名为 `knowledge`。

| 表 / 存储 | 内容 |
| --- | --- |
| `sys_user` | 用户、密码哈希与账号状态 |
| `knowledge_base`、`knowledge_base_member` | 知识库与成员关系 |
| `document` | 文件对象标识、所属知识库与文档处理状态 |
| `document_task` | 处理任务、尝试次数、错误与起止时间 |
| `document_upload_receipt` | 上传幂等键、文件摘要与回执关联 |
| `document_chunk` | 内容、来源元数据、父子块、关键词；旧向量列仅保留历史数据 |
| `managed_model`、`model_purpose_binding` | 按用户的模型配置、加密凭证与用途绑定 |
| `embedding_space`、`knowledge_index_generation / binding / rebuild_task` | 不可变空间、活动索引与持久重建任务 |
| `document_chunk_vector_1024 / 2560` | 按 generation 与 KB 定界的固定维度向量 |
| `mcp_api_key` | MCP 凭证哈希、所属用户与吊销状态 |
| MinIO bucket `knowledge-files` | 原始文件 |
| Redis | 刷新票据、登录失败计数、会话和记忆 |

生产向量由固定 `halfvec(1024)` / `halfvec(2560)` 表及 HNSW 索引承载，KB 活动 generation 决定模型空间。旧 `document_chunk.embedding` 保留但不自动认领或读取；来源未知的非空 KB 需要显式重建。`search_vector` 为 `tsvector`，使用 GIN 索引。中文全文检索使用解析阶段生成的 jieba 预分词文本。来源元数据可包含页码、标题路径、来源类型和表格信息。

Java 负责业务表与 chunk 写入；Python 使用独立账号 `rag_readonly` 读取 `document_chunk` 和新向量表，不能读取模型凭证表。知识库行范围由 Java 授权后传入 Python，并作为 SQL 绑定参数过滤。该数据库账号限制可读表，用户之间的知识库隔离仍由应用授权实现。

## 2. 安装与启动

以下命令使用 Windows PowerShell。完整入库和问答需要 PostgreSQL、Redis、MinIO、Python AI 服务及其模型依赖。

### 2.1 环境与必填配置

- JDK 17；项目提供 `mvnw.cmd` / `mvnw`，可使用 Maven 包装器。
- Docker Compose，或自行准备等价的 PostgreSQL 17 + pgvector、Redis、MinIO。
- Python 环境及 [ai/requirements.txt](../ai/requirements.txt) 中的依赖。
- 可达的模型服务；Java 按用户管理 Chat、Embedding、Rerank 配置/凭证，Python 按不可变快照解析执行。具体步骤见[模型管理与迁移](../docs/model-management.md)。

先从仓库根目录准备配置文件；已有 `.env` 时保留原文件：

```powershell
if (-not (Test-Path -LiteralPath '.env')) {
    Copy-Item -LiteralPath '.env.example' -Destination '.env'
}
```

编辑根 `.env`，至少填写：

| 项 | 要求 |
| --- | --- |
| `JWT_SECRET` | 至少 32 个原始 UTF-8 字节，Java 用于 JWT 签名 |
| `INTERNAL_API_TOKEN` | Java 与 Python 必须完全一致 |
| `PG_READONLY_USER` | 使用 `rag_readonly` |
| `PG_READONLY_PASSWORD` | 独立的数据库口令，后续设置到 PostgreSQL 角色上 |
| `MODEL_CONFIG_ENCRYPTION_KEY` | 保存模型 API Key 时必需；独立随机 32 字节的 Base64 值，供 AES-GCM 使用 |

`JWT_SECRET` 缺失或过短会阻止 Java 启动；`INTERNAL_API_TOKEN` 缺失会阻止服务正常启动。可用 `openssl rand -base64 48` 和 `openssl rand -hex 32` 分别生成随机值。

**配置加载分属各进程：**

| 运行方式 | 配置如何进入进程 |
| --- | --- |
| 本地 Java | `application.yml`、所选 profile、进程环境变量或启动参数；普通 Spring 配置不自动读取根 `.env` |
| Docker Compose | 根 `.env` 用于 Compose 插值；仅 `docker-compose.yml` 中声明的环境项会进入对应容器 |
| 本地 Python | 进程环境 > `ai/.env` > 根 `.env` > `ai/app/config.py` 默认值；模型选择通过 Java 管理接口 |

只有对应服务 `environment` 显式转发的部署变量进入容器。模型地址/凭证/用途使用管理接口，旧模型 dotenv 变量不再参与生产选择；普通 Java 启动仍不会自动全量加载根 dotenv。

### 2.2 本地运行 Java 后端

从仓库根目录启动本地开发依赖。

```powershell
docker compose up -d postgres redis minio-init
```

默认映射：PostgreSQL `5432`、Redis `6379`、MinIO API `9000`、MinIO 控制台 `9001`。`minio-init` 创建业务 bucket。

另开一个终端，从仓库根目录设置必填环境变量并启动 Java。把示例值替换为自己的配置，内部令牌与根 `.env` 保持一致。

```powershell
$env:JWT_SECRET = '<至少32字节的随机密钥>'
$env:INTERNAL_API_TOKEN = '<与Python相同的内部令牌>'

Set-Location './knowledge-platform'
.\mvnw.cmd spring-boot:run
```

数据库地址与本手册默认值不同时，还需设置 `POSTGRES_HOST`、`POSTGRES_PORT`、`POSTGRES_DB`、`POSTGRES_USER`、`POSTGRES_PASSWORD`。Java 启动时执行 Flyway，数据库用户需具备执行迁移的权限。

首次初始化数据库后，在另一终端从仓库根目录进入 PostgreSQL，为 AI 只读角色设置口令。

```powershell
docker compose exec postgres psql -U postgres -d knowledge
```

在 `psql` 内交互执行：

```text
\password rag_readonly
\q
```

输入与 `PG_READONLY_PASSWORD` 一致的口令。Flyway 管理角色权限，但不会替部署环境轮换该口令。

### 2.3 配套运行 Python AI 服务

在已安装 AI 依赖的 Python 环境中，从**仓库根目录**启动：

```powershell
$env:PYTHONUTF8 = '1'
$env:MINIO_ENDPOINT = 'localhost:9000'
$env:MINIO_ACCESS_KEY = 'admin'
$env:MINIO_SECRET_KEY = '12345678'
$env:MINIO_BUCKET = 'knowledge-files'
python -m uvicorn ai.app.main:app --host 127.0.0.1 --port 8000
```

根 `.env` 中的内部令牌和只读账号必须已填写。模型服务地址、模型名称、重排及证据准入配置参见 [AI 配置模板](../ai/.env.example)。

Java 进程启动成功与 AI 依赖就绪是不同状态。开始入库或问答前，应查看 `/observability/capabilities` 中相应能力，详见第 10 节。

### 2.4 打包与运行 JAR

在后端目录执行：

```powershell
.\mvnw.cmd -DskipTests package
java -jar .\target\knowledge-backend-0.0.1-SNAPSHOT.jar
```

可通过启动参数覆盖服务端配置，例如：

```powershell
java -jar .\target\knowledge-backend-0.0.1-SNAPSHOT.jar --server.port=8080 --spring.profiles.active=prod
```

`prod` profile 仅开放 `health,info,prometheus` 管理端点，并禁用 `loggers`、`env`、`configprops`。数据库、密钥和业务参数仍需独立配置。

### 2.5 使用 Compose 运行后端

填写根 `.env` 后，从仓库根目录启动后端及其依赖。

```powershell
docker compose up -d --build backend minio-init
docker compose logs -f backend ai
```

默认依赖包括 PostgreSQL、Redis、MinIO 和 AI；OpenTelemetry Collector 属于可选观测方案。首次启动时，等待后端执行 Flyway，再按第 2.2 节为 `rag_readonly` 设置与 `.env` 一致的口令。

Compose 中 Java 使用服务名访问依赖；模型服务地址在用户模型记录中登记。Ollama 原生协议使用服务根地址，容器访问宿主机时可使用环境可达的 `http://host.docker.internal:11434`。Chat、Embedding、Rerank 可分别选本地或云端，没有默认重排端口或隐式替代模型。

不指定服务名的 `docker compose up -d --build` 会启动 6 个业务服务。完整观测栈通过 `docker-compose.observability.yml` 显式合并，还需要填写根配置模板 E2/E3 项并预建独立的 `langfuse` 数据库；操作见 [观测说明](../docs/observability.md)。Compose 当前未设置 `SPRING_PROFILES_ACTIVE=prod`。

## 3. 配置参考

主配置：[application.yml](src/main/resources/application.yml)。生产端点覆盖：[application-prod.yml](src/main/resources/application-prod.yml)。

下表列出项目明确使用的环境变量和属性名。属性也可通过外部配置或 `--属性名=值` 启动参数覆盖；Compose 部署需另行检查变量是否转发。

### 3.1 连接、认证与文件

| 环境变量 | 默认值 | 含义 |
| --- | --- | --- |
| `POSTGRES_HOST` / `POSTGRES_PORT` / `POSTGRES_DB` | `localhost` / `5432` / `knowledge` | 数据库地址 |
| `POSTGRES_USER` / `POSTGRES_PASSWORD` | `postgres` / `123456` | Java 数据库账号，本地开发默认值 |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | Redis 地址 |
| `MINIO_ENDPOINT` | `http://localhost:9000` | Java 对象存储地址 |
| `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY` | `admin` / `12345678` | 本地开发存储凭据 |
| `MINIO_BUCKET` | `knowledge-files` | 业务文件 bucket |
| `JWT_SECRET` | 无可用默认值 | 原始 UTF-8 签名密钥，至少 32 字节 |
| `JWT_ACCESS_EXPIRE_MINUTES` / `JWT_REFRESH_EXPIRE_DAYS` | `15` / `7` | 访问令牌分钟数 / 刷新令牌天数 |
| `LOGIN_THROTTLE_ENABLED` | `true` | 登录失败计数开关 |
| `LOGIN_THROTTLE_MAX_ATTEMPTS` / `LOGIN_THROTTLE_LOCK_MINUTES` | `5` / `15` | 失败阈值 / 锁定分钟数 |
| `MULTIPART_MAX_FILE_SIZE` / `MULTIPART_MAX_REQUEST_SIZE` | `50MB` / `60MB` | 单文件 / 整个 multipart 请求大小上限 |
| `AI_BASE_URL` | `http://127.0.0.1:8000` | Java 调用 Python 的地址 |
| `INTERNAL_API_TOKEN` | 无 | Java → Python 内部令牌 |
| `VECTOR_STORE` | `pgvector` | 当前存储实现，使用此值 |

数据库连接池上限为 `10`，最小空闲连接为 `0`；JdbcTemplate 查询超时为 `8s`。Redis 连接超时为 `1s`，读取超时为 `2s`。

### 3.2 检索、问答与会话

| 属性 | 默认值 | 含义 / 显式环境入口 |
| --- | --- | --- |
| `rag.pipeline.engine` | `fixed` | `fixed / langchain`；`RAG_PIPELINE_ENGINE`，公开查询也可逐请求选择 |
| `retrieval.version` | `hybrid-rerank-rewrite` | 检索策略，见第 7 节 |
| `retrieval.recall-n` / `retrieval.rrf-k` | `20` / `60` | 重排候选数 / RRF 常量 |
| `rag.query.budget-ms` | `270000` | 生成问答传入 Python 的总预算，默认读取 `RAG_QUERY_DEADLINE_MS`。纯检索和 Agent 知识工具上限为 35000ms |
| `rag.history-window` | `6` | 多轮查询使用的历史消息条数 |
| RAG 会话存储 | Redis | 唯一运行实现；不提供内存配置开关 |
| `rag.compression.mode` | `none` | `none / extractive` |
| `rag.token-budget` | `1500` | 抽取压缩的 token 预算 |
| `rag.mmr.enabled` / `rag.mmr.lambda` | `false` / `0.7` | 多样性选择；`RAG_MMR_ENABLED` / `RAG_MMR_LAMBDA` |
| `rag.small-to-big.enabled` | `false` | 父块上下文扩展；`RAG_SMALL_TO_BIG_ENABLED` |
| `rag.small-to-big.parent-max-chars` | `4096` | 入库父块字符上限；`RAG_SMALL_TO_BIG_PARENT_MAX_CHARS` |
| `rag.llm-price-per-1m-tokens` | `2.0` | 评测成本估算单价 |

引擎、检索策略和压缩模式在 Java 启动时校验白名单。压缩、MMR、父块扩展等配置由 Java 随请求传给 Python。父块功能需要入库时生成父子关系，已有文档须重新入库才能获得该元数据。

`rag.budget.*` 用于记录阶段耗时、token、成本超限的日志与指标：rewrite `3000ms`、retrieval `2000ms`、rerank `3000ms`、compression `2000ms`、generation `30000ms`、tokens `4000`、cost-usd `0.01`。这些观测阈值不负责强制中断；查询截止预算由 `rag.query.budget-ms` 控制。

### 3.3 AI HTTP 与熔断

| 属性 | 默认值 | 作用 |
| --- | --- | --- |
| `ai.client.connect-timeout-ms` | `3000` | AI HTTP 建连超时 |
| `ai.client.ingest-read-timeout-ms` | `120000` | 入库传输配置；Embedding 按快照总预算另留 5000ms 外层余量 |
| `ai.client.query-read-timeout-ms` | `275000` | RAG HTTP 读取超时。带模型快照的同步和流式传输共用快照预算加 5000ms 的外层截止 |
| `ai.stream.timeout-ms` | `285000` | Java 问答 SSE 连接的总等待上限 |
| `ai.client.resilience.enabled` | `true` | 可用性熔断，`AI_RESILIENCE_ENABLED` |
| `ai.client.resilience.sliding-window-size` | `10` | 最近有效调用数，`AI_RESILIENCE_SLIDING_WINDOW_SIZE` |
| `ai.client.resilience.minimum-number-of-calls` | `5` | 计算失败率前最小调用数，`AI_RESILIENCE_MINIMUM_CALLS` |
| `ai.client.resilience.failure-rate-threshold` | `50` | 打开阈值（百分比），`AI_RESILIENCE_FAILURE_RATE` |
| `ai.client.resilience.open-cooldown-ms` | `10000` | 冷却期，`AI_RESILIENCE_OPEN_COOLDOWN_MS` |
| `ai.client.resilience.half-open-probes` | `1` | 半开探测并发数，`AI_RESILIENCE_HALF_OPEN_PROBES` |

熔断使用 Resilience4j 2.2.0 的计数滑窗：默认最近 10 次有效调用，至少 5 次后失败率达到 50% 打开，冷却 10 秒后放行 1 次探测。成功不清空已有失败；连接故障和 HTTP 5xx 计入失败，4xx、读超时和主动取消排除。同步与流式共用同一熔断器，每次 HTTP 调用最多结算一次；流式按响应头判定服务可达，业务完成仍由终态帧判定。旧失败次数/时间窗配置退出，不增加重试或模型回退。


熔断覆盖连接故障和可用性失败；成功调用会清空失败计数。读取超时由 HTTP 超时机制处理，不作为同类可用性失败累计。`AiServiceClient` 与 `AgentGatewayClient` 共用该可用性保护。

### 3.4 任务、对象回收与观测

| 属性 | 默认值 | 作用 |
| --- | --- | --- |
| `task.reliability.max-attempts` | `3` | 每轮自动处理的尝试上限，包含首次 |
| `task.reliability.backoff-base-ms` / `task.reliability.backoff-cap-ms` | `5000` / `60000` | 指数退避参数；默认上限下实际退避为 5s、10s |
| `task.reliability.stuck-timeout-ms` | `600000` | PROCESSING 卡死判定年龄 |
| `task.reliability.waiting-timeout-ms` | `60000` | WAITING 补投年龄 |
| `task.reliability.orphan-scan-interval-ms` | `30000` | 运行期恢复扫描间隔 |
| `task.reliability.recovery-batch-size` | `100` | 每批扫描量 |
| `task.reliability.recovery-enabled` | `true` | 启动恢复与周期扫描 |
| `storage.reclaim.mode` | `disabled` | 回收模式 `disabled / dry-run / delete`，通过 `STORAGE_RECLAIM_MODE` 设置 |
| `storage.reclaim.enabled` | `false` | 兼容 `STORAGE_RECLAIM_ENABLED`。未指定新模式时，`true` 对应 `delete`，`false` 对应 `disabled` |
| `storage.reclaim.grace-period-hours` | `24` | 回收宽限期，`STORAGE_RECLAIM_GRACE_HOURS` |
| `storage.reclaim.scan-interval-ms` | `86400000` | 回收间隔，`STORAGE_RECLAIM_SCAN_MS` |
| `storage.reclaim.initial-delay-ms` | `300000` | 回收首轮延迟，`STORAGE_RECLAIM_INITIAL_DELAY_MS` |
| `observability.capabilities.refresh-interval-ms` | `10000` | 能力快照刷新间隔 |
| `observability.capabilities.initial-delay-ms` / `observability.capabilities.probe-timeout-ms` | `1000` / `750` | 首次刷新延迟 / 探测等待上限 |
| `observability.trace.retention` | `1000` | 实例内存 Trace 保留条数 |

`dry-run` 与 `delete` 共用宽限期和候选筛选。预览记录候选对象和总数。数据库引用为空、引用查询失败或对象列表读取失败时跳过回收。无效的模式值会在启动时报错。根 Compose 通过 `STORAGE_RECLAIM_MODE` 传入新模式。

## 4. 认证与访问权限

### 4.1 访问令牌与刷新票据

注册、登录、刷新和登出入口允许匿名到达；刷新和登出仍须提交自身的 `refreshToken`。其余普通业务接口使用：

```http
Authorization: Bearer <access-token>
```

密码以 BCrypt 存储。登录由 `AuthenticationManager` 完成，JWT subject 为用户 ID。业务身份从认证上下文取，不接收客户端自报的当前用户 ID。

登录响应：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "id": 1,
    "username": "reader",
    "token": "<access-token>",
    "refreshToken": "<refresh-token>",
    "expiresIn": 900
  }
}
```

- `token` 用于业务请求；默认有效 15 分钟。
- `refreshToken` 默认有效 7 天，登记在 Redis。刷新会原子消费旧票据，返回新的一对令牌；客户端须同时更新两者。
- 重放已消费的刷新票据会吊销对应会话族。
- 登出吊销刷新会话；已签发的访问令牌仍可用到自身到期。
- JWT 请求认证不逐请求查询数据库账号状态或 Redis。
- 登录失败按 `(username, IP)` 组合计数，默认 5 次后锁定；HTTP 429 携带 `Retry-After`。Redis 限流故障时放行并记录错误。
- Redis 刷新票据登记失败时，登录仍可能成功；之后刷新不能据此保证可用。刷新读取失败会拒绝处理。

### 4.2 知识库权限矩阵

“创建者”指知识库创建者；“成员”指被加入该库的用户。

| 操作 | 创建者 | 成员 | 其他用户 |
| --- | --- | --- | --- |
| 库列表中可见、文档列表、任务查询、任务 SSE、按库问答 | 允许 | 允许 | 拒绝 |
| 上传、上传回执查询 | 允许 | 拒绝 | 拒绝 |
| 删除文档、手动重试 | 允许 | 拒绝 | 拒绝 |
| 成员添加、移除、列表 | 允许 | 拒绝 | 拒绝 |
| 检索评测指定知识库 | 允许 | 拒绝 | 拒绝 |

全局 `search / ask`、Agent 和 MCP 按调用用户的“创建库 ∪ 成员库”解析访问范围。没有可访问知识库时使用空范围，不能扩展为全库检索。

公共授权入口使用 `resolveReadableIds / canRead` 表达读取，使用 `canMaintainDocuments / canManageMembers / canEvaluate / canManageKnowledgeBase` 表达创建者操作。文档删除、重试继续由归属 SQL 限定目标。接口、任务和工具的具体检查位置见[知识库授权覆盖矩阵](../docs/architecture/contracts/access-matrix.md)。

Java 调 Python 的业务接口使用 `X-Internal-Token`；客户端应调用 Java 业务入口。`AiServiceClient` 和 `AgentGatewayClient` 分别设置内部令牌，并传递 `X-Request-ID`。

Java 在 Security 之前建立请求标识，所有响应通过 `X-Request-ID` 返回；认证拒绝、参数错误和正常业务请求都可据此关联日志。合法外部标识为 1–128 个 ASCII 字母、数字或 `._:-`，缺失、重复或非法时生成 UUID。两个 AI 出口优先沿用入口标识，Python 沿用并回显；非 HTTP 后台调用保留 Trace/span 或 UUID 兜底。Request ID 是关联字段，独立于实际 Trace/span ID，也不承担身份或资源授权。

## 5. API 约定与基础操作

### 5.1 响应格式

使用 `Result<T>` 的成功响应：

```json
{"code": 200, "message": "success", "data": {}}
```

成功响应按接口分为：

| 接口 | 成功格式 |
| --- | --- |
| 用户、知识库、文档、评测、观测、MCP 密钥管理 | `Result<T>` |
| `GET /knowledge/search` | `ChunkSearchResultDTO[]` 裸数组 |
| `POST /knowledge/ask` | `RAGResponseDTO` 裸对象 |
| `POST /rag/chat` | `RagChatResponseDTO` 裸对象 |
| `POST /agent/chat` | `AgentResponse` 裸对象 |
| `POST /agent/chat/stream` | `text/event-stream`，`accepted / round / delta / tool_call / tool_result / answer / done / error` |
| `POST /knowledge/ask/stream`、`POST /knowledge/sessions/{id}/ask/stream` | `text/event-stream`，支持 `fixed / langchain`，以 `done` 或 `error` 结束 |
| 文档任务流 | `text/event-stream`，`task` 事件 |
| `/mcp` | MCP 协议 |
| Actuator | 端点自身格式 |

全局异常处理通常返回 `Result`；调用方需先判断 HTTP 状态，再按接口读取响应。

| HTTP | 常见含义 |
| --- | --- |
| 400 | Bean / MVC 方法参数校验、缺少参数、JSON 格式或参数类型错误、非法编排引擎 |
| 401 | 用户未认证、访问令牌失效或登录凭证错误 |
| 403 | 无业务权限或登录账号状态不允许 |
| 409 | 上传幂等键对应的文件内容冲突 |
| 413 | multipart 大小超限 |
| 422 | 可预期业务规则失败 |
| 429 | 登录失败次数超限 |
| 503 | RAG 依赖、内部鉴权或会话历史不可用 |
| 504 | RAG 查询超时 |
| 500 | 未被具体处理器识别的错误，对外返回固定提示 |

### 5.2 用户与知识库接口

| 方法 | 路径 | 请求 / 用途 |
| --- | --- | --- |
| POST | `/user/register` | `{"username":"reader","password":"Example123"}`；密码 6–20 位 |
| POST | `/user/login` | 同名字段，返回登录信息 |
| POST | `/user/refresh` | `{"refreshToken":"..."}` |
| POST | `/user/logout` | `{"refreshToken":"..."}` |
| GET | `/user/search?username=reader` | 登录后按用户名搜索用户 |
| POST | `/knowledge-base` | `{"name":"企业制度","description":"内部制度文档"}` |
| GET | `/knowledge-base/list` | 当前用户创建或加入的知识库 |
| POST | `/knowledge-base/{knowledgeBaseId}/members` | `{"userId":2}` |
| GET | `/knowledge-base/{knowledgeBaseId}/members` | 成员列表 |
| DELETE | `/knowledge-base/{knowledgeBaseId}/members/{userId}` | 移除成员 |

知识库名称非空且不超过 100 字符，描述不超过 500 字符。创建接口返回成功文案；知识库 ID 从列表获取。用户名唯一性按 `lower(username)` 约束。

PowerShell 登录与创建示例：

```powershell
$baseUrl = 'http://localhost:8080'
$credentials = @{ username = 'reader'; password = 'Example123' } | ConvertTo-Json

Invoke-RestMethod -Method Post -Uri "$baseUrl/user/register" -ContentType 'application/json; charset=utf-8' -Body $credentials
$login = Invoke-RestMethod -Method Post -Uri "$baseUrl/user/login" -ContentType 'application/json; charset=utf-8' -Body $credentials
$accessToken = $login.data.token
$refreshToken = $login.data.refreshToken
$headers = @{ Authorization = "Bearer $accessToken" }

$kbBody = @{ name = '企业制度'; description = '内部制度文档' } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "$baseUrl/knowledge-base" -Headers $headers -ContentType 'application/json; charset=utf-8' -Body $kbBody
$kbList = Invoke-RestMethod -Uri "$baseUrl/knowledge-base/list" -Headers $headers
$kbList.data
```

后续示例沿用 `$baseUrl`、`$headers`、`$accessToken`，并将 `$kbId`、`$documentId` 替换成实际返回的 ID。

## 6. 文档上传与任务管理

### 6.1 接口与文件限制

| 方法 | 路径 | 参数与返回 |
| --- | --- | --- |
| POST | `/document/upload` | multipart：`knowledgeBaseId`、`file`；可选头 `Idempotency-Key`；返回上传回执 |
| GET | `/document/upload/receipt` | 查询串 `knowledgeBaseId`、`requestKey`；返回 `found` 与命中时的 `receipt` |
| GET | `/document/list` | `knowledgeBaseId`、`page`、`size`；返回分页文档 |
| GET | `/document/{documentId}/chunks` | 创建者/成员可读；`page=1`、`size=20`（最多 100），按切片序号和 ID 排序；返回正文、字符数、来源与父块关联的分页 |
| GET | `/document/{documentId}/task` | 返回任务快照 |
| GET | `/document/{documentId}/task/stream` | SSE，订阅后推送快照及变化 |
| POST | `/document/{documentId}/retry` | 失败任务重新入队，返回任务快照 |
| DELETE | `/document/{documentId}` | 删除业务记录及文件对象 |

Java 上传入口检查扩展名：`pdf / md / markdown / docx / doc / pptx / ppt / xlsx / xls / epub / xmind / txt / csv / json`。白名单只承诺「允许上传」，各格式的实际解析能力见 [AI README](../ai/README.md) 的格式表：`.doc` / `.ppt` 需要部署侧提供 LibreOffice，否则解析阶段返回 422 并提示另存为新格式。默认单文件 50MB、整个请求 60MB。文档分页默认 `page=1`、`size=10`，`size` 范围 1–100。

从仓库根目录运行上传示例。

```powershell
$kbId = 1
$requestKey = [guid]::NewGuid().ToString()
curl.exe -sS -H "Authorization: Bearer $accessToken" -H "Idempotency-Key: $requestKey" -F "knowledgeBaseId=$kbId" -F "file=@ai/enterprise_docs/employee_handbook.pdf" "$baseUrl/document/upload"
```

回执结构示例，状态会随异步处理进度变化：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "requestKey": "client-generated-key",
    "documentId": 10,
    "taskId": 10,
    "documentStatus": "UPLOADED",
    "taskStatus": "WAITING",
    "reused": false
  }
}
```

**回执表示上传已受理，不能据此判断解析完成。** 使用 `documentId` 查询任务，等待 `SUCCESS` 后再进行检索。

### 6.2 上传幂等与回执找回

`Idempotency-Key` 去除两端空白后长度不超过 128，允许字母、数字和 `._:-`。作用域为“用户 + 知识库 + 键”。

- 同一键与相同文件内容重复上传，复用回执并标记 `reused=true`。
- 同一键提交不同文件内容，返回 HTTP 409。
- 响应丢失后，可用原键调用回执查询；没有命中时 `found=false`。
- 客户端应在首次发送前保存请求键，网络重试时复用它。

```powershell
Invoke-RestMethod -Uri "$baseUrl/document/upload/receipt?knowledgeBaseId=$kbId&requestKey=$requestKey" -Headers $headers
```

### 6.3 入库执行过程与状态

```text
校验知识库写权限
  → 计算文件 SHA-256、上传 MinIO
  → 事务写入 document / document_task / 上传回执
  → 提交后由 TaskDispatchCoordinator 投递线程池
  → 调用 Python /parser/object：按 objectName 从 MinIO 读取并解析
  → 调用 /embedding 生成向量
  → 事务替换 chunk、写入向量及关键词
  → 事务更新任务与文档终态
  → 提交后发布任务事件
```

线程池 `documentTaskExecutor` 默认 core `2`、max `5`、队列 `100`。投递被拒绝时任务保留 / 回到 `WAITING`，交给恢复扫描补投。

| 处理阶段 | 文档状态（列表实体的数值） | 任务状态（任务 DTO 的字符串） |
| --- | --- | --- |
| 已受理 | `UPLOADED = 0` | `WAITING` |
| 正在处理 / 自动退避 | `PROCESSING = 1` | `PROCESSING` |
| 已完成 | `COMPLETED = 2` | `SUCCESS` |
| 处理失败 | `FAILED = 3` | `FAILED` |

回执中的 `documentStatus`、`taskStatus` 使用名称。任务快照还包含：

| 字段 | 含义 |
| --- | --- |
| `autoRetryCount` | 本轮引擎认领次数，包含首次处理 |
| `manualRetryCount` | 用户手动重试次数 |
| `attempt`、`retryCount` | 累计引擎认领次数 |
| `errorCode`、`errorMessage` | 当前错误 |
| `lastErrorCode`、`lastErrorMessage` | 保留的上次终态错误 |
| `startTime`、`finishTime` | 本次执行起止时间 |

### 6.4 重试、恢复与 SSE

网络、可用性、HTTP 5xx / 408 / 429 等瞬时失败进入自动重试；默认总计最多 3 次尝试，退避 5s、10s。自动退避期间维持 `PROCESSING`。

手动重试只接受 `FAILED`：转为 `WAITING`、清空本轮自动计数、增加手动计数，并保留累计尝试次数和上次失败信息。

启动恢复分页重新投递 `WAITING / PROCESSING`；运行期每 30s 扫描超龄 `WAITING` 和卡死 `PROCESSING`，每类每轮最多一批 100 条。默认年龄阈值分别为 60s、10min，这些参数不构成完成时间承诺。

内部职责集中在三个入口：`TaskDispatchCoordinator.dispatch / scheduleRetry` 管普通投递与延迟投递，`TaskCompletionService` 管任务/文档关联提交及投递失败后的恢复，`TaskRecoveryService` 管候选扫描。重试不再经过独立转发类；定时器拒绝和退避后的队列拒绝共用 `recoverRejectedRetry`，仍通过原独立事务转为任务 `WAITING` / 文档 `UPLOADED`。当前错误为 `TASK_DISPATCH_REJECTED`，最近引擎错误保留；状态已变化或文档已删除时不覆盖新状态。职责、固定接口和验证范围见 [任务模块页](../docs/architecture/modules/tasks.md)。

查询与订阅：

```powershell
$documentId = 10
Invoke-RestMethod -Uri "$baseUrl/document/$documentId/task" -Headers $headers
curl.exe -N -H "Authorization: Bearer $accessToken" "$baseUrl/document/$documentId/task/stream"
```

SSE 事件名为 `task`，数据是任务 DTO。连接超时为 180s；断开连接不取消后台任务。客户端重连时应重新读取快照，普通浏览器客户端需使用能够携带 Authorization 的请求方式。

### 6.5 索引重建维护窗口

KB 创建者可通过 `/knowledge-bases/{id}/index` 查看索引状态，选择自己的 Embedding 模型，并在文档任务结束后显式发起只读维护重建。后台读取现有非父块正文，批量生成影子 generation；校验成功后短事务切换活动指针。失败/取消保留旧有效索引并恢复写入，持久任务可按租约恢复。维护期间上传、删除、重处理返回 409，旧有效索引可继续查询。1024↔2560 和同维换模型都需要重建，不能只改默认绑定或维度变量。

### 6.6 数据一致性与部署边界

- `DocumentChunkService.replaceChunks` 在 Embedding 完成后进入短事务：锁定文档、删除旧块、插入新块；文档已删除时拒绝后续写入。
- `TaskCompletionService` 使用独立 `REQUIRES_NEW` 事务，按文档锁 → 任务 CAS → 文档状态的顺序提交终态，事件在提交后发送。
- 删除先提交数据库事务，由外键级联清理 chunk、任务、上传回执，再删除 MinIO 对象。对象删除失败写日志。
- 孤儿对象回收默认关闭；启用后按数据库引用与宽限期筛选，数据库读取失败时不执行删除。
- 当前任务协调、恢复与 SSE 使用单后端实例假设。同 JVM 协调器抑制重复投递；数据库认领条件允许 `PROCESSING` 再认领，未提供跨实例互斥租约或事件广播。部署时使用一个后端实例。

## 7. 检索与 RAG 问答

### 7.1 查询入口

| 入口 | 输入 | 范围与历史 |
| --- | --- | --- |
| `GET /knowledge/search` | `query`、`topK=5`、可选 `pipelineEngine` | 当前用户全部可访问库；无历史；只返回检索候选 |
| `POST /knowledge/ask` | `question`、`topK=5`、可选 `pipelineEngine` | 当前用户全部可访问库；单轮生成 |
| `POST /knowledge/ask/stream` | 同上 | 当前用户全部可访问库，单轮 SSE |
| `POST /rag/sessions/{id}/ask` | 会话 ID、`question`、`topK=5`、可选 `pipelineEngine` | 固定的全库或单库范围，可使用最近三轮完成历史 |
| `POST /knowledge/sessions/{id}/ask/stream` | 同上 | 同一会话范围和历史规则，使用 SSE |
| `POST /rag/chat` | `knowledgeBaseId`、`conversationId`、`question`、`topK=5`、可选 `pipelineEngine` | 单库、多轮 |

各入口的问题（`question / query`）均必填，去除首尾空白后长度为 1–4000 个 Unicode 码点；空白判断与 Python `str.strip` 一致，原始问题仍交给下游处理。JSON 的 `question / conversationId / pipelineEngine` 不接受数字、布尔、数组或对象隐式转为字符串，必填字段的 `null` 仍被拒绝。`topK` 省略时为 5，范围为 1–20；JSON 的 `topK` 仅接受整数或 `null`（按默认值处理），小数、布尔和字符串返回 400。显式引擎值为 `fixed / langchain`。同步入口省略引擎时使用服务默认值，SSE 入口沿用 `fixed`。非法输入在进入查询业务前返回 400，缺少有效认证的请求先返回 401。

`chat` 的 `knowledgeBaseId` 必须为 JSON 正整数；`conversationId` 必填，长度 1–128，允许 Unicode 字母、数字和 `._:-`。同一用户、同一库、同一 `conversationId` 复用历史；不同用户或知识库隔离。默认取最近 6 条消息。

Python 仍独立检查内部请求。公开 `topK` 上限 20 与 Python 默认 `RAG_QUERY_MAX_TOP_K=20` 一致；若部署将 Python 上限调低，需要同步核对公开请求范围，配置不会自动跨服务同步。

`search` 省略答案生成，但默认检索策略仍可能调用改写、Embedding 和重排模型。

```powershell
$searchQuery = [uri]::EscapeDataString('报销需要哪些材料')
Invoke-RestMethod -Uri "$baseUrl/knowledge/search?query=$searchQuery&topK=5&pipelineEngine=fixed" -Headers $headers

$askBody = @{ question = '报销需要哪些材料'; topK = 5; pipelineEngine = 'fixed' } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "$baseUrl/knowledge/ask" -Headers $headers -ContentType 'application/json; charset=utf-8' -Body $askBody

$chatBody = @{ knowledgeBaseId = $kbId; conversationId = 'expense-001'; question = '这些材料交给谁'; topK = 5; pipelineEngine = 'langchain' } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "$baseUrl/rag/chat" -Headers $headers -ContentType 'application/json; charset=utf-8' -Body $chatBody
```

多轮示例中的指代需要该会话此前已有上下文。公开单轮入口不写会话历史。全库会话绑定创建时的可读库集合，每轮读取历史前后重验该范围，权限变化后需新建会话。会话 SSE 在收到并核对完成终态后才保存该回合，LangChain 的临时增量用于页面展示。

### 7.2 编排与检索策略

`PythonRagPipeline` 组织授权范围、查询、历史及服务端参数。同步接口提交到 Python `POST /rag/query`，流式接口通过 `RagStreamClient` 转发 `POST /rag/query/stream`。Python 完成改写、召回、融合、重排、证据准入、后处理和生成。

`pipelineEngine` 选择编排实现 `fixed / langchain`；`retrieval.version` 选择检索策略，两者独立：

| `retrieval.version` | 执行内容 |
| --- | --- |
| `baseline` | 向量检索 |
| `hybrid` | 向量与关键词召回，RRF 融合 |
| `hybrid-rerank` | 融合候选后重排，取 Top-K |
| `hybrid-rerank-rewrite` | 先改写，再融合与重排；当前默认 |

MMR、抽取压缩、父块扩展由各自配置控制。`langchain` 响应中的实际引擎与检索版本必须符合请求契约，缺失或不一致会返回 `ai_contract_mismatch`。Trace 区分请求选择与 AI 实际回报的执行信息。

### 7.3 来源、证据与结果判断

`search` 返回原始排序候选数组，元素包含 `id`（chunk ID）、`documentId`、`knowledgeBaseId`、`chunkIndex`、`content`、`distance` 及可用来源元数据。

`ask / chat` 的 `sources` 来自**通过证据准入的片段**，使用 `chunkId` 字段标识切片。公开响应没有独立的 `evidenceSources` 字段。来源可带 `headingPath`、`pageNo`、`sourceType`，需允许元数据为空。

问答响应主要字段：

| 字段 | 用途 |
| --- | --- |
| `answer`、`sources` | 答案或提示，以及可引用证据 |
| `pipelineEngine`、`retrievalVersion` | 编排与策略信息 |
| `outcome` | `success / no_evidence / dependency_unavailable / timeout` |
| `reasonCode`、`failedStage`、`requestId` | 错误识别与定位 |
| `retrievalStatus`、`evidenceDecision` | 检索完成情况与证据判定 |
| `degraded`、`degradeReason`、`providerUsed` | 降级与实际 provider |
| `originalQuery`、`rewrittenQuery` | `chat` 附带的原始 / 改写查询 |

**HTTP 200 仍需判断 `outcome`。** 无匹配证据的正常结果为 `no_evidence`；依赖失败或超时且无准入证据时返回 503 / 504。有准入证据、生成阶段失败时，可能以 HTTP 200 保留来源并返回失败提示，`outcome` 仍为失败。

503 / 504 的结构示例：

```json
{
  "code": 503,
  "message": "问答服务暂时不可用，请稍后重试",
  "data": {
    "outcome": "dependency_unavailable",
    "reasonCode": "dependency_unavailable",
    "failedStage": "rag.query",
    "requestId": "<request-id>"
  }
}
```

正常答案与正常无证据回答会进入 `chat` 历史；依赖故障和超时提示不作为正常回答写入历史。默认 Redis 历史不可用时返回 503，`reasonCode=redis_unavailable`、`failedStage=chat.history`，不会切换到内存存储。

证据策略由 Python 部署配置选择；默认 `score` 使用当前评分模型语义指纹匹配的校准阈值。缺少校准时拒绝生成准入；只检索仍要求实际执行用途的模型绑定。阈值通过独立模型校准接口登记，不能沿用旧全局评分变量，详见[模型管理](../docs/model-management.md#3-凭证与校准)。

## 8. Agent 与 MCP

### 8.1 Agent 对话

`POST /agent/chat` 使用 JWT，输入：

```json
{"question":"查找报销规定并说明需要提交的材料","sessionId":"as_服务端签发的会话ID","agentId":123}
```

`agentId` 可省略，使用默认 Agent。同步和流式对话都必填 `sessionId`，先通过 `POST /agent/sessions` 创建会话并使用返回的 `as_...` ID。旧客户端记录留作历史。

后端从 JWT 取得用户，并解析可访问知识库。`GET /agent/tools` 返回运行时实际注册工具的公开目录，配置与执行都检查工具白名单。当前工具包括：

| 工具 | 作用 |
| --- | --- |
| `knowledge_search` | 调用检索能力返回片段 |
| `knowledge_ask` | 基于知识库证据回答 |
| `read_document` | 阅读指定文档中经证据准入的片段 |
| `list_documents` | 在有效知识库范围内分页列出已完成处理的文档元数据 |
| `memory_save` | 写入该用户的跨会话长期记忆（仅在用户明确要求记住时使用） |
| `memory_search` | 检索该用户自己的长期记忆（用户画像 / 偏好） |

工具执行身份来自服务端上下文。这些工具默认不要求人工审批，服务没有相应用户审批交互入口。
`memory_save` / `memory_search` 在 `agent.memory.long-term.enabled=false` 时不装配（不会出现在 schema 里）。

`GET/POST /agent/profiles`、`PUT/DELETE /agent/profiles/{id}` 管理当前用户配置。知识库模式为 `all / selected / none`，执行时重新验证权限；`allowedTools=[]` 表示无工具。`answerPolicy=grounded|general` 默认 grounded，旧配置由 V13 迁移为 grounded。grounded 只交付本轮获准证据完整进入成功模型输入后的知识结论；模型跳过检索时，可在剩余轮次、预算和白名单内补一次 `knowledge_search`。general 允许普通模型回答。

来源含稳定 `citationId`，模型正文使用 `[[cite:ID]]`；页面只将已知标识渲染为来源按钮。引用用于追溯，尚未逐句核验答案全部断言。grounded 正文在最终门禁后由 `answer` 帧交付，阶段与工具事件仍实时发送。

`list_documents` 默认每页 5 条、最多 10 条，按 `nextOffset` 继续；目录不读取原文件，也不作为知识证据。`read_document` 默认 `mode=query`，保留相关片段检索；`mode=page` 按 `chunk_index,id` 读取一个有界原文窗口（排除 parent），仍经真实评分与证据准入。默认 4000 字符工具输出预算下窗口为 366 个 Unicode 字符，最高 1200；预算不足明确失败，不交付新游标。

连续阅读初始参数示例：`{"documentId":10,"query":"核对报销材料","mode":"page"}`。之后复制 `nextOffset / nextContentOffset` 和非空 `endOffset`，`hasMore=false` 时停止；`no_evidence` 表示本页扫描完成但窗口未准入，仍可按游标继续。围绕命中块阅读时，初始页传 `aroundChunkId` 和 `contextRadius=0..2`，后续只复制返回游标。page 模式每次一个窗口，`limit` 仅允许省略或 1。窗口引用为 `S{documentId}_{chunkId}_O{contentOffset}`，query 引用保持 `S{documentId}_{chunkId}`。分页使用实时块集，文档重新处理期间没有快照一致性保证。

| 会话接口 | 作用 |
| --- | --- |
| `POST /agent/sessions`，可选 `{"agentId":123}` | 服务端签发绑定用户、Agent 与生效策略的 ID |
| `GET /agent/sessions?offset=0&limit=30` | 当前用户目录，最多 50 条，返回 nextOffset / hasMore |
| `GET /agent/sessions/{id}/history?offset=0&limit=20` | 从最近回合向前翻页，每页最多 50 轮；页内正序 |
| `DELETE /agent/sessions/{id}` | 删除会话、历史和摘要；活动回合明确拒绝删除 |

会话读取与续聊重验权限及策略，改变后需创建新会话。确认终态保存后才发送 `answer / done`；取消、异常和断流半段不作为完成历史。历史保留答案、有限步骤摘要、四态、停止原因和引用来源，不保存工具全文。默认滑动保留 30 天（`agent.memory.session-retention-days`，允许 1–365）；元数据、消息和摘要使用同一到期点，晚到摘要不能复活已删除会话。

Agent 配置还提供 `memoryReadEnabled / memoryWriteEnabled`，省略默认 true，由 V14 为已有配置补默认值。读关闭同时阻止自动注入和 `memory_search`，写关闭阻止 `memory_save`，模型可见工具与执行入口使用相同约束。全局 `agent.memory.long-term.enabled=false` 优先禁止模型读写；全局与 Agent 开关均进入会话策略指纹，改变后不能继续复用旧策略历史。`memory_save` 作为顺序屏障执行，不要求人工审批。

| 长期记忆接口 | 作用 |
| --- | --- |
| `GET /agent/memories` | 返回 enabled、容量/字符上限及当前用户条目（id、kind、content、创建/更新时间） |
| `PUT /agent/memories/{id}`，`{"content":"纠正后的内容","kind":"PREFERENCE"}` | 纠正该用户已有条目；全局停用时明确拒绝写入 |
| `DELETE /agent/memories/{id}` | 删除该用户条目；不操作会话历史 |

管理接口不接受 userId，也不公开内部会话键或来源会话 ID。全局停用后仍可查看和删除已存条目；停用不等于删除。长期记忆仍只做显式保存和词法召回，注入最多 400 Token，并标明用户自述可能过期；没有自动抽取或向量画像。Redis 条目按容量淘汰，长期记忆不采用产品会话的 30 天期限；持久可靠性取决于部署的 Redis 配置，不能据此宣称灾难恢复已验收。

`AgentResponse` 包含 `answer`、`sources`、`outcome`、`reasonCode`、`stopReason`、轮次/工具/token 用量、降级和合成状态。知识工具保留 `success / no_evidence / dependency_unavailable / timeout`；取消、模型降级、预算耗尽和截断通过停止原因另行表达，不能只看 HTTP 200 判成功。

| 属性 | 默认值 | 含义 |
| --- | --- | --- |
| 模型选择 | 当前用户用途绑定 / Agent `chatModelId` | 决策与摘要统一通过 Python gateway，旧 direct 选型入口退出生产 |
| `agent.llm.gateway.connect-timeout-ms` / `agent.llm.gateway.read-timeout-ms` | `3000` / `35000` | gateway 传输超时；模型快照默认总预算 30000ms，外层留 5000ms |
| `agent.max-iterations` / `agent.max-tool-calls` | `12` / `10` | 循环与工具调用上限 |
| `agent.max-concurrent-tools` / `agent.parallel-tools` | `4` / `true` | 工具并发 |
| `agent.budget-time-ms` | `120000` | Agent 执行时间预算 |
| `tool.timeout-ms` / `tool.max-retries` / `tool.retry-backoff-ms` | `60000` / `1` / `500` | 单工具超时、瞬时失败重试与退避 |
| `agent.context.max-tokens` / `agent.context.reserved-output-tokens` / `agent.context.reserved-tool-tokens` | `8192` / `1200` / `400` | 上下文窗口与两项预留；可用提示词 = 8192 − 1200 − 400 = 6592 |
| `agent.react.tool-output-max-chars` | `4000` | 单条工具结果字符上限；必须 ≤ 可用提示词 |
| `agent.memory.summary.max-tokens` / `agent.memory.summary.retain-recent` / `agent.memory.summary.timeout-ms` | `3000` / `6` / `30000` | 摘要与近期消息保留；摘要阈值必须 < 可用提示词 |
| `agent.memory.long-term.enabled` / `max-items` / `inject-limit` / `max-content-chars` | `true` / `50` / `5` / `500` | 跨会话长期记忆（显式写入 + 词法召回；无自动抽取与向量召回） |
| Agent 记忆与产品会话存储 | Redis | 唯一运行实现；内存替身仅在测试源码 |

Agent Memory 与 RAG ConversationStore 是两套存储。选择 `redis` 时，不会因 Redis 不可用而自动切换到内存。

模型 CRUD、凭证更新/清除、运行预览与分能力测试、用途绑定、Agent 专用聊天模型和 KB 索引重建都由 Java 接口授权。更新带预期修订，配置陈旧或被引用返回 409。后台入库/摘要显式传 owner；含密钥的内部快照不写 Redis 会话。接口表和部署顺序见[模型管理与迁移](../docs/model-management.md)。

### 8.2 MCP 接入与凭证管理

当前 MCP 地址为 `http://localhost:8080/mcp`，传输为 Streamable HTTP。MCP 客户端使用独立凭证：

```http
Authorization: Bearer ekip_mcp_<credential>
```

先通过 JWT 调用密钥管理接口：

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| POST | `/mcp/keys` | `{"name":"desktop-client"}`，名称非空且不超过 100 字符 |
| GET | `/mcp/keys` | 查看当前用户的密钥信息 |
| DELETE | `/mcp/keys/{keyId}` | 吊销自己的密钥 |

签发响应 `data` 含 `id`、`name`、`keyPrefix`、`apiKey`；明文仅在签发时返回，数据库保存 SHA-256 哈希。当前签发请求没有自定义过期时间字段。

MCP 工具调用时按凭证所属用户重新解析知识库访问范围。服务默认名称 `ekip-knowledge`、版本 `1.0.0`，暴露 `knowledge_search / knowledge_ask`，keep-alive 为 `15s`，请求超时为 `90s`。

端点路径由 `mcp.endpoint` 统一决定：传输层注册路径、API Key 过滤器的匹配路径与 Spring Security 的放行路径读取同一配置项，因此可以改成非默认路径。放行只匹配该精确路径，`/mcp/keys` 等子路径仍由 JWT 保护；MCP 端点上的 Bearer 只按 API Key 解释，不会再被 JWT 过滤器解析并清空身份。

`mcp.security.allowed-hosts` 默认只包含 `localhost:8080`、`127.0.0.1:8080`；部署域名变化时需对应配置。`allowed-origins` 默认为空，不额外放行浏览器 Origin。`mcp.exposed-tools` 中未知的工具名会阻止启动。

## 9. 评测接口

评测使用 JWT。涉及知识库检索的用例要求当前用户为该库创建者；批量评测先授权全部检索样本，再执行首个样本。

| 方法 / 路径 | 输入重点 | 行为 |
| --- | --- | --- |
| `POST /evaluation/retrieval` | 单个用例 | Top-5 检索、生成及生成质量评判 |
| `POST /evaluation/batch` | `k`、`cases`、`evaluateGeneration` | 批量检索，按 difficulty / type 聚合；默认不执行生成评判 |
| `POST /evaluation/regression` | `baseline`、`current`、`threshold` | 本地指标对比 |

检索指标在 Java 中计算，包括 recall、precision、precisionN、MRR、hit、nDCG；生成质量由 Python `/evaluation/generate` 进行 faithfulness、correctness、relevance 评判。启用生成评测会调用模型。

批量检索请求示例：

```json
{
  "k": 5,
  "evaluateGeneration": false,
  "cases": [
    {
      "knowledgeBaseId": 1,
      "question": "报销需要哪些材料",
      "groundTruth": "按实际制度填写标准答案",
      "relevantChunkIds": [101, 102],
      "history": [],
      "difficulty": "normal",
      "type": "keyword"
    }
  ]
}
```

`relevantChunkIds` 应取自当前数据库的实际切片。完整字段见 [EvaluationBatchRequestDTO](src/main/java/com/pony/knowledge/dto/EvaluationBatchRequestDTO.java)。

## 10. 运行观测与排错

### 10.1 状态、能力与 Trace

| 接口 | 内容 |
| --- | --- |
| `GET /observability/status` | 运行配置快照与版本信息 |
| `GET /observability/capabilities` | 后台刷新的依赖 / 业务能力快照 |
| `GET /observability/trace?limit=50&entrypoint=chat` | 最近 Trace；limit 限定在 1–200，入口筛选可省略 |
| `GET /observability/trace/{traceId}` | Trace 详情与阶段信息 |

这些接口均需登录。Trace 在保存时按服务端认证上下文标注归属用户，**列表与详情只返回当前用户自己的记录**；归属未知的记录（无认证上下文的调用）不向普通用户回显。按 ID 读取他人记录与读取不存在的记录都返回 HTTP 404 且响应体 `code` 同为 404，不区分两种情况。跨用户排障的访问方式尚未提供，需结合部署方案另行确认。

能力快照包含 `backend / database / redis / minio / ingest / search / answer / chat / evidenceGate / rerank` 等项，状态为 `ready / degraded / not_ready / unknown`。后台通过数据库 `SELECT 1`、Redis ping、MinIO bucket 检查及 AI `/ready` 更新；请求只读取缓存，因此依赖变化会存在刷新延迟。`chat` 表示「问答能力 + Redis 会话存储」；Redis 不可用时该能力不可用，Redis 状态仍单列在 `redis` 项，也是整体状态的必需项。

Trace 保存在 Java 进程内存，默认最近 1000 条，重启丢失。字段包括请求 / 实际编排与版本、原始 / 改写查询、provider、scorer、结果状态、失败原因与各阶段 spans。版本字段在未注入构建信息时可能为 `unknown`。

产品 Trace、指标及 Micrometer Tracer 默认保留，**外部 OTLP 导出默认关闭**（`management.otlp.tracing.export.enabled=${OTEL_ENABLED:false}`）。完整观测方案显式开启，并将 Java/Python 导向同一 Collector；裸跑 Java 时需在进程环境设置开关和 `OTEL_TRACES_ENDPOINT`。采样率默认 1.0，日志中的 `requestId / traceId / spanId` 关联不变。配置和验证边界见 [观测说明](../docs/observability.md)。

### 10.2 健康、指标与日志

| 端点 | 默认访问控制 |
| --- | --- |
| `/actuator/health` 及子路径 | 匿名 |
| `/actuator/info` | 匿名 |
| `/actuator/prometheus` | 匿名 |
| `/actuator/metrics`、`/actuator/loggers` | JWT；`prod` profile 不暴露这两个端点 |

Python 的 `/health` 表示进程存活；受内部令牌保护的 `/ready` 提供依赖能力信息。Java 使用 `/actuator/health` 和 `/observability/capabilities`。单个健康端点成功不能代替入库、检索和回答能力就绪判断。

Java 默认输出到控制台。Compose 可使用 `docker compose logs -f backend` 查看；日志落盘和保留策略由部署环境配置。定位查询时结合 `requestId`、Trace、Java 日志和 AI 日志。

### 10.3 常见现象与处理入口

| 现象 | 优先检查 |
| --- | --- |
| 启动提示 JWT 密钥无效 / 内部令牌缺失 | Java 进程是否实际获得环境变量；根 `.env` 不会自动注入裸跑 Java |
| Flyway 或数据库初始化失败 | pgvector 可用性、数据库存在性、连接参数、迁移权限；迁移文件由 Flyway 校验，不直接改已应用文件 |
| 上传返回成功但尚不能检索 | 回执中的 `documentId` 对应任务是否 `SUCCESS`，检查任务错误与依赖能力 |
| 上传响应丢失 | 用原 `Idempotency-Key` 查询回执，再决定是否重发 |
| 任务持续 `WAITING` | 恢复开关、线程池拒绝日志、WAITING 年龄阈值及数据库更新时间 |
| 任务 `FAILED` | `errorCode / errorMessage`、上次错误、MinIO 对象、AI 解析和 Embedding 状态 |
| `chat` 返回 `redis_unavailable` | Redis 连通性；依赖故障返回 503，不回落内存 |
| 检索有候选但问答未生成 | `evidenceDecision / reasonCode`、证据阈值和实际 scorer，候选不等于获准证据 |
| `ai_internal_auth` | Java / Python 内部令牌一致性；此 503 不表示用户 JWT 过期 |
| `ai_contract_mismatch` | AI 返回的实际编排引擎、检索版本与两侧契约 |
| RAG 超时 | `failedStage`、默认 270 秒的生成问答预算、275 秒 HTTP 等待上限及具体模型服务耗时 |
| SSE 连接结束 | 180s 连接超时、网络与鉴权；回读快照并重连 |
| MCP 被拒绝 | 使用的是 MCP API Key、密钥是否吊销，以及 Host / Origin 白名单 |
| 删除后仍有存储对象 | 对象删除错误日志；默认未启用孤儿对象回收 |

### 10.4 操作审计（`audit_log`）

`audit_log` 是一张 append-only 的追溯表：只增不改（无更新时间、无软删除），删除只发生在保留策略清扫。写入方唯一（`com.pony.knowledge.audit.AuditLogService`），**业务代码里不要直接 INSERT** —— 否则 `actor_user_id` / `request_id` 必然有人漏填，而「查不到操作者的行」和「没人操作过」在事后无法区分。

本次必须覆盖 `AuditAction` 中的七类写操作：成功变更记录 `success`；进入对应业务服务后发生的身份/权限拒绝记录 `denied`，业务校验、冲突及依赖异常记录 `failed`。

| 动作 | 成功变更 | 拒绝 / 失败时的资源定位 |
| --- | --- | --- |
| `kb.created` | `knowledge_base/{id}` 创建成功 | `knowledge_base`，未产生目标 ID |
| `rbac.member_added` / `rbac.member_removed` | `knowledge_base/{id}` 成员新增 / 移除 | 请求的知识库 ID、`member_user_id` |
| `document.uploaded` | `document/{id}` 上传受理已提交。**2026-10-23 之前为 `knowledge.created`**，历史行未回写 | `document`，未产生目标 ID；details 记录 `knowledge_base_id` |
| `knowledge.deleted` | `document/{id}` 数据库删除已提交 | 请求的文档 ID |
| `system.api_key_created` / `system.api_key_revoked` | `mcp_api_key/{id}` 密钥签发 / 吊销 | 签发失败无目标 ID；吊销使用请求的密钥 ID |

失败详情由 `recordFailure` 限制为数值型 `knowledge_base_id / member_user_id` 与固定 `reason_code`：`authentication_required / permission_denied` 对应 denied，`business_rejected / idempotency_conflict / operation_failed` 对应 failed。操作者仍从认证上下文取得，请求关联使用 `request_id`；不保存异常消息、请求正文、文件名、密钥或幂等键。

成功审计在**业务状态提交之后**调用；文档上传的提交后投递故障、文档删除后的 MinIO 清理故障不反转已完成变更。复用上传回执、已存在成员、并发重复添加及移除不存在成员不重复记录成功变更。拒绝/失败在服务异常出口记录，原错误协议保持。审计写失败只记 ERROR、**绝不外抛**，并使用独立事务（`REQUIRES_NEW`），避免审计 SQL 失败污染业务事务。

本范围不含进入服务前被 Security 或参数校验拦截的请求、只读查询，以及模型、索引、Agent 等其他写动作；不能把七类操作的覆盖描述为全系统审计。数据库真实写入和目标版本部署需在对应环境核对。

不提供查询 API：单实例部署下直接查库即可，常用语句写在 [V12__audit_log.sql](src/main/resources/db/migration/V12__audit_log.sql) 末尾。保留天数取 `audit.retention.days`（默认 90，`<=0` 表示关闭清扫），每日清扫一次。

## 11. 源码导航

```text
knowledge-platform/
├── pom.xml                         # Java 版本、依赖、打包
├── Dockerfile                      # Maven / JDK 17 多阶段构建
├── mvnw / mvnw.cmd                  # Maven 包装器
└── src/
    ├── main/
    │   ├── java/com/pony/knowledge/
    │   │   ├── KnowledgeApplication.java
    │   │   ├── controller/         # 用户、库、文档、RAG、评测、观测入口
    │   │   ├── config/             # Security、线程池、存储、会话、任务参数
    │   │   ├── security/           # JWT 请求认证、登录限流、刷新票据
    │   │   ├── dto/、vo/、entity/   # 传输对象、视图对象、数据实体
    │   │   ├── mapper/             # MyBatis-Plus 与任务条件更新 SQL
    │   │   ├── service/
    │   │   │   ├── impl/           # 用户、知识库、文档、chunk 业务
    │   │   │   ├── ai/             # AiServiceClient
    │   │   │   ├── rag/            # 授权会话编排与 PythonRagPipeline
    │   │   │   ├── task/           # 投递、重试、恢复、终态事务与事件
    │   │   │   ├── storage/        # 孤儿对象回收
    │   │   │   └── vectorstore/    # 存储实现标识
    │   │   ├── agent/              # 执行循环、gateway、工具、记忆与上下文
    │   │   ├── mcp/                # 传输、工具适配、API Key
    │   │   ├── evaluation/         # 检索指标、聚合与回归
    │   │   ├── observability/      # 能力快照、Trace、阶段预算
    │   │   ├── resilience/         # AI 可用性熔断
    │   │   └── exception/          # 业务异常与 HTTP 映射
    │   └── resources/
    │       ├── application.yml
    │       ├── application-prod.yml
    │       └── db/migration/       # Flyway schema 与数据约束
    └── test/java/                  # Java 测试源码
```

| 查阅目标 | 核心入口 |
| --- | --- |
| 认证与公开路径 | [SecurityConfig](src/main/java/com/pony/knowledge/config/SecurityConfig.java)、[UserServiceImpl](src/main/java/com/pony/knowledge/service/impl/UserServiceImpl.java) |
| 知识库访问范围 | [KnowledgeBaseAccessService](src/main/java/com/pony/knowledge/service/KnowledgeBaseAccessService.java) |
| 上传与删除 | [DocumentServiceImpl](src/main/java/com/pony/knowledge/service/impl/DocumentServiceImpl.java) |
| 任务状态与恢复 | [service/task/](src/main/java/com/pony/knowledge/service/task)、[DocumentTaskMapper](src/main/java/com/pony/knowledge/mapper/DocumentTaskMapper.java) |
| chunk 生成与替换 | [DocumentChunkServiceImpl](src/main/java/com/pony/knowledge/service/impl/DocumentChunkServiceImpl.java) |
| 查询授权、历史与响应 | [RagService](src/main/java/com/pony/knowledge/service/rag/RagService.java) |
| Java → Python 查询契约 | [PythonRagPipeline](src/main/java/com/pony/knowledge/service/rag/PythonRagPipeline.java)、[AI 查询 schema](../ai/app/rag/schema.py) |
| HTTP 错误语义 | [GlobalExceptionHandler](src/main/java/com/pony/knowledge/exception/GlobalExceptionHandler.java) |
| 部署注入项 | [docker-compose.yml](../docker-compose.yml)、[根配置模板](../.env.example) |
