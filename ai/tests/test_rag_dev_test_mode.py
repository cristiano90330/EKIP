"""验证开发联调模式的启用条件和模型通信范围。"""

from types import SimpleNamespace

import pytest

from ai.app import config
from ai.app.models.schema import ModelConfig, ModelExecution
from ai.app.rag import pipeline
from ai.app.rag.schema import RagEvidenceDecision, RagQueryRequest, RagQueryResponse


def _request():
    """向 `test_dev_test_mode_runs_direct_chat_without_retrieval` 提供预设数据。返回 `RagQueryRequest` 的结果。

    上游为 `test_dev_test_mode_forwards_real_stream_deltas`，下游调用 `ai.app.models.schema.ModelConfig`、`ai.app.models.schema.ModelExecution`。
    """
    chat = ModelConfig(
        type="chat", protocol="openai_chat", model="test-chat",
        baseUrl="http://127.0.0.1:9009/v1", capabilities={"streaming": True})
    execution = ModelExecution(bindings={"generate": chat})
    return RagQueryRequest(
        knowledgeBaseIds=[17], question="请只回复：MODEL_OK", modelExecution=execution)


def test_dev_test_mode_runs_direct_chat_without_retrieval(monkeypatch):
    """验证 `response.answer` 等于 `'MODEL_OK'`，且 `response.outcome` 等于 `'success'`。"""
    monkeypatch.setattr(pipeline, "RAG_DEV_TEST_MODE", True)
    monkeypatch.setattr(pipeline, "prepare_query", lambda *_: (_ for _ in ()).throw(AssertionError("retrieval path used")))

    def direct_chat(*args, **kwargs):
        """返回 `SimpleNamespace` 的结果。输入为 `args`、`kwargs`。"""
        assert kwargs["allow_cache"] is False
        return SimpleNamespace(content="MODEL_OK", total_tokens=4, provider="test-provider", degraded=False)

    monkeypatch.setattr(pipeline.gate, "call_messages", direct_chat)

    response = pipeline.run_query(_request())

    assert response.answer == "MODEL_OK"
    assert response.outcome == "success"
    assert response.reasonCode == "development_test_mode"
    assert response.retrievalStatus == "skipped"
    assert response.sources == []
    assert response.evidenceSources == []
    assert response.evidenceDecision.state == "bypassed"
    assert response.providerUsed == "test-provider"


def test_dev_test_mode_forwards_real_stream_deltas(monkeypatch):
    """验证 `deltas` 等于 `['MODEL_', 'OK']`，且 `[event['event'] for event in events][-1]` 等于 `'done'`。"""
    monkeypatch.setattr(pipeline, "RAG_DEV_TEST_MODE", True)
    monkeypatch.setattr(pipeline, "prepare_query", lambda *_: (_ for _ in ()).throw(AssertionError("retrieval path used")))
    terminal = SimpleNamespace(content="MODEL_OK", total_tokens=4, provider="test-provider", degraded=False)
    monkeypatch.setattr(pipeline.gate, "stream", lambda *args, **kwargs: iter(["MODEL_", "OK", terminal]))

    events = list(pipeline.run_query_stream(_request()))

    deltas = [event["data"]["text"] for event in events if event["event"] == "delta"]
    assert deltas == ["MODEL_", "OK"]
    assert [event["event"] for event in events][-1] == "done"
    response = events[-1]["data"]
    assert response["answer"] == "MODEL_OK"
    assert response["reasonCode"] == "development_test_mode"
    assert response["evidenceDecision"]["state"] == "bypassed"


def test_dev_test_mode_rejects_final_only_model_as_not_streaming(monkeypatch):
    """验证 `not any((event['event'] == 'delta' for event in events))` 成立，且 `error['reasonCode']` 等于 `'model_streaming_no_deltas'`。"""
    monkeypatch.setattr(pipeline, "RAG_DEV_TEST_MODE", True)
    terminal = SimpleNamespace(content="MODEL_OK", total_tokens=4, provider="test-provider", degraded=False)
    monkeypatch.setattr(pipeline.gate, "stream", lambda *args, **kwargs: iter([terminal]))

    events = list(pipeline.run_query_stream(_request()))

    assert not any(event["event"] == "delta" for event in events)
    error = next(event["data"] for event in events if event["event"] == "error")
    assert error["reasonCode"] == "model_streaming_no_deltas"
    assert error["failedStage"] == "generate"


def test_dev_test_mode_leaves_generate_false_retrieval_calls_unchanged(monkeypatch):
    """验证 `called` 等于 `[request]`，且 `response.evidenceDecision.state` 等于 `'accepted'`。"""
    monkeypatch.setattr(pipeline, "RAG_DEV_TEST_MODE", True)
    request = _request().model_copy(update={"generate": False})
    expected = RagQueryResponse(
        answer="", originalQuery=request.question, rewrittenQuery=request.question,
        evidenceDecision=RagEvidenceDecision(
            state="accepted", policyVersion="test", scorer="test"))
    called = []

    def normal_pipeline(current):
        """返回 `expected`。输入为 `current`。"""
        called.append(current)
        return expected

    monkeypatch.setattr(pipeline, "_execute", normal_pipeline)

    response = pipeline.run_query(request)

    assert called == [request]
    assert response.evidenceDecision.state == "accepted"


def test_dev_test_mode_is_rejected_outside_development_environment(monkeypatch):
    """验证用例触发 `RuntimeError` 异常。"""
    monkeypatch.setattr(config, "EKIP_ENV", "production")
    monkeypatch.setattr(config, "RAG_DEV_TEST_MODE", True)

    with pytest.raises(RuntimeError, match="requires EKIP_ENV=development"):
        config.validate_rag_limits()
