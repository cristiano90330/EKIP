"""验证关联标识的校验、响应回传和上下文恢复。"""

import asyncio
import logging
from uuid import UUID

import pytest
from fastapi.testclient import TestClient

from ai.app import main as main_module
from ai.app.llm import telemetry
from ai.app.observability import stage_trace
from ai.app.request_id import RequestIdMiddleware
from ai.app.rag.admission import RagQueryAdmissionMiddleware
from ai.app.rag import pipeline
from ai.app.retrieval.retriever import Candidate
from ai.tests.conftest import AUTH_HEADERS


@pytest.fixture(autouse=True)
def no_telemetry_network(monkeypatch):
    # Request correlation tests retain stage collection/logging without a live OTLP exporter.
    """为需要该夹具的用例准备输入和隔离状态。输入为 `monkeypatch`。"""
    monkeypatch.setattr(stage_trace, "_get_tracer", lambda: None)


def test_http_rejections_and_generated_ids_have_response_header():
    """验证 `denied.status_code` 等于 `401`，且 `denied.headers['X-Request-ID']` 等于 `'java-inbound'`。"""
    client = TestClient(main_module.app, raise_server_exceptions=False)
    denied = client.post("/rag/query", json={}, headers={"X-Request-ID": "java-inbound"})
    assert denied.status_code == 401
    assert denied.headers["X-Request-ID"] == "java-inbound"
    invalid = client.post("/rag/query", json={}, headers={**AUTH_HEADERS, "X-Request-ID": "java-validation"})
    assert invalid.status_code == 422
    assert invalid.headers["X-Request-ID"] == "java-validation"
    generated = [client.get("/health", headers={"X-Request-ID": value}).headers["X-Request-ID"]
                 for value in ("", "unsafe id", "x" * 129)]
    generated.append(client.get("/health").headers["X-Request-ID"])
    assert len(set(generated)) == len(generated)
    for value in generated:
        assert str(UUID(value)) == value


@pytest.mark.parametrize("incoming", ["java-error", "unsafe id", None])
def test_unhandled_exception_keeps_same_id_in_header_body_and_log(monkeypatch, caplog, incoming):
    """验证 `response.status_code` 等于 `500`，且 `request_id` 等于 `incoming`。"""
    from ai.app.parser import router as parser_module
    from ai.app.parser import pipeline as parser_pipeline
    monkeypatch.setattr(parser_module, "minio_service", type("Storage", (), {
        "download_file": staticmethod(lambda *args: None)})())
    monkeypatch.setattr(parser_pipeline, "_parse_by_ext", lambda *args: [])
    monkeypatch.setattr(parser_pipeline, "chunk_blocks", lambda *args, **kwargs: (_ for _ in ()).throw(RuntimeError("boom")))
    caplog.set_level(logging.ERROR)
    client = TestClient(main_module.app, raise_server_exceptions=False, headers=AUTH_HEADERS)
    headers = {} if incoming is None else {"X-Request-ID": incoming}
    response = client.post("/parser/object", headers=headers, json={
        "documentId": 1, "objectName": "x.pdf", "bucketName": "b", "fileExtension": "pdf"})
    assert response.status_code == 500
    request_id = response.headers["X-Request-ID"]
    if incoming == "java-error":
        assert request_id == incoming
    else:
        assert str(UUID(request_id)) == request_id
    assert response.json()["request_id"] == request_id
    assert f"request_id={request_id}" in caplog.text


@pytest.mark.parametrize("engine", ["fixed", "langchain"])
@pytest.mark.parametrize("has_matches", [False, True])
def test_rag_body_and_header_share_request_id_inside_sync_pipeline(monkeypatch, engine, has_matches):
    """验证 `response.status_code` 等于 `200`，且 `response.headers['X-Request-ID']` 等于 `'java-rag-request'`。"""
    def retrieve(*args, **kwargs):
        # FastAPI runs the synchronous route/pipeline in its worker pool.
        """委托 `Candidate` 完成当前操作，无直接返回值。输入为 `args`、`kwargs`。下游调用 `ai.app.llm.telemetry.get_request_id`。"""
        assert telemetry.get_request_id() == "java-rag-request"
        return [Candidate(id=11, document_id=1, knowledge_base_id=7, chunk_index=0,
                          content="授权范围内的资料")] if has_matches else []

    monkeypatch.setattr(pipeline, "vector_retrieve", retrieve)
    client = TestClient(main_module.app, headers=AUTH_HEADERS)
    response = client.post("/rag/query", headers={"X-Request-ID": "java-rag-request"}, json={
        "knowledgeBaseIds": [7], "question": "q", "pipelineEngine": engine,
        "version": "baseline", "generate": False, "modelExecution": {}})
    assert response.status_code == 200
    assert response.headers["X-Request-ID"] == "java-rag-request"
    assert response.json()["requestId"] == "java-rag-request"
    assert response.json()["outcome"] == ("success" if has_matches else "no_evidence")


def test_rag_stream_terminal_event_uses_same_request_id():
    """验证 `response.status_code` 等于 `200`，且 `response.headers['X-Request-ID']` 等于 `'java-rag-stream'`。"""
    import json
    client = TestClient(main_module.app, headers=AUTH_HEADERS)
    response = client.post("/rag/query/stream", headers={"X-Request-ID": "java-rag-stream"}, json={
        "knowledgeBaseIds": [], "question": "q", "version": "baseline"})
    assert response.status_code == 200
    assert response.headers["X-Request-ID"] == "java-rag-stream"
    events = [part for part in response.text.split("\n\n") if part.startswith("event: done\n")]
    assert len(events) == 1
    assert json.loads(events[0].split("\ndata: ", 1)[1])["requestId"] == "java-rag-stream"


def test_duplicate_request_id_headers_are_replaced_by_single_generated_id():
    """验证 `response.status_code` 等于 `200`，且 `len(response.headers.get_list('X-Request-ID'))` 等于 `1`。"""
    client = TestClient(main_module.app)
    response = client.get("/health", headers=[("X-Request-ID", "first-id"), ("X-Request-ID", "second-id")])
    assert response.status_code == 200
    assert len(response.headers.get_list("X-Request-ID")) == 1
    assert str(UUID(response.headers["X-Request-ID"])) == response.headers["X-Request-ID"]


def _scope(request_id=None):
    """向 `run` 提供预设数据。返回 包含 `type`、`method`、`path`、`headers` 的字典。输入为 `request_id`。上游为 `call`。"""
    headers = [] if request_id is None else [(b"x-request-id", request_id.encode("ascii"))]
    return {"type": "http", "method": "GET", "path": "/stream", "headers": headers}


async def _receive():
    """返回 包含 `type`、`body`、`more_body` 的字典。"""
    return {"type": "http.request", "body": b"", "more_body": False}


def test_scope_restores_on_stream_completion_and_exception():
    """通过 `run` 核对本用例构造的状态。"""
    async def run():
        """委托 `telemetry.reset_request_id` 完成当前操作，无直接返回值。下游调用 `ai.app.request_id.RequestIdMiddleware`、`_scope`。"""
        token = telemetry.set_request_id("outer-context")
        try:
            messages = []

            async def send(message):
                """将当前记录追加到 `messages`，供调用方后续读取。输入为 `message`。下游调用 `ai.app.llm.telemetry.get_request_id`。"""
                messages.append(message)
                assert telemetry.get_request_id() == "stream-id"

            async def stream(scope, receive, send):
                """委托 `asyncio.sleep` 完成当前操作，无直接返回值。

                输入为 `scope`、`receive`、`send`。
                下游调用 `ai.app.llm.telemetry.get_request_id`、`send`。
                """
                assert telemetry.get_request_id() == "stream-id"
                await send({"type": "http.response.start", "status": 200, "headers": []})
                await send({"type": "http.response.body", "body": b"data: first\n\n", "more_body": True})
                # The first chunk is forwarded before the remaining stream work runs.
                assert len(messages) == 2
                await asyncio.sleep(0)
                assert telemetry.get_request_id() == "stream-id"
                await send({"type": "http.response.body", "body": b"data: done\n\n", "more_body": False})

            await RequestIdMiddleware(stream)(_scope("stream-id"), _receive, send)
            assert telemetry.get_request_id() == "outer-context"
            assert dict(messages[0]["headers"])[b"x-request-id"] == b"stream-id"

            async def failing(scope, receive, send):
                """触发 `RuntimeError`，交由上游处理。输入为 `scope`、`receive`、`send`。"""
                raise RuntimeError("failure")

            with pytest.raises(RuntimeError):
                await RequestIdMiddleware(failing)(_scope("failed-id"), _receive, send)
            assert telemetry.get_request_id() == "outer-context"
        finally:
            telemetry.reset_request_id(token)

    asyncio.run(run())


def test_concurrent_requests_keep_their_own_telemetry_and_response_headers():
    """通过 `run` 核对本用例构造的状态。"""
    async def run():
        """委托 `call` 完成当前操作，无直接返回值。下游调用 `ai.app.request_id.RequestIdMiddleware`。"""
        entered = 0
        ready = asyncio.Event()

        async def app(scope, receive, send):
            """更新 `ready` 的当前状态，供调用方继续处理。

            输入为 `scope`、`receive`、`send`。
            下游调用 `ai.app.llm.telemetry.get_request_id`、`send`。
            """
            nonlocal entered
            expected = scope["state"]["request_id"]
            entered += 1
            if entered == 2:
                ready.set()
            await asyncio.wait_for(ready.wait(), 2)
            assert telemetry.get_request_id() == expected
            await send({"type": "http.response.start", "status": 200, "headers": []})
            await send({"type": "http.response.body", "body": expected.encode()})

        middleware = RequestIdMiddleware(app)

        async def call(expected):
            """委托 `decode` 完成当前操作，无直接返回值。输入为 `expected`。上游为 `run`，下游调用 `ai.app.llm.telemetry.get_request_id`、`_scope`。"""
            messages = []

            async def send(message):
                """将当前记录追加到 `messages`，供调用方后续读取。输入为 `message`。"""
                messages.append(message)

            previous = telemetry.get_request_id()
            await middleware(_scope(expected), _receive, send)
            assert telemetry.get_request_id() == previous
            assert dict(messages[0]["headers"])[b"x-request-id"].decode() == expected
            assert messages[1]["body"].decode() == expected

        await asyncio.gather(call("first-request"), call("second-request"))

    asyncio.run(run())


def test_capacity_rejection_uses_normalized_id_in_body_and_header():
    """通过 `run` 核对本用例构造的状态。"""
    async def run():
        """释放 `admission._slots` 持有的资源。

        下游调用 `ai.app.rag.admission.RagQueryAdmissionMiddleware`、`ai.app.request_id.RequestIdMiddleware`。
        """
        async def should_not_run(scope, receive, send):
            """触发 `AssertionError`，交由上游处理。输入为 `scope`、`receive`、`send`。"""
            raise AssertionError("saturated request reached downstream")

        admission = RagQueryAdmissionMiddleware(should_not_run, max_inflight=1)
        assert admission._slots.acquire(blocking=False)
        try:
            middleware = RequestIdMiddleware(admission)
            scope = _scope("unsafe id")
            scope.update(method="POST", path="/rag/query")
            scope["headers"].append((b"x-internal-token", AUTH_HEADERS["X-Internal-Token"].encode()))
            messages = []

            async def send(message):
                """将当前记录追加到 `messages`，供调用方后续读取。输入为 `message`。"""
                messages.append(message)

            await middleware(scope, _receive, send)
            import json
            assert messages[0]["status"] == 429
            header_id = dict(messages[0]["headers"])[b"x-request-id"].decode()
            assert str(UUID(header_id)) == header_id
            assert json.loads(messages[1]["body"])["detail"]["requestId"] == header_id
        finally:
            admission._slots.release()

    asyncio.run(run())
