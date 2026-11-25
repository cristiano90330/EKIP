"""验证 SSE 阶段事件、来源、增量和唯一终态的组织方式。"""

from contextvars import copy_context
from unittest.mock import Mock

import pytest
from fastapi.testclient import TestClient

from ai.app.main import app
from ai.app.observability import stage_trace
from ai.app.rag import pipeline, router
from ai.app.rag.deadline import current_deadline_at
from ai.app.rag.rag_service import GenerationResult
from ai.tests.conftest import AUTH_HEADERS
from ai.tests.test_rag_pipeline_engines import _request, io


def test_stream_rejects_langchain():
    """验证用例触发 `ValueError` 异常。"""
    with pytest.raises(ValueError, match="streaming supports pipelineEngine=fixed only"):
        pipeline.run_query_stream(_request("langchain"))


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
