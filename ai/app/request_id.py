"""校验和传递请求关联标识，使 ASGI 响应、日志及下游调用可对应。"""

import re
from uuid import uuid4

from ai.app.llm import telemetry

_SAFE_ID = re.compile(r"[A-Za-z0-9._:-]{1,128}", re.ASCII)
REQUEST_ID_STATE_KEY = "request_id"


def resolve_request_id(incoming: str | None) -> str:
    """校验请求头中的关联标识，非法或缺失时生成内部可使用的新标识。

    上游为 `ai.app.main.unhandled_exception_handler`、`__call__`。
    """
    return incoming if incoming is not None and _SAFE_ID.fullmatch(incoming) else str(uuid4())


class RequestIdMiddleware:
    """Keep ContextVar scope until streaming/background response work completes; never buffer bodies."""

    def __init__(self, app):
        """保存下层 ASGI 应用，供请求标识绑定后继续处理。"""
        self.app = app

    async def __call__(self, scope, receive, send):
        """绑定合法的请求关联标识与入口时刻，包装响应起始消息，并在结束后恢复原有上下文。

        下游调用 `resolve_request_id`、`ai.app.llm.telemetry.set_request_id`。
        """
        if scope.get("type") != "http":
            await self.app(scope, receive, send)
            return

        headers = scope.get("headers", [])
        values = [value for key, value in headers if key.lower() == b"x-request-id"]
        incoming = values[0].decode("latin-1") if len(values) == 1 else None
        request_id = resolve_request_id(incoming)
        scope.setdefault("state", {})[REQUEST_ID_STATE_KEY] = request_id
        # Admission errors also read this header when constructing their existing JSON contract.
        scope["headers"] = [(key, value) for key, value in headers if key.lower() != b"x-request-id"]
        scope["headers"].append((b"x-request-id", request_id.encode("ascii")))
        token = telemetry.set_request_id(request_id)

        async def send_with_request_id(message):
            """为 HTTP 响应起始消息附加当前请求标识，再将消息交给实际 ASGI 发送器。"""
            if message["type"] == "http.response.start":
                response_headers = [(key, value) for key, value in message.get("headers", [])
                                    if key.lower() != b"x-request-id"]
                response_headers.append((b"x-request-id", request_id.encode("ascii")))
                message = {**message, "headers": response_headers}
            await send(message)

        try:
            await self.app(scope, receive, send_with_request_id)
        finally:
            telemetry.reset_request_id(token)
