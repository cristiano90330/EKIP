"""读取 AI 服务环境配置，校验数据库凭据、请求边界和服务启动所需参数。"""

import os

try:
    from dotenv import load_dotenv

    # 依次加载 ai/.env 与仓库根 .env，或直接导出环境变量
    _here = os.path.dirname(os.path.abspath(__file__))   # ai/app
    load_dotenv(os.path.join(_here, "..", ".env"))        # ai/.env
    load_dotenv(os.path.join(_here, "..", "..", ".env"))  # 仓库根 .env
except ImportError:
    pass

# ===== MinIO =====
MINIO_ENDPOINT = os.getenv("MINIO_ENDPOINT", "localhost:9000")
MINIO_ACCESS_KEY = os.getenv("MINIO_ACCESS_KEY", "minioadmin")
MINIO_SECRET_KEY = os.getenv("MINIO_SECRET_KEY", "minioadmin")
MINIO_BUCKET = os.getenv("MINIO_BUCKET", "knowledge")

# ===== 只读 PostgreSQL 检索连接 =====
# Java 先解析知识库授权范围；Python 只按请求中的 knowledgeBaseIds 读取 document_chunk，不做授权判断或写入。
# 使用独立只读凭据并启用只读事务，避免复用业务数据库账号。
# Host/port/database have safe local defaults; identity and password are required and never fall
# back to the backend's potentially privileged POSTGRES_* credentials.
PG_READONLY_HOST = os.getenv("PG_READONLY_HOST", "localhost")
PG_READONLY_PORT = int(os.getenv("PG_READONLY_PORT", "5432"))
PG_READONLY_DB = os.getenv("PG_READONLY_DB", "knowledge")
PG_READONLY_USER = os.getenv("PG_READONLY_USER", "").strip()
PG_READONLY_PASSWORD = os.getenv("PG_READONLY_PASSWORD", "")


def validate_pg_readonly_config() -> None:
    """启动时检查只读数据库身份和密码，缺项即抛出异常，避免检索回退到业务账号。

    上游为 `ai.app.main.lifespan`、`ai.app.retrieval.db._build_pool`。
    """
    if not PG_READONLY_USER or not PG_READONLY_PASSWORD:
        raise RuntimeError("PG_READONLY_USER and PG_READONLY_PASSWORD are required")

# ===== Java → Python 内部鉴权 =====
# 查询请求携带调用方提供的知识库 ID，因此只允许持有共享内部令牌的后端调用；未配置时服务拒绝启动。
INTERNAL_API_TOKEN = os.getenv("INTERNAL_API_TOKEN", "")

# Chunking is infrastructure configuration. Embedding dimensions belong to index scopes.
CHUNK_SIZE = int(os.getenv("CHUNK_SIZE", "800"))
CHUNK_OVERLAP = int(os.getenv("CHUNK_OVERLAP", "100"))

# 每路召回有独立等待上限；单路超时可按部分结果继续处理，整条查询仍受总截止时间约束。
RETRIEVAL_LEG_TIMEOUT_MS = int(os.getenv("RETRIEVAL_LEG_TIMEOUT_MS", "2000"))

# One query-wide budget. Java supplies the requested budget and leaves time to return a terminal response;
# ingest/parse calls do not use this setting. Zero and negative values are rejected at startup.
RAG_QUERY_DEADLINE_MS = int(os.getenv("RAG_QUERY_DEADLINE_MS", "270000"))
RAG_QUERY_MAX_INFLIGHT = int(os.getenv("RAG_QUERY_MAX_INFLIGHT", "4"))
RAG_QUERY_MAX_TOP_K = int(os.getenv("RAG_QUERY_MAX_TOP_K", "20"))
RAG_QUERY_MAX_RECALL_N = int(os.getenv("RAG_QUERY_MAX_RECALL_N", "100"))
RAG_QUERY_MAX_HISTORY_MESSAGES = int(os.getenv("RAG_QUERY_MAX_HISTORY_MESSAGES", "32"))
RAG_QUERY_MAX_HISTORY_CHARS = int(os.getenv("RAG_QUERY_MAX_HISTORY_CHARS", "30000"))
RAG_RETRIEVAL_MAX_INFLIGHT = int(os.getenv("RAG_RETRIEVAL_MAX_INFLIGHT", "4"))

# Evidence policy remains a deployment choice; calibrated thresholds are model records.
RAG_EVIDENCE_POLICY_VERSION = os.getenv("RAG_EVIDENCE_POLICY_VERSION", "fail-closed-v1")
RAG_EVIDENCE_MODE = os.getenv("RAG_EVIDENCE_MODE", "score").strip()

# Local-only chat connectivity and actual text-stream verification. Defaults off.
EKIP_ENV = os.getenv("EKIP_ENV", "production").strip().lower()
RAG_DEV_TEST_MODE = os.getenv("RAG_DEV_TEST_MODE", "false").strip().lower() == "true"


def validate_rag_limits() -> None:
    """启动时校验 RAG 请求与并发入口的配置区间，拒绝无法执行的预算或运行模式。

    上游为 `ai.app.main.lifespan`。
    """
    if RAG_DEV_TEST_MODE and EKIP_ENV != "development":
        raise RuntimeError("RAG_DEV_TEST_MODE requires EKIP_ENV=development")
    if RAG_EVIDENCE_MODE not in {"score", "support"}:
        raise RuntimeError("RAG_EVIDENCE_MODE must be score or support")
    if RAG_QUERY_DEADLINE_MS <= 0:
        raise RuntimeError("RAG_QUERY_DEADLINE_MS must be positive")
    if RAG_QUERY_MAX_TOP_K <= 0 or RAG_QUERY_MAX_RECALL_N <= 0:
        raise RuntimeError("RAG_QUERY_MAX_TOP_K and RAG_QUERY_MAX_RECALL_N must be positive")
    if RAG_QUERY_MAX_INFLIGHT <= 0:
        raise RuntimeError("RAG_QUERY_MAX_INFLIGHT must be positive")
    if RAG_QUERY_MAX_HISTORY_MESSAGES <= 0 or RAG_QUERY_MAX_HISTORY_CHARS <= 0:
        raise RuntimeError("RAG_QUERY_MAX_HISTORY_MESSAGES and RAG_QUERY_MAX_HISTORY_CHARS must be positive")
    if RAG_RETRIEVAL_MAX_INFLIGHT < 2:
        raise RuntimeError("RAG_RETRIEVAL_MAX_INFLIGHT must be at least 2")

# ===== RAG 阶段观测 =====
# 阶段信息通过查询响应、结构化日志和可选的 OpenTelemetry 导出；观测初始化失败不阻止服务启动。
OTEL_ENABLED = os.getenv("OTEL_ENABLED", "false").lower() == "true"
OTEL_SERVICE_NAME = os.getenv("OTEL_SERVICE_NAME", "ekip-ai")
# OTLP/HTTP 的 traces 端点（Langfuse 走 OTLP 时填自托管地址，如 http://langfuse:3000/api/public/otel/v1/traces）
OTEL_EXPORTER_OTLP_ENDPOINT = os.getenv(
    "OTEL_EXPORTER_OTLP_ENDPOINT", "http://127.0.0.1:4318/v1/traces"
)
# 每条 /rag/query 打一行「各阶段耗时 + token」的结构化日志（默认开）。
# 无 trace 存储时的最低成本可观测；默认开是因为它只在一次请求结束时打一行，噪声可控。
RAG_STAGE_LOG_ENABLED = os.getenv("RAG_STAGE_LOG_ENABLED", "true").lower() == "true"

# History shaping is a business budget, not a model selector.
RAG_GENERATE_HISTORY_TOKENS = int(os.getenv("RAG_GENERATE_HISTORY_TOKENS", "300"))

# 是否启用受内部令牌保护的 Prometheus /metrics 端点。
METRICS_ENABLED = os.getenv("METRICS_ENABLED", "true").lower() == "true"
