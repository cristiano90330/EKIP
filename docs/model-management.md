# 模型管理、向量索引与部署配置

这里说明如何配置共享默认模型、个人用途绑定、模型凭证和知识库索引。

## 1. 配置归属

首次启动会登记部署共享、普通用户只读的本地标准模型组：

| 用途 | 模型 | 协议与地址 |
| --- | --- | --- |
| Chat / support / Agent | `ekip-qwen3:4b-instruct-8k` | Ollama，OpenAI 兼容 `/v1`，8192 上下文、最多 512 输出 |
| Embedding | `ekip-qwen3-embedding:0.6b-4k` | Ollama，OpenAI 兼容 `/v1`，1024 维 |
| Rerank | `ekip-qwen3-reranker-0.6b` | llama.cpp，Cohere 兼容 `/v1/rerank` |

这三项沿用[轻量模型验收契约](architecture/contracts/lightweight-acceptance.md)中的模型权重；2026-11-18 将 Chat 运行上下文由 4096 调为 8192，历史质量与性能成绩仍属于原配置。应用只登记模型配置，不下载权重、不启动 Ollama/llama.cpp，也不在启动时调用模型。部署须让这些标签已存在于 Ollama，并运行本地重排服务。

### 本地模型服务配置

| 模型 | 服务端参数 | 项目登记与运行治理 |
| --- | --- | --- |
| Chat 4B | `num_ctx=8192`、`num_batch=128`，复用原 Q4_K_M 权重 | `contextWindow=8192`。生成问答最多输出 1024 token，单次最多 180 秒。证据审查单次最多 60 秒。其他 Chat 用途单次最多 20 秒，非生成用途的模型输出配置上限为 512 token |
| Embedding 0.6B | `num_ctx=4096`、`num_batch=128`，保持原模型标签 | 1024 维，单次模型超时 120 秒；既有空间和活动 generation 不变 |
| Rerank 0.6B | `ctx-size=2048`、`batch-size=2048`、`ubatch-size=2048`、`parallel=1` | `contextWindow=2048`、`maxBatchSize=20`，单次最多 60 秒，受本次请求剩余预算限制 |

三类模型保持 `maxConcurrency=1`、`resourceGroup=local-gpu` 和零重试。Chat 服务端参数由 [8K Modelfile](../ai/Modelfile.qwen3-instruct-8k) 管理；在已有 `ekip-qwen3:4b-instruct-4k` 的机器上，从仓库根目录执行：

```powershell
ollama create ekip-qwen3:4b-instruct-8k -f .\ai\Modelfile.qwen3-instruct-8k
ollama show ekip-qwen3:4b-instruct-8k --parameters
```

Ollama 运行后按请求加载 Chat 和 Embedding。重排仍通过 `scripts/start-local-reranker.ps1` 单独启动；已有监听时不重复启动。只修改项目的 `contextWindow` 不会改变 Ollama 实际上下文。初始化代码只创建缺失的共享模型，已有登记不会随常量更新。

个人 Chat、Embedding、Rerank 记录和用途绑定仍由当前用户管理。个人绑定优先；清除某用途的个人绑定后自动使用部署默认。用户 Agent 配置的 `chatModelId` 可覆盖当前 Agent 模型，留空时回到个人 `agent` 绑定，再回到部署默认。创建 Agent 配置不是使用默认 Agent 的前置条件。知识库成员使用索引创建者已建立的活动向量空间，不读取创建者的模型凭证。

首次默认模型记录在 Flyway V19 后初始化为共享只读行（`managed_model.owner_id IS NULL`），不出现在“我的模型”列表，也不能被个人编辑或删除。`GET /models/defaults` 只返回公开配置与密钥是否已提供，不返回密钥。

模型不再由旧 dotenv 变量选定。`ALLOW_CLOUD_LLM`、`LLM_MODEL*`、`OLLAMA_*`、`VECTOR_DIM`、旧重排档位与全局评分阈值退出生产选型；Java Agent 统一经 Python gateway 执行。基础设施连接、内部令牌、查询容量、证据策略等仍由部署配置管理。

公开模型区分三个版本：

| 字段 | 变化条件 | 用途 |
| --- | --- | --- |
| `revision` | 配置、凭证或校准更新 | 提交更新时的乐观锁 |
| `credentialVersion` | 凭证更新、清除或目标变化导致失效 | 隔离凭证版本对应的执行状态 |
| `semanticFingerprint` | 模型、协议、地址、维度、能力或语义参数变化 | 标识向量空间与评分语义 |

名称、超时、重试、并发和密钥轮换不改变语义指纹。模型已经被向量空间引用后，语义字段不能原地修改；应添加另一模型并显式重建知识库。名称和运行策略仍可更新，同端点可轮换凭证。被用途、Agent 或知识库索引引用的模型不能删除。删除个人模型时，会在同一事务中移除该模型下已无知识库索引引用的向量空间。

## 2. 协议与服务地址

厂商用于默认配置和参数约束，协议决定实际 HTTP 请求。预览、连接测试和生产使用同一解析入口，优先级为协议/厂商默认值、目录模型默认值、单模型覆盖。目录中的 CSV 候选均标记为未验证，不推断免费额度、维度或工具能力。

| 协议 | 类型 | 地址示例 | 实际路径 |
| --- | --- | --- | --- |
| `openai_chat` | Chat | `https://example.com/v1` | `/chat/completions` |
| `openai_embedding` | Embedding | `https://example.com/v1` | `/embeddings` |
| `cohere_rerank` | Rerank | `https://api.cohere.com/v2` | `/rerank` |
| `dashscope_rerank` | Rerank | `https://dashscope.aliyuncs.com` | `/api/v1/services/rerank/text-rerank/text-rerank` |

OpenAI 兼容地址可使用带对应终点的完整路径；解析器不会重复追加同一后缀。Ollama 使用 `openai_chat/openai_embedding` 与 `http://localhost:11434/v1`；原生协议已停用。DashScope 原生重排接受服务根地址、`/compatible-mode/v1` 根路径或完整原生重排终点，最终解析到原生路径。带反向代理前缀时先检查运行预览。

`baseUrl` 只允许 HTTP(S)，不能内嵌用户名、密码、查询参数或 fragment。容器中的 `localhost` 指该容器；访问宿主机 Ollama 时应登记部署环境可达的地址，例如 Docker Desktop 下的 `http://host.docker.internal:11434/v1`。

兼容参数采用协议白名单。Chat 可配置思考模式、输出 token 字段、温度/流用量兼容项；Embedding 可配置批大小、query/document instruction 和服务是否支持维度覆盖；旧原生参数 `keepAlive / numCtx` 已移除；重排可配置批大小和 `probability / logit` 分数尺度。参数是否适合当前模型必须通过真实测试确认。

2026-11-03 的收缩范围、配置和逐能力验证见 [模型与 Agent 能力矩阵](architecture/contracts/model-agent-capability-matrix.md)。当前开发版本默认重新登记支持的模型、使用新账号/知识库，不要求迁移旧配置。旧协议记录保留在数据库作为历史，活动列表和模型管理操作只处理当前协议。旧个人绑定指向停用模型时，实际使用该用途会报告依赖不可用，可在设置里重新选择或清除覆盖。若需复用旧文档，按其向量空间显式重建。Agent 仅消费原生 `tool_calls`，不再从正文恢复调用。

## 3. 凭证与校准

Java 使用 `model-management.encryption-key`，通常由 `MODEL_CONFIG_ENCRYPTION_KEY` 提供。它必须是 **Base64 编码的 32 个随机字节**，独立于 JWT 密钥、内部令牌和模型 API Key。缺少有效主密钥时拒绝保存/解密模型凭证，不以明文保存。无凭证的本地模型记录仍可管理。

凭证使用 AES-GCM 存储，并绑定用户与模型 ID。数据库备份和加密主密钥应分别妥善保存；随意更换主密钥会使已有密文无法解密，当前接口不提供自动重加密迁移。公开模型只返回 `credentialConfigured / credentialVersion`，不回填密钥。

先保存模型配置，再通过独立凭证接口设置 API Key。修改服务地址或厂商时，在同一事务中清除旧凭证。草稿测试只用本次明确输入的密钥；不会自动从旧模型补用已存密钥。已保存版本测试只用该模型自己保存的地址和凭证。

证据准入阈值通过独立校准接口登记：`{semanticFingerprint, threshold, reference}`，阈值为 0–1，记录应关联实际评测数据与结论。语义变化使旧校准失效；未校准的新模型不能沿用旧全局阈值放行答案。连接测试成功不等同阈值已经校准。

## 4. 用途与执行快照

用户可覆盖 `generate / rewrite / agent / summarize / judge / evidence_support / embedding / rerank`。个人绑定优先于部署默认；没有个人绑定时使用标准模型组。Agent 配置的可空 `chatModelId` 优先于个人 `agent` 绑定，再回到部署默认模型，并进入会话策略指纹；变更后旧策略会话需要新建。Agent 聊天模型需声明工具调用能力，并通过相应连接测试确认服务支持。

Embedding 由知识库活动索引决定。新 KB 首次建索引时，先用创建者的个人 `embedding` 绑定，否则用部署默认；已有 KB 不会因绑定变化而改索引。需要换模型时由创建者显式重建。后台入库显式按知识库创建者解析；异步摘要携带归属信息，不借用工作线程的当前登录用户。

Java 在完成授权后构造内部 `modelExecution`：

```text
schemaVersion: 1
budgetMs: 本次总预算
bindings: 本次可能执行的普通用途 → 完整模型快照
indexScopes: 每个已授权 KB 的 generationId、spaceId、dimension、embeddingModel 快照
```

内部快照包含执行所需 API Key，仅用于 Java→Python 受保护传输，不能交给浏览器、写入会话历史或作为日志输出。Python 使用 `SecretStr` 并清理校验/上游错误。Python 不读取模型或凭证管理表。业务客户端不能自行提交或覆盖该快照。

仅实际执行的用途要求绑定：例如 `generate=false` 不要求生成模型，但所选检索策略仍可能执行改写、向量化或重排。生产缺绑定返回明确依赖故障，不跨模型回退。合法空知识库范围在模型和检索 I/O 前短路。

## 5. 预算、显存与就绪

| 执行入口 | Java 默认模型总预算 | 外层等待余量 |
| --- | --- | --- |
| 生成问答 | 270000ms | Java 到 AI 的 HTTP 等待默认最多 275000ms，Java SSE 连接默认最多 285000ms，页面兜底 300000ms |
| 纯检索和 Agent 知识工具 | 最多 35000ms | 使用各入口的传输与回合预算 |
| Agent 决策 / 摘要 | 30000ms | 5000ms |
| 文档 Embedding | 120000ms | 5000ms |
| 管理连接测试 | 最多 30000ms，不重试 | 管理传输另留余量 |

单模型 `runtime.timeoutMs` 从取得模型及资源组槽位后计算，限制本次推理。共享资源排队消耗请求总预算，总截止预算覆盖排队、网络读取、重试退避和流式正文。缺少请求总预算的内部直接调用沿用单模型超时作为整次调用上限。个人模型默认 `retries=0`、`backoffMs=500`、`maxConcurrency=1`，Embedding 默认模型超时 120000ms，其他个人模型默认 20000ms。部署共享模型按用途使用本页的时限。

模型并发和共享资源组容量分别管理。部署变量 `MODEL_RESOURCE_GROUPS` 留空时采用 `{"local-gpu":1}`，`MODEL_RESOURCE_QUEUE_LIMIT` 默认 8。`source=local` 且未指定组时进入 `local-gpu`。首版应运行 **单 Python worker、单 AI 实例** 来维持该设备容量保证；多进程会各持一份资源状态。

容量 1 能限制 EKIP 同时发起的本地模型调用，不能保证多个模型权重同时驻留 8GB 显存。Ollama 的量化、上下文、KV cache 和其他进程仍影响冷加载与显存。可将 Chat、Embedding、Rerank 分别迁至云端或较小本地模型，按实测决定；登记一个 4B 名称不证明它在本机可用。

启动与 `/ready` 不主动加载或推理模型。后台只探测 PostgreSQL 和 MinIO；模型可用性来自显式测试或真实调用观察，并按模型身份隔离。没有请求选定模型的 `/ready` 可显示 `unknown`；`models` 中的观察记录也不能替代某个用户本次执行的选择。`/health` 只报告存活。

## 6. 1024/2560 向量与重建

正文、来源和父子关系保留在 `document_chunk`。Flyway V16 创建固定 `halfvec(1024)` 与 `halfvec(2560)` 向量表、HNSW 和范围索引，以及不可变 `embedding_space`、KB 活动 generation 和持久重建任务。没有运行时动态建表。

来源未知的向量保留在历史 `document_chunk.embedding` 列中。检索使用已验证的活动 generation。有旧正文但缺少该 generation 的 KB 状态为 `requires_rebuild`，需显式重建后才可用语义检索。

创建者发起重建流程：

1. 查看 KB 索引状态，选择自己的 Embedding 模型及实际输出维度。等待该 KB 的所有 `WAITING / PROCESSING` 文档任务结束。
2. 明确进入只读维护窗口，以页面读取的 `expectedActiveGenerationId` 发起重建；首次无活动索引时显式提交 `null`。陈旧指针返回 409。
3. 后台按稳定块序分批读取现有非父块正文生成新向量，不重新下载或解析原文件。上传、删除、重处理等写入口经过同一维护屏障，维护期间返回 409。已有有效活动索引继续查询。
4. 每批幂等写入影子 generation，并检查任务状态、执行代号和租约。取消使执行代号失效，晚到模型结果不能继续写入或激活。
5. 全量校验条数、维度、有限值、非零向量与源正文指纹后，在短事务中比较并切换活动 generation，同时恢复写入。

失败或取消保留原活动索引并结束维护；没有原有效索引的 KB 仍需重建。重启后持久任务可在租约过期后被重新认领，从已提交批次继续，默认租约为 180s。旧 generation 保留用于在途查询；恢复文档写入后不提供直接回滚旧 generation 的承诺，当前没有自动清理旧 generation 的管理入口。

跨 KB 查询按 `spaceId` 分别生成查询向量，SQL 同时绑定授权 KB 和 generation。即使两个空间维度相同也不混用向量；跨空间以名次做 RRF 融合，不直接比较不同模型的余弦距离。Python 只读角色仅增加新向量表 SELECT 权限，无模型凭证表访问权。

## 7. 对外接口

下列 Java 接口需要用户 JWT，返回 `Result<T>`；地址省略前端 `/api` 代理前缀。

| 方法与路径 | 用途 |
| --- | --- |
| `GET /models`、`POST /models` | 列表、创建 |
| `PUT /models/{id}` | 更新，携带 `expectedRevision` |
| `DELETE /models/{id}?expectedRevision=N` | 删除无引用模型 |
| `PUT /models/{id}/credentials` | `{apiKey, expectedRevision}` |
| `DELETE /models/{id}/credentials?expectedRevision=N` | 清除凭证 |
| `PUT /models/{id}/calibration` | 独立记录语义校准及预期修订 |
| `GET /models/providers` | 厂商、协议白名单与未验证模型目录 |
| `POST /models/resolve` | 草稿运行预览，不调用模型 |
| `POST /models/test` | 草稿连接测试，显式本次密钥 |
| `POST /models/{id}/test` | 已存配置及凭证测试 |
| `GET /models/defaults` | 查看部署共享模型，不返回服务密钥 |
| `GET /models/bindings`、`PUT /models/bindings` | 个人用途覆盖；PUT 完整替换，空对象表示全部恢复部署默认 |
| `GET /knowledge-bases/{id}/index` | KB 活动索引、维护与最近任务 |
| `POST /knowledge-bases/{id}/index/rebuilds` | `{modelId, expectedActiveGenerationId}` |
| `GET /knowledge-bases/{id}/index/rebuilds/{taskId}` | 持久任务进度 |
| `POST /knowledge-bases/{id}/index/rebuilds/{taskId}/cancel` | 取消重建 |

索引状态读取按 KB 访问权限控制；发起与取消仅创建者。配置错误为 400，跨用户模型引用为 404，修订陈旧/引用/维护冲突为 409，缺执行依赖为 503，超时为 504。RAG 与 Agent 仍保留 `success / no_evidence / dependency_unavailable / timeout`，流式以终止事件为准。

连接测试 `checks` 可选 Chat 的 `text / tools / stream / json`、Embedding 的 `embedding` 或 Rerank 的 `rerank`，逐项报告。显式点击会请求模型并可能消耗供应商额度。模型测试不上传业务文档，但仍须由用户决定执行。演示模式不执行推理或真实重建，也不把演示结果报告为连通通过。

## 8. 当前开发版本配置

1. 默认使用新的开发数据库、账号和知识库，Flyway 建立当前表结构并自动登记标准模型组；不要求普通用户重新创建模型。不会自动迁移旧账号、旧模型绑定或历史向量，也不清空既有数据。
2. 在 Compose 部署中一次设置 `MODEL_DEFAULT_OLLAMA_BASE_URL`、`MODEL_DEFAULT_RERANK_BASE_URL`；后者服务密钥放入未提交的根 `.env` 中 `MODEL_DEFAULT_RERANK_API_KEY`。Ollama 与 rerank 标签/权重须由部署准备。使用 `scripts/start-local-reranker.ps1` 时，将忽略目录 `tmp/local-reranker-api-key.txt` 中的当前密钥交给部署环境配置；不要把密钥写入源码、模型快照、日志或本说明。普通 Java 进程默认连接 localhost，可按服务部署位置覆盖地址变量。
3. 用户可直接使用部署默认。需要个人模型时再添加模型，并在“用途绑定”保存覆盖；点击“清除个人覆盖并恢复部署默认”会清空本人的用途绑定，不删除模型、Agent 配置、历史或向量索引。
4. 新 KB 首次建索引使用个人 Embedding 覆盖或部署默认。已经有活动 generation 的 KB 不会因模型绑定变化而切换。更换模型/维度或明确复用来源未知的旧文档时，才由 KB 创建者显式重建；成员不需要取得创建者模型凭证。
5. 已配置的模型可在目标部署中检查连接、问答和 Agent 调用。冷启动、并发和质量按交付目标另行验证。故障四态与模型语义指纹可用于定位问题。
