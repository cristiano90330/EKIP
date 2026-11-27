"""受理内部 RAG 同步及 SSE 请求，控制并发并交付处理阶段和终态。"""

import json
from contextvars import copy_context
from concurrent.futures import ThreadPoolExecutor
from queue import Queue, Empty, Full
from threading import BoundedSemaphore, Event

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import StreamingResponse

from ai.app.embedding.errors import EmbeddingDimensionError, EmbeddingUnavailableError
from ai.app.rag.pipeline import run_query, run_query_stream
from ai.app.rag.schema import RagQueryRequest, RagQueryResponse
from ai.app.retrieval.db import ReadOnlyPgError
from ai.app.models.resolution import ModelConfigurationError
from ai.app.security import is_internal_token_valid
from ai.app.llm.context_input import context_progress
from ai.app.models.cancellation import cancellation_scope, check_cancelled, current_cancellation_event

router = APIRouter(prefix="/rag", tags=["rag"])
_stream_workers = ThreadPoolExecutor(max_workers=4, thread_name_prefix="rag-progress")
_stream_slots = BoundedSemaphore(4)


def _progress_stream(request):
    """用有界队列连接提交问答的工作线程与 SSE 消费者，使长上下文整理能提前报告进展。

    上游为 `rag_query_stream`。工作线程继承请求取消信号，断连时停止生成和 SSE 消费。
    """
    stopped = current_cancellation_event() or Event()
    if stopped.is_set():
        return
    if not _stream_slots.acquire(blocking=False):
        yield {"event": "error", "data": {"outcome": "dependency_unavailable",
               "reasonCode": "query_capacity_exceeded"}}
        return

    # 将模型工作线程和流式消费者解耦，让证据整理期间的进度能够及时发送，并对积压事件设上限。
    queue, context = Queue(maxsize=16), copy_context()

    def put(item):
        """将问答事件放入流式队列，在浏览器断连或流终止后退出等待。

        上游为 `produce`。
        """
        while not stopped.is_set():
            try:
                queue.put(item, timeout=0.1)
                return
            except Full:
                continue

    def produce():
        """在当前请求的模型和取消上下文推进问答迭代器，将阶段、错误与终止信号写入队列。

        下游调用 `ai.app.models.cancellation.cancellation_scope`、`ai.app.llm.context_input.context_progress`。
        """
        iterator = None
        try:
            with cancellation_scope(stopped), context_progress(
                    lambda data: put(("event", {"event": "context", "data": data}))):
                iterator = run_query_stream(request)
                for event in iterator:
                    check_cancelled()
                    put(("event", event))
        except Exception as exc:
            put(("error", exc))
        finally:
            try:
                if iterator is not None:
                    iterator.close()
            finally:
                put(("end", None))
                _stream_slots.release()

    try:
        _stream_workers.submit(context.run, produce)
    except BaseException:
        _stream_slots.release()
        raise
    try:
        while not stopped.is_set():
            try:
                kind, value = queue.get(timeout=0.1)
            except Empty:
                continue
            if stopped.is_set() or kind == "end":
                return
            if kind == "error":
                raise value
            yield value
            if value["event"] in {"done", "error"}:
                return
    finally:
        stopped.set()


class _DuplicateJsonFieldError(ValueError):
    pass


def _unique_object_pairs(pairs: list[tuple[str, object]]) -> dict[str, object]:
    """将 JSON 键值对合并为对象，发现同一字段重复出现时抛出协议错误。"""
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise _DuplicateJsonFieldError(key)
        result[key] = value
    return result


async def reject_duplicate_json_fields(request: Request) -> None:
    """在请求体进入业务校验前拒绝重复的 JSON 字段。

    下游调用 `ai.app.security.is_internal_token_valid`。
    """
    if not is_internal_token_valid(request.headers.get("x-internal-token")):
        return

    try:
        json.loads(await request.body(), object_pairs_hook=_unique_object_pairs)
    except _DuplicateJsonFieldError as exc:
        raise HTTPException(
            status_code=422,
            detail={"code": "duplicate_json_field", "message": "请求 JSON 不允许重复字段"},
        ) from exc
    except (json.JSONDecodeError, UnicodeDecodeError):
        # Let FastAPI's normal body parser return its existing malformed-JSON response.
        return


_STRICT_JSON = [Depends(reject_duplicate_json_fields)]


# 接收 Java 同步问答请求，申请并发配额并返回流水线生成的标准响应。
# 下游调用 `ai.app.rag.pipeline.run_query`。
@router.post("/query", response_model=RagQueryResponse, dependencies=_STRICT_JSON)
def rag_query(request: RagQueryRequest):
    """接收完整 JSON 问答请求，委托 run_query 执行并返回 RagQueryResponse。

    将数据库及 Embedding 依赖异常映射为 503，维度错误映射为 422，非法运行参数映射为 400；
    管线已表达为四态结果的响应继续保留其业务状态，不因 HTTP 成功就认定生成成功。
    """
    try:
        return run_query(request)
    except ReadOnlyPgError as e:
        raise HTTPException(status_code=503, detail="只读检索库不可用") from e
    except EmbeddingUnavailableError as e:
        raise HTTPException(status_code=503, detail=f"嵌入服务不可用: {e}") from e
    except EmbeddingDimensionError as e:
        raise HTTPException(status_code=422, detail=f"嵌入维度配置错误: {e}") from e
    except ModelConfigurationError:
        raise
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e)) from e


def encode_sse(event: dict) -> str:
    """将流水线产生的事件对象编码为浏览器和 Java 网关可解析的 SSE 文本。

    上游为 `rag_query_stream`。
    """
    return f"event: {event['event']}\ndata: {json.dumps(event['data'], ensure_ascii=False, separators=(',', ':'))}\n\n"


# 接收 Java 流式问答请求，建立事件生成器并以 SSE 交付结果。
# 下游调用 `ai.app.rag.pipeline.run_query_stream`、`encode_sse`。
@router.post("/query/stream", dependencies=_STRICT_JSON)
def rag_query_stream(request: RagQueryRequest):
    """先调用 run_query_stream 执行入口校验，再由 StreamingResponse 惰性迭代事件并推送。

    HTTP 响应开始前的参数错误返回 400。fixed 和 LangChain 都以 done 或 error 表达终态，
    LangChain 的正文 delta 只供客户端在完成前预览。
    """
    try:
        run_query_stream(request)  # Keep validation before response headers.
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e)) from e
    return StreamingResponse((encode_sse(event) for event in _progress_stream(request)),
                             media_type="text/event-stream",
                             headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})
