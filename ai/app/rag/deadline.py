"""向问答阶段传播绝对截止时间，按剩余预算限制子调用并区分超时异常。"""

from contextlib import contextmanager
from contextvars import ContextVar, Token
from time import monotonic
from urllib.error import URLError

_deadline_at: ContextVar[float | None] = ContextVar("rag_deadline_at", default=None)
_request_started_at: ContextVar[float | None] = ContextVar("rag_request_started_at", default=None)


def set_request_started_at(value: float | None):
    """为请求记下单调时钟起点，返回 ASGI 入口恢复上下文所需的 Token。

    上游为 `ai.app.models.cancellation.ModelCancellationMiddleware.__call__`、`ai.app.rag.admission.RagQueryAdmissionMiddleware.__call__`。
    """
    return _request_started_at.set(value)


def reset_request_started_at(token) -> None:
    """使用上下文 Token 恢复先前的请求起点，供 ASGI 入口清理。

    上游为 `ai.app.models.cancellation.ModelCancellationMiddleware.__call__`、`ai.app.rag.admission.RagQueryAdmissionMiddleware.__call__`。
    """
    _request_started_at.reset(token)


def current_request_started_at() -> float | None:
    """读取请求的单调时钟起点，供查询预算计入入口等待时间。

    上游为 `ai.app.rag.pipeline.run_query`、`ai.app.rag.pipeline._stream_events`。
    """
    return _request_started_at.get()


class QueryDeadlineExceeded(TimeoutError):
    """The shared /rag/query deadline expired before an operation could finish."""


def current_deadline_at() -> float | None:
    """返回当前执行范围内的最晚单调时钟时间，未建立预算时为空。

    上游为 `ai.app.llm.guard.ModelGate._run`、`ai.app.llm.guard.ModelGate.stream`。
    """
    return _deadline_at.get()


def remaining_seconds() -> float | None:
    """将绝对截止时间转换为当前剩余秒数，供底层模型和数据库等待使用。

    上游为 `ai.app.models.runtime.ResourceLimits.acquire`，下游调用 `current_deadline_at`。
    """
    deadline = current_deadline_at()
    return None if deadline is None else max(0.0, deadline - monotonic())


def bounded_timeout(configured_seconds: float) -> float:
    """根据调用配置与剩余全链路预算确定本次等待上限，预算耗尽时抛出超时。

    上游为 `ai.app.models.runtime.ModelRuntime._json`，下游调用 `remaining_seconds`。
    """
    remaining = remaining_seconds()
    if remaining is None:
        return configured_seconds
    if remaining <= 0:
        raise QueryDeadlineExceeded("query deadline expired before IO")
    return min(configured_seconds, remaining)


def deadline_expired() -> bool:
    """检查当前作用域的截止时间是否耗尽，为阶段跳过和终态转换提供信号。

    上游为 `ai.app.embedding.embedding_service.EmbeddingService.embed_batch`，下游调用 `remaining_seconds`。
    """
    remaining = remaining_seconds()
    return remaining is not None and remaining <= 0


def is_timeout_exception(exc: BaseException) -> bool:
    """识别由请求预算或底层传输抛出的超时异常，供问答响应统一分类。

    上游为 `ai.app.observability.readiness._classify_probe_error`。
    """
    if isinstance(exc, TimeoutError):
        return True
    if isinstance(exc, URLError) and isinstance(exc.reason, TimeoutError):
        return True
    return any(base.__name__ in {"APITimeoutError", "TimeoutException", "ConnectTimeout", "ReadTimeout"}
               for base in type(exc).__mro__)


@contextmanager
def query_deadline(budget_ms: int, *, started_at: float | None = None):
    """用请求预算和起点建立截止时间，新作用域受上层截止时间约束，退出后恢复原值。

    上游为 `ai.app.models.context.model_execution`，下游调用 `current_request_started_at`、`current_deadline_at`。
    """
    start = started_at if started_at is not None else current_request_started_at()
    proposed = (start if start is not None else monotonic()) + max(1, budget_ms) / 1000.0
    existing = current_deadline_at()
    effective = proposed if existing is None else min(existing, proposed)
    token: Token = _deadline_at.set(effective)
    try:
        yield current_deadline_at()
    finally:
        _deadline_at.reset(token)


@contextmanager
def inherit_deadline(deadline_at: float | None):
    """将调用方的绝对截止时间传播到当前线程的处理范围，结束时恢复上下文。

    上游为 `ai.app.llm.guard.ModelGate._run`、`ai.app.llm.guard.ModelGate.stream`。
    """
    token: Token = _deadline_at.set(deadline_at)
    try:
        yield
    finally:
        _deadline_at.reset(token)
