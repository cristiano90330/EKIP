"""维护请求上下文里的关联标识，供日志和模型调用追踪读取。"""

from contextvars import ContextVar, Token


_request_id: ContextVar[str] = ContextVar("request_id", default="-")


def get_request_id() -> str:
    """读取当前请求关联 ID，供中间件、模型请求头和阶段日志共用。

    上游为 `ai.app.rag.pipeline._development_test_response`、`ai.app.rag.pipeline._failure_response`。
    """
    return _request_id.get()


def set_request_id(rid: str) -> Token:
    """为当前上下文写入关联 ID，返回中间件在请求结束时恢复原值所需的 Token。

    上游为 `ai.app.main.unhandled_exception_handler`、`ai.app.request_id.RequestIdMiddleware.__call__`。
    """
    return _request_id.set(rid)


def reset_request_id(token: Token) -> None:
    """使用 set_request_id 返回的 Token 恢复之前的请求上下文。

    上游为 `ai.app.main.unhandled_exception_handler`、`ai.app.request_id.RequestIdMiddleware.__call__`。
    """
    _request_id.reset(token)
