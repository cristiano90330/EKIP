"""用异步与线程安全资源门控限制并发请求进入重型 RAG 阶段。"""

import json
import threading
from time import monotonic

from ai.app.rag.deadline import reset_request_started_at, set_request_started_at
from ai.app.security import is_internal_token_valid


class RagQueryAdmissionMiddleware:
    """Reject excess authenticated queries before FastAPI queues sync work in its threadpool.

    The permit spans the whole ASGI request, including synchronous retrieval and generation. It
    bounds slow model calls as well as database work. A rejected request gets a stable 429
    contract; requests with missing or invalid credentials continue to the router so the normal
    internal-auth dependency still returns 401 even while the service is saturated.
    """

    def __init__(self, app, *, max_inflight: int):
        """保存 ASGI 应用并创建指定上限的并发槽位，用于保护重型问答入口。"""
        self.app = app
        self._slots = threading.BoundedSemaphore(max_inflight)

    async def __call__(self, scope, receive, send):
        """对内部调用的 RAG 请求尝试取得配额，容量满时回传 429，处理结束后归还槽位。

        下游调用 `ai.app.security.is_internal_token_valid`、`ai.app.rag.deadline.set_request_started_at`。
        """
        if (scope.get("type") != "http" or scope.get("method") != "POST"
                or scope.get("path") not in ("/rag/query", "/rag/query/stream")):
            await self.app(scope, receive, send)
            return

        headers = {key.lower(): value for key, value in scope.get("headers", [])}
        token = headers.get(b"x-internal-token", b"").decode("latin-1")
        if not is_internal_token_valid(token):
            # Let FastAPI's dependency own the error response and preserve its 401 semantics.
            await self.app(scope, receive, send)
            return

        started_at_token = set_request_started_at(monotonic())
        if not self._slots.acquire(blocking=False):
            reset_request_started_at(started_at_token)
            request_id = headers.get(b"x-request-id", b"").decode("latin-1")
            payload = json.dumps({
                "detail": {
                    "outcome": "dependency_unavailable",
                    "reasonCode": "query_capacity_rejected",
                    "failedStage": "admission",
                    "requestId": request_id,
                }
            }, separators=(",", ":")).encode("utf-8")
            response_headers = [
                (b"content-type", b"application/json"),
                (b"content-length", str(len(payload)).encode("ascii")),
            ]
            if request_id:
                response_headers.append((b"x-request-id", request_id.encode("latin-1")))
            await send({"type": "http.response.start", "status": 429, "headers": response_headers})
            await send({"type": "http.response.body", "body": payload})
            return

        try:
            await self.app(scope, receive, send)
        finally:
            self._slots.release()
            reset_request_started_at(started_at_token)
