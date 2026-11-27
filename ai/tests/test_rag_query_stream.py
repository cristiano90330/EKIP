"""验证 SSE 阶段事件、来源、增量和唯一终态的组织方式。"""

import asyncio
import json
from contextlib import closing
from contextvars import copy_context
from threading import Event
from unittest.mock import Mock

import pytest
from fastapi.testclient import TestClient

from ai.app.main import app
from ai.app.llm.chat_result import ChatResult
from ai.app.models.cancellation import wait_or_cancel
from ai.app.models.context import binding
from ai.app.observability import stage_trace
from ai.app.rag import pipeline, router
from ai.app.rag.deadline import current_deadline_at
from ai.app.rag.langchain_pipeline import ScopedPgRetriever
from ai.app.rag.rag_service import GenerationResult
from ai.tests.conftest import AUTH_HEADERS
from ai.tests.test_rag_pipeline_engines import _request, io


def test_development_test_stream_rejects_langchain(monkeypatch):
    """开发联调的证据绕过仍限定为 fixed。"""
    monkeypatch.setattr(pipeline, "RAG_DEV_TEST_MODE", True)
    with pytest.raises(ValueError, match="development test mode supports pipelineEngine=fixed only"):
        pipeline.run_query_stream(_request("langchain"))


@pytest.mark.parametrize("provider", ["generic", "ollama"])
def test_langchain_stream_delivers_provisional_text_before_model_completion(io, monkeypatch, provider):
    """检索走 LCEL，消费者在允许模型结束前就收到临时正文。"""
    from langsmith.run_helpers import get_tracing_context

    terminal_allowed = Event()
    invoked_retrievers = []
    request = _request("langchain")
    model = request.modelExecution.bindings["generate"]
    request.modelExecution.bindings["generate"] = model.model_copy(update={"provider": provider})
    invoke = ScopedPgRetriever.invoke

    def scoped_recall(self, *args, **kwargs):
        assert get_tracing_context().get("enabled") is False
        invoked_retrievers.append(self.leg)
        return invoke(self, *args, **kwargs)

    def model_stream(purpose, system, prompt, *, response_format):
        assert purpose == "generate"
        assert binding("generate").model == "mock-chat"
        assert current_deadline_at() is not None
        assert response_format["type"] == "json_schema"
        assert "额度 500 元" in prompt
        if provider == "ollama":
            assert response_format["json_schema"]["name"] == "rag_ordered_answer_v1"
            assert "第一项是状态" in system
            prefix, suffix = '{"result":["success","答案', '[资料1]"]}'
        else:
            assert response_format["json_schema"]["name"] == "rag_answer_v1"
            prefix, suffix = '{"status":"success","answer":"答案', '[资料1]"}'
        yield prefix
        assert terminal_allowed.wait(timeout=5), "the consumer never received the preview"
        yield suffix
        yield ChatResult(prefix + suffix, 30, 7, finish_reason="stop")

    monkeypatch.setattr(ScopedPgRetriever, "invoke", scoped_recall)
    monkeypatch.setattr(pipeline.gate, "stream", model_stream)

    with closing(router._progress_stream(request)) as events:
        first = next(events)
        assert first == {"event": "execution", "data": {
            "pipelineEngine": "langchain", "retrievalVersion": "hybrid-rerank", "evidenceMode": "score"}}
        preview = next(event for event in events if event["event"] == "delta")
        assert preview["data"] == {"text": "答案", "provisional": True}
        terminal_allowed.set()
        rest = list(events)

    assert sorted(invoked_retrievers) == ["keyword", "vector"]
    assert not io["generate"]
    assert [event["data"] for event in rest if event["event"] == "delta"] == [
        {"text": "[资料1]", "provisional": True}]
    assert [event["event"] for event in rest if event["event"] in {"error", "done"}] == ["done"]
    end = rest[-1]["data"]
    assert end["answer"] == "答案[资料1]"
    assert end["pipelineEngine"] == "langchain" and end["evidenceMode"] == "score"
    assert end["evidenceSources"] and end["tokenCost"] == 37


@pytest.mark.parametrize(("raw", "preview", "terminal", "reason"), [
    ('{"result":["no_evidence","不输出"]}', [], "done", "generation_no_evidence"),
    ('{"result":["success","临时', ["临时"], "error", "generation_invalid_response"),
    ('{"result":["不输出","success"]}', [], "error", "generation_invalid_response"),
])
def test_ordered_preview_retains_refusal_and_invalid_terminal(io, monkeypatch, raw, preview, terminal, reason):
    """确认有序状态仍能拦住拒答或乱序正文，未完整结束的预览直接进入错误终态。"""
    request = _request("langchain")
    model = request.modelExecution.bindings["generate"]
    request.modelExecution.bindings["generate"] = model.model_copy(update={"provider": "ollama"})

    def model_stream(*args, **kwargs):
        yield raw
        yield ChatResult(raw, 2, 3, finish_reason="stop")

    monkeypatch.setattr(pipeline.gate, "stream", model_stream)
    events = list(pipeline.run_query_stream(request))

    assert [event["data"]["text"] for event in events if event["event"] == "delta"] == preview
    assert [event["event"] for event in events if event["event"] in {"error", "done"}] == [terminal]
    assert events[-1]["data"]["reasonCode"] == reason


@pytest.mark.parametrize(("chunks", "finish", "preview", "event_name", "reason"), [
    (['{"status":"no_evidence","answer":"不输出"}'], "stop",
     [], "done", "generation_no_evidence"),
    (['{"status":"success","answer":"临时'], "length",
     ["临时"], "error", "generation_invalid_response"),
    (['{"status":"success","answer":"临时'], "stop",
     ["临时"], "error", "generation_invalid_response"),
    (['{"answer":"不输出","status":"success'], "stop",
     [], "error", "generation_invalid_response"),
])
def test_langchain_preview_never_overrides_terminal(io, monkeypatch, chunks, finish, preview, event_name, reason):
    """只有已确认的 success 允许预览，截断由完整结构和模型终止原因判定。"""
    def model_stream(*args, **kwargs):
        yield from chunks
        yield ChatResult("".join(chunks), 2, 3, finish_reason=finish)

    monkeypatch.setattr(pipeline.gate, "stream", model_stream)
    events = list(pipeline.run_query_stream(_request("langchain")))

    assert [event["data"]["text"] for event in events if event["event"] == "delta"] == preview
    assert [event["event"] for event in events if event["event"] in {"error", "done"}] == [event_name]
    assert events[-1]["data"]["reasonCode"] == reason
    if event_name == "done":
        assert events[-1]["data"]["outcome"] == "no_evidence"
        assert events[-1]["data"]["evidenceSources"] == []
    else:
        assert "answer" not in events[-1]["data"]


def test_langchain_missing_evidence_skips_model_stream(io, monkeypatch):
    """召回有候选但未获证据准入时，不开始生成或发送正文。"""
    monkeypatch.setattr(pipeline, "evidence_threshold", lambda scorer: None)
    generate = Mock(side_effect=AssertionError("must not generate without calibration"))
    monkeypatch.setattr(pipeline.gate, "stream", generate)

    events = list(pipeline.run_query_stream(_request("langchain")))

    generate.assert_not_called()
    assert not any(event["event"] == "delta" for event in events)
    assert events[-1]["event"] == "error"
    assert events[-1]["data"]["reasonCode"] == "threshold_not_configured"


def test_closing_langchain_sse_notifies_and_releases_the_model(io, monkeypatch):
    """断开 SSE 消费，等待模型的遗留工作能通过取消作用域退出。"""
    closed = Event()
    reached_terminal = Event()

    def model_stream(*args, **kwargs):
        try:
            yield '{"status":"success","answer":"临时'
            wait_or_cancel(5)
            reached_terminal.set()
        finally:
            closed.set()

    monkeypatch.setattr(pipeline.gate, "stream", model_stream)

    with closing(router._progress_stream(_request("langchain"))) as events:
        preview = next(event for event in events if event["event"] == "delta")
        assert preview["data"] == {"text": "临时", "provisional": True}

    assert closed.wait(timeout=3)
    assert not reached_terminal.is_set()


def test_asgi_disconnect_releases_silent_model_connection(io, monkeypatch):
    """让真实模型传输在首个正文增量后阻塞读，验证断流正常结束并使模型端收到 EOF。"""
    monkeypatch.setenv("NO_PROXY", "127.0.0.1")

    async def scenario():
        upstream_closed = asyncio.Event()
        model_finished = asyncio.Event()
        model_connected = asyncio.Event()
        stop_model = asyncio.Event()

        async def serve_model(reader, writer):
            model_connected.set()
            try:
                headers = (await reader.readuntil(b"\r\n\r\n")).split(b"\r\n")
                length = next(line.split(b":", 1)[1] for line in headers
                              if line.lower().startswith(b"content-length:"))
                await reader.readexactly(int(length))
                frame = {"choices": [{"delta": {"content": '{"status":"success","answer":"临时'}}]}
                writer.write(b"HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n"
                             b"Connection: close\r\n\r\ndata: " + json.dumps(frame).encode() + b"\n\n")
                await writer.drain()
                read_task = asyncio.create_task(reader.read(1))
                stop_task = asyncio.create_task(stop_model.wait())
                done, pending = await asyncio.wait((read_task, stop_task), return_when=asyncio.FIRST_COMPLETED)
                for task in pending:
                    task.cancel()
                await asyncio.gather(*pending, return_exceptions=True)
                if read_task in done and read_task.result() == b"":
                    upstream_closed.set()
            finally:
                writer.close()
                await writer.wait_closed()
                model_finished.set()

        async with await asyncio.start_server(serve_model, "127.0.0.1", 0) as model_server:
            port = model_server.sockets[0].getsockname()[1]
            request = _request("langchain")
            model = request.modelExecution.bindings["generate"]
            request.modelExecution.bindings["generate"] = model.model_copy(
                update={"baseUrl": f"http://127.0.0.1:{port}/v1",
                        "capabilities": {**model.capabilities, "streaming": True}})
            incoming = asyncio.Queue()
            incoming.put_nowait({"type": "http.request", "body": request.model_dump_json().encode()})
            preview = asyncio.Event()
            responses = []

            async def send(message):
                responses.append(message)
                if b"event: delta\n" in message.get("body", b""):
                    preview.set()

            path = "/rag/query/stream"
            scope = {"type": "http", "asgi": {"version": "3.0", "spec_version": "2.3"},
                     "method": "POST", "path": path, "raw_path": path.encode(), "scheme": "http",
                     "query_string": b"", "headers": [(b"content-type", b"application/json"),
                         (b"x-internal-token", AUTH_HEADERS["X-Internal-Token"].encode())]}
            response = asyncio.create_task(app(scope, incoming.get, send))
            try:
                await asyncio.wait_for(preview.wait(), timeout=5)
                incoming.put_nowait({"type": "http.disconnect"})
                response_result, closed = await asyncio.gather(
                    asyncio.wait_for(response, timeout=2),
                    asyncio.wait_for(upstream_closed.wait(), timeout=2), return_exceptions=True)
                assert response_result is None, f"ASGI 流在断开后异常退出: {response_result!r}"
                assert closed is True, "模型连接在无后续响应时未关闭"
                assert not any(b"event: error\n" in message.get("body", b"") for message in responses)
            finally:
                stop_model.set()
                if not response.done():
                    response.cancel()
                    await asyncio.gather(response, return_exceptions=True)
                if model_connected.is_set():
                    await asyncio.wait_for(model_finished.wait(), timeout=2)

    asyncio.run(scenario())


def test_stream_keeps_context_across_iterations_and_emits_one_terminal(io):
    """验证 `names[0]` 等于 `'stage'`，且 `names.index('delta') < names.index('sources') < names.index('done')` 成立。"""
    deadlines = []

    def generate(*args):
        """产出供调用方迭代的结果。

        输入为 `args`。
        下游调用 `ai.app.rag.deadline.current_deadline_at`、`ai.app.observability.stage_trace.current`。
        """
        deadlines.append(current_deadline_at())
        yield "delta", "答"
        deadlines.append(current_deadline_at())
        assert stage_trace.current() is not None
        yield "done", GenerationResult("答案", provider="ollama")

    stream = pipeline.run_query_stream(_request(), generate_stream=generate)
    events = []
    while True:
        event = copy_context().run(next, stream, None)
        if event is None:
            break
        events.append(event)

    names = [event["event"] for event in events]
    assert next(event for event in events if event["event"] == "delta")["data"] == {"text": "答"}
    assert names[0] == "stage"
    assert names.index("delta") < names.index("sources") < names.index("done")
    assert [name for name in names if name in {"done", "error"}] == ["done"]
    assert names[-1] == "done"
    end = events[-1]["data"]
    assert end["answer"] == "答案" and end["providerUsed"] == "ollama"
    assert any(stage["stage"] == "generate" for stage in end["stages"])
    assert deadlines[0] is not None and deadlines[0] == deadlines[1]


@pytest.mark.parametrize(("failure", "reason", "has_delta"), [
    ("deadline", "query_deadline_exceeded", False),
    ("generation", "generation_interrupted", True),
    ("retrieval", "internal_error", False),
])
def test_stream_failure_emits_a_single_sanitized_terminal(io, monkeypatch, failure, reason, has_delta):
    """验证 `any((event['event'] == 'delta' for event in events))` 等于 `has_delta`，且 `events[-1]['event']` 等于 `'error'`。"""
    expired = False
    monkeypatch.setattr(pipeline, "deadline_expired", lambda: expired)

    def generate(*args):
        """产出供调用方迭代的结果。输入为 `args`。"""
        nonlocal expired
        expired = failure == "deadline"
        yield "delta", "部分答案"
        raise RuntimeError("private detail")

    def fail_retrieval(*args, **kwargs):
        """触发 `RuntimeError`，交由上游处理。输入为 `args`、`kwargs`。"""
        raise RuntimeError("private detail")

    events = list(pipeline.run_query_stream(_request(), generate_stream=generate,
                  retrieve=fail_retrieval if failure == "retrieval" else None))
    assert [event["event"] for event in events if event["event"] in {"done", "error"}] == ["error"]
    assert any(event["event"] == "delta" for event in events) == has_delta
    assert events[-1]["event"] == "error"
    assert events[-1]["data"]["reasonCode"] == reason
    assert "private detail" not in str(events[-1]["data"])


def test_stream_missing_current_calibration_cannot_generate(io, monkeypatch):
    """验证 `events[-1]['event']` 等于 `'error'`，且 `not any((event['event'] == 'delta' for event in events))` 成立。"""
    monkeypatch.setattr(pipeline, "evidence_threshold", lambda scorer: None)
    generate = Mock(side_effect=AssertionError("must not generate without calibration"))
    events = list(pipeline.run_query_stream(_request(), generate_stream=generate))
    generate.assert_not_called()
    assert events[-1]["event"] == "error"
    assert not any(event["event"] == "delta" for event in events)
    end = events[-1]["data"]
    assert end["reasonCode"] == "threshold_not_configured"
    assert "answer" not in end and "sources" not in end


def test_stream_admission_only_returns_evidence_without_generating(io):
    """验证 `events[-1]['data']['evidenceSources']` 成立，且 `events[-1]['data']['answer']` 等于 `''`。"""
    generate = Mock(side_effect=AssertionError("admission-only requests must not generate"))
    request = _request(generate=False, admitEvidence=True)
    events = list(pipeline.run_query_stream(request, generate_stream=generate))

    generate.assert_not_called()
    assert [event["event"] for event in events if event["event"] != "stage"] == ["sources", "done"]
    assert events[-1]["data"]["evidenceSources"]
    assert events[-1]["data"]["answer"] == ""


def test_stream_http_encodes_frames(monkeypatch):
    """验证 `response.status_code` 等于 `200`，且 `response.headers['content-type'].startswith('text/event-stream')` 成立。"""
    def stream(request):
        """产出供调用方迭代的结果。输入为 `request`。"""
        yield {"event": "delta", "data": {"text": "hi"}}
        yield {"event": "done", "data": {"answer": "hi"}}

    monkeypatch.setattr(router, "run_query_stream", stream)
    response = TestClient(app, headers=AUTH_HEADERS).post("/rag/query/stream", json={
        "knowledgeBaseIds": [1], "question": "q", "topK": 2, "recallN": 2,
    })

    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/event-stream")
    assert response.text == 'event: delta\ndata: {"text":"hi"}\n\nevent: done\ndata: {"answer":"hi"}\n\n'


def test_stream_http_dispatches_to_langchain_and_preserves_preview_flag(io, monkeypatch):
    """真实 AI HTTP 入口保留引擎、临时增量和经过校验的终态。"""
    def model_stream(*args, **kwargs):
        content = '{"status":"success","answer":"正文"}'
        yield content
        yield ChatResult(content, 2, 3, finish_reason="stop")

    monkeypatch.setattr(pipeline.gate, "stream", model_stream)
    response = TestClient(app, headers=AUTH_HEADERS).post(
        "/rag/query/stream", json=_request("langchain").model_dump(mode="json"))

    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/event-stream")
    frames = {}
    for raw in response.text.strip().split("\n\n"):
        event, data = raw.split("\n", 1)
        frames.setdefault(event.removeprefix("event: "), []).append(
            json.loads(data.removeprefix("data: ")))
    assert frames["execution"][0]["pipelineEngine"] == "langchain"
    assert frames["delta"] == [{"text": "正文", "provisional": True}]
    assert frames["done"][0]["answer"] == "正文"
    assert frames["done"][0]["evidenceSources"]
