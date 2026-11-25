"""验证同步 RAG 从请求装配到结果组装、拒答及依赖失败的契约。"""

import time
from concurrent.futures import ThreadPoolExecutor

import pytest

from ai.app.models.schema import Calibration, ModelConfig, ModelExecution
from ai.app.rag import pipeline
from ai.app.rag.pipeline import _retrieve, run_query as _run_query
from ai.app.rag.rag_service import GenerationResult
from ai.app.rag.schema import RagQueryRequest
from ai.app.query.schema import ChatMessage, RewriteResponse
from ai.app.rerank.schema import RerankResponse
from ai.app.retrieval import retriever as ret
from ai.app.retrieval.retriever import RRF_K, Candidate, fan_out_legs, fuse


def _cand(cid, doc=1, idx=0, content="c"):
    """向 `_eligible_cand` 提供预设数据。返回 `Candidate` 的结果。

    输入为 `cid`、`doc`、`idx`、`content`。
    上游为 `test_fuse_rrf_merges_and_orders_by_score`。
    """
    return Candidate(
        id=cid, document_id=doc, knowledge_base_id=1, chunk_index=idx, content=content)


def _eligible_cand(cid, doc=1, idx=0, content="c", score=0.9, scorer="model"):
    """向 `test_run_query_attaches_stages` 提供预设数据。返回 `candidate`。

    输入为 `cid`、`doc`、`idx`、`content`、`score`、`scorer`。
    上游为 `test_partial_retrieval_without_admitted_evidence_is_dependency_failure`，下游调用 `_cand`。
    """
    candidate = _cand(cid, doc=doc, idx=idx, content=content)
    candidate.score = score
    candidate.score_source = "rerank"
    candidate.rerank_scorer = scorer
    candidate.score_valid = True
    return candidate


def _boom(exc):
    """向 `test_document_window_uses_real_admission_and_advances_only_completed_scan` 提供预设数据。返回 `f`。

    输入为 `exc`。
    上游为 `test_document_window_db_failure_does_not_return_advanced_cursor`、`test_invalid_document_window_is_rejected_even_with_empty_scope_before_io`。
    """
    def f(*a, **k):
        """触发 `exc`，交由上游处理。输入为 `a`、`k`。"""
        raise exc

    return f


def _score_execution(threshold):
    """向 `run_query` 提供预设数据。返回 `ModelExecution` 的结果。

    输入为 `threshold`。
    上游为 `test_document_window_uses_real_admission_and_advances_only_completed_scan`，下游调用 `ai.app.models.schema.ModelConfig`、`ai.app.models.schema.Calibration`。
    """
    fingerprint = "test-rerank-semantic-fingerprint"
    rerank_model = ModelConfig(
        id="test-rerank", type="rerank", protocol="cohere_rerank",
        model="test-rerank", baseUrl="http://models.invalid/v1",
        semanticFingerprint=fingerprint,
        calibration=(Calibration(semanticFingerprint=fingerprint, threshold=threshold, reference="fixture")
                     if threshold is not None else None),
    )
    chat_model = ModelConfig(
        id="test-chat", type="chat", protocol="openai_chat", model="test-chat",
        baseUrl="http://models.invalid/v1", capabilities={"json": True},
    )
    embedding_model = ModelConfig(
        id="test-embedding", type="embedding", protocol="openai_embedding",
        model="test-embedding", baseUrl="http://models.invalid/v1", dimension=1024,
    )
    return ModelExecution(bindings={
        "rerank": rerank_model, "generate": chat_model, "rewrite": chat_model,
        "evidence_support": chat_model, "embedding": embedding_model,
    })


# 输入为 `request`。
# 上游为 `test_document_window_db_failure_does_not_return_advanced_cursor`，下游调用 `_score_execution`。
def run_query(request):
    """Give unit cases an explicit model snapshot; production still requires Java to provide one."""
    if request.modelExecution is None:
        request.modelExecution = _score_execution(0.5)
    return _run_query(request)


@pytest.mark.parametrize("engine", ["fixed", "langchain"])
@pytest.mark.parametrize("score,threshold,outcome", [
    (0.9, 0.7, "success"), (0.2, 0.7, "no_evidence"), (0.9, None, "dependency_unavailable"),
])
def test_document_window_uses_real_admission_and_advances_only_completed_scan(monkeypatch, engine, score, threshold, outcome):
    """验证 `response.outcome` 等于 `outcome`，且 `len(calls) == 1 and calls[0].candidates[0].content == '有界原文'` 成立。"""
    from ai.app.rag.schema import DocumentReadRequest
    monkeypatch.setattr(pipeline, "RAG_EVIDENCE_MODE", "score")
    monkeypatch.setattr(ret.db, "query_rows", lambda sql, params: [
        {"id": 11, "document_id": 42, "knowledge_base_id": 7, "chunk_index": 0,
         "content": "有界原文", "content_length": 20}])
    calls = []

    def rank(request):
        """返回 `RerankResponse` 的结果。输入为 `request`。"""
        calls.append(request)
        return RerankResponse(ids=[11], scores=[score], scorer="model")

    monkeypatch.setattr(pipeline, "rerank", rank)
    monkeypatch.setattr(pipeline, "rewrite", _boom(AssertionError("sequential reading must not rewrite")))
    monkeypatch.setattr(pipeline.rag_service, "generate_with_usage", _boom(AssertionError("reading must not generate")))
    response = run_query(RagQueryRequest(pipelineEngine=engine, version="baseline", knowledgeBaseIds=[7],
                                        question="阅读目标", documentId=42, generate=False, admitEvidence=True,
                                        documentRead=DocumentReadRequest(windowChars=10),
                                        modelExecution=_score_execution(threshold)))
    assert response.outcome == outcome
    assert len(calls) == 1 and calls[0].candidates[0].content == "有界原文"
    if outcome == "dependency_unavailable":
        assert response.documentPage is None  # Missing calibration is a failed page, not an empty successful scan.
    else:
        assert response.documentPage.nextOffset == 0
        assert response.documentPage.nextContentOffset == 4
    if outcome == "success":
        assert response.evidenceSources[0].content == "有界原文"
        assert response.evidenceSources[0].contentOffset == 0
    else:
        assert response.evidenceSources == []


@pytest.mark.parametrize("engine", ["fixed", "langchain"])
def test_document_window_db_failure_does_not_return_advanced_cursor(monkeypatch, engine):
    """验证 `response.outcome` 等于 `'dependency_unavailable'`，且 `response.documentPage` 为 `None`。"""
    from ai.app.rag.schema import DocumentReadRequest
    monkeypatch.setattr(ret.db, "query_rows", _boom(ret.db.ReadOnlyPgError("offline")))
    response = run_query(RagQueryRequest(pipelineEngine=engine, knowledgeBaseIds=[7], question="阅读目标",
                                        documentId=42, generate=False, admitEvidence=True,
                                        documentRead=DocumentReadRequest(offset=3, contentOffset=120)))
    assert response.outcome == "dependency_unavailable"
    assert response.documentPage is None


def test_invalid_document_window_is_rejected_even_with_empty_scope_before_io(monkeypatch):
    """验证用例触发 `ValueError` 异常。"""
    from ai.app.rag.schema import DocumentReadRequest
    monkeypatch.setattr(ret.db, "query_rows", _boom(AssertionError("invalid request must not read")))
    with pytest.raises(ValueError, match="window"):
        run_query(RagQueryRequest(knowledgeBaseIds=[], question="阅读目标", documentId=42,
                                 generate=False, admitEvidence=True,
                                 documentRead=DocumentReadRequest(windowChars=1201)))


# ---------- RRF 融合（复刻 Java FusionService） ----------

def test_fuse_rrf_merges_and_orders_by_score():
    """验证 `ids[0]` 等于 `3`，且 `set(ids)` 等于 `{1, 2, 3, 4}`。"""
    v = [
        _cand(1, doc=10, idx=0),   # 向量 rank1 → 1/61
        _cand(2, doc=10, idx=1),   # 向量 rank2 → 1/62
        _cand(3, doc=11, idx=0),   # 向量 rank3 → 1/63
    ]
    k = [
        _cand(3, doc=11, idx=0),   # 关键词 rank1 → +1/61
        _cand(4, doc=12, idx=0),   # 关键词 rank2 → 1/62
    ]
    out = fuse(v, k, 5)
    ids = [c.id for c in out]
    # doc11:0 两路命中、分最高（1/63+1/61）；其余按 RRF 分降序
    assert ids[0] == 3
    assert set(ids) == {1, 2, 3, 4}
    # 每个候选都回填了 score
    assert all(c.score is not None for c in out)


def test_fuse_rrf_k_is_parameterised():
    """C-1（2026-09-30）：rrf_k 是参数而不是模块常量。

    钉两件事：① 缺省仍等于模块常量 RRF_K（改造前后逐位相同）；② 传不同的 k 真的改变分母
    ——否则「逐请求透传」只是把值搬来搬去、评测扫描扫了个寂寞。
    """
    v = [_cand(1, doc=10, idx=0)]
    kw = [_cand(2, doc=11, idx=0)]

    default_scores = {c.id: c.score for c in fuse(v, kw, 5)}
    assert default_scores[1] == pytest.approx(1.0 / (RRF_K + 1))

    tuned = {c.id: c.score for c in fuse(v, kw, 5, 10)}
    assert tuned[1] == pytest.approx(1.0 / 11)      # 分母用传进来的 k=10
    assert tuned[1] > default_scores[1]             # k 越小，同一 rank 的分越大


def test_retrieve_passes_rrf_k_through(monkeypatch):
    """_retrieve 必须把 rrf_k 一路传到 fuse——否则 yml 里的 retrieval.rrf-k 是死旋钮。"""
    seen = {}

    def _fake_fuse(v, kw, n, k=RRF_K):
        """返回 列表。输入为 `v`、`kw`、`n`、`k`。下游调用 `_cand`。"""
        seen["k"] = k
        return [_cand(1)]

    monkeypatch.setattr(pipeline, "vector_retrieve", lambda kb, q, n: [_cand(1)])
    monkeypatch.setattr(pipeline, "keyword_retrieve", lambda kb, q, n: [_cand(2)])
    monkeypatch.setattr(pipeline, "fuse", _fake_fuse)

    _retrieve([1], "q", 5, 20, "hybrid", 7)
    assert seen["k"] == 7


# ---------- version 路由（_retrieve） ----------

def test_retrieve_baseline_uses_vector_only(monkeypatch):
    """验证 `[c.id for c in out]` 等于 `[1]`，且 `calls` 等于 `[('v', 'q', 5)]`。"""
    calls = []
    monkeypatch.setattr(pipeline, "vector_retrieve",
                        lambda kb, q, k: calls.append(("v", q, k)) or [_cand(1)])
    monkeypatch.setattr(pipeline, "keyword_retrieve",
                        lambda kb, q, k: calls.append(("k", q, k)) or [_cand(2)])
    out = _retrieve([1], "q", 5, 20, "baseline")
    assert [c.id for c in out] == [1]
    assert calls == [("v", "q", 5)]   # 关键词路不该被调


def test_retrieve_hybrid_fuses_then_limits(monkeypatch):
    """验证 `[c.id for c in out]` 等于 `[1, 3]`。"""
    monkeypatch.setattr(pipeline, "vector_retrieve", lambda kb, q, k: [_cand(1), _cand(2)])
    monkeypatch.setattr(pipeline, "keyword_retrieve", lambda kb, q, k: [_cand(3)])
    monkeypatch.setattr(pipeline, "fuse", lambda v, kw, n, k=60: [_cand(1), _cand(3), _cand(2)])
    out = _retrieve([1], "q", 2, 20, "hybrid")
    assert [c.id for c in out] == [1, 3]   # 融合后截到 topK


# ---------- 并行召回：单路超时 + 降级（对齐 Java RetrievalService.awaitRetrieval） ----------

@pytest.fixture(autouse=True)
def _fresh_leg_pool(monkeypatch):
    """每个用例换一个干净的 2 worker 池。

    为什么必须换（不是洁癖）：`retriever._LEG_EXECUTOR` 是模块级单例，而**被超时的 leg 不会停止运行**
    ——这正是它要复刻的 Java 语义（超时只让 future 提前返回，不中断底层任务）。于是前一个用例
    留下的 sleeping 任务会占住 2 个 worker，下一个用例的 leg 只能排队「还没开始就超时」，
    断言随机失败。生产侧这个性质与 Java 一致（core=2 + 队列），不修；测试侧换成干净池即可。
    """
    pool = ThreadPoolExecutor(max_workers=2, thread_name_prefix="test-retrieval")
    monkeypatch.setattr(pipeline, "RAG_EVIDENCE_MODE", "score")
    monkeypatch.setattr(ret, "_LEG_EXECUTOR", pool)
    monkeypatch.setattr(ret, "_LEG_SLOTS", __import__("threading").BoundedSemaphore(4))
    yield pool
    pool.shutdown(wait=False)


# 输入为 `delay`、`ret`。
# 上游为 `test_fan_out_legs_both_timeout_yields_empty`、`test_fan_out_legs_zero_timeout_disables_guard`。
def _slow(delay, ret):
    """返回一个「睡 delay 秒后给出 ret」的可调用；兼容 0 参（leg）与 3 参（vector_retrieve）两种签名。"""

    def f(*a, **k):
        """返回 `ret`。输入为 `a`、`k`。"""
        time.sleep(delay)
        return ret

    return f


def test_fan_out_legs_returns_both_when_fast():
    """验证 `timed_out` 等于 `[]`，且 `[c.id for c in results['vector']]` 等于 `[1]`。"""
    results, timed_out, _ = fan_out_legs(
        {"vector": lambda: [_cand(1)], "keyword": lambda: [_cand(2)]}, timeout_ms=2000)
    assert timed_out == []
    assert [c.id for c in results["vector"]] == [1]
    assert [c.id for c in results["keyword"]] == [2]


def test_fan_out_legs_degrades_only_the_timed_out_leg():
    """单路超时 → 该路空结果、另一路照常出结果（「一个慢答案」不该变成「一个失败答案」）。"""
    results, timed_out, _ = fan_out_legs(
        {"vector": _slow(0.4, [_cand(1)]), "keyword": lambda: [_cand(2)]}, timeout_ms=60)
    assert timed_out == ["vector"]
    assert results["vector"] == []
    assert [c.id for c in results["keyword"]] == [2]


def test_fan_out_legs_both_timeout_yields_empty():
    """两路都超时 → 两路空 → 交上层空证据短路，不抛异常。"""
    results, timed_out, _ = fan_out_legs(
        {"vector": _slow(0.4, [_cand(1)]), "keyword": _slow(0.4, [_cand(2)])}, timeout_ms=60)
    assert sorted(timed_out) == ["keyword", "vector"]
    assert results == {"vector": [], "keyword": []}


def test_fan_out_legs_non_timeout_error_propagates():
    """非超时故障（依赖报错）**原样抛出**——与 Java 一致，本次只改「挂住」这一种失败。"""
    with pytest.raises(RuntimeError, match="ollama 500"):
        fan_out_legs(
            {"vector": _boom(RuntimeError("ollama 500")), "keyword": lambda: []}, timeout_ms=2000)


def test_fan_out_legs_zero_timeout_disables_guard():
    """timeout_ms<=0 = 不设超时（对照用旧行为）：慢但有限的 leg 也能跑完。"""
    results, timed_out, _ = fan_out_legs(
        {"vector": _slow(0.2, [_cand(1)]), "keyword": lambda: []}, timeout_ms=0)
    assert timed_out == []
    assert [c.id for c in results["vector"]] == [1]


def test_fan_out_legs_reports_per_leg_durations():
    """每路耗时是真在 worker 里测的：快的 keyword 不该被慢的 vector 的等待时间污染（阶段埋点要用它）。"""
    results, timed_out, durations = fan_out_legs(
        {"vector": _slow(0.25, [_cand(1)]), "keyword": lambda: [_cand(2)]}, timeout_ms=2000)
    assert timed_out == []
    assert durations["vector"] >= 200          # 慢的那路：接近 250ms
    assert durations["keyword"] < 200          # 快的那路：不该被算成 vector 的等待时长
    assert set(durations) == {"vector", "keyword"}


# ---------- 阶段级埋点（决策文档 §5.12.5） ----------

def test_collect_and_record_stages_roundtrip():
    """`collecting()` 内 record 的阶段能按序读出，字段对齐 Java RagSpan。"""
    from ai.app.observability.stage_trace import collecting, record

    with collecting() as collector:
        record("vector_retrieval", 12)
        record("keyword_retrieval", 3, success=False, degraded=True)
        record("generate", 800, total_tokens=321)

    assert [r.stage for r in collector.records] == ["vector_retrieval", "keyword_retrieval", "generate"]
    assert collector.records[1].degraded is True
    assert collector.records[1].success is False
    assert collector.records[2].total_tokens == 321
    assert "generate=800ms/321t" in collector.summary()


def test_stage_context_manager_records_failure_and_reraises():
    """阶段抛错要**如实记失败**并原样抛出（埋点不能吞异常、也不能把失败记成成功）。"""
    from ai.app.observability.stage_trace import collecting, stage

    with collecting() as collector:
        with pytest.raises(RuntimeError, match="boom"):
            with stage("rerank"):
                raise RuntimeError("boom")
    assert len(collector.records) == 1
    assert collector.records[0].success is False
    assert "boom" in collector.records[0].error


def test_record_without_collector_is_noop_not_error():
    """没开 collecting（如直接调 _retrieve 的旧路径）时 record 不该炸——埋点必须对调用方透明。"""
    from ai.app.observability.stage_trace import current, record

    assert current() is None
    rec = record("fusion", 1)
    assert rec.stage == "fusion" and rec.success is True


def test_run_query_retrieve_only_skips_generation(monkeypatch):
    """`generate=False` = 只检索（决策文档 §5.12.10 第 4 步）。

    `/knowledge/search` 与 agent 的 knowledge_search 用它：**必须一次 LLM 都不调**，
    但取到的证据要与问答路径是同一批（rewrite+检索+rerank 照跑）。
    """
    monkeypatch.setattr(pipeline, "_retrieve", lambda *a, **k: [_cand(1)])
    called = []
    monkeypatch.setattr(pipeline.rag_service, "generate_with_usage",
                        lambda *a, **k: called.append(1) or GenerationResult("不该被调", provider="ollama"))

    req = RagQueryRequest(knowledgeBaseIds=[1], question="q", version="hybrid", generate=False)
    resp = run_query(req)

    assert called == []                                 # 生成一次都没调
    assert resp.answer == ""
    assert [s.chunkId for s in resp.sources] == [1]      # 证据照常返回
    assert resp.evidenceSources == []                    # raw retrieval bypasses evidence admission
    assert resp.evidenceDecision.state == "bypassed"
    assert resp.evidenceDecision.acceptedCount == 0
    assert [s.stage for s in resp.stages] == []          # 没有 generate 阶段


def test_run_query_attaches_stages(monkeypatch):
    """端到端：run_query 的响应要带上各阶段记录（Java 侧靠它把 span 写回 RagTrace）。"""
    monkeypatch.setattr(pipeline, "_retrieve", lambda *a, **k: [_eligible_cand(1)])
    monkeypatch.setattr(pipeline.rag_service, "generate_with_usage",
                        lambda *a, **k: GenerationResult("答案", 42, provider="ollama"))
    req = RagQueryRequest(knowledgeBaseIds=[1], question="q", version="hybrid")
    resp = run_query(req)
    assert [s.stage for s in resp.stages] == ["generate"]
    assert resp.stages[0].totalTokens == 42
    assert resp.stages[0].durationMs >= 0
    assert resp.stages[0].success is True
    assert resp.stages[0].degraded is False
    assert resp.stages[0].error == ""


def test_retrieve_hybrid_degrades_to_keyword_when_vector_times_out(monkeypatch, caplog):
    """管线级：向量腿超时后 hybrid 仍能返回关键词那一路的结果（降级必须真的传导到 RRF）。"""
    monkeypatch.setattr(pipeline, "RETRIEVAL_LEG_TIMEOUT_MS", 60)
    monkeypatch.setattr(pipeline, "vector_retrieve", _slow(0.4, [_cand(1)]))
    monkeypatch.setattr(pipeline, "keyword_retrieve", lambda kb, q, k: [_cand(3)])
    with caplog.at_level("WARNING"):
        out = _retrieve([1], "q", 5, 20, "hybrid")
    assert [c.id for c in out] == [3]
    assert "召回降级" in caplog.text          # 降级不能是静默的


def test_required_retrieval_timeout_is_not_reported_as_empty_evidence(monkeypatch):
    """验证 `resp.outcome` 等于 `'timeout'`，且 `resp.reasonCode` 等于 `'query_deadline_exceeded'`。"""
    monkeypatch.setattr(pipeline, "RETRIEVAL_LEG_TIMEOUT_MS", 30)
    monkeypatch.setattr(pipeline, "vector_retrieve", _slow(0.15, []))
    monkeypatch.setattr(pipeline, "keyword_retrieve", lambda *a, **k: [])
    resp = run_query(RagQueryRequest(knowledgeBaseIds=[1], question="q", version="hybrid"))
    assert resp.outcome == "timeout"
    assert resp.reasonCode == "query_deadline_exceeded"
    assert resp.sources == []


def test_successful_empty_retrieval_is_no_evidence(monkeypatch):
    """验证 `resp.outcome` 等于 `'no_evidence'`，且 `resp.reasonCode` 等于 `'no_match'`。"""
    monkeypatch.setattr(pipeline, "vector_retrieve", lambda *a, **k: [])
    monkeypatch.setattr(pipeline, "keyword_retrieve", lambda *a, **k: [])
    resp = run_query(RagQueryRequest(knowledgeBaseIds=[1], question="q", version="hybrid"))
    assert resp.outcome == "no_evidence"
    assert resp.reasonCode == "no_match"


def test_partial_retrieval_without_admitted_evidence_is_dependency_failure(monkeypatch):
    """验证 `resp.outcome` 等于 `'dependency_unavailable'`，且 `resp.reasonCode` 等于 `'partial_retrieval'`。"""
    monkeypatch.setattr(pipeline, "_retrieve_with_status",
                        lambda *a, **k: ([_eligible_cand(2, score=0.1)], "partial", "partial_retrieval"))
    resp = run_query(RagQueryRequest(knowledgeBaseIds=[1], question="q", version="hybrid-rerank",
                                    modelExecution=_score_execution(0.5)))
    assert resp.outcome == "dependency_unavailable"
    assert resp.reasonCode == "partial_retrieval"


@pytest.mark.parametrize(
    ("candidate", "threshold", "outcome", "reason"),
    [
        (_eligible_cand(1, score=0.1), 0.5, "no_evidence", "below_threshold"),
        (_eligible_cand(2, score=0.9), None, "dependency_unavailable", "threshold_not_configured"),
    ],
)
def test_evidence_gate_outcome_mapping(monkeypatch, candidate, threshold, outcome, reason):
    """验证 `(resp.outcome, resp.reasonCode)` 等于 `(outcome, reason)`。"""
    monkeypatch.setattr(pipeline, "_retrieve", lambda *a, **k: [candidate])
    resp = run_query(RagQueryRequest(knowledgeBaseIds=[1], question="q", version="hybrid-rerank",
                                    modelExecution=_score_execution(threshold)))
    assert (resp.outcome, resp.reasonCode) == (outcome, reason)


def test_budget_includes_time_queued_before_sync_endpoint(monkeypatch):
    """验证 `response.outcome` 等于 `'timeout'`，且 `response.reasonCode` 等于 `'query_deadline_exceeded'`。"""
    from ai.app.rag import deadline

    touched = []
    monkeypatch.setattr(pipeline, "_retrieve", lambda *a, **k: touched.append(True))
    token = deadline.set_request_started_at(time.monotonic() - 1.0)
    try:
        response = run_query(RagQueryRequest(
            knowledgeBaseIds=[1], question="q", version="baseline", budgetMs=100))
    finally:
        deadline.reset_request_started_at(token)
    assert response.outcome == "timeout"
    assert response.reasonCode == "query_deadline_exceeded"
    assert touched == []


def test_retrieve_baseline_has_no_fan_out_guard(monkeypatch):
    """baseline 是纯向量、不走两路 fan-out ⇒ 不受单路超时影响（与 Java 档位差异一致）。"""
    monkeypatch.setattr(pipeline, "RETRIEVAL_LEG_TIMEOUT_MS", 60)
    monkeypatch.setattr(pipeline, "vector_retrieve", _slow(0.2, [_cand(1)]))
    monkeypatch.setattr(pipeline, "keyword_retrieve", lambda kb, q, k: [_cand(9)])
    out = _retrieve([1], "q", 5, 20, "baseline")
    assert [c.id for c in out] == [1]         # 慢但没被 60ms 掐掉


def test_retrieve_hybrid_rerank_reorders_by_score(monkeypatch):
    """验证 `[c.id for c in out]` 等于 `[2, 3]`。"""
    monkeypatch.setattr(pipeline, "vector_retrieve", lambda kb, q, k: [_cand(1), _cand(2), _cand(3)])
    monkeypatch.setattr(pipeline, "keyword_retrieve", lambda kb, q, k: [])
    monkeypatch.setattr(pipeline, "fuse", lambda v, kw, n, k=60: [_cand(1), _cand(2), _cand(3)])
    monkeypatch.setattr(pipeline, "rerank",
                        lambda req: RerankResponse(scores=[0.1, 0.9, 0.5], ids=[1, 2, 3]))
    out = _retrieve([1], "q", 2, 20, "hybrid-rerank")
    assert [c.id for c in out] == [2, 3]   # 按分降序取 topK


def test_retrieve_unknown_version_raises():
    """验证用例触发 `ValueError` 异常。"""
    with pytest.raises(ValueError):
        _retrieve([1], "q", 5, 20, "nope")


# ---------- run_query 编排 ----------

def test_run_query_rewrite_version_uses_rewritten_query(monkeypatch):
    """验证 `captured['q']` 等于 `'改写后的问题'`，且 `resp.rewrittenQuery` 等于 `'改写后的问题'`。"""
    monkeypatch.setattr(pipeline, "rewrite",
                        lambda req: RewriteResponse(
                            originalQuery="q", rewrittenQuery="改写后的问题",
                            rewritten=True, reason="补全指代"))
    monkeypatch.setattr(pipeline, "_retrieve",
                        lambda kb, q, k, n, v, rk=RRF_K, **_kw: [_cand(1)])
    captured = {}

    def fake_gen(q, ctxs, hist):
        """返回 `GenerationResult` 的结果。输入为 `q`、`ctxs`、`hist`。"""
        captured["q"] = q
        captured["hist"] = hist
        return GenerationResult("答案", 10, provider="ollama")

    monkeypatch.setattr(pipeline.rag_service, "generate_with_usage", fake_gen)
    monkeypatch.setattr(pipeline, "_retrieve",
                        lambda kb, q, k, n, v, rk=RRF_K, **_kw: [_eligible_cand(1)])
    resp = run_query(RagQueryRequest(
        knowledgeBaseIds=[1], question="q",
        history=[ChatMessage(role="user", content="上文")],
        version="hybrid-rerank-rewrite", modelExecution=_score_execution(0.5)))

    assert captured["q"] == "改写后的问题"        # 生成用改写后的查询
    assert resp.rewrittenQuery == "改写后的问题"
    assert resp.answer == "答案"
    assert len(resp.sources) == 1
    assert resp.sources[0].chunkId == 1
    assert captured["hist"][0].content == "上文"  # 多轮历史透传给生成


def test_run_query_empty_evidence_short_circuits(monkeypatch):
    """验证 `resp.answer` 等于 `''`，且 `resp.sources` 等于 `[]`。"""
    monkeypatch.setattr(pipeline, "_retrieve", lambda *a, **k: [])
    gen_called = []
    monkeypatch.setattr(pipeline.rag_service, "generate_with_usage",
                        lambda *a, **k: gen_called.append(1) or GenerationResult("x"))
    resp = run_query(RagQueryRequest(knowledgeBaseIds=[1], question="q", version="baseline"))
    assert resp.answer == ""            # 空证据：不调 LLM，兜底文案归 Java
    assert resp.sources == []
    assert gen_called == []


def test_run_query_generate_degraded_passthrough(monkeypatch):
    """验证 `resp.degraded` 为 `True`，且 `resp.degradeReason` 等于 `'prompt 超预算直接降级'`。"""
    monkeypatch.setattr(pipeline, "_retrieve", lambda *a, **k: [_eligible_cand(1)])
    monkeypatch.setattr(pipeline.rag_service, "generate_with_usage",
                        lambda *a, **k: GenerationResult("", outcome="dependency_unavailable",
                            reason_code="generation_degraded", degrade_reason="prompt 超预算直接降级", provider="ollama"))
    resp = run_query(RagQueryRequest(knowledgeBaseIds=[1], question="q", version="baseline",
                                    modelExecution=_score_execution(0.5)))
    assert resp.degraded is True
    assert resp.degradeReason == "prompt 超预算直接降级"
    assert resp.providerUsed == "ollama"
    assert resp.answer == ""
    generate_stage = next(s for s in resp.stages if s.stage == "generate")
    assert generate_stage.success is False
    assert generate_stage.degraded is True
    assert generate_stage.error == "generation_degraded"


def test_run_query_rejects_unknown_version():
    """验证用例触发 `ValueError` 异常。"""
    with pytest.raises(ValueError):
        run_query(RagQueryRequest(knowledgeBaseIds=[1], question="q", version="nope"))


# ---------- 父子块 small-to-big（回填父块，复刻 Java ParentChunkExpander） ----------

def test_expand_parent_chunks_backfills_and_dedupes(monkeypatch):
    """验证 `[c.id for c in out]` 等于 `[1, 3]`，且 `out[0].content` 等于 `'父块A'`。"""
    cands = [_cand(1, content="子1"), _cand(2, content="子2"), _cand(3, content="子3")]
    # 子1、子2 同父块 100；子3 无父块
    monkeypatch.setattr(ret.db, "query_rows", lambda sql, params: [
        {"child_id": 1, "document_id": 1, "knowledge_base_id": 1,
         "parent_chunk_id": 100, "content": "父块A"},
        {"child_id": 2, "document_id": 1, "knowledge_base_id": 1,
         "parent_chunk_id": 100, "content": "父块A"},
        {"child_id": 3, "document_id": 1, "knowledge_base_id": 1,
         "parent_chunk_id": None, "content": "子3"},
    ])
    out = ret.expand_parent_chunks(cands, [1])
    assert [c.id for c in out] == [1, 3]   # 子2 与子1 同父块 → 去重
    assert out[0].content == "父块A"         # 回填父块内容
    assert out[1].content == "子3"           # 无父块 → 保留子块自身内容


def test_expand_parent_chunks_falls_back_when_parent_missing(monkeypatch):
    """验证 `len(out)` 等于 `1`，且 `out[0].content` 等于 `'子1'`。"""
    cands = [_cand(1, content="子1")]
    # LEFT JOIN 命中但父块行已不存在（理论上不会）：COALESCE 回落子块内容
    monkeypatch.setattr(ret.db, "query_rows", lambda sql, params: [
        {"child_id": 1, "parent_chunk_id": 999, "content": "子1"},
    ])
    # A row whose child metadata is absent cannot be trusted as belonging to this candidate.
    out = ret.expand_parent_chunks(cands, [1])
    assert len(out) == 1
    assert out[0].content == "子1"


def test_expand_parent_chunks_empty_returns_empty():
    """验证 `ret.expand_parent_chunks([], [1])` 等于 `[]`。"""
    assert ret.expand_parent_chunks([], [1]) == []


def test_run_query_small_to_big_expands_before_generate(monkeypatch):
    """验证 `captured['n']` 等于 `1`，且 `resp.sources[0].content` 等于 `'子1'`。"""
    monkeypatch.setattr(pipeline, "_retrieve", lambda *a, **k: [_eligible_cand(1, content="子1")])
    captured = {}

    def fake_expand(cands, authorized_kb_ids):
        """返回 `cands`。输入为 `cands`、`authorized_kb_ids`。"""
        assert authorized_kb_ids == [1]
        captured["n"] = len(cands)
        # The production expander replaces content on the admitted child object, retaining its
        # score provenance and authorization metadata for the evidence response.
        cands[0].content = "父块A"
        return cands

    monkeypatch.setattr(pipeline, "expand_parent_chunks", fake_expand)
    monkeypatch.setattr(pipeline.rag_service, "generate_with_usage",
                        lambda *a, **k: GenerationResult("答案", 10, provider="ollama"))
    resp = run_query(RagQueryRequest(
        knowledgeBaseIds=[1], question="q", version="baseline", smallToBigEnabled=True,
        modelExecution=_score_execution(0.5)))
    assert captured["n"] == 1                       # expander 被调用
    assert resp.sources[0].content == "子1"          # raw ranked retrieval remains the child
    assert resp.evidenceSources[0].content == "父块A"  # generation evidence carries parent content


def test_run_query_small_to_big_off_skips_expand(monkeypatch):
    """验证 `expanded` 等于 `[]`。"""
    monkeypatch.setattr(pipeline, "_retrieve", lambda *a, **k: [_cand(1, content="子1")])
    expanded = []
    monkeypatch.setattr(pipeline, "expand_parent_chunks", lambda cands: expanded.append(1) or cands)
    monkeypatch.setattr(pipeline.rag_service, "generate_with_usage",
                        lambda *a, **k: GenerationResult("答案", 10, provider="ollama"))
    run_query(RagQueryRequest(knowledgeBaseIds=[1], question="q", version="baseline"))
    assert expanded == []                            # 默认关，不调 expander


# ---------- 路由层错误映射 ----------

def test_router_maps_readonly_pg_error_to_503(monkeypatch):
    """验证用例触发 `HTTPException` 异常，并检查 `e.value.status_code` 等于 `503`。"""
    from ai.app.rag import router as rag_router
    from ai.app.retrieval.db import ReadOnlyPgError
    from fastapi import HTTPException

    monkeypatch.setattr(rag_router, "run_query", _boom(ReadOnlyPgError("x")))
    with pytest.raises(HTTPException) as e:
        rag_router.rag_query(RagQueryRequest(knowledgeBaseIds=[1], question="q"))
    assert e.value.status_code == 503


def test_router_maps_bad_version_to_400(monkeypatch):
    """验证用例触发 `HTTPException` 异常，并检查 `e.value.status_code` 等于 `400`。"""
    from ai.app.rag import router as rag_router
    from fastapi import HTTPException

    monkeypatch.setattr(rag_router, "run_query", _boom(ValueError("非法 retrieval.version")))
    with pytest.raises(HTTPException) as e:
        rag_router.rag_query(RagQueryRequest(knowledgeBaseIds=[1], question="q"))
    assert e.value.status_code == 400


# ---------- 契约形状（Java 侧 camelCase 直绑） ----------

def test_request_accepts_java_camelcase_payload():
    """验证 `req.knowledgeBaseIds` 等于 `[1, 2]`，且 `req.version` 等于 `'hybrid-rerank-rewrite'`。"""
    req = RagQueryRequest.model_validate({
        "knowledgeBaseIds": [1, 2],
        "question": "它快吗？",
        "history": [{"role": "user", "content": "上文"}],
        "topK": 5,
        "version": "hybrid-rerank-rewrite",
        "recallN": 20,
    })
    assert req.knowledgeBaseIds == [1, 2]
    assert req.version == "hybrid-rerank-rewrite"
    assert req.history[0].role == "user"
