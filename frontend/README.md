# frontend —— EKIP 前端控制台

Vue 3 + Vite + TypeScript 单页应用（包名 `ekip-observability`），承载企业智能知识管理平台的三类界面：**知识库与文档接入**、**RAG 智能问答**、**可观测（Trace / 指标 / 状态）**。

后端有两个运行单元，前端不直接区分它们——浏览器侧全部同源请求 `/api/**` 与 `/ai/**`，由 Vite（开发）或反向代理（部署）转发：

| 前缀 | 目标 | 承载 |
| --- | --- | --- |
| `/api` | `http://127.0.0.1:8080` | Java Spring Boot 业务后端（登录、知识库、文档、问答、观测） |
| `/ai` | `http://127.0.0.1:8000` | Python FastAPI AI 服务的健康端点 |

## 快速开始

```bash
cd frontend
npm ci                  # 按 package-lock.json 安装，避免依赖漂移
npm run typecheck       # vue-tsc --noEmit，CI 前必跑
npm run dev -- --host 127.0.0.1
```

打开 `http://127.0.0.1:5173/login`，首次使用点击“没有账号？注册”。填写用户名、6–20 位密码和确认密码，成功后返回登录并保留用户名。重复用户名等业务错误直接显示在表单中。

可用脚本：

| 命令 | 作用 |
| --- | --- |
| `npm run dev` | Vite 开发服务器，默认 5173，带下方两条代理 |
| `npm run build` | 产出 `dist/`（已被 `.gitignore` 忽略，不提交） |
| `npm run preview` | 本地预览 `dist/` 产物，**不带** `/api`、`/ai` 代理 |
| `npm run typecheck` | 类型检查。项目未配置 ESLint，静态约束来自 `tsconfig.json` 的 `strict` + `noUnusedLocals` + `noUnusedParameters` |
| `npm run test:auth` | 执行登录页实际注册/登录回调，检查输入边界、成功与错误状态、重复提交和登录跳转 |
| `npm run test:chunks` | 执行切片抽屉实际回调，验证正文请求、分页、错误重试和晚到结果隔离 |
| `npm run test:sse` | 使用 Node 内置测试器与 tsx 运行 SSE 契约测试；固定字节分块、错误和取消行为，不连接业务服务 |
| `npm run test:models` | 验证四协议选项、演示元数据与历史配置不被静默迁移，不请求真实模型 |
| `npm run test:uploads` | 执行真实文件选择回调，验证 14 种声明格式、未知格式拒绝和统一 accept 清单；不挂载浏览器或上传文件 |

`package.json` 未声明 `engines`，但 Vite 5 要求 Node 18 或 20+，建议使用 Node 20 LTS。

SSE 模块接口和重构边界见 [一页说明](../docs/architecture/modules/streaming.md)。帧解析使用 `eventsource-parser` 3.x；请求仍由现有 fetch 发起，不自动重连或重新提交聊天 POST。RAG、Agent 和文档任务共用 `api/sse.ts`，任务的快照对账顺序保持不变。

## 环境变量

前端只有一个环境变量，读取处为 `src/config.ts`：

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `VITE_USE_MOCK` | 未设置（= 走真实后端） | 仅当字面值为 `'true'` 时启用 `src/api/mock.ts` 的模拟数据层 |

```bash
VITE_USE_MOCK=true npm run dev     # 前端独立开发，不依赖任何后端
```

使用约定：

- **真实接口是默认值**，不要在日常开发或验收时打开 mock——根 `README.md` 明确要求复现时不得设置该变量。
- mock 是**逐函数分支**而非拦截器（每个 API 方法内部 `USE_MOCK ? mockX() : http...`），因此正式数据流零侵入；但这也意味着**新增接口必须同时补 `mock.ts` 分支**，否则 mock 模式下会直接报错。
- 该变量也可写入 `frontend/.env.local`（Vite 自动加载，已被 `.gitignore` 的 `*.local` 覆盖）。

## 开发代理

`vite.config.ts` 中两条代理都配置了 `changeOrigin` 与**去前缀 rewrite**：

```ts
'/api': { target: 'http://127.0.0.1:8080', rewrite: p => p.replace(/^\/api/, '') }
'/ai':  { target: 'http://127.0.0.1:8000', rewrite: p => p.replace(/^\/ai/, '') }
```

即前端写 `/api/user/login`，到达后端的是 `/user/login`；`/ai/health` 到达 AI 服务的是 `/health`。**不要在前端代码里直接写死 `127.0.0.1:8080`**，否则部署时会同时破坏同源假设与代理收益。

## 路由与页面

路由定义在 `src/router/index.ts`，使用 `createWebHistory`（history 模式，部署需 SPA fallback）。

| 路径 | 视图 | 主要内容 |
| --- | --- | --- |
| `/login` | `LoginView.vue` | 登录与注册切换；注册成功返回登录，登录成功写入令牌并跳转 |
| `/overview` | `OverviewView.vue` | Bento 总览：快速提问、待处理、能力就绪、最近文档、请求分布、延迟与成本（15 秒轮询） |
| `/library` | `LibraryView.vue` | 知识库新建与编辑、共享状态和成员管理、整库删除、文档上传回执、任务重试与切片浏览 |
| `/ask` | `AskView.vue` | 全部可读库单轮问答、指定库多轮追问、服务端历史与删除、流程选择（`fixed` / `langchain`）、引用和失败诊断 |
| `/agent` | `AgentView.vue` | Agent 配置、工具目录、证据引用、服务端会话恢复与执行步骤 |
| `/traces` | `TracesView.vue` | RAG 请求列表（`limit=50`，5 秒轮询）+ 阶段瀑布图 |
| `/metrics` | `MetricsView.vue` | Actuator 指标卡片，5 秒轮询 |
| `/status` | `StatusView.vue` | 后端与 AI 健康、能力就绪矩阵、配置快照，10 秒轮询健康与就绪 |
| `/settings` | `SettingsView.vue` | 模型、用途绑定、知识库索引、MCP Key、单条检索评测；已配置的管理员还可管理账号并查询操作审计 |
| `/`、`/*` | 重定向 / `NotFoundView.vue` | `/` → `/overview` |

全局守卫只看「有没有票」，不看角色：

```ts
// 只要还有 access 或 refresh 任一票据就算已登录；只剩 refresh 时，第一个请求 401 会触发静默刷新
const loggedIn = !!auth.token || !!auth.refreshToken
```

因此**权限判定完全在后端**，前端守卫只负责「未登录跳登录页、已登录不进登录页」。不要在前端新增角色判断作为授权手段。

## 目录结构

```
src/
├─ api/                 # 唯一的数据出入口，按后端域拆分
│  ├─ http.ts           # axios 实例、令牌拦截器、401 重放、单飞刷新
│  ├─ auth.ts           # 登录 / 刷新 / 登出（后两者走裸客户端）
│  ├─ library.ts        # 知识库、文档、上传回执、任务查询与 SSE
│  ├─ knowledge.ts      # /knowledge/ask 与错误文案映射
│  ├─ agent.ts          # Agent 配置、模型覆盖、工具目录、会话与带 Bearer 的对话流
│  ├─ models.ts         # 用户模型/凭证/用途、预览测试、KB 索引重建
│  ├─ mockModels.ts     # 无真实推理和重建的模型管理演示
│  ├─ observability.ts  # Trace / 状态 / 能力就绪
│  ├─ actuator.ts       # Actuator 健康与指标 + AI /health
│  ├─ userIdentity.ts   # 从 JWT 的 sub 取用户 ID，仅用于本地 UI 命名空间
│  └─ mock.ts           # DEV ONLY 模拟数据层
├─ stores/auth.ts       # Pinia：令牌状态、刷新窗口、跨标签页同步
├─ router/index.ts      # 路由表 + 登录守卫
├─ views/               # 页面级组件
├─ components/TraceWaterfall.vue  # Trace 阶段瀑布图
├─ types.ts             # 与后端 DTO 对应的类型契约
├─ ragOutcome.ts        # 问答结果 → 提示文案 / reasonCode 白名单
├─ ragExecution.ts      # 执行元数据 → 中文标签（引擎、检索版本、provider、评分器、outcome）
├─ config.ts            # VITE_USE_MOCK 开关
├─ theme.ts             # 主题三态（跟随系统 / 亮 / 暗），维护 <html theme-mode>
├─ styles/              # 样式分层（见下文「7. 样式体系」）
│  ├─ tokens.css        # 设计令牌：primitive → semantic → component
│  ├─ base.css          # 重置、外壳布局、跨页通用件、焦点与动效降级
│  ├─ tdesign.css       # TDesign 变量桥接（亮 / 暗两套）
│  └─ chat.css          # Ask 与 Agent 共用的聊天界面样式
├─ App.vue              # 顶栏胶囊导航 + 命令面板 + 主题/退出
└─ main.ts              # 挂载、样式分层导入、主题初始化、共用组件注册
```

## 关键机制

以下小节说明认证、上传、SSE 与问答的数据边界。

### 1. 令牌生命周期：双票、提前刷新、单飞、跨标签页

令牌存于 localStorage 的三个键（`stores/auth.ts` 导出常量）：`token`（access）、`refreshToken`、`tokenExpiresAt`（epoch ms）。`expiresAt` 由后端 `LoginVO.expiresIn` 换算，**不要从 JWT 自行解析过期时间**。

请求链路（`api/http.ts`）：

1. **发请求前惰性提前刷新**：距 access 过期不足 `REFRESH_SKEW_MS = 60_000` 时先静默换票，正常路径上不出现 401。用「请求前检查」而不是后台定时器——后台标签页定时器会被浏览器节流，且用户不操作时刷新是纯浪费。
2. **单飞刷新**：同一批并发 401 / 同一批提前刷新只发一次 `/user/refresh`，其余复用同一个 Promise。
3. **401 重放一次**：刷新成功则重放原请求（`_retried` 标记防循环）；刷新失败才 `forceLogout()`（清本地 + 跳登录）。刷新/登录/登出端点自身不参与 401 刷新，否则刷新失败会自触发刷新形成死循环。
4. **提前刷新失败但旧票未过期 → 照常发出去**，把判断权交回 401 兜底链。不要在这里直接登出，否则一次后端抖动就会把正在打字的用户踢回登录页。
5. **刷新时现读 `localStorage`**，不用 store 里的 `refreshToken`：本页内存态可能已被另一个标签页轮换掉，拿陈旧票去刷在服务端与窃取者重放**同形**，会触发**整族吊销**。
6. **跨标签页同步**：`main.ts` 在 Pinia 装好后调用 `useAuth().startStorageSync()`，监听 `storage` 事件覆盖本页内存态。`storage` 只在**其它**标签页写入时触发，不存在自激循环。

登出语义（`stores/auth.ts`）：**先清本地再尽力调后端**。后端不可达也视为已登出——刷新票会随自身过期自然失效，不能让「网络不通」变成「退不出去」。

### 2. 两套 axios 实例

| 实例 | 响应处理 | 用于 |
| --- | --- | --- |
| `http` | 成功解包 `Result<T>` 的 `data`；`code !== 200` 抛 `Error(message)`；失败从响应体取 `message` | 所有返回 `Result<T>` 的业务端点 |
| `raw` | 保留完整 `AxiosResponse`，只做 401 兜底 | `/actuator/**`（原生 JSON）、`/knowledge/ask`（直接返回 DTO） |

两者都挂 `ensureFresh`。`raw` 也挂提前刷新是有意为之：观测页常长时间开着，access 15 分钟过期后不提前换票就会被 `forceLogout` 踢登出。

`api/auth.ts` 里还有第三个**裸实例** `authClient`（不带任何拦截器）：`/user/refresh` 与 `/user/logout` 在后端是 permitAll，凭证放在请求体里自带，既不需要 `Authorization`，也不能被 401 拦截器捕获。

### 3. 文档上传：幂等、回执对账、断点续传意图

上传是这条链路里最容易产生「重复文档 / 幽灵失败」的环节，`LibraryView.vue` + `api/library.ts` 的处理顺序如下：

1. 上传请求带 `Idempotency-Key`（`newRequestKey()` 生成），后端据此去重；超时放宽到 **180 秒**，`multipart` 自动带进度回调。
2. 上传前把「上传意图」（`userId + knowledgeBaseId + 文件指纹 + requestKey`）写入 localStorage；文件指纹由 `fileFingerprint()` 计算，键按三者组合，**不会跨用户或跨知识库串**。
3. 若上传结果不确定（网络中断、5xx、响应丢失），先调 `GET /document/upload/receipt?knowledgeBaseId&requestKey` **查回执**，再决定「视为成功 / 重试」——而不是盲目重传。
4. 成功后清除该条意图；页面重新进入时 `inspectSelectedUploadIntent()` 会提示存在未完成的上传。
5. 解析任务状态通过 SSE 订阅，断流后 `recoverTaskAfterStreamDrop()` 回读任务快照补齐，避免漏掉终态。

### 4. 任务状态 SSE：为什么不用 `EventSource`

`watchDocumentTask()` 用 `fetch` + `ReadableStream` 手工解析 `text/event-stream`，原因是**原生 `EventSource` 无法设置 `Authorization` 头**，而该端点需要 Bearer。实现细节：

- 先把 `\r\n` 归一为 `\n` **再**找 `\n\n` 帧边界，否则跨 chunk 分裂的 CRLF 会被漏判。
- 只合并 `data:` 行，容忍 `{task} | {data} | 裸对象` 三种载荷形态，缺 `status` 的帧直接丢弃。
- 帧解析失败**不抛错**——由调用方回读快照兜底，流式通道是优化而非唯一真相。
- 连接建立（服务端已发头）后回调 `onConnected` 回读一次快照，消除「先订阅后取快照」之间的竞态。

### 5. 问答链路

问答页通过 `/rag/sessions` 管理会话。固定流程使用 `/knowledge/sessions/{id}/ask/stream`，LangChain 使用 `/rag/sessions/{id}/ask`。

- 前端问答请求预算为 **300 秒**，覆盖检索、重排和生成以及流式结束。
- `AskView.vue` 通过 `askKnowledgeStream()` 读取增量正文；选择 `langchain` 或 mock 时，API 层改用完整 JSON。无增量正文时保留打字机展示。
- 流式使用带 Bearer 的 `fetch`；收到 `done` 才确认完成，`error` 或缺少终止帧均按失败处理。HTTP 200 与部分正文不能作为成功依据。
- 生成结果经 JSON Schema 完整校验后才发送正文 delta，阶段进度仍实时到达。`done` 为 Python 结果，前端把 `evidenceSources` 映射为页面 `sources`，只在 `success` 时展示；原始候选 `sources` 不作为页面来源。`no_evidence` 显示无资料提示，生成格式错误按依赖失败处理。
- 失败统一抛 `RagRequestError`，把 `reasonCode` / HTTP 状态映射为用户可读文案（`query_deadline_exceeded` → 超时、`threshold_not_configured` → 门槛未配置、`scorer_unavailable`、`retrieval_unavailable`、403/400/422 等），并保留 `outcome` / `failedStage` / `requestId` 供排障。
- **普通问答**默认使用全部可读知识库进行单轮检索。首次发送前选定一个库后，可以在同一会话继续追问，后端读取最近三个完成回合。换库需要新建会话。
- **本次问答策略**默认沿用部署配置。轻量问答使用混合检索与正文审查，跳过重排。也可选重排加正文审查，或重排加分数阈值。后者需要已校准的有效阈值。答案区显示实际回传的检索与证据模式。
- **普通问答历史**可从左侧目录恢复、向前翻页或删除。服务器保存成功回答和正常拒答。当前知识库权限不匹配时会话不可读取，仍可删除。模拟会话仅保存在页面内。

### 6. Agent 配置与会话

`AgentProfileManager.vue` 从用户模型列表选择支持工具调用的 Chat 模型；可空 `chatModelId` 使用用户 Agent 用途默认值，修改后需开始新策略会话。`AgentProfileManager.vue` 从 `GET /agent/tools` 读取可选工具，保存的白名单由后端再次校验。`allowedTools=[]` 表示不开放工具；目录失效项需显式移除后保存。`answerPolicy=grounded` 默认要求本轮获准证据进入模型，`general` 允许普通模型回答。

`AgentView.vue` 从 `/agent/sessions` 恢复当前用户目录，打开会话时读取最近完成回合，可向前加载历史。后端重验 Agent 配置与知识库权限，策略变化后需新建会话。刷新会恢复答案、有限步骤摘要、停止原因、四态及来源；取消或断流半段不作为完成历史。真实模式使用服务端存储，mock 仅使用浏览器 sessionStorage 演示。

对话使用带 Bearer 的 `fetch` 读取 `/agent/chat/stream`。grounded 正文在最终门禁后由 `answer` 帧交付，general 允许 delta；`done` 确认终态。服务端先保存完成回合，再发送 `answer / done`，存储失败由 error 表达。`agentCitations.ts` 只把服务端来源中已知的 `[[cite:ID]]` 转为来源按钮，未知标识与 HTML 保持普通文本；来源可追溯不代表逐句核验完成。

Agent 配置可分别关闭长期记忆读取与写入；开关改变后旧策略会话需重新创建。记忆管理入口通过 `/agent/memories` 查看、纠正和删除当前用户条目，显示全局状态与容量/字符限制。全局停用时仍可查看、删除，纠正写入被禁止；停用不代表已存内容被清除。条目属于用户自述，可能过期，不能代替知识库证据。

用户以「记住：」发起请求后，Agent 可在当前会话提出长期记忆申请。审批卡片显示将要保存的内容和类型。会话所有者批准后才保存，拒绝或过期的申请不能写入记忆。刷新会话后可以继续处理仍有效的申请。

自建 Agent 可以启用「自动建议记住回复偏好」。它从本轮消息提取明确的语言、格式或详略偏好，复用同一审批卡片，并遵守长期记忆写入开关。

### 模型与索引管理

`SettingsView.vue` 使用 `api/models.ts` 管理当前用户的 Chat、Embedding、Rerank 模型与用途绑定，并提供厂商/协议目录、运行预览和分能力连接测试。配置更新携带 `expectedRevision`，冲突后刷新再编辑。凭证通过独立接口旋转/清除，公开响应不含 API Key，不把密钥写入 localStorage；草稿测试仅使用本次明确输入的密钥，已存版本测试只用已保存配置。

校准阈值独立绑定模型语义指纹；连接测试不等于质量或校准验收。KB 索引管理显示活动 generation、空间、维度、维护与任务进度；创建者明确选择只读维护后发起重建，成员只读状态。重建期间文档写入可能返回 409，失败/取消保留旧有效索引。演示模式不执行真实推理和重建，不能作为实际连通性证明。详细接口和迁移边界见[模型管理说明](../docs/model-management.md)。

### 6. 观测页的数据口径

- **指标**：`MetricsView.vue` 用 `PRIORITY` 白名单取关键指标（`rag.requests.total`、各阶段 latency、token、cost、`agent.*`），再用 `^(rag|agent)\.` 兜底补足，**总共最多 12 张卡**。新增指标若不在白名单且不匹配该前缀，页面上不会出现。
- **Trace**：列表 `limit=50`、5 秒轮询；详情按 `stage` 渲染瀑布图。执行元数据一律走 `ragExecution.ts` 的标签函数，缺失值显示「未记录」——**不得用当前配置推断历史执行的引擎或检索版本**。
- **状态**：后端 `/actuator/health`、AI `/health`、Java 聚合的 `/observability/capabilities` 三个来源；`READY` 只代表依赖就绪，不代表答案质量或 SLA。

### 7. 样式体系

**视觉风格：Bento Box Grid**（来源 `ui-ux-pro-max` 的 `bento-box-grid`）。核心是从「发丝线与直角」换成「白卡 + 柔影 + 圆角」——层级由卡片体积与留白表达，不再由边框表达。约束写在 `tokens.css` 的尺度层：

| 约束 | 取值 | 落地方式 |
| --- | --- | --- |
| 圆角 | 卡片 `20px`、控件 `12px`、胶囊 `999px` | `--radius-xl / md / full`；TDesign 侧映射 `--td-radius-*` |
| 阴影 | 双层柔影 | `--shadow-1/2/3`；暗色下阴影不构成层级，改用底色差 + 1px 描边 |
| 字重 | `400 / 500 / 600 / 650` | `--fw-normal/medium/semi/bold`；卡片标题 600，数值 650 |
| 强调色 | 单一（靛蓝 `#4F46E5`） | `--accent` 一族；状态色仅用于点、短标签与提示条 |
| 间距节奏 | 4px 基数，**16px** 为卡片间距 | `--space-*`；bento 的 `gap` 一律取 `--space-4` |
| 版心 | `1320px` | `--content-max`，`.content > *` 统一限宽居中 |

亮色是默认面（正文、表格、卡片都在这套下设计）；暗色不是亮度翻转，而是另配一套底色与描边。

样式分四层，导入顺序在 `main.ts` 固定，**不可调换**（后三层都依赖 `tokens.css` 的语义变量，`tdesign.css` 必须晚于组件库自身样式）：

| 层 | 文件 | 职责 |
| --- | --- | --- |
| 令牌 | `styles/tokens.css` | primitive（中性阶 / 靛蓝阶 / 4px 间距 / 字阶 / 圆角与柔影）→ semantic（`--bg`、`--border`、`--accent` 等，亮暗各一套）→ 组件尺度 |
| 基础 | `styles/base.css` | 重置、顶栏外壳（`.topbar` / `.nav-pill` / `.search-trigger`）、`.card` / `.panel` / `.field` / `.page-head`、焦点态与动效降级 |
| 桥接 | `styles/tdesign.css` | 映射 `--td-*` 把组件库换成靛蓝 + 圆角 + 柔影；类名覆盖只用于变量表达不了的地方（输入填充、表格圆角、标签胶囊） |
| 共用 | `styles/chat.css` | Ask / Agent 两页的会话卡、气泡、来源卡片、输入区；选择器统一挂在 `.chat-page` 下 |

约定：

- **主题三态**：顶栏右侧按钮切换「跟随系统 / 亮色 / 暗色」。选择结果写入 `localStorage` 的 `ekip-theme`（选「跟随系统」时删除该键），键值只可能是 `light` / `dark`。`index.html` 在首屏渲染前按「存储值优先、否则看 `prefers-color-scheme`」预置 `<html theme-mode>`，`theme.ts` 的 `initTheme()` 做同样的事并监听系统变化。**两处逻辑等价，改一处必须同步另一处**，否则刷新时会闪一帧错误主题。系统偏好不可用时两处都兜底为亮色。
- 新增颜色先进 `tokens.css` 的语义层，**页面里不写字面色值**（`rgba(...)`、`#xxxxxx` 都不行），否则另一套主题必然漏改。
- **卡片是基本容器**：默认 `.card` / `.panel` 自带 20px 圆角与柔影。不要再给卡片叠加边框；暗色下的描边由 `tokens.css` 的 `[theme-mode='dark'] .card` 统一给出。
- **选中态用淡靛蓝底**（`var(--accent-soft)` + 强调色文字），不用左侧 2px 色条——那套是上一版 Swiss 的写法。
- **宽表不进 bento 列**：bento 栅格固定 4 列，`grid-column: span 2` 表示宽卡。文档台账这类宽表给通栏卡，塞进 1 列会横向溢出。
- 页面级标题用 `.page-head`（h1），区块标题用 `.section-head`（h2）；聊天页的页头在卡片内，已由 `chat.css` 压成一行，不要另造结构。
- **导航只有 6 项**：总览 / 知识库 / 问答 / Agent / 观测（下拉：Trace、指标、状态）/ 模型。新增页面时同步改两处：`App.vue` 的 `NAV` 与 `OBSERVE_OPTIONS`，以及 `CommandPalette.vue` 的 `commands`——否则命令面板会漏项。
- 两个聊天页共享的结构一律放 `chat.css`，一页单独改会导致两页漂移。
- 侧栏已移除：`--nav-w` 与 `.sidebar` / `.nav-item` 只为兼容旧 scoped 样式保留，**新代码不要再依赖侧栏尺寸**。

## 接口依赖清单

所有方法均在 `src/api/` 内封装，`baseURL` 为 `/api`（下表省略该前缀）。

| 前端方法 | 端点 | 封装形态 |
| --- | --- | --- |
| `register` | `POST /user/register` | `http`（解包 `Result<string>`），公开账户端点直接发送 |
| `login` | `POST /user/login` | `http`（解包 `Result<LoginVO>`） |
| `refresh` | `POST /user/refresh` | 裸 `authClient`，`refreshToken` 在请求体 |
| `logout` | `POST /user/logout` | 裸 `authClient`，幂等 |
| `listKnowledgeBases` | `GET /knowledge-base/list` | `http` |
| `createKnowledgeBase` | `POST /knowledge-base` | `http` |
| `listDocuments` | `GET /document/list?knowledgeBaseId&page=1&size=100` | `http` |
| `listDocumentChunks` | `GET /document/{documentId}/chunks?page=1&size=20` | `http`，创建者或成员可读正文分页 |
| `uploadDocument` | `POST /document/upload?knowledgeBaseId` | `http`，`multipart` + `Idempotency-Key`，180s |
| `lookupUploadReceipt` | `GET /document/upload/receipt?knowledgeBaseId&requestKey` | `http` |
| `getDocumentTask` | `GET /document/{documentId}/task` | `http` |
| `retryDocumentTask` | `POST /document/{documentId}/retry` | `http`（归属校验在后端） |
| `cancelDocumentTask` | `POST /document/{documentId}/task/{taskId}/cancel` | `http`，创建者取消当前任务并保留原文件 |
| `watchDocumentTask` | `GET /document/{documentId}/task/stream` | `fetch` + SSE 手工解帧 |
| `askKnowledge` | `POST /knowledge/ask` | `raw`，60s，返回 DTO 而非 `Result` |
| `askKnowledgeStream` | `POST /knowledge/ask/stream` | 带 Bearer 的 `fetch`；`fixed` 增量输出，其它引擎使用完整 JSON |
| `listAgentTools` | `GET /agent/tools` | `http`，实际注册工具的公开目录 |
| Agent 配置 CRUD | `/agent/profiles[/{id}]` | `http`，当前用户配置 |
| Agent 会话目录 / 创建 / 删除 | `/agent/sessions[/{id}]` | `http`，服务端签发会话 ID |
| `getAgentSessionHistory` | `GET /agent/sessions/{id}/history` | `http`，最近回合向前分页 |
| `streamAgentChat` | `POST /agent/chat/stream` | 带 Bearer 的 `fetch`，完成终态由 done 判断 |
| 长期记忆列表 / 纠正 / 删除 | `/agent/memories[/{id}]` | `http`，当前用户条目与全局启用状态 |
| 查询 / 决定长期记忆申请 | `/agent/sessions/{sessionId}/memory-approvals/{approvalId}[/decision]` | `http`，会话所有者审批 |
| `listTraces` | `GET /observability/trace?limit=50[&entrypoint=]` | `http` |
| `getTrace` | `GET /observability/trace/{id}` | `http` |
| `getStatus` | `GET /observability/status` | `http` |
| `getCapabilities` | `GET /observability/capabilities` | `http` |
| `getHealth` | `GET /actuator/health` | `raw` |
| `getMetrics` | `GET /actuator/metrics` | `raw` |
| `getMetric` | `GET /actuator/metrics/{name}` | `raw` |
| `getAiHealth` | `GET /ai/health` → AI `/health` | 原生 `fetch`（不经 axios） |

## 构建与部署

```bash
npm run build     # 产出 frontend/dist
```

部署约束三条：

1. **SPA fallback 必需**：路由用 `createWebHistory`，服务器须把未命中的路径回落到 `index.html`，否则刷新 `/traces` 直接 404。
2. **同源反向代理必需**：`/api/**` → Java 8080（去前缀）、`/ai/**` → AI 8000（去前缀）。若不代理，需改 `http.ts` 的 `baseURL` 并处理 CORS——当前代码没有 CORS 兜底。
3. **当前不在容器栈内**：根 `docker-compose.yml` 没有 frontend 服务，前端产物需单独托管（Nginx / 静态站点）。构建时**不要**注入 `VITE_USE_MOCK`。

## 已知限制

以下为当前实现的真实状态，不要按「已完成」理解：

| 项 | 现状 |
| --- | --- |
| 问答流式输出 | 已接通 `fixed` SSE；`langchain` / mock 使用完整 JSON，尚需真实依赖环境验收 |
| 模型管理验收 | 通过 Java 模型管理接口持久化；真实模型连通性、质量、冷加载与显存容量需独立验收，mock 不执行真实推理或重建 |
| RAG 多会话管理 | 真实模式恢复服务端会话和历史。全部可读库按单轮执行，选定单库可追问。模拟模式的会话在刷新后清空。 |
| 前端测试 | `npm run test:sse / test:models / test:uploads` 提供定向契约测试；未配置通用单元测试或浏览器 E2E 框架，另运行 typecheck/build |
| 代码分割 | 路由和设置中的独立功能面板按需加载。库页的表格、抽屉、上传控件及 Agent 的选择框由对应页面引入。 |
| 主题 | 亮 / 暗双主题，顶栏可切「跟随系统 / 亮色 / 暗色」，偏好存 `localStorage['ekip-theme']`；登录页没有顶栏，不能在登录页切换（已存的偏好照常生效） |
| 样式自动化 | 无 CSS 单测、无视觉回归基线；改样式后的唯一门禁是 `npm run typecheck` 与人工对照 |

## 排错

| 现象 | 对因与处置 |
| --- | --- |
| 登录成功但立即被踢回登录页 | `/user/refresh` 失败导致 `forceLogout`。检查后端 refresh 端点、`JWT_SECRET` 是否与签发时一致；若曾多标签页并发刷新，可能是刷新票被轮换后重放 |
| 页面数据全 404 / 代理无效 | 8080 被旧容器占用（本机常见：`ekip-backend`），`/api` 被转发到没有本轮代码的实例。先确认 8080 的占用者，不要直接改 `vite.config.ts` 指向别处 |
| 观测页 401 或指标空白 | `/actuator/metrics`、`loggers`、`env` 等管理面端点需要认证；`getMetrics` 空白还可能是白名单与真实指标名不一致 |
| `/ai/health` 报错、状态页 AI `DOWN` | AI 服务未在 8000 启动，或未从仓库根启动（模块以 `ai.app.*` 导入）。启动命令见根 `README.md` |
| 上传卡住或出现重复文档 | 检查 `Idempotency-Key` 是否透传到后端；确认回执查询端点可用，不要靠重复点击重传 |
| 任务状态不更新 | SSE 需要 Bearer 头，只能走 `fetch`；若代理层缓冲了 `text/event-stream`（Nginx 需 `proxy_buffering off`），事件会被攒着不发 |
| 刷新子路由 404 | 静态服务器缺少 SPA fallback |
| `npm run build` 类型报错但 `dev` 正常 | `dev` 不做类型检查。以 `npm run typecheck` 为准，注意 `noUnusedLocals` / `noUnusedParameters` 会让未使用变量直接失败 |

## 相关文档

- 项目总览与全栈启动：[`../README.md`](../README.md)
- 后端实现：[`../knowledge-platform/README.md`](../knowledge-platform/README.md)
- AI 服务：[`../ai/README.md`](../ai/README.md)
- [运行单元与调用关系](../README.md#架构设计)
