# 观测运行说明

默认使用产品 Trace、指标和阶段日志。外部追踪平台按需启用，业务启动不要求 Collector、ClickHouse 或 Langfuse。

## 观测输出及消费者

| 输出 | 产生位置 | 消费者 | 默认行为 |
| --- | --- | --- | --- |
| 产品 Trace | Java `RagStageTracer / AgentTracer / TraceStore`，Python 响应阶段回填 | 控制台 Trace 列表与瀑布图；按认证用户隔离 | 保留，默认进程内最近 1000 条，重启不持久化 |
| 聚合指标 | Java Micrometer/Actuator、Python Prometheus 指标端点 | 控制台指标页、外部采集器 | 保留；Compose 没有额外部署 Prometheus Server |
| 请求与阶段日志 | requestId/traceId/spanId，Python `stages[]` 和阶段摘要 | 本地排障和跨服务关联 | 保留，不依赖外部追踪平台 |
| OTLP 链路导出 | Java OTel exporter、Python stage exporter | Collector → debug/JSONL/Langfuse | 默认关闭，仅显式接入时开启 |

指标与链路追踪各有职责。S3 减少默认运行依赖，未更改产品 Trace 的字段、指标名称、权限或 readiness 语义。

## 默认业务方案

```powershell
docker compose up -d --build
```

主文件 `docker-compose.yml` 定义 PostgreSQL、Redis、MinIO、一次性建业务桶任务、AI、Java 后端，共 6 个服务。前端仍按前端 README 单独启动。原来的 `backend minio-init` 定向启动命令也可使用。

主文件固定 `OTEL_ENABLED=false`，不读取 Langfuse 密钥；`METRICS_ENABLED / RAG_STAGE_LOG_ENABLED` 默认 true。Java 保留 Tracer 和采样配置，只将 `management.otlp.tracing.export.enabled` 默认设为 false，避免产品观测装配依赖被一并关掉。

## 可选完整观测方案

1. 在现有根 `.env` 中填写模板 E2/E3 的安全配置和首次初始化项。`LANGFUSE_NEXTAUTH_SECRET / LANGFUSE_SALT / LANGFUSE_ENCRYPTION_KEY / LANGFUSE_BASIC_AUTH` 是可选 Compose 的必需项；Basic 值为项目 public key 与 secret key 的 Base64 组合，不带 `Basic` 前缀。不要更换已有部署的加密主密钥来处理启动问题。
2. 首次使用时先启动业务方案。确认独立 `langfuse` 数据库尚不存在后，再显式创建（已有则跳过）：

   ```powershell
   docker compose exec postgres psql -U postgres -d postgres -c 'CREATE DATABASE langfuse;'
   ```

3. 显式合并观测文件并启动：

   ```powershell
   docker compose -f docker-compose.yml -f docker-compose.observability.yml up -d --build
   ```

可选文件新增 Collector、ClickHouse、Langfuse Web/Worker，合并后共 10 个服务；覆盖建桶任务以创建 `langfuse` 桶，并让 Web/Worker 等待该任务成功。应用端显式开启导出，默认目标为 `http://otel-collector:4318/v1/traces`。Collector 配置见 [`docker/otel-collector-config.yaml`](../docker/otel-collector-config.yaml)，输出到 debug、JSONL 和 Langfuse；Basic 凭据通过环境注入。

使用同一项目名和相同 `clickhouse_data / clickhouse_logs` 卷名、`logs/otel` 路径与镜像版本；拆分文件不会迁移或清理已有数据。Langfuse 页面仍为 `http://127.0.0.1:3000`。已有观测容器不会因文件拆分自动停止，本轮也未操作现有容器；切换正在运行的部署需另行安排。

开发版本默认按当前支持的协议重新配置模型和新知识库。仅在明确复用 B0 旧索引时，才核对 [S2 数据复用说明](architecture/contracts/model-agent-capability-matrix.md#旧配置迁移)，避免混用向量空间。本文中的命令不是助手自动执行数据库或部署操作的授权。

## 裸跑配置与验证边界

裸跑 Python 的 `OTEL_ENABLED` 默认 false，进程环境与现有 dotenv 的显式配置仍优先；Java 不自动导入根 dotenv。要接入已部署的 Collector，给两个服务分别设置 `OTEL_ENABLED=true`，并设置 Python 的 `OTEL_EXPORTER_OTLP_ENDPOINT` 与 Java 的 `OTEL_TRACES_ENDPOINT`。宿主机默认地址为 `http://127.0.0.1:4318/v1/traces`；容器须使用从容器可达的地址。开关在启动时读取。

两种 Compose 方案复用现有应用镜像与 OTel 库，不增加镜像构建分支。`/health` 只表示存活；Python `/ready /metrics` 继续要求内部令牌，Java Actuator 的既有访问规则不变。OTLP 初始化或上报失败不影响业务阶段记录，不代表远端观测平台已可用。

support 门禁就绪要求当前模型的传输和完整审查观测均有效。模型 HTTP 成功但审查输出无效时为 `not_ready`，合法拒绝无证据问题仍可证明审查协议就绪。审查记录按现有不可变模型身份隔离，300 秒后为 `unknown`，读取不会触发模型调用。普通 `/ready` 没有用户模型快照，返回 `unknown / ready=null`。

配置回归入口为 `tests/test_observability_compose.py`，使用虚拟夹具运行 `docker compose config`。Python 阶段契约在 `ai/tests/test_stage_trace_contract.py`，Java 出口装配在 `TracingExportConfigurationTest`。这些检查使用测试配置。Langfuse 接收、数据库初始化、鉴权和容量需要在目标部署里检查。
