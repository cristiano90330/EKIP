"""装配 FastAPI 的生命周期、内部鉴权路由、异常映射、健康和指标入口。"""

import logging
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, Response

from ai.app.config import (
    INTERNAL_API_TOKEN,
    METRICS_ENABLED,
    RAG_QUERY_MAX_INFLIGHT,
    validate_rag_limits,
    validate_pg_readonly_config,
)
from ai.app.agent.router import router as agent_router
from ai.app.models.router import router as models_router
from ai.app.models.resolution import ModelConfigurationError
from ai.app.models.runtime import ModelCallError
from ai.app.models.cancellation import ModelCancellationMiddleware
from ai.app.rag.deadline import QueryDeadlineExceeded
from ai.app.parser.router import router as parser_router
from ai.app.embedding.router import router as embedding_router
from ai.app.rag.router import router as rag_router
from ai.app.rag.admission import RagQueryAdmissionMiddleware
from ai.app.observability.readiness import readiness_service
from ai.app.evaluation.router import router as evaluation_router
from ai.app.security import require_internal_token
from ai.app.llm import telemetry
from ai.app.request_id import RequestIdMiddleware, resolve_request_id

logger = logging.getLogger("ekip.app")

_LOG_FORMAT = "%(asctime)s %(levelname)s [%(name)s] %(message)s"


def _configure_logging() -> None:
    """从服务环境配置日志等级和格式，在应用装配阶段初始化日志输出。"""
    root = logging.getLogger()
    root.setLevel(logging.INFO)
    if not root.handlers:
        handler = logging.StreamHandler()
        handler.setFormatter(logging.Formatter(_LOG_FORMAT))
        root.addHandler(handler)


_configure_logging()


# 启动时校验基础配置并准备编排器；后台仅探测存储，不执行模型请求。
@asynccontextmanager
async def lifespan(app: FastAPI):
    # 查询端只读取 Java 授权范围内的文档块。
    """在 FastAPI 启停时准备配置、问答引擎和依赖探针，并在退出时清理资源。

    下游调用 `ai.app.config.validate_pg_readonly_config`、`ai.app.config.validate_rag_limits`。
    """
    if not INTERNAL_API_TOKEN:
        raise RuntimeError(
            "INTERNAL_API_TOKEN 未设置：拒绝以无鉴权状态启动 AI 服务。"
            "生成一个（如 `openssl rand -hex 32`）后写入环境变量或仓库根 .env；"
            "compose 部署时该值由 docker-compose.yml 从 .env 透传给 ai 与 backend 两侧。"
        )

    # Validate dedicated read-only credentials before serving requests.
    validate_pg_readonly_config()
    validate_rag_limits()

    # Prepare both selectable pipeline engines before accepting requests.
    from ai.app.rag.pipeline import prepare_pipeline_engines
    prepare_pipeline_engines()

    # Model selection exists only on authenticated Java requests. Startup never
    # prewarms or probes a model, so readiness cannot evict a user's GPU model.
    readiness_service.start()
    try:
        yield
    finally:
        readiness_service.stop()


app = FastAPI(
    title="Knowledge AI Service",
    version="1.0.0",
    lifespan=lifespan,
)
app.add_middleware(RagQueryAdmissionMiddleware, max_inflight=RAG_QUERY_MAX_INFLIGHT)
app.add_middleware(ModelCancellationMiddleware)
app.add_middleware(RequestIdMiddleware)


@app.exception_handler(RequestValidationError)
async def validation_error_handler(request: Request, exc: RequestValidationError):
    # FastAPI normally includes the submitted input. A nested model snapshot may
    # contain credentials even when Pydantic's repr/output excludes SecretStr.
    """将 FastAPI 的输入验证错误转换为内部调用方可读的 HTTP 响应。"""
    errors = [{"loc": item["loc"], "type": item["type"], "msg": item["msg"]}
              for item in exc.errors()]
    return JSONResponse(status_code=400 if request.url.path.startswith("/models/") else 422,
                        content={"detail":errors})


@app.exception_handler(ModelConfigurationError)
async def model_configuration_error(request: Request, exc: ModelConfigurationError):
    """将模型请求配置异常转换为内部 API 的结构化错误响应。"""
    management = request.url.path.startswith("/models/")
    return JSONResponse(status_code=400 if management else 503,
        content={"detail":{"outcome":"dependency_unavailable", "reasonCode":"model_configuration_invalid" if management else "model_not_configured",
                           "message":str(exc)}})


@app.exception_handler(ModelCallError)
async def model_call_error(request: Request, exc: ModelCallError):
    """将模型运行时异常的原因码转换为 HTTP 错误，供上游区别依赖失败。"""
    return JSONResponse(status_code=503, content={"detail":{"outcome":"dependency_unavailable",
        "reasonCode":exc.code,"message":"模型调用暂时不可用"}})


@app.exception_handler(QueryDeadlineExceeded)
async def model_deadline_error(request: Request, exc: QueryDeadlineExceeded):
    """在查询预算耗尽时返回超时响应，供 Java 映射问答终态。"""
    return JSONResponse(status_code=504, content={"detail":{"outcome":"timeout",
        "reasonCode":"query_deadline_exceeded","message":"模型调用超过总预算"}})


@app.exception_handler(Exception)
async def unhandled_exception_handler(request: Request, exc: Exception):
    """记录未映射异常的调用信息，并返回不包含异常细节的服务端错误。

    下游调用 `ai.app.request_id.resolve_request_id`、`ai.app.llm.telemetry.set_request_id`。
    """
    # ServerErrorMiddleware handles this outside user middleware, after its ContextVar cleanup.
    request_id = resolve_request_id(getattr(request.state, "request_id", None))
    token = telemetry.set_request_id(request_id)
    try:
        logger.exception("unhandled exception on %s %s request_id=%s",
                         request.method, request.url.path, request_id)
        return JSONResponse(
            status_code=500,
            content={"detail": "服务内部错误", "request_id": request_id},
            headers={"X-Request-ID": request_id},
        )
    finally:
        telemetry.reset_request_id(token)


# 统一保护业务 router；内部令牌验证服务调用身份，知识库授权范围仍由 Java 解析并随请求传入。
_AUTH = [Depends(require_internal_token)]

app.include_router(parser_router, dependencies=_AUTH)
app.include_router(embedding_router, dependencies=_AUTH)
app.include_router(rag_router, dependencies=_AUTH)
app.include_router(evaluation_router, dependencies=_AUTH)
app.include_router(agent_router, dependencies=_AUTH)
app.include_router(models_router, dependencies=_AUTH)


# 返回进程存活状态，供部署和隔离运行器检查 HTTP 服务是否已启动。
@app.get("/health")
def health():
    """返回进程存活状态；此端点不检查外部依赖，也不要求内部令牌。"""
    return {"status": "ok"}


# 返回 ReadinessService 汇总的能力快照，供 Java 查询依赖状态。
# 下游调用 `ai.app.observability.readiness.readiness_service.snapshot`。
@app.get("/ready", dependencies=_AUTH)
def readiness():
    """返回最近的依赖能力快照；不执行请求内探测，也不决定进程存活。"""
    return readiness_service.snapshot()


# 输出 Prometheus 可读取的当前进程指标。
@app.get("/metrics", dependencies=_AUTH)
def metrics():
    """在启用时返回 Prometheus 指标；本端点与其他业务路由一样受内部令牌保护。"""
    if not METRICS_ENABLED:
        raise HTTPException(status_code=404, detail="metrics disabled (METRICS_ENABLED=false)")
    # 延迟 import：没装 prometheus-client 时不影响其它接口
    from prometheus_client import CONTENT_TYPE_LATEST, generate_latest
    return Response(generate_latest(), media_type=CONTENT_TYPE_LATEST)
