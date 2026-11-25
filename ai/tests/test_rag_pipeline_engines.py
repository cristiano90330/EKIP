"""对照固定流程与 LCEL 流程的阶段产出、失败与证据契约。"""

from concurrent.futures import ThreadPoolExecutor
from threading import BoundedSemaphore, Event
from time import monotonic
from types import SimpleNamespace

import pytest
from fastapi import Depends, FastAPI
from fastapi.testclient import TestClient

from ai.app.llm import telemetry
from ai.app.models.schema import Calibration, ModelConfig, ModelExecution
from ai.app.query.schema import ChatMessage, RewriteResponse
from ai.app.rag import deadline, pipeline
from ai.app.rag.admission import RagQueryAdmissionMiddleware
from ai.app.rag.rag_service import gate
from ai.app.rag.router import router
from ai.app.rag.schema import RagQueryRequest
from ai.app.rerank.schema import RerankResponse
from ai.app.retrieval import retriever
from ai.app.retrieval.db import ReadOnlyPgError
from ai.app.retrieval.retriever import Candidate
from ai.app.security import require_internal_token
from ai.tests.conftest import AUTH_HEADERS

ENGINES = ("fixed", "langchain")
VERSIONS = ("baseline", "hybrid", "hybrid-rerank", "hybrid-rerank-rewrite")


def _candidate(cid, *, content=None):
    """向 `test_admit_evidence_partial_recall_without_admission_is_dependency_failure` 提供预设数据。返回 `Candidate` 的结果。

    输入为 `cid`、`content`。
    上游为 `vector`。
    """
    return Candidate(
        id=cid, document_id=10, knowledge_base_id=1, chunk_index=cid - 101,
        content=content or f"报销规定条款 {cid}", distance=0.12,
        heading_path="报销 > 额度", page_no=2, source_type="paragraph",
    )


def _request(engine="fixed", **kwargs):
    """向 `test_default_engine_is_fixed_and_explicit_engines_are_echoed` 提供预设数据。返回 `RagQueryRequest` 的结果。

    输入为 `engine`、`kwargs`。
    上游为 `test_explicit_score_mode_requires_calibration_even_when_deployment_uses_support`，下游调用 `ai.app.models.schema.ModelConfig`、`ai.app.models.schema.Calibration`。
    """
    chat = ModelConfig(
        id="engine-chat", type="chat", protocol="openai_chat", model="mock-chat",
        baseUrl="http://models.invalid/v1", capabilities={"json": True},
    )
    reranker = ModelConfig(
        id="engine-rerank", type="rerank", protocol="cohere_rerank", model="mock-rerank",
        baseUrl="http://models.invalid/v2", semanticFingerprint="engine-rerank-v1",
        calibration=Calibration(semanticFingerprint="engine-rerank-v1", threshold=0.5,
                                reference="deterministic engine fixture"),
    )
    embedding = ModelConfig(
        id="engine-embedding", type="embedding", protocol="openai_embedding",
        model="mock-embedding", baseUrl="http://models.invalid/v1", dimension=1024,
        semanticFingerprint="engine-embedding-v1",
        calibration=Calibration(semanticFingerprint="engine-embedding-v1", threshold=0.7,
                                reference="deterministic engine fixture"),
    )
    values = dict(
        knowledgeBaseIds=[1], question="报销有什么规定？", pipelineEngine=engine,
        version="hybrid-rerank", topK=2, recallN=4,
        modelExecution=ModelExecution(bindings={
            "generate": chat, "rewrite": chat, "rerank": reranker, "embedding": embedding,
        }),
    )
    values.update(kwargs)
    return RagQueryRequest(**values)


# 输入为 `monkeypatch`。
# 产出供调用方迭代的结果。
# 下游调用 `ai.app.rag.pipeline.prepare_pipeline_engines`、`ai.app.llm.telemetry.set_request_id`。
@pytest.fixture
def io(monkeypatch):
    """Fresh candidates and executor prevent mutation/leaked-worker test coupling."""
    pipeline.prepare_pipeline_engines()
    pool = ThreadPoolExecutor(max_workers=2, thread_name_prefix="engine-contract")
    monkeypatch.setattr(retriever, "_LEG_EXECUTOR", pool)
    monkeypatch.setattr(retriever, "_LEG_SLOTS", BoundedSemaphore(4))
    monkeypatch.setattr(pipeline, "RAG_EVIDENCE_MODE", "score")
    calls = {"vector": [], "keyword": [], "rewrite": [], "rerank": [], "generate": []}

    def vector(kbs, query, limit):
        """返回 列表。输入为 `kbs`、`query`、`limit`。下游调用 `ai.app.rag.deadline.current_deadline_at`、`_candidate`。"""
        calls["vector"].append((list(kbs), query, limit, deadline.current_deadline_at()))
        return [_candidate(101, content='额度 500 元；字段 {literal} 与 {"key":"value"}。'),
                _candidate(102)]

    def keyword(kbs, query, limit):
        """返回 列表。输入为 `kbs`、`query`、`limit`。下游调用 `ai.app.rag.deadline.current_deadline_at`、`_candidate`。"""
        calls["keyword"].append((list(kbs), query, limit, deadline.current_deadline_at()))
        return [_candidate(102), _candidate(103)]

    def rewrite(request):
        """返回 `RewriteResponse` 的结果。输入为 `request`。下游调用 `ai.app.rag.deadline.current_deadline_at`。"""
        calls["rewrite"].append((request, deadline.current_deadline_at()))
        return RewriteResponse(
            originalQuery=request.question, rewrittenQuery="差旅报销规定是什么？",
            rewritten=True, reason="补全历史指代", tokenCost=11,
        )

    def rerank(request):
        """返回 `RerankResponse` 的结果。输入为 `request`。下游调用 `ai.app.rag.deadline.current_deadline_at`。"""
        calls["rerank"].append((request, deadline.current_deadline_at()))
        scores = {101: 0.9, 102: 0.8, 103: 0.2}
        # Deliberately change the returned id order: scores must be matched by id.
        ids = list(reversed([c.id for c in request.candidates]))
        return RerankResponse(ids=ids, scores=[scores[cid] for cid in ids], scorer="model")

    def generate(purpose, system_prompt, user_prompt, **kwargs):
        """返回 `SimpleNamespace` 的结果。

        输入为 `purpose`、`system_prompt`、`user_prompt`、`kwargs`。
        下游调用 `ai.app.rag.deadline.current_deadline_at`。
        """
        calls["generate"].append((purpose, system_prompt, user_prompt,
                                  deadline.current_deadline_at()))
        return SimpleNamespace(content='{"status":"success","answer":"报销额度为 500 元。[资料1]"}', total_tokens=37,
                               prompt_tokens=30, completion_tokens=7,
                               degraded=False, reason="", provider="ollama")

    monkeypatch.setattr(pipeline, "vector_retrieve", vector)
    monkeypatch.setattr(pipeline, "keyword_retrieve", keyword)
    monkeypatch.setattr(pipeline, "rewrite", rewrite)
    monkeypatch.setattr(pipeline, "rerank", rerank)
    monkeypatch.setattr(gate, "call", generate)
    telemetry.set_request_id("engine-contract-id")
    yield calls
    pool.shutdown(wait=True, cancel_futures=True)


# 输入为 `response`。
# 上游为 `test_generation_preserves_production_prompt_sources_history_and_usage`、`test_parent_expansion_and_compression_preserve_raw_sources_and_prompt_parity`。
def _stable(response):
    """Compare public semantics; wall-clock timings and the chosen engine may differ."""
    result = response.model_dump()
    result.pop("pipelineEngine")
    result["stages"] = [{key: value for key, value in stage.items() if key != "durationMs"}
                        for stage in result["stages"]]
    return result


def _http_app():
    # Same production router, token dependency and admission shell, without model warmup.
    """向 `test_invalid_engine_rejected_before_io` 提供预设数据。返回 `app`。

    上游为 `test_duplicate_json_fields_are_rejected_before_query_execution`、`test_http_top_k_requires_a_json_integer_before_io`。
    """
    app = FastAPI()
    app.add_middleware(RagQueryAdmissionMiddleware, max_inflight=1)
    app.include_router(router, dependencies=[Depends(require_internal_token)])
    return app


def test_default_engine_is_fixed_and_explicit_engines_are_echoed(io):
    """验证 `req.pipelineEngine` 等于 `'fixed'`，且 `pipeline.run_query(req).pipelineEngine` 等于 `'fixed'`。"""
    req = RagQueryRequest(knowledgeBaseIds=[], question="q")
    assert req.pipelineEngine == "fixed"
    assert pipeline.run_query(req).pipelineEngine == "fixed"
    for engine in ENGINES:
        response = pipeline.run_query(_request(engine, generate=False))
        assert response.pipelineEngine == engine
        assert response.retrievalVersion == "hybrid-rerank"

@pytest.mark.parametrize("engine", ENGINES)
def test_explicit_score_mode_requires_calibration_even_when_deployment_uses_support(io, monkeypatch, engine):
    """验证 `result.evidenceMode` 等于 `'score'`，且 `(result.outcome, result.reasonCode)` 等于 `('dependency_unavailable', 'threshold_not_configured')`。"""
    monkeypatch.setattr(pipeline, "RAG_EVIDENCE_MODE", "support")
    request = _request(engine, evidenceMode="score")
    request.modelExecution.bindings["rerank"].calibration = None
    result = pipeline.run_query(request)
    assert result.evidenceMode == "score"
    assert (result.outcome, result.reasonCode) == ("dependency_unavailable", "threshold_not_configured")
    assert result.evidenceSources == []
    assert io["rerank"] and not io["generate"]


def test_score_override_without_reranking_is_rejected_before_io(io):
    """验证用例触发 `ValueError` 异常，并检查 `all((not calls for calls in io.values()))` 成立。"""
    with pytest.raises(ValueError, match="score evidence mode requires reranking"):
        pipeline.run_query(_request(version="hybrid", evidenceMode="score"))
    assert all(not calls for calls in io.values())


@pytest.mark.parametrize("engine", ["nope", "LangChain", "", "langgraph"])
def test_invalid_engine_rejected_before_io(io, engine):
    """验证 `response.status_code` 属于 `(400, 422)`，且 `all((not calls for calls in io.values()))` 成立。"""
    client = TestClient(_http_app(), headers=AUTH_HEADERS)
    response = client.post("/rag/query", json={
        "knowledgeBaseIds": [1], "question": "q", "pipelineEngine": engine,
    })
    assert response.status_code in (400, 422)
    assert all(not calls for calls in io.values())


@pytest.mark.parametrize("path", ["/rag/query", "/rag/query/stream"])
@pytest.mark.parametrize("body", [
    '{"knowledgeBaseIds":[1],"question":"q","topK":1,"topK":2}',
    '{"knowledgeBaseIds":[1],"question":"q","history":[{"role":"user","content":"a","content":"b"}]}',
], ids=["top-level", "nested"])
def test_duplicate_json_fields_are_rejected_before_query_execution(monkeypatch, io, path, body):
    """验证 `response.status_code` 等于 `422`，且 `response.json()['detail']['code']` 等于 `'duplicate_json_field'`。"""
    def unexpected_execution(*_args, **_kwargs):
        """委托 `pytest.fail` 完成当前操作，无直接返回值。输入为 `_args`、`_kwargs`。"""
        pytest.fail("duplicate JSON input must be rejected before query execution")

    monkeypatch.setattr("ai.app.rag.router.run_query", unexpected_execution)
    monkeypatch.setattr("ai.app.rag.router.run_query_stream", unexpected_execution)
    response = TestClient(_http_app()).post(path, content=body, headers=AUTH_HEADERS)

    assert response.status_code == 422
    assert response.json()["detail"]["code"] == "duplicate_json_field"
    assert all(not calls for calls in io.values())


@pytest.mark.parametrize("top_k", ['"1"', "true", "false", "1.0", "1e0"])
def test_http_top_k_requires_a_json_integer_before_io(io, top_k):
    """验证 `response.status_code` 等于 `422`，且 `all((not calls for calls in io.values()))` 成立。"""
    response = TestClient(_http_app()).post(
        "/rag/query",
        content='{"knowledgeBaseIds":[],"question":"q","topK":' + top_k + '}',
        headers=AUTH_HEADERS,
    )

    assert response.status_code == 422
    assert all(not calls for calls in io.values())


@pytest.mark.parametrize("path", ["/rag/query", "/rag/query/stream"])
def test_invalid_compression_is_rejected_for_empty_scope_before_io(io, path):
    """验证 `response.status_code` 等于 `422`，且 `all((not calls for calls in io.values()))` 成立。"""
    response = TestClient(_http_app()).post(
        path,
        content='{"knowledgeBaseIds":[],"question":"q","compressionMode":"invalid"}',
        headers=AUTH_HEADERS,
    )

    assert response.status_code == 422
    assert all(not calls for calls in io.values())


@pytest.mark.parametrize("entry", ["sync", "stream"])
def test_pipeline_rejects_invalid_compression_before_empty_scope_short_circuit(io, entry):
    """验证用例触发 `ValueError` 异常，并检查 `all((not calls for calls in io.values()))` 成立。"""
    request = RagQueryRequest.model_construct(
        knowledgeBaseIds=[], question="q", pipelineEngine="fixed", version="baseline",
        compressionMode="invalid",
    )

    with pytest.raises(ValueError, match="compressionMode"):
        if entry == "sync":
            pipeline.run_query(request)
        else:
            list(pipeline.run_query_stream(request))

    assert all(not calls for calls in io.values())


@pytest.mark.parametrize("version", VERSIONS)
def test_all_retrieval_strategies_have_same_sources_and_routing(io, version):
    """验证 `response.pipelineEngine` 等于 `engine`，且 `response.retrievalVersion` 等于 `version`。"""
    outputs = []
    for engine in ENGINES:
        response = pipeline.run_query(_request(engine, version=version, generate=False))
        assert response.pipelineEngine == engine
        assert response.retrievalVersion == version
        assert response.outcome == "success"
        outputs.append(_stable(response))
    assert outputs[0] == outputs[1]
    assert len(io["vector"]) == 2
    assert len(io["keyword"]) == (0 if version == "baseline" else 2)
    assert len(io["rerank"]) == (2 if "rerank" in version else 0)
    assert len(io["rewrite"]) == (2 if version.endswith("rewrite") else 0)
    assert not io["generate"]


@pytest.mark.parametrize("engine", ENGINES)
def test_legacy_generate_false_still_bypasses_admission(io, engine):
    """验证 `(response.outcome, response.answer)` 等于 `('success', '')`，且 `response.sources and response.evidenceSources == []` 成立。"""
    response = pipeline.run_query(_request(engine, generate=False))
    assert (response.outcome, response.answer) == ("success", "")
    assert response.sources and response.evidenceSources == []
    assert response.evidenceDecision.state == "bypassed"
    assert response.evidenceDecision.reasonCode == "retrieve_only"
    assert not any(stage.stage == "evidence_gate" for stage in response.stages)
    assert not io["generate"]


@pytest.mark.parametrize("engine", ENGINES)
def test_admit_evidence_runs_gate_and_postprocess_without_generation(io, engine):
    """验证 `(response.outcome, response.answer)` 等于 `('success', '')`，且 `[source.chunkId for source in response.sources]` 等于 `[101, 102]`。"""
    response = pipeline.run_query(_request(engine, generate=False, admitEvidence=True))
    assert (response.outcome, response.answer) == ("success", "")
    assert [source.chunkId for source in response.sources] == [101, 102]
    assert [source.chunkId for source in response.evidenceSources] == [101, 102]
    assert response.evidenceDecision.state == "accepted"
    assert response.evidenceDecision.acceptedCount == 2
    assert "rerank" in {stage.stage for stage in response.stages}
    assert not any(stage.stage == "generate" for stage in response.stages)
    assert not io["generate"]


@pytest.mark.parametrize("engine", ENGINES)
def test_admit_evidence_document_scope_reaches_both_retrieval_legs(monkeypatch, io, engine):
    """验证 `response.outcome` 等于 `'success'`，且 `[source.chunkId for source in response.evidenceSources] == [101]` 成立。"""
    seen = []

    def scoped(kbs, query, limit, document_id=None):
        """返回 列表。输入为 `kbs`、`query`、`limit`、`document_id`。下游调用 `_candidate`。"""
        seen.append((tuple(kbs), document_id))
        return [_candidate(101)]

    monkeypatch.setattr(pipeline, "vector_retrieve", scoped)
    monkeypatch.setattr(pipeline, "keyword_retrieve", scoped)
    response = pipeline.run_query(_request(
        engine, generate=False, admitEvidence=True, documentId=10))
    assert response.outcome == "success"
    assert [source.chunkId for source in response.evidenceSources] == [101]
    assert seen and all(scope == (1,) and document_id == 10 for scope, document_id in seen)
    assert not io["generate"]


@pytest.mark.parametrize("engine", ENGINES)
def test_admit_evidence_gate_unavailable_is_dependency_failure(monkeypatch, io, engine):
    """验证 `response.sources and response.evidenceSources == []` 成立，且 `not io['generate']` 成立。"""
    request = _request(engine, generate=False, admitEvidence=True)
    request.modelExecution.bindings["rerank"].calibration = None
    response = pipeline.run_query(request)
    assert (response.outcome, response.reasonCode, response.failedStage) == (
        "dependency_unavailable", "threshold_not_configured", "evidence_gate")
    assert response.sources and response.evidenceSources == []
    assert not io["generate"]


@pytest.mark.parametrize("engine", ENGINES)
def test_admit_evidence_partial_recall_without_admission_is_dependency_failure(
        monkeypatch, io, engine):
    """验证 `(response.outcome, response.reasonCode)` 等于 `('dependency_unavailable', 'partial_retrieval')`，且 `response.evidenceSources` 等于 `[]`。"""
    candidate = _candidate(102)
    candidate.score = 0.1
    candidate.score_source = "rerank"
    candidate.rerank_scorer = "model"
    candidate.score_valid = True
    monkeypatch.setattr(pipeline, "_retrieve_with_status",
                        lambda *args, **kwargs: (
                            [candidate], "partial", "partial_retrieval"))
    response = pipeline.run_query(_request(engine, generate=False, admitEvidence=True))
    assert (response.outcome, response.reasonCode) == (
        "dependency_unavailable", "partial_retrieval")
    assert response.evidenceSources == []
    assert not io["generate"]


@pytest.mark.parametrize("engine", ENGINES)
def test_admit_evidence_empty_recall_is_no_evidence(monkeypatch, io, engine):
    """验证 `(response.outcome, response.reasonCode)` 等于 `('no_evidence', 'no_match')`，且 `response.sources == response.evidenceSources == []` 成立。"""
    monkeypatch.setattr(pipeline, "vector_retrieve", lambda *args: [])
    monkeypatch.setattr(pipeline, "keyword_retrieve", lambda *args: [])
    response = pipeline.run_query(_request(engine, generate=False, admitEvidence=True))
    assert (response.outcome, response.reasonCode) == ("no_evidence", "no_match")
    assert response.sources == response.evidenceSources == []
    assert not io["rerank"] and not io["generate"]


@pytest.mark.parametrize("engine", ENGINES)
def test_admit_evidence_budget_exhausted_is_timeout(io, engine):
    """验证 `(response.outcome, response.reasonCode)` 等于 `('timeout', 'query_deadline_exceeded')`，且 `not io['generate']` 成立。"""
    token = deadline.set_request_started_at(monotonic() - 2.0)
    try:
        response = pipeline.run_query(_request(
            engine, generate=False, admitEvidence=True, budgetMs=100))
    finally:
        deadline.reset_request_started_at(token)
    assert (response.outcome, response.reasonCode) == ("timeout", "query_deadline_exceeded")
    assert not io["generate"]


def test_admit_evidence_rejects_generate_true_before_io(io):
    """验证用例触发 `ValueError` 异常，并检查 `all((not calls for calls in io.values()))` 成立。"""
    with pytest.raises(ValueError, match="admitEvidence"):
        pipeline.run_query(_request("fixed", admitEvidence=True))
    assert all(not calls for calls in io.values())


def test_document_id_requires_admit_evidence(io):
    """验证用例触发 `ValueError` 异常，并检查 `all((not calls for calls in io.values()))` 成立。"""
    with pytest.raises(ValueError, match="documentId"):
        pipeline.run_query(_request("fixed", generate=False, documentId=10))
    assert all(not calls for calls in io.values())


@pytest.mark.parametrize("engine", ENGINES)
def test_empty_authorized_scope_never_starts_io(io, engine):
    """验证 `(response.outcome, response.reasonCode)` 等于 `('no_evidence', 'no_match')`，且 `response.sources == response.evidenceSources == []` 成立。"""
    response = pipeline.run_query(_request(
        engine, knowledgeBaseIds=[], version="hybrid-rerank-rewrite",
        history=[ChatMessage(role="user", content="差旅报销")],
        compressionMode="extractive", mmrEnabled=True, smallToBigEnabled=True,
    ))
    assert (response.outcome, response.reasonCode) == ("no_evidence", "no_match")
    assert response.sources == response.evidenceSources == []
    assert response.pipelineEngine == engine
    assert all(not calls for calls in io.values())


@pytest.mark.parametrize("engine", ENGINES)
def test_successful_empty_recall_short_circuits_generation(monkeypatch, io, engine):
    """验证 `(response.outcome, response.reasonCode)` 等于 `('no_evidence', 'no_match')`，且 `response.retrievalStatus` 等于 `'complete'`。"""
    monkeypatch.setattr(pipeline, "vector_retrieve", lambda *args: [])
    monkeypatch.setattr(pipeline, "keyword_retrieve", lambda *args: [])
    response = pipeline.run_query(_request(engine))
    assert (response.outcome, response.reasonCode) == ("no_evidence", "no_match")
    assert response.retrievalStatus == "complete"
    assert not io["rerank"] and not io["generate"]


@pytest.mark.parametrize("engine", ENGINES)
@pytest.mark.parametrize("scorer,purpose", [
    ("model", "rerank"), ("embed_sim", "embedding"),
])
@pytest.mark.parametrize("calibration_state", ["missing", "mismatched"])
def test_unusable_scorer_calibration_cannot_generate(
        monkeypatch, io, engine, scorer, purpose, calibration_state):
    """验证 `(response.outcome, response.reasonCode)` 等于 `('dependency_unavailable', 'threshold_not_configured')`，且 `response.failedStage` 等于 `'evidence_gate'`。"""
    request = _request(engine)
    model = request.modelExecution.bindings[purpose]
    if calibration_state == "missing":
        model.calibration = None
    else:
        model.calibration.semanticFingerprint = "retired-model-fingerprint"
    monkeypatch.setattr(pipeline, "rerank", lambda request: RerankResponse(
        ids=[c.id for c in request.candidates], scores=[0.99] * len(request.candidates), scorer=scorer))
    response = pipeline.run_query(request)
    assert (response.outcome, response.reasonCode) == (
        "dependency_unavailable", "threshold_not_configured")
    assert response.failedStage == "evidence_gate"
    assert response.sources and response.evidenceSources == []
    assert not io["generate"]


def test_generation_preserves_production_prompt_sources_history_and_usage(io):
    """验证 `response.answer` 等于 `'报销额度为 500 元。[资料1]'`，且 `response.tokenCost == 37 and response.providerUsed == 'ollama'` 成立。"""
    outputs = []
    for engine in ENGINES:
        response = pipeline.run_query(_request(
            engine, version="hybrid-rerank-rewrite",
            history=[ChatMessage(role="user", content="差旅政策是什么？"),
                     ChatMessage(role="assistant", content="请问报销额度还是手续？")],
        ))
        assert response.answer == "报销额度为 500 元。[资料1]"
        assert response.tokenCost == 37 and response.providerUsed == "ollama"
        assert response.rewrittenQuery == "差旅报销规定是什么？"
        assert [s.chunkId for s in response.evidenceSources] == [101, 102]
        assert response.evidenceDecision.state == "accepted"
        assert response.requestId == "engine-contract-id"
        outputs.append(_stable(response))
    assert outputs[0] == outputs[1]
    assert [call[:3] for call in io["generate"]][0] == [call[:3] for call in io["generate"]][1]
    prompt = io["generate"][0][2]
    assert "【对话历史】" in prompt and "差旅政策是什么？" in prompt
    assert "[资料1]" in prompt and "第 2 页" in prompt
    assert '{literal} 与 {"key":"value"}' in prompt


@pytest.mark.parametrize("engine", ENGINES)
def test_unreranked_retrieval_scores_cannot_authorize_generation(io, engine):
    """验证 `(response.outcome, response.reasonCode)` 等于 `('dependency_unavailable', 'scorer_unavailable')`，且 `response.sources and (not response.evidenceSources)` 成立。"""
    for version in ("baseline", "hybrid"):
        response = pipeline.run_query(_request(engine, version=version))
        assert (response.outcome, response.reasonCode) == (
            "dependency_unavailable", "scorer_unavailable")
        assert response.sources and not response.evidenceSources
    assert not io["generate"]


@pytest.mark.parametrize("engine", ENGINES)
@pytest.mark.parametrize("accepted", [True, False])
def test_partial_recall_is_observable_and_never_claims_no_match(monkeypatch, io, engine, accepted):
    """验证 `response.retrievalStatus` 等于 `'partial'`，且 `response.reasonCode` 等于 `'partial_retrieval'`。"""
    def unavailable(*args):
        """触发 `ReadOnlyPgError`，交由上游处理。输入为 `args`。"""
        raise ReadOnlyPgError("fake-pg-secret-must-not-be-returned")

    monkeypatch.setattr(pipeline, "vector_retrieve", unavailable)
    request = _request(engine)
    if not accepted:
        request.modelExecution.bindings["rerank"].calibration.threshold = 0.95
    response = pipeline.run_query(request)
    assert response.retrievalStatus == "partial"
    assert response.reasonCode == "partial_retrieval"
    assert response.outcome == ("success" if accepted else "dependency_unavailable")
    assert bool(io["generate"]) == accepted
    assert "fake-pg-secret" not in response.model_dump_json()
    vector_stage = next(s for s in response.stages if s.stage == "vector_retrieval")
    assert vector_stage.degraded and not vector_stage.success


@pytest.mark.parametrize("engine", ENGINES)
def test_all_recall_legs_failed_is_dependency_failure(monkeypatch, io, engine):
    """验证 `response.sources == response.evidenceSources == []` 成立，且 `not io['rerank'] and (not io['generate'])` 成立。"""
    def unavailable(*args):
        """触发 `ReadOnlyPgError`，交由上游处理。输入为 `args`。"""
        raise ReadOnlyPgError("fake-pg-secret")

    monkeypatch.setattr(pipeline, "vector_retrieve", unavailable)
    monkeypatch.setattr(pipeline, "keyword_retrieve", unavailable)
    response = pipeline.run_query(_request(engine))
    assert (response.outcome, response.reasonCode, response.retrievalStatus) == (
        "dependency_unavailable", "retrieval_unavailable", "unavailable")
    assert response.sources == response.evidenceSources == []
    assert not io["rerank"] and not io["generate"]
    assert "fake-pg-secret" not in response.model_dump_json()


@pytest.mark.parametrize("engine", ENGINES)
@pytest.mark.parametrize("kind,reason", [
    ("missing", "invalid_score_mapping"), ("duplicate", "invalid_score_mapping"),
    ("nan", "scorer_unavailable"), ("out_of_range", "scorer_unavailable"),
])
def test_invalid_scores_are_rejected_without_generation(monkeypatch, io, engine, kind, reason):
    """验证 `(response.outcome, response.reasonCode)` 等于 `('dependency_unavailable', reason)`，且 `response.sources and response.evidenceSources == []` 成立。"""
    def invalid(request):
        """返回 `RerankResponse` 的结果。输入为 `request`。"""
        ids = [c.id for c in request.candidates]
        if kind == "missing":
            return RerankResponse(ids=ids[:-1], scores=[0.9] * (len(ids) - 1), scorer="model")
        if kind == "duplicate":
            return RerankResponse(ids=[ids[0]] * len(ids), scores=[0.9] * len(ids), scorer="model")
        value = float("nan") if kind == "nan" else 1.2
        return RerankResponse(ids=ids, scores=[value] * len(ids), scorer="model")

    monkeypatch.setattr(pipeline, "rerank", invalid)
    response = pipeline.run_query(_request(engine))
    assert (response.outcome, response.reasonCode) == ("dependency_unavailable", reason)
    assert response.sources and response.evidenceSources == []
    assert not io["generate"]
    if kind in ("missing", "duplicate", "nan"):
        assert all(s.score is None for s in response.sources)


@pytest.mark.parametrize("engine", ENGINES)
def test_generation_degradation_preserves_admission_and_usage(monkeypatch, io, engine):
    """验证 `response.answer == '' and response.degraded` 成立，且 `response.degradeReason == 'retry_exhausted' and response.tokenCost == 23` 成立。"""
    monkeypatch.setattr(gate, "call", lambda *args, **kwargs: SimpleNamespace(
        content="must not leak as an answer", total_tokens=23, prompt_tokens=20,
        completion_tokens=3, degraded=True, reason="retry_exhausted", provider="ollama"))
    response = pipeline.run_query(_request(engine))
    assert response.answer == "" and response.degraded
    assert (response.outcome, response.reasonCode, response.failedStage) == (
        "dependency_unavailable", "generation_degraded", "generate")
    assert response.degradeReason == "retry_exhausted" and response.tokenCost == 23
    assert response.sources and response.evidenceSources == []
    assert response.evidenceDecision.state == "accepted"
    assert response.evidenceDecision.acceptedCount == 2
    generate_stage = next(s for s in response.stages if s.stage == "generate")
    assert not generate_stage.success and generate_stage.degraded


@pytest.mark.parametrize("engine", ENGINES)
def test_gate_keeps_raw_retrieval_and_only_admitted_context(monkeypatch, io, engine):
    """验证 `[source.chunkId for source in response.sources]` 等于 `[101, 102]`，且 `[source.chunkId for source in response.evidenceSources] == [101]` 成立。"""
    request = _request(engine)
    request.modelExecution.bindings["rerank"].calibration.threshold = 0.85
    response = pipeline.run_query(request)
    assert [source.chunkId for source in response.sources] == [101, 102]
    assert [source.chunkId for source in response.evidenceSources] == [101]
    assert response.evidenceDecision.acceptedCount == 1
    assert response.evidenceDecision.rejectedCount == 1
    prompt = io["generate"][0][2]
    assert "额度 500 元" in prompt and "报销规定条款 102" not in prompt


@pytest.mark.parametrize("engine", ENGINES)
def test_empty_postprocessed_context_cannot_generate(monkeypatch, io, engine):
    """验证 `(response.outcome, response.reasonCode)` 等于 `('no_evidence', 'empty_context_after_postprocess')`，且 `response.sources and response.evidenceSources == []` 成立。"""
    monkeypatch.setattr(pipeline, "_postprocess", lambda *args: ([], None))
    response = pipeline.run_query(_request(engine))
    assert (response.outcome, response.reasonCode) == (
        "no_evidence", "empty_context_after_postprocess")
    assert response.sources and response.evidenceSources == []
    assert not io["generate"]


def test_parent_expansion_and_compression_preserve_raw_sources_and_prompt_parity(monkeypatch, io):
    """验证 `response.outcome` 等于 `'success'`，且 `'字段 {literal}'` 属于 `response.sources[0].content`。"""
    def expand(candidates, authorized_ids):
        """返回 `candidates`。输入为 `candidates`、`authorized_ids`。"""
        assert authorized_ids == [1]
        # Production expansion retains the authorized child id and score provenance.
        candidates[0].content = "差旅报销额度是 500 元。父节含其他办事手续。"
        return candidates

    monkeypatch.setattr(pipeline, "expand_parent_chunks", expand)
    results = []
    for engine in ENGINES:
        response = pipeline.run_query(_request(
            engine, smallToBigEnabled=True, compressionMode="extractive", tokenBudget=100,
            mmrEnabled=True,
        ))
        assert response.outcome == "success"
        assert "字段 {literal}" in response.sources[0].content
        assert "差旅报销额度是 500 元" in response.evidenceSources[0].content
        assert response.compression.tokensAfter <= 100
        results.append(_stable(response))
    assert results[0] == results[1]
    assert io["generate"][0][:3] == io["generate"][1][:3]


@pytest.mark.parametrize("engine", ENGINES)
def test_budget_consumed_before_endpoint_stops_all_io(io, engine):
    """验证 `(response.outcome, response.reasonCode)` 等于 `('timeout', 'query_deadline_exceeded')`，且 `response.pipelineEngine` 等于 `engine`。"""
    token = deadline.set_request_started_at(monotonic() - 2.0)
    try:
        response = pipeline.run_query(_request(engine, budgetMs=100))
    finally:
        deadline.reset_request_started_at(token)
    assert (response.outcome, response.reasonCode) == ("timeout", "query_deadline_exceeded")
    assert response.pipelineEngine == engine
    assert all(not calls for calls in io.values())


@pytest.mark.parametrize("engine", ENGINES)
def test_all_stages_inherit_one_outer_deadline(monkeypatch, io, engine):
    """验证 `response.outcome` 等于 `'success'`，且 `observed and observed[0] is not None` 成立。"""
    monkeypatch.setattr(pipeline, "RETRIEVAL_LEG_TIMEOUT_MS", 10000)
    response = pipeline.run_query(_request(engine, version="hybrid-rerank-rewrite", budgetMs=5000))
    assert response.outcome == "success"
    observed = [entry[-1] for calls in io.values() for entry in calls]
    assert observed and observed[0] is not None
    assert all(value == observed[0] for value in observed)
    assert deadline.current_deadline_at() is None


@pytest.mark.parametrize("engine", ENGINES)
@pytest.mark.parametrize("headers", [{}, {"X-Internal-Token": "wrong-test-token"}])
def test_http_requires_internal_auth_before_any_engine_io(io, engine, headers):
    """验证 `response.status_code` 等于 `401`，且 `all((not calls for calls in io.values()))` 成立。"""
    response = TestClient(_http_app()).post(
        "/rag/query", json=_request(engine).model_dump(), headers=headers)
    assert response.status_code == 401
    assert all(not calls for calls in io.values())


@pytest.mark.parametrize("engine", ENGINES)
def test_shared_http_capacity_covers_both_engines_and_auth_remains_401(monkeypatch, io, engine):
    """验证 `entered.wait(timeout=5)` 成立，且 `rejected.status_code` 等于 `429`。"""
    entered, release = Event(), Event()

    def waiting(*args):
        """返回 列表。输入为 `args`。"""
        entered.set()
        assert release.wait(timeout=5), "test request was not released"
        return []

    monkeypatch.setattr(pipeline, "vector_retrieve", waiting)
    client = TestClient(_http_app())
    with ThreadPoolExecutor(max_workers=1) as pool:
        future = pool.submit(client.post, "/rag/query", json=_request(
            engine, version="baseline", generate=False).model_dump(), headers=AUTH_HEADERS)
        try:
            assert entered.wait(timeout=5)
            other_engine = "langchain" if engine == "fixed" else "fixed"
            rejected = client.post("/rag/query", json=_request(other_engine).model_dump(),
                                   headers={**AUTH_HEADERS, "X-Request-ID": "capacity-contract"})
            assert rejected.status_code == 429
            detail = rejected.json()["detail"]
            assert detail["reasonCode"] == "query_capacity_rejected"
            assert detail["failedStage"] == "admission"
            assert detail["requestId"] == "capacity-contract"
            unauthenticated = client.post("/rag/query", json=_request(other_engine).model_dump())
            assert unauthenticated.status_code == 401
        finally:
            release.set()
        assert future.result(timeout=5).status_code == 200
    # An occupied request must release the shared slot when it finishes.
    after = client.post("/rag/query", json=_request(
        engine, knowledgeBaseIds=[]).model_dump(), headers=AUTH_HEADERS)
    assert after.status_code == 200


def test_langsmith_environment_cannot_enable_main_or_worker_content_tracing(monkeypatch, io):
    """验证 `response.outcome` 等于 `'success'`，且 `all((enabled is False for _, enabled in observed))` 成立。"""
    from langsmith import Client, tracing_context
    from langsmith.run_helpers import get_tracing_context

    monkeypatch.setenv("LANGSMITH_TRACING", "true")
    monkeypatch.setenv("LANGCHAIN_TRACING_V2", "true")
    exported = []
    # Prevent accidental remote IO even if the test discovers a regression.
    monkeypatch.setattr(Client, "create_run", lambda *args, **kwargs: exported.append("create"))
    monkeypatch.setattr(Client, "update_run", lambda *args, **kwargs: exported.append("update"))
    observed = []

    def wrap(function, phase):
        """向 `test_langsmith_environment_cannot_enable_main_or_worker_content_tracing` 提供预设数据。返回 `checked`。

        输入为 `function`、`phase`。
        """
        def checked(*args, **kwargs):
            """返回 `function` 的结果。输入为 `args`、`kwargs`。"""
            observed.append((phase, get_tracing_context().get("enabled")))
            return function(*args, **kwargs)

        return checked

    for attribute in ("rewrite", "vector_retrieve", "keyword_retrieve", "rerank"):
        monkeypatch.setattr(pipeline, attribute, wrap(getattr(pipeline, attribute), attribute))
    monkeypatch.setattr(gate, "call", wrap(gate.call, "model"))
    # Also defeat an explicitly enabled parent tracing context, not just env defaults.
    with tracing_context(enabled=True):
        response = pipeline.run_query(_request("langchain", version="hybrid-rerank-rewrite"))
    assert response.outcome == "success"
    assert {phase for phase, _ in observed} == {
        "rewrite", "vector_retrieve", "keyword_retrieve", "rerank", "model"}
    assert all(enabled is False for _, enabled in observed)
    assert exported == []


@pytest.mark.parametrize("engine", ENGINES)
def test_concurrent_requests_keep_scopes_queries_and_sources_separate(monkeypatch, io, engine):
    """验证 `response.outcome` 等于 `'success'`，且 `response.pipelineEngine` 等于 `engine`。"""
    def retrieve(kbs, query, limit):
        """返回 列表。输入为 `kbs`、`query`、`limit`。下游调用 `ai.app.retrieval.retriever.Candidate`。"""
        kb = kbs[0]
        return [Candidate(id=kb * 100 + 1, document_id=kb * 10,
                          knowledge_base_id=kb, chunk_index=0, content=f"库 {kb}: {query}")]

    def rank(request):
        """返回 `RerankResponse` 的结果。输入为 `request`。"""
        return RerankResponse(ids=[c.id for c in request.candidates],
                              scores=[0.99] * len(request.candidates), scorer="model")

    monkeypatch.setattr(pipeline, "vector_retrieve", retrieve)
    monkeypatch.setattr(pipeline, "keyword_retrieve", retrieve)
    monkeypatch.setattr(pipeline, "rerank", rank)

    def query(index):
        """返回 元组。输入为 `index`。下游调用 `ai.app.llm.telemetry.set_request_id`、`ai.app.rag.pipeline.run_query`。"""
        kb = 11 if index % 2 == 0 else 22
        request_id = f"concurrent-{engine}-{index}"
        telemetry.set_request_id(request_id)
        question = f"库{kb}的报销问题{index}"
        response = pipeline.run_query(_request(
            engine, knowledgeBaseIds=[kb], question=question, topK=1))
        return kb, question, request_id, response

    with ThreadPoolExecutor(max_workers=2) as pool:
        outputs = list(pool.map(query, range(8)))
    for kb, question, request_id, response in outputs:
        assert response.outcome == "success"
        assert response.pipelineEngine == engine
        assert response.originalQuery == response.rewrittenQuery == question
        assert response.requestId == request_id
        assert all(source.knowledgeBaseId == kb for source in response.sources + response.evidenceSources)
        assert response.evidenceSources[0].content == f"库 {kb}: {question}"
        assert response.sources[0].chunkId == kb * 100 + 1


@pytest.mark.parametrize("engine", ENGINES)
@pytest.mark.parametrize("version,failing_step,failed_stage", [
    ("hybrid-rerank-rewrite", "rewrite", "rewrite"),
    ("baseline", "vector_retrieve", "vector_retrieval"),
])
def test_deadline_failure_stage_survives_framework_context_copy(
        monkeypatch, io, engine, version, failing_step, failed_stage):
    """验证 `response.pipelineEngine` 等于 `engine`，且 `not io['generate'] and 'private-runtime-detail' not in response.model_dump_json()` 成立。"""
    def timed_out(*args, **kwargs):
        """触发 `deadline.QueryDeadlineExceeded`，交由上游处理。输入为 `args`、`kwargs`。"""
        raise deadline.QueryDeadlineExceeded("private-runtime-detail")

    monkeypatch.setattr(pipeline, failing_step, timed_out)
    response = pipeline.run_query(_request(engine, version=version))
    assert (response.outcome, response.reasonCode, response.failedStage) == (
        "timeout", "query_deadline_exceeded", failed_stage)
    assert response.pipelineEngine == engine
    assert not io["generate"] and "private-runtime-detail" not in response.model_dump_json()


@pytest.mark.parametrize("engine", ENGINES)
def test_necessary_retrieval_leg_timeout_is_failure_not_empty_match(monkeypatch, io, engine):
    """验证 `response.retrievalStatus` 等于 `'partial'`，且 `response.sources == response.evidenceSources == []` 成立。"""
    def timed_out(*args, **kwargs):
        """触发 `deadline.QueryDeadlineExceeded`，交由上游处理。输入为 `args`、`kwargs`。"""
        raise deadline.QueryDeadlineExceeded("private-runtime-detail")

    monkeypatch.setattr(pipeline, "vector_retrieve", timed_out)
    monkeypatch.setattr(pipeline, "keyword_retrieve", lambda *args: [])
    response = pipeline.run_query(_request(engine))
    assert (response.outcome, response.reasonCode, response.failedStage) == (
        "timeout", "query_deadline_exceeded", "retrieval")
    assert response.retrievalStatus == "partial"
    assert response.sources == response.evidenceSources == []
    assert not io["generate"]


@pytest.mark.parametrize("has_token_details", [True, False])
def test_lcel_model_output_is_native_message_and_never_invents_usage(monkeypatch, io, has_token_details):
    """验证 `response.outcome == 'success' and response.tokenCost == 37` 成立，且 `isinstance(message, AIMessage)` 成立。"""
    from langchain_core.messages import AIMessage
    from ai.app.rag.langchain_pipeline import GuardedChatModel

    captured = []
    original = GuardedChatModel._generate

    def inspect(self, *args, **kwargs):
        """返回 `result`。输入为 `args`、`kwargs`。"""
        result = original(self, *args, **kwargs)
        captured.append(result.generations[0].message)
        return result

    monkeypatch.setattr(GuardedChatModel, "_generate", inspect)
    if not has_token_details:
        monkeypatch.setattr(gate, "call", lambda *args, **kwargs: SimpleNamespace(
            content='{"status":"success","answer":"有依据的答案"}', total_tokens=37,
            degraded=False, reason="", provider="ollama"))
    response = pipeline.run_query(_request("langchain"))
    assert response.outcome == "success" and response.tokenCost == 37
    message, = captured
    assert isinstance(message, AIMessage)
    assert message.response_metadata["provider"] == "ollama"
    assert message.response_metadata["token_cost"] == 37
    if has_token_details:
        assert message.usage_metadata == {"input_tokens": 30, "output_tokens": 7, "total_tokens": 37}
    else:
        assert message.usage_metadata is None


def test_langchain_is_independent_of_the_complete_fixed_orchestrator(monkeypatch, io):
    """验证 `response.outcome == 'success' and response.pipelineEngine == 'langchain'` 成立。"""
    def forbidden(*args, **kwargs):
        """触发 `AssertionError`，交由上游处理。输入为 `args`、`kwargs`。"""
        raise AssertionError("LCEL must compose steps, not invoke the complete fixed orchestrator")

    monkeypatch.setattr(pipeline, "_execute", forbidden)
    response = pipeline.run_query(_request("langchain"))
    assert response.outcome == "success" and response.pipelineEngine == "langchain"


@pytest.mark.parametrize("engine", ENGINES)
def test_bounded_retrieval_capacity_is_shared_and_cannot_be_bypassed(monkeypatch, io, engine):
    """验证 `slots.acquire(blocking=False)` 成立，且 `response.sources == response.evidenceSources == []` 成立。"""
    slots = BoundedSemaphore(1)
    assert slots.acquire(blocking=False)
    monkeypatch.setattr(retriever, "_LEG_SLOTS", slots)
    try:
        response = pipeline.run_query(_request(engine))
    finally:
        slots.release()
    assert (response.outcome, response.reasonCode, response.retrievalStatus) == (
        "dependency_unavailable", "retrieval_unavailable", "unavailable")
    assert not io["vector"] and not io["keyword"] and not io["rerank"] and not io["generate"]
    assert response.sources == response.evidenceSources == []
    recall = [s for s in response.stages if s.stage in {"vector_retrieval", "keyword_retrieval"}]
    assert len(recall) == 2
    assert all(s.degraded and not s.success and s.error == "capacity_rejected" for s in recall)
