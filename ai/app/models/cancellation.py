"""在 ASGI 连接和模型调用之间共享取消信号，缩短断连后的等待。"""

import asyncio
from contextlib import suppress, contextmanager
from contextvars import ContextVar
from threading import Event
from time import monotonic

from ai.app.rag.deadline import (QueryDeadlineExceeded, reset_request_started_at,
                                 set_request_started_at)

_cancelled: ContextVar[Event | None] = ContextVar("model_request_cancelled", default=None)


def current_cancellation_event() -> Event | None:
    """返回当前请求的取消事件，让 RAG 流的生产者和消费者同时收到断连信号。"""
    return _cancelled.get()


def check_cancelled():
    """读取当前模型操作的取消信号，已取消时抛出对应异常使工作线程停止。

    上游为 `ai.app.embedding.embedding_service.EmbeddingService.embed_batch`，下游调用 `ai.app.rag.deadline.QueryDeadlineExceeded`。
    """
    event = _cancelled.get()
    if event is not None and event.is_set():
        raise QueryDeadlineExceeded("model execution was cancelled")


def wait_or_cancel(seconds):
    """等待退避时间或当前取消信号，取消时提前结束等待并抛出异常。

    上游为 `ai.app.models.runtime.ModelRuntime._backoff`，下游调用 `check_cancelled`。
    """
    event = _cancelled.get()
    if event is None:
        Event().wait(seconds)
    elif event.wait(seconds):
        check_cancelled()


@contextmanager
def cancellation_scope(event):
    """为下游模型操作绑定取消事件，并在离开当前请求作用域时还原旧值。

    上游为 `ai.app.rag.router._progress_stream.produce`、`__call__`。
    """
    token = _cancelled.set(event)
    try:
        yield
    finally:
        _cancelled.reset(token)


class ModelCancellationMiddleware:
    """Listen for disconnect while sync inference waits, with bounded body buffering.

    A single ASGI receiver forwards request messages in order. Its one-message
    buffer also lets it observe a disconnect after the request body is consumed.
    Starlette's streaming disconnect listener receives the same messages.
    """

    def __init__(self, app):
        """保存待包装的 ASGI 应用，供请求和断连处理向下转发。"""
        self.app = app

    async def __call__(self, scope, receive, send):
        """为模型 HTTP 路由建立独立取消事件，以断连消息或应用结束为信号释放等待中的操作。

        下游调用 `pump`、`ai.app.rag.deadline.set_request_started_at`。
        """
        path = scope.get("path", "")
        if scope.get("type") != "http" or not path.startswith(
                ("/agent/", "/rag/", "/embedding", "/models/test", "/evaluation/", "/parser/")):
            await self.app(scope, receive, send)
            return
        event, messages = Event(), asyncio.Queue(maxsize=1)

        async def pump():
            """将 ASGI 输入消息送入应用接收队列，遇到 HTTP 断开时同步标记取消。

            上游为 `__call__`。
            """
            while True:
                message = await receive()
                if message["type"] == "http.disconnect":
                    event.set()
                await messages.put(message)
                if message["type"] == "http.disconnect":
                    return

        worker = asyncio.create_task(pump())
        started = set_request_started_at(monotonic())
        try:
            with cancellation_scope(event):
                await self.app(scope, messages.get, send)
        finally:
            reset_request_started_at(started)
            event.set()
            worker.cancel()
            with suppress(asyncio.CancelledError):
                await worker
