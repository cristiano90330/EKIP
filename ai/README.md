# EKIP AI 服务使用手册

企业智能知识管理平台的 Python AI 服务，默认监听 `http://127.0.0.1:8000`。它为 Java 后端提供文档解析、Embedding、RAG 查询、生成质量评判、Agent 决策与摘要能力。

本文依据当前工作区源码和配置编写。表中“代码默认值”指未被环境变量或配置文件覆盖时的值；实际模型、阈值、可用性与容量需按部署环境确认。

项目整体说明见[根 README](../README.md)，业务接口与权限使用见[后端手册](../knowledge-platform/README.md)。

## 目录

- [1. 服务组成与职责](#1-服务组成与职责)
- [2. 安装与启动](#2-安装与启动)
- [3. 接口与鉴权约定](#3-接口与鉴权约定)
- [4. 文档解析与 Embedding](#4-文档解析与-embedding)
- [5. RAG 查询使用说明](#5-rag-查询使用说明)
- [6. 模型与重排配置](#6-模型与重排配置)
- [7. Agent 与生成质量评判](#7-agent-与生成质量评判)
- [8. 配置参考](#8-配置参考)
- [9. 运行观测与排错](#9-运行观测与排错)
- [10. 源码导航](#10-源码导航)

## 1. 服务组成与职责

### 1.1 与业务后端的分工

| 能力 | Java 后端 | Python AI 服务 |
| --- | --- | --- |
| 身份与权限 | JWT、知识库创建者 / 成员授权 | 校验服务间令牌，使用已授权的知识库 ID 范围 |
| 上传与任务 | MinIO 上传、业务记录、任务调度、重试、状态与 SSE | 根据对象标识下载文件并解析 |
| 文档入库 | 管理 chunk 写入事务、父子块关系与删除 | 生成结构化 chunk、分词文本及 Embedding |
| 检索问答 | 解析访问范围、读写会话历史、映射业务响应 | 改写、召回、融合、重排、证据准入、后处理与生成 |
| Agent | 循环执行、工具、权限、预算、记忆 | 单次模型决策与摘要 |
| 评测 | 用例编排、检索指标、报告 | 生成质量的模型裁判 |

AI 服务的运行路径只读访问 PostgreSQL `document_chunk`、`document_chunk_retrieval` 视图和固定 1024/2560 维向量表，不读取模型凭证表，不负责建表、修改业务状态或写入向量。V18 视图提供切片检索字段和文档公开文件名，审查来源元数据与生成上下文 `sourceName` 共同携带来源标识。数据库 schema 由 Java Flyway 管理。AI 不连接 Redis，也不保存 RAG 会话历史；调用方通过 `history` 提供本次需要的历史。

```text
Java 后端
  ├─ /parser/object → MinIO 下载 → 格式解析 → Block → 结构化切块
  ├─ /models/providers、resolve、test → 元数据 / 同一解析器 / 显式测试
  ├─ /embedding + modelExecution → OpenAI 兼容 Embedding（含 Ollama）
  ├─ /rag/query
  │    → fixed / langchain
  │    → 改写 → 授权范围内召回 → RRF / 重排
  │    → 证据准入 → MMR / 压缩 / 父块扩展 → ModelGate 生成
  ├─ /evaluation/generate → ModelGate 裁判
  └─ /agent/decide、/agent/summarize → ModelGate
```

### 1.2 运行环境与依赖

容器使用 Python 3.10。依赖以 [requirements.txt](requirements.txt) 为准，其中 `langchain-core==1.5.3`、`langsmith==0.10.10` 固定版本，其余多数依赖未固定版本。

| 组成 | 实现 |
| --- | --- |
| HTTP | FastAPI、Uvicorn、Pydantic |
| 解析 | pdfplumber、python-docx、python-pptx、openpyxl、xlrd、BeautifulSoup（EPUB）、自定义 Markdown 解析 |
| 切块与分词 | `structured_chunker.chunk_blocks`、jieba |
| 模型访问 | `models/runtime.py`：httpx 执行六种明确协议，消费 Java 模型快照 |
| RAG 编排 | 固定流程、LangChain LCEL / Runnable |
| 检索 | psycopg2，只读 PostgreSQL + pgvector |
| 文件 | MinIO SDK、临时文件 |
| 观测 | logging、Prometheus client、OpenTelemetry |

Embedding 通过选定协议的 HTTP 服务完成，AI 不直接加载权重。知识库活动索引明确绑定模型空间与 1024/2560 维分表；模型输出必须与该索引维度一致。模型登记、凭证、迁移与维护窗口见[模型管理说明](../docs/model-management.md)。

## 2. 安装与启动

以下命令以 Windows PowerShell 为例；Python 可替换为已准备的 Python 3.10 环境。

### 2.1 安装依赖

统一从仓库根目录运行，确保 `ai.app.*` 导入可解析：

```powershell
$env:PYTHONUTF8 = '1'
python -m pip install -r .\ai\requirements.txt
```

### 2.2 配置加载顺序

[app/config.py](app/config.py) 在模块导入时读取环境变量，依次调用 `load_dotenv` 加载 `ai/.env` 和仓库根 `.env`，均不覆盖已有值。有效优先级是：

```text
进程环境变量 > ai/.env > 仓库根 .env > 代码默认值
```

已定义的空字符串也会影响后续补充；两份文件同名项冲突时，根文件不会替换 `ai/.env` 中的值。修改配置后需重启进程。模板文件 `.env.example` 不会被直接加载。

容器部署没有这两份文件：`docker-compose.yml` 把根 `.env` 的变量以显式白名单注入容器，所以**容器里只有进程环境变量**，等价于「根 `.env` 是唯一入口」。转发范围与自检方式见第 8.5 节。

建议明确选定本地配置入口。共享令牌可放根 `.env`，AI 专用覆盖项可放 `ai/.env`，同名项需保持一致。至少准备：

```dotenv
INTERNAL_API_TOKEN=<与Java相同的随机内部令牌>

PG_READONLY_HOST=localhost
PG_READONLY_PORT=5432
PG_READONLY_DB=knowledge
PG_READONLY_USER=rag_readonly
PG_READONLY_PASSWORD=<独立只读口令>

MINIO_ENDPOINT=localhost:9000
MINIO_ACCESS_KEY=admin
MINIO_SECRET_KEY=12345678
MINIO_BUCKET=knowledge-files

```

`MINIO_ENDPOINT` 使用 `host:port`，当前客户端设置 `secure=False`。上面的 MinIO 凭据是仓库本地开发配置；按实际环境替换。

### 2.3 数据库与模型前置条件

1. PostgreSQL 17 + pgvector 已准备，Java Flyway 已创建 `document_chunk`、固定维度向量表及只读角色权限。
2. 为 `rag_readonly` 设置与 `PG_READONLY_PASSWORD` 一致的独立口令。Flyway 管理权限，不替部署环境轮换口令。
3. MinIO bucket 与对象可访问。
4. 在 Java 模型管理登记可达的 Chat、Embedding、Rerank 服务，独立保存凭证并绑定实际使用用途；已有历史 KB 显式重建索引。无需为了启动而加载全部模型。

使用仓库 PostgreSQL 容器时，可在根目录执行以下命令进入数据库，再交互设置口令：

```powershell
docker compose exec postgres psql -U postgres -d knowledge
```

```text
\password rag_readonly
\q
```

完整基础设施与 Java 初始化步骤见[后端启动说明](../knowledge-platform/README.md#2-安装与启动)。AI 数据库连接使用独立 `PG_READONLY_*` 项，不借用 Java 的通用数据库账号。

### 2.4 启动服务

在上述 Python 环境中，从仓库根目录运行。

```powershell
$env:PYTHONUTF8 = '1'
python -m uvicorn ai.app.main:app --host 127.0.0.1 --port 8000
```

启动检查内部令牌、独立只读账号和 RAG 限制，并准备两种编排对象。模型配置只来自受信任请求，启动不预热模型、不发模型探针。后台只探测 PostgreSQL 与 MinIO；模型可用性来自显式连接测试或真实流量观察。

内部令牌、只读身份或查询限制非法会阻止启动。成功启动不代表某个用户已完成模型绑定、索引迁移或模型质量验收。

### 2.5 Compose 与容器

根 `docker-compose.yml` 定义了 AI 构建上下文和环境注入。完成共享配置与数据库初始化后，从仓库根目录运行。

```powershell
docker compose up -d --build ai
docker compose logs -f ai
```

Compose 会带起 AI 的基础依赖；`minio-init` 负责创建 bucket，完整业务部署方式见根 README。容器使用 `uvicorn ai.app.main:app --host 0.0.0.0 --port 8000`，宿主映射端口为 `8000`。

**Compose 注入值与代码默认值分别如下：**

| 项 | 代码默认值 | 当前 Compose 注入 |
| --- | --- | --- |
| PostgreSQL host | `localhost` | `postgres`，可用 `PG_READONLY_HOST` 覆盖 |
| MinIO | `localhost:9000`、`minioadmin / minioadmin`、bucket `knowledge` | `minio:9000`、`admin / 12345678`、bucket `knowledge-files` |

模型地址和选择在模型管理登记。`MODEL_RESOURCE_GROUPS` 留空时使用 `{"local-gpu":1}`，`MODEL_RESOURCE_QUEUE_LIMIT` 默认为 8。多个 AI 进程共用设备时，可配置 [Redis 共享资源组](../docs/model-management.md#5-预算显存与就绪)。容器访问宿主机 Ollama 时，使用 OpenAI 兼容协议和部署环境可达的 `http://host.docker.internal:11434/v1`。

根 `.env` 用于 Compose 插值，只有服务 `environment` 中声明的基础设施变量进入容器。旧模型选型和全局评分阈值变量不再影响生产调用。

## 3. 接口与鉴权约定

### 3.1 HTTP 入口

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/health` | 进程存活，返回 `{"status":"ok"}` |
| GET | `/ready` | 缓存的能力快照 |
| GET | `/metrics` | Prometheus 文本；`METRICS_ENABLED=false` 时返回 404 |
| POST | `/parser/object` | 按扩展名解析 MinIO 对象 |
| POST | `/parser/pdf/object` | 固定按 PDF 解析同一对象请求 |
| POST | `/embedding` | 批量文本向量化，供入库与查询使用 |
| POST | `/rag/query` | 同步 RAG / 只检索 |
| POST | `/rag/query/stream` | 固定流程和 LangChain RAG SSE |
| POST | `/evaluation/generate` | 生成质量裁判 |
| POST | `/agent/decide` | 单次工具调用决策 |
| POST | `/agent/decide/stream` | 决策 SSE：`delta` 正文增量 + `final` 终态（含 tool_calls / finish_reason） |
| POST | `/agent/summarize` | 会话摘要 |
| GET | `/models/providers` | 厂商、协议与未验证模型目录 |
| POST | `/models/resolve` | 运行预览，不调用模型 |
| POST | `/models/test` | 明确指定模型和本次密钥的分能力测试 |

业务 router（包括模型解析/测试）及 `/ready`、`/metrics` 均需：

```http
X-Internal-Token: <INTERNAL_API_TOKEN>
X-Request-ID: <request-id>
Content-Type: application/json
```

`X-Request-ID` 用于串联 Java / AI 日志，AI 会通过同名响应头返回，包括鉴权、参数和未预期异常响应。合法标识为 1–128 个 ASCII 字母、数字或 `._:-`；未提供、重复或非法时生成 UUID。请求上下文覆盖流式响应生命周期并在结束后恢复，已有响应体中的 `requestId / request_id` 沿用该标识。该标识独立于 Trace/span ID。内部令牌缺失或不匹配返回 401；它不同于用户 JWT 和 MCP API Key。

`/health` 不要求令牌。应用还保留 FastAPI 默认的 `/docs`、`/redoc`、`/openapi.json` 文档入口；这些文档路由当前未挂业务鉴权依赖。

调用方必须先完成知识库授权，再提交 `knowledgeBaseIds`。AI 不根据这些 ID 自行查询用户归属，内部令牌持有者承担传入授权范围的责任。

### 3.2 响应格式与错误

AI 接口直接返回各自 JSON 结构，不使用 Java 的 `Result<T>` 包装。RAG、parser、evaluation 主要使用 camelCase；Agent 消息和治理字段使用 snake_case。

| 情况 | HTTP / 结果 |
| --- | --- |
| 内部鉴权失败 | 401，`detail` 为鉴权提示 |
| Pydantic 请求体校验失败 | 422，`detail` 为校验信息 |
| RAG 引擎、策略、问题或数值范围校验失败 | 400 |
| 完整 RAG 请求容量已满 | 429，`detail.reasonCode=query_capacity_rejected` |
| 不支持 / 损坏文件、无文本、Embedding 维度错误 | 422 |
| 对象下载或独立 Embedding 服务不可用 | 503 |
| RAG 已转换为查询结果的依赖故障 / 超时 | 通常为 200，通过 `outcome`、`reasonCode` 判断 |
| Agent 模型治理降级 | 200，`degraded=true` |
| 未预期异常 | 500，`{"detail":"服务内部错误","request_id":"..."}` |

HTTPException 保留自身 `detail`；未预期异常的堆栈写入日志。Java 边界会进一步映射 RAG 结果，因此面向业务用户的 HTTP 状态应按 Java 手册读取。

生产请求由 Java 注入 `modelExecution`，浏览器只调用 Java 业务接口。下面的直连示例说明业务字段形态；执行模型用途时还必须提供完整、已授权的模型快照，见[当前模型执行契约](../docs/architecture/modules/models.md)与[快照装配说明](../docs/model-management.md#4-用途与执行快照)。没有快照的非空执行请求会返回明确依赖故障；不要通过删除鉴权或重新启用旧 dotenv 来绕过。

后续 PowerShell 示例复用：

```powershell
$baseUrl = 'http://127.0.0.1:8000'
$internalToken = '<与AI进程配置相同的内部令牌>'
$headers = @{
    'X-Internal-Token' = $internalToken
    'X-Request-ID' = [guid]::NewGuid().ToString()
}
```

## 4. 文档解析与 Embedding

### 4.1 对象解析

`POST /parser/object` 接收 JSON 对象引用，文件由 Java 事先上传到 MinIO：

```json
{
  "documentId": 10,
  "objectName": "documents/example.pdf",
  "bucketName": "knowledge-files",
  "fileExtension": "pdf"
}
```

`objectName` 是真实对象键；`fileExtension` 应填写小写且不带点，`bucketName` 随请求提供。解析流程：

```text
router：下载到临时文件 → 本地解析流程：提取 Block → 清理 NUL
→ 标题 / 段落 / 表格结构切块与 jieba 分词 → 返回 chunks → router 清理临时文件
```

支持的格式（2026-10-24 起与 WeKnora 的文档/文本类格式对齐；表格首行视为表头）：

| 格式 | 当前处理 |
| --- | --- |
| `pdf` | pdfplumber 提取文字层与表格；无正文文字层的扫描页走本地 Tesseract，按页保留识别文本 |
| `docx` | python-docx 按正文顶层顺序提取段落与表格 |
| `pptx` | python-pptx 按页取标题（heading 2）、正文/要点、表格与演讲者备注 |
| `xlsx` | openpyxl 按工作表取值，读取已有公式缓存结果 |
| `xls` | xlrd 读 BIFF；日期还原为日期文本，整数还原为整数。扩展名是 `.xls` 但内容是 OOXML 时按 xlsx 解析 |
| `epub` | 解析 OPF spine，按阅读顺序取标题、段落、列表与表格（不引入第三方 EPUB 库） |
| `xmind` | 读 `content.json`（新版）或 `content.xml`（XMind 8），主题深度映射为标题层级，备注作为正文 |
| `md / markdown` | UTF-8 文本，提取标题、段落等结构 |
| `txt` | 按空行分段；`-`/`*`/数字编号开头的行为列表块。编码依次尝试 UTF-8、GB18030 |
| `csv` | 嗅探分隔符（, ; Tab \|）后读成一张表，首行为表头 |
| `json` | 同键字典数组转表格；其余结构输出缩进文本 |
| `doc` / `ppt` | 旧版二进制（OLE2），需部署侧提供 LibreOffice 才能转成 docx/pptx 再解析；未提供时按输入错误拒绝并提示另存为新格式 |

公式与旧格式的两个已知边界：

- Excel 只读**文件保存时的公式缓存值**。无缓存值的公式单元格会是空字符串，解析时按数量记 warning 日志，不会让整份文档失败。
- `.doc` / `.ppt` 不做纯 Python 文本抽取（不可靠）。设置 `SOFFICE_BIN` 或让 PATH 上存在 `soffice` / `libreoffice` 即启用转换；否则返回 422 并提示另存为 `.docx` / `.pptx`。扩展名是 `.doc`/`.ppt` 但内容其实是 OOXML 时直接按新格式解析。
- 扫描 PDF 每份最多处理 30 个扫描页，使用 90 秒 OCR 文档预算与 20 秒单页上限。Docker 镜像提供 Tesseract、中英文语言包。直接运行 Python 服务时，需让 PATH 找到 `tesseract` 且语言包中包含 `chi_sim` 和 `eng`。识别文本按段落入库。

图片、音频与在线 URL 抓取**不在支持范围**：它们需要视觉模型或 ASR，属于另一条能力线。

响应结构：

```json
{
  "documentId": 10,
  "chunks": [
    {
      "chunkIndex": 0,
      "content": "报销材料提交给财务部门。",
      "searchText": "报销 材料 提交 给 财务 部门",
      "sourceType": "text",
      "pageNo": 1,
      "headingPath": "财务制度 > 报销",
      "tableId": null,
      "rowIndex": null
    }
  ]
}
```

该示例仅说明字段，实际分词与元数据取决于文件。`chunkIndex` 从 0 编号，页码和表格字段允许为空；标题路径按提取到的标题层级生成。

切块默认 `CHUNK_SIZE=800`、`CHUNK_OVERLAP=100`，单位为字符，要求 `CHUNK_SIZE > 0` 且 `0 <= CHUNK_OVERLAP < CHUNK_SIZE`。段落仅在同页、同来源类型内累积；标题、表格、页码或 text/list 类型切换时结束当前文本块，不携带旧来源 overlap。页码未知时保持 `null`。

每个 chunk 正文不超过 `CHUNK_SIZE`。长段落按滑动窗口拆分；表格普通行保持“表头 + 数据行”，长行分片保留全部内容，表头较短时在各片重复，表头过宽时连同数据有序分片。来自同一数据行的片段沿用 `tableId / rowIndex`，以连续 `chunkIndex` 区分；`rowIndex` 从 2 表示解析表格的首条数据行，不承诺等于过滤空行前的 Excel 物理行号。字符上限不等于模型 token 上限。

已知损坏输入、不支持格式与无文本返回 422；MinIO 下载故障返回 503；未知内部错误交给全局处理返回 500。提取、清洗、切块记录阶段耗时与数量，复用请求标识，不记录文档正文。临时文件在成功和失败路径均清理。

解析接口只返回 chunks。向量生成、父块组装及写库由 Java 入库流程继续完成。

### 4.2 批量 Embedding

`POST /embedding` 接收 `{texts, modelExecution}`。`modelExecution.bindings.embedding` 为明确模型快照，入库快照同时携带该 KB 的活动 `indexScopes`；Java 从 KB 创建者和活动空间解析，不依赖后台线程的登录状态。

返回 `{"embeddings":[[...],[...]]}`，与输入顺序一致。同批文本去重，进程缓存按模型身份、凭证版本和输入类型隔离；返回条数、维度、有限值和非零向量均需验证。缺绑定或服务不可用为依赖故障，不自动切换其他模型。

入库模型总预算默认 120000ms；RAG 内 Embedding 共用查询剩余预算。模型批大小与 query/document instruction 来自兼容参数，不使用全局 `VECTOR_DIM`。

## 5. RAG 查询使用说明

### 5.1 同步请求

`POST /rag/query` 使用 [RagQueryRequest](app/rag/schema.py)。业务字段示例（还需 Java 授权后提供的 `modelExecution`）：

```json
{
  "knowledgeBaseIds": [1],
  "question": "报销需要提交哪些材料"
}
```

`knowledgeBaseIds` 必须来自 Java 授权范围。空列表在合法请求校验后直接得到空证据结果，不调用改写、检索或生成。

| 字段 | 默认值 | 作用 |
| --- | --- | --- |
| `pipelineEngine` | `fixed` | `fixed / langchain` 编排实现 |
| `version` | `hybrid-rerank-rewrite` | 检索策略 |
| `topK` | `5` | 原始返回候选数，默认允许 1–20 |
| `recallN` | `20` | 融合 / 重排候选规模，默认允许 1–100 |
| `rrfK` | `60` | RRF 融合常量，必须为正数 |
| `history` | `[]` | `[{role,content}]`；供改写和生成使用 |
| `modelExecution` | 无隐式默认 | 用途绑定与每个 KB 的活动 generation / space / dimension / 模型快照 |
| `generate` | `true` | `false` 时返回检索候选，`answer` 为空 |
| `compressionMode` | `none` | `none / extractive` |
| `tokenBudget` | `1500` | 抽取上下文预算 |
| `mmrEnabled` / `mmrLambda` | `false` / `0.7` | 重排策略下的多样性选择 |
| `smallToBigEnabled` | `false` | 扩展为数据库中已建立的父块内容 |
| `budgetMs` | 未指定 | 本次总预算，不超过服务端 `RAG_QUERY_DEADLINE_MS` |

问题去除首尾空白后不能为空，最多 4000 字符；知识库 ID 必须为正数且最多 1000 个。默认历史最多 32 条、内容合计 30000 字符。`budgetMs` 指定时必须大于 0。

编排、策略、后处理值按本次请求生效。Python 不读取 Java 的 `RAG_PIPELINE_ENGINE` 或 `rag.compression.mode`；Java 根据自身配置把它们转换为请求字段。

仅检索示例：

```powershell
$queryBody = @{
    pipelineEngine = 'fixed'
    knowledgeBaseIds = @(1)
    question = '报销需要提交哪些材料'
    topK = 5
    version = 'hybrid-rerank-rewrite'
    generate = $false
} | ConvertTo-Json -Depth 6
Invoke-RestMethod -Method Post -Uri "$baseUrl/rag/query" -Headers $headers -ContentType 'application/json; charset=utf-8' -Body $queryBody
```

将 `generate` 改为 `$true` 可请求生成；证据门禁配置与模型依赖需先满足。只检索仍会执行对应策略需要的 Embedding、改写和重排，不能理解为完全不调用模型。

### 5.2 编排、检索与后处理

两种同步编排共享授权范围、截止预算、证据规则、Prompt 与模型治理：

- `fixed` 在 `pipeline.py` 顺序执行各阶段。
- `langchain` 在 `langchain_pipeline.py` 使用 Runnable、带范围的 Retriever、Document 与 ChatModel 接口编排，模型调用仍进入 ModelGate。
- 两种依赖在启动时准备；错误引擎不会自动换成另一种。LangSmith 远端内容追踪在该执行路径中显式关闭。

| `version` | 执行 |
| --- | --- |
| `baseline` | 查询 Embedding → 向量召回 |
| `hybrid` | 向量 + 关键词并行召回 → RRF |
| `hybrid-rerank` | 上述融合候选 → 重排 → 取候选 |
| `hybrid-rerank-rewrite` | 先按历史改写 → 融合与重排 |

没有历史时改写直接保留原问题；改写模型失败或输出不能解析时回到原问题，并记录阶段降级。

向量召回使用 `halfvec` 余弦距离；关键词召回经 jieba 分词生成 OR tsquery，匹配 `search_vector`。两路均将知识库 ID 作为绑定参数限制范围。RRF 按 `(documentId,chunkIndex)` 合并，使用 `Σ 1/(rrfK+rank)` 累加分数。

整体顺序为：

```text
准备范围 → 改写 → 召回 / 融合 / 重排
→ 证据准入 → MMR → 抽取压缩 → 父块扩展 → 生成
```

MMR 只在重排策略下使用；抽取压缩按查询相关句子筛选并遵守近似 token 计数预算。父块扩展需要 Java 入库侧已生成父子关系；同时开启扩展和抽取压缩时，扩展后会再次压缩。来源 ID 与元数据随处理保留。

### 5.3 原始来源与证据门禁

**`sources` 是原始排序候选；`evidenceSources` 才是允许进入生成的内容。** Java 的公开问答响应会把获准证据映射为自己的 `sources`；对接时需区分这两个层次。

普通 `generate=false` 跳过证据准入，`evidenceDecision.state=bypassed`、`reasonCode=retrieve_only`，只返回候选。Agent 知识工具显式传 `generate=false, admitEvidence=true`，继续执行准入与后处理但不生成答案，只消费 `evidenceSources`。`documentId` 仅用于这个准入阅读路径：Java 先核验文档状态和归属，Python 的 SQL 同时绑定知识库范围与文档 ID。

`documentRead` 在该路径增加连续阅读，`fixed / langchain` 共用同一窗口获取、评分与准入：

```json
{"offset":0,"contentOffset":0,"windowChars":366}
```

块序按 `chunk_index,id` 排列且排除 parent，`contentOffset` 按 Unicode 字符计。初始页可用 `aroundChunkId` 与 `contextRadius=0..2` 确定有限上下文；继续时复制 `documentPage.nextOffset / nextContentOffset` 及非空 `endOffset`。`windowChars` 为 1–1200，Java 会依据工具输出预算进一步限制。该模式跳过查询改写，禁用父块扩展、MMR 与压缩，先确定原文窗口再走现有评分/准入；没有常数评分放行。成功或正常无证据才返回扫描进度，依赖故障与超时不返回推进游标。来源可带 `contentOffset`，用于区分同一长块的窗口。分页基于实时块集，文档重新处理时不保证快照一致性。

`generate=true` 使用服务端 `RAG_EVIDENCE_MODE`：

| 模式 | 当前行为 |
| --- | --- |
| `score`（默认） | 候选需有合法重排分数，按当前评分模型语义指纹匹配的校准阈值准入 |
| `support` | 基于原始问题与历史拆分答案要点，审查召回窗口内来源，机械校验逐字引用，要求所需要点完整受支持 |

当前 support 在拆分 JSON 中使用 `execution_requirements` 表达执行要求，`elements` 表达知识要点。执行要求最多八项，每项为非空且不超过 4000 字符的字符串。后续审查的内部输入分别使用 `original_request` 保留完整请求、`question` 表达独立知识问题，审查仍需核对原请求的全部知识维度。日期/期间须同时保留在独立问题与相关要点中；短主题后附带的引用形式不另造知识要点。生成同时接收完整原请求及审查后的独立问题，原日期、范围、条件和回答形式以完整原请求为准，同步/流式与预算估算使用相同输入。列文档、读原文和排版要求由工具执行及回答呈现满足，业务流程步骤继续要求资料支持。HTTP 请求字段 `question` 的语义是用户原问题。

`score` 模式中：

- 阈值来自本次评分模型的 `calibration`，必须与 `semanticFingerprint` 一致；不读取旧全局阈值。
- 阈值必须是 0–1 的数值；空值表示未配置，不表示 0。
- 未配置阈值、分数映射无效或 scorer 不可用会产生 `dependency_unavailable`。
- 完整检索下，候选都低于阈值属于 `no_evidence`。
- 不经重排的策略没有可供该门禁使用的重排分数，不能仅凭距离或 RRF 分数放行生成。

`support` 模式使用用户 `evidence_support` 绑定，既可本地也可远端；不使用另一模型回退，不复用模型判断缓存。历史和来源正文中的内部代号映射可以解析范围，但事实依据只能来自来源正文。用户明确指定项目、部门等范围时，候选正文必须证明其适用；标题只能用于来源层级，不能证明范围。数值冲突时，来源名标识为“细则”的来源优先于“制度”，引用绑定到细则正文；同级或未知层级冲突且无明确规则时拒绝。指代无法解析、要点缺证或剩余冲突时不生成；模型或结构化结果不可用时返回依赖失败。

第二阶段输出包含 `alignment / question_fully_covered / elements`，模型逐要点判断语义，程序核验引用、来源范围和数值冲突。当前策略还为 page 审查传入可信窗口位置，位置不能提供事实或代替正文。审查实现可从[RAG 模块](../docs/architecture/modules/rag.md)定位。

该模式审查 `max(topK,recallN)` 候选窗口，并校验来源引用在压缩、父块扩展等处理后仍被保留，且能装入上下文和生成输入预算。获准证据可能多于原始 `sources` 的 `topK` 数量。

当前 `score` 在部分召回仍有获准证据时可继续生成；`support` 要求召回完整。这是已确定的策略差异，客户端应同时读取 `retrievalStatus`。

### 5.4 结果字段与判定

| 字段 | 含义 |
| --- | --- |
| `pipelineEngine`、`retrievalVersion` | 本次实际执行引擎与策略 |
| `answer`、`originalQuery`、`rewrittenQuery` | 答案与查询 |
| `sources`、`evidenceSources` | 原始候选 / 获准生成证据 |
| `outcome` | `success / no_evidence / dependency_unavailable / timeout` |
| `reasonCode`、`failedStage`、`requestId` | 结果原因与定位 |
| `retrievalStatus` | `complete / partial / unavailable` |
| `evidenceDecision` | 准入状态、策略版本、scorer、阈值、计数、原因 |
| `degraded`、`degradeReason`、`providerUsed` | 生成阶段降级与 provider |
| `tokenCost` | 本次生成阶段 token 用量，其他阶段另计 |
| `stages` | 阶段、耗时、成功 / 降级、错误与 token |
| `compression` | 压缩模式、前后 token 数及比例 |

来源元素带 `chunkId / documentId / knowledgeBaseId / chunkIndex / content`，以及 `score / scoreSource / scorer / distance` 和可用来源元数据。距离、RRF 分数与重排分数含义不同，不能混用同一阈值。

RAG 的 HTTP 200 表示返回了查询结果，仍需检查 `outcome`。无证据和生成降级时答案可以为空；业务用户提示由 Java 负责。常见原因包括 `no_match`、`below_threshold`、`threshold_not_configured`、`invalid_score_mapping`、`partial_retrieval`、`retrieval_unavailable`、`query_deadline_exceeded`、`generation_degraded`。

生成模块返回具名的 `GenerationResult`。模型通过 OpenAI 兼容 JSON Schema 输出 `status=success|no_evidence` 与 `answer`，由 Pydantic 完整校验，生成用途需要 JSON 能力。合法成功答案必须非空。生成拒答映射为 `no_evidence / generation_no_evidence`，使用服务端无证据文案并清空最终 `evidenceSources`。未知状态、格式错误、空成功答案或截断输出映射为 `dependency_unavailable / generation_invalid_response`。准入阶段的 `evidenceDecision` 保留实际审查结果，最终交付状态看 `outcome`。

### 5.5 流式请求

`POST /rag/query/stream` 接收与同步接口相同的请求模型，支持 `fixed` 和 `langchain`，响应为 `text/event-stream`。LangChain 同步与流式共用 LCEL 的生成前流程，再按选定的模型执行生成。

固定流程在完整校验模型 JSON 后发送正文。LangChain 在解析到明确的 `status=success` 后，以增量发送已解码的答复正文。增量属于生成中内容，完整模型结果仍须通过结构校验。客户端用 `done` 的 `answer` 和 `evidenceSources` 确认最终正文和引用。

| 事件 | `data` |
| --- | --- |
| `stage` | 阶段记录 |
| `sources` | `sources`、`evidenceSources`、`evidenceDecision` |
| `execution` | 实际引擎、检索版本和证据模式，供 Java 在转发正文前核对 |
| `delta` | `{"text":"..."}`，LangChain 的生成中正文另带 `provisional:true` |
| `done` | 完整 `RagQueryResponse` |
| `error` | `outcome / reasonCode / failedStage / requestId / message` |

已完成阶段通过 `stage` 回报，长上下文整理通过 `context` 报告进度。只检索可直接以 `done` 结束。正常拒答以 `done` 和 `outcome=no_evidence` 结束，公开证据为空。结构错误、依赖故障或超时以 `error` 结束，客户端需清除 LangChain 的临时正文。

建立流之前的参数、鉴权或容量错误仍使用普通 HTTP 错误。流建立后的失败通过事件报告，不能只看 HTTP 200。

使用 UTF-8 请求文件避免 PowerShell 对管道中文编码的影响：

```powershell
$streamBody = @{
    pipelineEngine = 'fixed'
    knowledgeBaseIds = @(1)
    question = '报销需要提交哪些材料'
    generate = $true
} | ConvertTo-Json -Depth 6
$streamPath = Join-Path $env:TEMP 'ekip-rag-stream.json'
[System.IO.File]::WriteAllText($streamPath, $streamBody, (New-Object System.Text.UTF8Encoding($false)))
curl.exe -N -H "X-Internal-Token: $internalToken" -H 'Content-Type: application/json' --data-binary "@$streamPath" "$baseUrl/rag/query/stream"
```

### 5.6 截止预算与并发

完整查询与流式请求共享默认 4 个请求槽。有效令牌请求在进入同步工作队列前尝试占槽，超限立即返回 429，`detail` 中包含：

```json
{
  "outcome": "dependency_unavailable",
  "reasonCode": "query_capacity_rejected",
  "failedStage": "admission",
  "requestId": "<request-id>"
}
```

无效令牌仍按 401 处理。流式请求占槽持续到流结束。

总预算上限默认为 270000ms，自进入查询中间件计时，阶段共用一个截止点。Embedding、数据库 statement timeout、模型及重试退避受剩余预算约束，请求传入更小 `budgetMs` 时使用更小值。Java 为生成问答默认传入 270000ms，纯检索和 Agent 知识工具最多传入 35000ms。

混合召回使用 2 个 worker，已提交的召回任务受 `RAG_RETRIEVAL_MAX_INFLIGHT` 限制，默认 4；两路共享等待预算 `RETRIEVAL_LEG_TIMEOUT_MS=2000`。超时、失败或容量拒绝进入检索状态记录；不把依赖失败当成正常空结果。

只读数据库连接池惰性创建，最小 1、最大 4，建连超时 1s，会话启用只读。连接池、请求槽、缓存与熔断均在进程内；增加 Uvicorn worker 会分别建立这些资源，总容量也随之变化。截止预算不等同于强制终止已开始的任意外部计算。


Agent 知识检索可分别传 `question / retrievalQuery / history`。原问题与历史用于证据审查，检索词用于召回及改写，省略检索词时沿用 question。`retrievalQuery` 必须为 1–4000 字符，空白与超长输入在 I/O 前拒绝。只取获准证据按请求预算检查，实际生成才读取生成模型预算。

## 6. 模型与重排配置

模型登记、独立凭证、用途绑定、协议地址、运行策略和索引迁移的完整步骤见[模型管理说明](../docs/model-management.md)。Java 持有用户配置和权限，Python `models/resolution.py` 统一解析厂商默认、目录默认和单模型覆盖，`models/runtime.py` 执行模型请求。

支持 `openai_chat / openai_embedding / cohere_rerank / dashscope_rerank` 四协议。Ollama 同样使用 `/v1` 兼容接口，原生协议已停用；开发版本默认登记当前支持的配置，明确复用旧数据时才安排迁移或重建，见[能力矩阵](../docs/architecture/contracts/model-agent-capability-matrix.md)。`ModelGate` 保留业务用途输入预算、缓存与遥测；实际模型来自本次不可变快照，`purposes.py` 不再从 dotenv 选型。Chat 的 `tools / streaming / json / thinking` 能力需明确配置并分别测试。

默认不重试，不切换其他模型。模型运行策略与请求总截止同时生效，排队、退避和慢流读取都计入预算。模型并发与 GPU 共享资源组容量分别控制；本地默认 `local-gpu` 容量 1，队列有界。缓存、客户端与熔断按模型身份、语义指纹、凭证版本及用途隔离，密钥轮换不会复用旧版本状态。

重排使用用户 `rerank` 用途绑定，通过 Cohere 兼容或百炼原生协议批量打分。旧 `server / chat / embed_sim / quality` 档位及相似度自动回退退出生产；服务失败保持明确依赖故障。评分尺度、向量空间或模型语义变化均不能沿用旧校准。

本机轻量演示账号已接入 llama.cpp 的 Qwen3-Reranker-0.6B，采用 `cohere_rerank`、`http://host.docker.internal:8091/v1` 和概率分数，部署策略为 `hybrid-rerank + support`。Chat 4B 与 Embedding 0.6B 由 Ollama 执行。本地重排启动方式见[重排脚本](../scripts/start-local-reranker.ps1)。

## 7. Agent 与生成质量评判

### 7.1 单次 Agent 决策

`POST /agent/decide` 接收完整消息、工具定义与 `modelExecution.bindings.agent`，返回一次模型决策。Python 不执行工具，也不推进 Agent 循环；工具权限、执行结果、最大轮数与记忆由 Java 管理。

```json
{
  "messages": [
    {"role": "system", "content": "根据工具结果回答问题。"},
    {"role": "user", "content": "请查询报销制度。"}
  ],
  "tools": [],
  "timeout_s": null
}
```

| 字段 | 说明 |
| --- | --- |
| `messages` | 必填；使用 `system / user / assistant / tool` 消息约定 |
| `messages[].content` | 可为 `null`，发给 provider 前按空字符串处理 |
| `messages[].tool_calls` | assistant 返回的工具调用记录 |
| `messages[].tool_call_id` | tool 消息对应的工具调用 ID |
| `tools` | 工具 schema 列表，默认空列表 |

响应中的 `finish_reason` 取自供应商（`stop / length / content_filter / tool_calls` 等）。
Java 的 ReAct 循环据此区分「自然停止」「输出上限截断」「内容安全过滤」三条终止路径；
该字段缺省（旧客户端或供应商未返回）时 Java 退化为「无工具 + 正文非空 ⇒ 最终答案」，不产生行为回退。

#### 7.1.1 流式决策

`POST /agent/decide/stream` 与同步入口共用同一份请求体与同一套治理规则（预算、熔断、显式重试和取消），
只把「一次拿全」换成逐帧下发：

| 帧 | 载荷 | 说明 |
| --- | --- | --- |
| `delta` | `{"text": "…"}` | 正文增量；模型发起工具调用前的内容是「引子」，由调用方判定，不是答案 |
| `final` | 与同步响应同形，含 `tool_calls`、`finish_reason`、`prompt_tokens`、`provider_used`、`degraded` | 每轮恰好一次 |
| `error` | `{"code","message"}` | 首个 delta 之后中断时发出；已收到的正文由调用方保留 |

工具调用在流里是**分片**下发的：`function.arguments` 必须按 `index` 拼接，`id` / `name` 通常只在首片出现。
首个 delta 发出后不再重试；所有入口均不跨模型回退。
| `timeout_s` | 本次超时覆盖；`null` 使用用途默认值 |

工具调用使用如下结构，`arguments` 是 JSON 字符串：

```json
{
  "id": "call_1",
  "type": "function",
  "function": {
    "name": "search_knowledge",
    "arguments": "{\"query\":\"报销制度\"}"
  }
}
```

工具名称与参数需由 Java 实际提供的 schema 定义；示例名称不代表 Python 注册了该工具。Java 在完成授权后提供模型快照，Python 按快照执行；业务客户端只提供消息/工具选择等业务输入，不能覆盖内部模型配置。

响应字段：

```json
{
  "content": "模型文本，或 null",
  "tool_calls": [],
  "prompt_tokens": 0,
  "completion_tokens": 0,
  "degraded": false,
  "degrade_reason": "",
  "provider_used": "ollama"
}
```

`tool_calls` 非空时由 Java 执行。治理降级仍可返回 HTTP 200，此时 `degraded=true`，`content` 为空、`tool_calls` 为空；应根据降级字段处理，不能据此判定模型已经完成回答。

### 7.2 会话摘要

`POST /agent/summarize` 使用 `summarize` 用途处理两段提示：

```json
{
  "system_prompt": "将对话压缩为便于后续使用的摘要，保留关键事实。",
  "user_prompt": "待摘要的已有摘要与消息内容"
}
```

请求还需显式提供 `modelExecution.bindings.summarize`。返回 `content / prompt_tokens / completion_tokens / degraded / degrade_reason / provider_used`。Python 不保存摘要，也不决定何时摘要；Java 后台任务显式传归属信息，组装本次快照并持久化结果，不持久化含密钥的快照。

### 7.3 生成质量评判

`POST /evaluation/generate` 使用 `modelExecution.bindings.judge` 对答案进行模型评判。下面只列业务字段：

```json
{
  "question": "报销需要提交哪些材料？",
  "answer": "需要发票和审批记录。",
  "contexts": ["报销申请应附发票及审批记录。"],
  "groundTruth": "提交发票与审批记录。"
}
```

| 请求字段 | 默认值 / 用途 |
| --- | --- |
| `answer` | 必填，被评判的答案 |
| `question` | `""`，用于答案及资料相关性评判 |
| `contexts` | `[]`，检索上下文列表 |
| `groundTruth` | `""`，标准答案 |

响应包括：

| 字段 | 含义与执行条件 |
| --- | --- |
| `faithfulness` | 答案对上下文的忠实度；始终尝试评判 |
| `correctness` | 答案与标准答案的吻合度；始终尝试评判 |
| `relevance` | 答案对问题的切题程度；`question` 非空时执行 |
| `contextRelevance` | 资料与问题的相关度；`contexts` 非空时执行 |
| `tokenCost` | 本次各裁判调用的 token 总量 |

分数归一化到 0～1。未执行、治理降级或无法解析的评分为 `null`，应保留为无分，不能转换成 0 分。裁判分是模型评估结果，不代表人工验收；应提供有效上下文和标准答案再比较结果。

检索的 recall、precision、MRR 等指标由 Java 计算；本服务的 evaluation router 只注册上述生成评判端点。

## 8. 配置参考

以下是 [config.py](app/config.py) 与模型运行时读取的部署配置。模型地址、凭证、能力、阈值和超时配置见第 6 节。加载优先级见第 2 节；容器部署的维护入口、转发范围与自检方式见第 8.5 节，采样参数的作用范围见第 8.6 节。

### 8.1 基础连接与解析

| 环境变量 | 代码默认值 | 用途 |
| --- | --- | --- |
| `INTERNAL_API_TOKEN` | 空，必填 | 与 Java 一致的内部令牌 |
| `PG_READONLY_HOST / PORT / DB` | `localhost / 5432 / knowledge` | 只读检索连接 |
| `PG_READONLY_USER / PASSWORD` | 空，均必填 | 独立只读身份 |
| `MINIO_ENDPOINT` | `localhost:9000` | 对象存储 host:port |
| `MINIO_ACCESS_KEY / SECRET_KEY` | `minioadmin / minioadmin` | 对象存储身份 |
| `MINIO_BUCKET` | `knowledge` | 默认 bucket |
| `CHUNK_SIZE` | `800` | 切块目标字符数，具体边界见第 4 节 |
| `CHUNK_OVERLAP` | `100` | 切块重叠参数 |

### 8.2 模型执行资源

| 环境变量 | 默认值 | 用途 |
| --- | --- | --- |
| `MODEL_RESOURCE_GROUPS` | 留空采用 `{"local-gpu":1}` | 部署资源组容量，与每模型并发分开 |
| `MODEL_RESOURCE_QUEUE_LIMIT` | `8` | 单进程内等待本地或共享槽位的队列上限 |
| `MODEL_RESOURCE_REDIS_URL` | 空 | 共享资源组协调地址。留空使用进程内配额 |
| `MODEL_RESOURCE_REDIS_NAMESPACE` | `ekip` | 共用资源组的进程使用同一 Redis 命名空间 |

模型记录由 Java 管理。独立加密主密钥 `MODEL_CONFIG_ENCRYPTION_KEY` 只注入 Java，不转发 Python。旧模型 dotenv 变量不再参与生产选型，修改旧超时/维度/模型名也不会改变本次快照。

### 8.3 查询、证据与治理

| 环境变量 | 代码默认值 | 用途 |
| --- | --- | --- |
| `RAG_QUERY_DEADLINE_MS` | `270000` | RAG 查询总截止上限，Compose 同时注入 Java 与 Python |
| `RAG_QUERY_MAX_INFLIGHT` | `4` | 同步与流式共享的请求容量 |
| `RAG_QUERY_MAX_TOP_K` | `20` | 请求 `topK` 上限 |
| `RAG_QUERY_MAX_RECALL_N` | `100` | 请求 `recallN` 上限 |
| `RAG_QUERY_MAX_HISTORY_MESSAGES` | `32` | 历史消息条数上限 |
| `RAG_QUERY_MAX_HISTORY_CHARS` | `30000` | 历史内容总字符数上限 |
| `RETRIEVAL_LEG_TIMEOUT_MS` | `2000` | 混合召回等待预算 |
| `RAG_RETRIEVAL_MAX_INFLIGHT` | `4` | 已提交召回任务上限，至少 2 |
| `RAG_EVIDENCE_MODE` | `score` | `score / support` |
| `RAG_EVIDENCE_POLICY_VERSION` | `fail-closed-v1` | score 策略响应标识 |
| `RAG_GENERATE_HISTORY_TOKENS` | `300` | 生成历史额度 |

score 阈值通过模型独立校准接口登记，范围 0～1，必须匹配语义指纹。缺校准影响生成准入；只检索仍可能执行改写/向量/重排并要求这些用途的绑定。

`version / topK / recallN / rrfK / pipelineEngine / compressionMode / tokenBudget / mmrEnabled / mmrLambda / smallToBigEnabled` 是查询请求参数，不由同名 Python 环境变量控制。Java 会把自己的配置或本次选择传入请求，直连 AI 的请求默认值见第 5 节。

### 8.4 观测

| 环境变量 | 代码默认值 | 用途 |
| --- | --- | --- |
| `OTEL_ENABLED` | `false` | 外部阶段追踪出口，显式接入时开启 |
| `OTEL_SERVICE_NAME` | `ekip-ai` | OTel 服务名 |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://127.0.0.1:4318/v1/traces` | OTLP/HTTP traces 地址 |
| `RAG_STAGE_LOG_ENABLED` | `true` | 查询阶段汇总日志 |
| `METRICS_ENABLED` | `true` | Prometheus 文本端点 |

### 8.5 维护入口与容器转发范围

容器部署由根 `.env` 为 Compose 插值，只有 `ai.environment` 白名单项进入 AI 容器；本机裸跑仍为「进程环境 > `ai/.env` > 根 `.env` > 默认值」。模板本身不自动加载。内部令牌必须与 Java 一致，数据库只读身份仍独立配置；模型凭证通过 Java 管理接口加密保存。

`docker-compose.yml`、`ai/.env.example` 和运行时代码需要同步维护。`scripts/check_ai_config_forwarding.py` 用于静态核对转发范围，不能证明值已进入运行进程，也不能替代真实模型验收。

### 8.6 采样与兼容参数

业务用途默认温度为 0.0，输出额度由用途和选定模型上限共同约束。生产不读取旧 `LLM_TEMPERATURE / LLM_SEED` 选择模型或覆盖快照。上游是否接受温度、输出 token 字段、思考参数和流式 usage，由对应协议的兼容参数决定；用预览确认实际参数，再显式测试所需能力。

## 9. 运行观测与排错

### 9.1 存活与能力状态

`GET /health` 只说明 HTTP 进程存活。`GET /ready` 读取存储探测缓存和模型调用观察，不在每次访问时同步请求模型；调用时需内部令牌：

```powershell
Invoke-RestMethod "$baseUrl/health"
Invoke-RestMethod "$baseUrl/ready" -Headers $headers
```

ready 响应包含顶层 `status` 与 `capabilities`：

| 能力 | 判断依据 |
| --- | --- |
| `ingest` | 数据库、MinIO、Embedding |
| `search` | 数据库、Embedding |
| `answer` | search、证据门禁、generate 用途最近调用状态 |
| `evidenceGate` | score 的实际 scorer / 阈值，或 support 模型与审查状态 |
| `rerank` | 当前选定模型的真实调用观察，无选定快照时为 unknown |

能力项包含 `status / ready / reasonCode`，部分项目还带 `scorer / policyVersion`。状态使用 `ready / degraded / not_ready / unknown`；`unknown` 对应的 `ready` 可为 `null`。顶层按 ingest、search、answer 聚合，诊断项不单独决定总体状态。

后台默认每 15s 仅探测 PostgreSQL 与 MinIO，各有 1500ms 探测预算；不发 Embedding、Chat 或重排探针。数据库确认 `document_chunk` 可读，MinIO 检查 bucket。

模型能力按不可变模型身份保存最近实际调用观察，响应的 `models` 可供诊断。普通 `/ready` 没有用户模型快照，模型相关能力可以是 `unknown`；未选定模型与模型不可用是不同含义。`/ready` 的 HTTP 200、历史成功观察与 `/health` 都不能证明本次用户的模型、质量或容量已达标。

support 的 `evidenceGate` 聚合当前 `evidence_support` 模型的传输与完整审查观测。传输成功但 JSON、结构、引用校验失败时为 `not_ready`，合法无证据拒绝可以为 `ready`。审查记录复用模型身份指纹隔离，最多保留最近 256 个身份，300 秒后返回 `unknown / ready=null`。只读就绪检查不触发模型请求，无候选且未调用模型时不刷新审查记录。

### 9.2 日志、指标与追踪

默认日志级别为 INFO，输出到控制台，应用不自动创建日志文件。需要文件留存时由启动方式、容器日志或进程管理工具负责收集。

- 托管模型的 `model_call` 日志记录模型 ID、修订、用途、协议、调用结果、耗时和尝试次数。请求 ID 仍从入站请求传给模型服务。
- RAG 响应 `stages[]` 与 `rag_summary` 日志用于定位改写、召回、重排、门禁、后处理、生成的耗时与结果。
- OTel 外部导出默认关闭；阶段响应、结构化日志和指标默认保留。需要完整观测时显式使用 [可选观测方案](../docs/observability.md)；依赖缺失或初始化失败不影响业务阶段记录。
- `GET /metrics` 默认返回 Prometheus 文本，需要内部令牌；关闭 `METRICS_ENABLED` 后返回 404。
- LangChain 编排显式关闭 LangSmith tracing；阶段 OTel 使用独立出口。

token 费用按模型名称规则判断本地模型计 0，其余按配置单价估算；`cost_usd` 不能作为供应商账单。模型调用明细来自日志，不能假定 `/metrics` 已包含全部模型业务指标。

排查同一次调用时，以 `X-Request-ID` 串联 Java 日志、AI 响应与阶段记录，再检查实际 `pipelineEngine / retrievalVersion / scorer / providerUsed`。

### 9.3 常见问题

| 现象 | 先检查 |
| --- | --- |
| `No module named ai` | 是否从仓库根目录以 `ai.app.main:app` 启动 |
| 启动时报令牌或只读账号缺失 | 当前进程环境、两份 dotenv 的优先级；模板不会自动加载 |
| MinIO 下载失败 | endpoint、账号、bucket、objectName；本地代码默认值与 Compose 显式值有区别 |
| parser 返回 422 | 检查格式、文本编码、文件是否损坏或无法提取文字，以及扫描页数是否超过上限 |
| 扫描 PDF 返回 503 | 检查 Tesseract、语言包及 OCR 时间预算。耗时过长的 PDF 可分拆后上传 |
| Embedding 返回 503 | 明确绑定、模型协议/地址/凭证、模型服务状态及返回条数 |
| Embedding 返回 422 | 模型输出是否与该 KB 的活动空间维度一致 |
| 只检索有来源，生成却被拒绝 | `evidenceDecision`、实际 scorer、对应阈值、support 审查原因及后处理预算 |
| `retrievalStatus=partial` | 两路召回的失败 / 超时、2000ms 等待预算、数据库池与模型负载 |
| RAG 返回 429 | 请求槽占用及未结束的流式连接；并发参数按进程生效 |
| support 报上下文丢失或超预算 | 准入证明在压缩 / 父块扩展后是否保留、输入预算是否容纳必要证据 |
| 流式 HTTP 200 但回答中断 | 最终事件为 `done` 还是 `error`，是否仅收到部分增量 |
| `/ready` 显示 `unknown` | 此请求是否选定模型、相应模型身份是否已有实际调用观察 |
| `requires_rebuild` | KB 是否仍只有来源未知的历史向量；由创建者显式进入维护窗口重建 |
| 文档写入返回 409 | KB 是否正在重建，或提交的配置/活动索引修订已陈旧 |
| 找不到应用日志文件 | 默认只写控制台，检查启动进程或容器的日志收集 |

涉及生成不可用时，保留 `requestId / outcome / reasonCode / failedStage / retrievalStatus / evidenceDecision` 及阶段记录，避免仅凭兜底文案定位问题。

## 10. 源码导航

| 路径 | 内容 |
| --- | --- |
| [app/main.py](app/main.py) | 应用生命周期、路由、查询容量中间件、错误与请求 ID |
| [app/config.py](app/config.py)、[app/security.py](app/security.py) | 配置加载、启动校验、内部令牌 |
| [app/parser/](app/parser) | 对象解析入口、各格式解析器、Block 与响应 schema |
| [app/chunker/structured_chunker.py](app/chunker/structured_chunker.py) | 结构化切块 |
| [app/service/minio_service.py](app/service/minio_service.py) | 对象下载与 MinIO 客户端 |
| [app/embedding/](app/embedding) | 向量化、缓存、维度 / 可用性错误 |
| [app/keyword/segmenter.py](app/keyword/segmenter.py) | jieba 分词与关键词查询文本 |
| [app/query/](app/query)、[app/rerank/](app/rerank) | 管线内部改写与重排实现 |
| [app/retrieval/](app/retrieval) | 只读 PG 连接、向量 / 关键词召回、RRF 与父块扩展 |
| [app/rag/router.py](app/rag/router.py)、[schema.py](app/rag/schema.py) | 同步 / 流式契约 |
| [app/rag/pipeline.py](app/rag/pipeline.py)、[langchain_pipeline.py](app/rag/langchain_pipeline.py) | 两种生产查询编排 |
| [app/rag/evidence_support.py](app/rag/evidence_support.py) | support 证据审查、引用与覆盖检查 |
| [app/rag/postprocess.py](app/rag/postprocess.py) | 证据准入、MMR、压缩与准入上下文保护 |
| [app/rag/prompt_builder.py](app/rag/prompt_builder.py)、[rag_service.py](app/rag/rag_service.py) | 生成提示与模型生成 |
| [app/rag/deadline.py](app/rag/deadline.py)、[admission.py](app/rag/admission.py) | 截止预算、请求 / 召回容量 |
| [app/llm/](app/llm) | ModelGate、provider、用途、SDK、token 与遥测 |
| [app/models/](app/models) | 不可变模型契约、统一解析、四协议执行、预算取消和资源组 |
| [app/agent/](app/agent)、[app/evaluation/](app/evaluation) | Agent 决策 / 摘要、生成评判 |
| [app/observability/](app/observability) | 能力缓存、阶段日志与 OTel |
| [tests/](tests) | AI 模块测试源码 |

`query/router.py`、`rerank/router.py` 中的函数供管线内部调用；HTTP 接口以 `main.py` 的路由注册为准。离线探针、评测与数据维护脚本不属于正常服务启动流程。
