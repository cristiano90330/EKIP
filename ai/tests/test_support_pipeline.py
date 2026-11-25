"""验证正文证据准入、摘录规划、后处理和终态生成的连贯性。"""

import json
from types import SimpleNamespace

import pytest

from ai.app.llm.guard import gate
from ai.app.models.context import model_execution
from ai.app.models.schema import ModelConfig, ModelExecution
from ai.app.rag import pipeline
from ai.app.rag.schema import DocumentReadPage, DocumentReadRequest, RagQueryRequest
from ai.app.rerank.schema import RerankResponse
from ai.app.retrieval.retriever import Candidate


@pytest.fixture
def support_io(monkeypatch):
    """为需要该夹具的用例准备输入和隔离状态。

    输入为 `monkeypatch`。
    返回 `calls`。
    下游调用 `ai.app.rag.pipeline.prepare_pipeline_engines`、`ai.app.rerank.schema.RerankResponse`。
    """
    pipeline.prepare_pipeline_engines()
    monkeypatch.setattr(pipeline, "RAG_EVIDENCE_MODE", "support", raising=False)
    calls = {"limits": [], "queries": [], "review": [], "generate": []}

    def recall(scope, query, limit):
        """返回 列表。输入为 `scope`、`query`、`limit`。下游调用 `ai.app.retrieval.retriever.Candidate`。"""
        calls["limits"].append(limit)
        calls["queries"].append(query)
        return [Candidate(id=i, document_id=i, knowledge_base_id=1, chunk_index=0,
                          content="采购合同在签署前由法务审核。" if i == 4 else f"无关资料{i}。")
                for i in range(1, min(limit, 10) + 1)]

    monkeypatch.setattr(pipeline, "vector_retrieve", recall)
    monkeypatch.setattr(pipeline, "keyword_retrieve", lambda *args: [])
    monkeypatch.setattr(pipeline, "rerank", lambda request: RerankResponse(
        ids=[c.id for c in request.candidates],
        scores=[1 - i / 20 for i in range(len(request.candidates))], scorer="model"))

    def model(purpose, system, user, **kwargs):
        """返回 `SimpleNamespace` 的结果。输入为 `purpose`、`system`、`user`、`kwargs`。"""
        if purpose == "evidence_support":
            calls["review"].append(user)
            if len(calls["review"]) % 2:
                content = {"resolved": True, "execution_requirements": [], "standalone_question": "采购合同由谁在何时审核？",
                           "elements": [{"id": "E1", "text": "采购合同由谁在何时审核"}]}
            else:
                content = {"alignment": "问题与来源的适用范围一致。", "question_fully_covered": True, "elements": [
                    {"supported": True, "citations": [
                        {"source_index": 4, "quote": "采购合同在签署前由法务审核。"}]}]}
            return SimpleNamespace(content=json.dumps(content, ensure_ascii=False),
                                   total_tokens=20, degraded=False, finish_reason="stop")
        assert purpose == "generate"
        calls["generate"].append(user)
        return SimpleNamespace(content='{"status":"success","answer":"签署前由法务审核。[资料1]"}', total_tokens=12,
                               prompt_tokens=7, completion_tokens=5, degraded=False,
                               reason="", provider="ollama")

    monkeypatch.setattr(gate, "call", model)
    return calls


def request(engine="fixed", **kwargs):
    # Model and retrieval I/O are stubbed, but the entry requires an execution envelope.
    """向 `test_explicit_light_mode_skips_rerank_and_runs_body_review` 提供预设数据。返回 `RagQueryRequest` 的结果。

    输入为 `engine`、`kwargs`。
    上游为 `test_page_review_receives_actual_window_end_not_a_guessed_document_end`，下游调用 `ai.app.models.schema.ModelConfig`、`ai.app.models.schema.ModelExecution`。
    """
    chat = ModelConfig(id="support-test", type="chat", protocol="openai_chat",
                       model="mock-chat", baseUrl="http://models.invalid/v1",
                       capabilities={"json": True})
    values = dict(pipelineEngine=engine, knowledgeBaseIds=[1],
                  question="采购合同由谁在何时审核？", version="hybrid-rerank",
                  topK=3, recallN=10, modelExecution=ModelExecution(
                      bindings={"generate": chat, "evidence_support": chat}))
    values.update(kwargs)
    return RagQueryRequest(**values)


@pytest.mark.parametrize("entry", ["fixed", "langchain", "stream"])
def test_explicit_light_mode_skips_rerank_and_runs_body_review(support_io, monkeypatch, entry):
    """验证 `events[-1]['event']` 等于 `'done'`，且 `result['evidenceMode']` 等于 `'support'`。"""
    monkeypatch.setattr(pipeline, "RAG_EVIDENCE_MODE", "score")

    def unexpected_rerank(_request):
        """委托 `pytest.fail` 完成当前操作，无直接返回值。输入为 `_request`。"""
        pytest.fail("light mode must not call rerank")

    monkeypatch.setattr(pipeline, "rerank", unexpected_rerank)
    selected = request("fixed" if entry == "stream" else entry, version="hybrid", evidenceMode="support")
    if entry == "stream":
        def stream(*_args, **_kwargs):
            """产出供调用方迭代的结果。输入为 `_args`、`_kwargs`。"""
            content = '{"status":"success","answer":"签署前由法务审核。[资料1]"}'
            yield content
            yield SimpleNamespace(content=content, total_tokens=12, provider="ollama", finish_reason="stop")

        monkeypatch.setattr(gate, "stream", stream)
        events = list(pipeline.run_query_stream(selected))
        assert events[0] == {"event": "execution", "data": {
            "pipelineEngine": "fixed", "retrievalVersion": "hybrid", "evidenceMode": "support"}}
        assert events[-1]["event"] == "done"
        result = events[-1]["data"]
    else:
        result = pipeline.run_query(selected).model_dump()
    assert result["evidenceMode"] == "support"
    assert result["retrievalVersion"] == "hybrid"
    assert result["outcome"] == "success"
    assert support_io["review"]
    assert result["evidenceSources"]
    assert not any(stage["stage"] == "rerank" for stage in result["stages"])


@pytest.mark.parametrize("engine", ["fixed", "langchain"])
@pytest.mark.parametrize(("has_more", "end_offset", "at_document_end"), [
    (True, None, False), (False, None, True), (False, 5, False),
])
def test_page_review_receives_actual_window_end_not_a_guessed_document_end(
        support_io, monkeypatch, engine, has_more, end_offset, at_document_end):
    """验证 `response.outcome` 等于 `'no_evidence'`，且 `response.documentPage` 等于 `page`。"""
    page = DocumentReadPage(offset=0, contentOffset=366, endOffset=end_offset,
                            nextOffset=1 if has_more else None,
                            nextContentOffset=0 if has_more else None,
                            hasMore=has_more, scannedChars=20)
    monkeypatch.setattr(pipeline, "read_document_window", lambda *args: (
        [Candidate(1, 101, 1, 0, "本窗口未记录所问的核验代号。", score=1.0)], page))
    original_call = gate.call
    observed = {}

    def model(purpose, system, user, **kwargs):
        """返回 `SimpleNamespace` 的结果 或 `original_call` 的结果。输入为 `purpose`、`system`、`user`、`kwargs`。"""
        payload = json.JSONDecoder().raw_decode(user.split("\n", 1)[1])[0] if purpose == "evidence_support" else {}
        if "sources" in payload:
            observed.update(payload)
            return SimpleNamespace(content=json.dumps({
                "alignment": "本窗口没有所问事实。", "question_fully_covered": True,
                "elements": [{"supported": False, "citations": []}],
            }), total_tokens=20, degraded=False, finish_reason="stop")
        return original_call(purpose, system, user, **kwargs)

    monkeypatch.setattr(gate, "call", model)
    response = pipeline.run_query(request(engine, documentId=101, generate=False,
        admitEvidence=True, documentRead=DocumentReadRequest(contentOffset=366)))

    assert response.outcome == "no_evidence"
    assert response.documentPage == page
    assert observed["source_window"]["hasMore"] is has_more
    assert observed["source_window"]["documentEndReached"] is at_document_end
    assert observed["source_window"]["contentOffset"] == 366


@pytest.mark.parametrize("entry", ["fixed", "langchain", "stream"])
def test_valid_scope_refusal_stops_before_generation(support_io, monkeypatch, entry):
    """验证 `events[-1]['event']` 等于 `'done'`，且 `not any((event['event'] == 'delta' for event in events))` 成立。"""
    original_call = gate.call

    def model(purpose, system, user, **kwargs):
        """返回 `SimpleNamespace` 的结果 或 `original_call` 的结果。输入为 `purpose`、`system`、`user`、`kwargs`。"""
        if purpose == "evidence_support" and '"sources"' in user:
            assert "alignment" in kwargs["response_format"]["json_schema"]["schema"]["required"]
            return SimpleNamespace(content=json.dumps({
                "alignment": "问题限定其他项目，现有资料未确认适用于该项目。",
                "question_fully_covered": True,
                "elements": [{"supported": False, "citations": []}],
            }), total_tokens=20, finish_reason="stop")
        return original_call(purpose, system, user, **kwargs)

    monkeypatch.setattr(gate, "call", model)
    selected = request("fixed" if entry == "stream" else entry, question="其他项目采购合同由谁在何时审核？")
    if entry == "stream":
        events = list(pipeline.run_query_stream(selected))
        assert events[-1]["event"] == "done"
        assert not any(event["event"] == "delta" for event in events)
        result = events[-1]["data"]
    else:
        result = pipeline.run_query(selected).model_dump()
    assert (result["outcome"], result["reasonCode"]) == ("no_evidence", "support_incomplete")
    assert result["evidenceDecision"]["state"] == "rejected"
    assert result["evidenceSources"] == []
    assert not any(item["stage"] == "generate" for item in result["stages"])
    assert not support_io["generate"]


@pytest.mark.parametrize("engine", ["fixed", "langchain"])
def test_unproven_named_project_scope_is_blocked_before_generation(support_io, monkeypatch, engine):
    """验证 `(result['outcome'], result['reasonCode'])` 等于 `('no_evidence', 'support_scope_unproven')`，且 `result['evidenceDecision']['state']` 等于 `'rejected'`。"""
    question = "柏川项目采购合同由谁审核？"
    source = "采购合同由法务审核。"
    monkeypatch.setattr(pipeline, "vector_retrieve", lambda *args: [Candidate(
        id=4, document_id=44, knowledge_base_id=1, chunk_index=0, content=source)])

    def model(purpose, system, user, **kwargs):
        """返回 `SimpleNamespace` 的结果。输入为 `purpose`、`system`、`user`、`kwargs`。"""
        if purpose == "evidence_support":
            support_io["review"].append(user)
            if '"sources"' not in user:
                content = {"resolved": True, "execution_requirements": [],
                           "standalone_question": question,
                           "elements": [{"id": "E1", "text": "柏川项目采购合同的审核人"}]}
            else:
                content = {"alignment": "原文没有项目范围，但内容看似适用。",
                           "question_fully_covered": True,
                           "elements": [{"supported": True, "citations": [
                               {"source_index": 1, "quote": source}]}]}
            return SimpleNamespace(content=json.dumps(content, ensure_ascii=False),
                                   total_tokens=20, degraded=False, finish_reason="stop")
        support_io["generate"].append(user)
        raise AssertionError("generation must not run after an unproven scope")

    monkeypatch.setattr(gate, "call", model)
    result = pipeline.run_query(request(engine, question=question)).model_dump()

    assert (result["outcome"], result["reasonCode"]) == ("no_evidence", "support_scope_unproven")
    assert result["evidenceDecision"]["state"] == "rejected"
    assert result["evidenceSources"] == []
    assert support_io["generate"] == []


@pytest.mark.parametrize("engine", ["fixed", "langchain"])
def test_generation_refusal_overrides_admitted_evidence(support_io, monkeypatch, engine):
    """验证 `(result.outcome, result.reasonCode)` 等于 `('no_evidence', 'generation_no_evidence')`，且 `result.evidenceDecision.state` 等于 `'accepted'`。"""
    original_call = gate.call

    def model(purpose, *args, **kwargs):
        """返回 `SimpleNamespace` 的结果 或 `original_call` 的结果。输入为 `purpose`、`args`、`kwargs`。"""
        if purpose == "generate":
            return SimpleNamespace(content='{"status":"no_evidence","answer":"无法回答。[资料1]"}',
                                   total_tokens=13, provider="ollama", finish_reason="stop")
        return original_call(purpose, *args, **kwargs)

    monkeypatch.setattr(gate, "call", model)
    result = pipeline.run_query(request(engine))

    assert (result.outcome, result.reasonCode) == ("no_evidence", "generation_no_evidence")
    assert result.evidenceDecision.state == "accepted"
    assert result.sources
    assert result.evidenceSources == []
    assert "[资料1]" not in result.answer
    assert result.failedStage is None
    assert result.providerUsed == "ollama" and result.tokenCost == 13


@pytest.mark.parametrize("status,terminal,reason", [
    ("no_evidence", "done", "generation_no_evidence"),
    ("invalid", "error", "generation_invalid_response"),
])
def test_generation_stream_publishes_only_verified_answer(support_io, monkeypatch, status, terminal, reason):
    """验证 `events[-1]['event']` 等于 `terminal`，且 `events[-1]['data']['reasonCode']` 等于 `reason`。"""
    content = json.dumps({"status": status, "answer": "模型正文。[资料1]"})

    def stream(*args, **kwargs):
        """产出供调用方迭代的结果。输入为 `args`、`kwargs`。"""
        yield content[:20]
        yield content[20:]
        yield SimpleNamespace(content=content, total_tokens=13, provider="ollama", finish_reason="stop")

    monkeypatch.setattr(gate, "stream", stream)
    events = list(pipeline.run_query_stream(request()))

    assert events[-1]["event"] == terminal
    assert events[-1]["data"]["reasonCode"] == reason
    assert not any(event["event"] == "delta" for event in events)
    assert all(not event["data"]["evidenceSources"] for event in events if event["event"] == "sources")
    if terminal == "done":
        assert events[-1]["data"]["evidenceSources"] == []
        assert "[资料1]" not in events[-1]["data"]["answer"]


@pytest.mark.parametrize("engine", ["fixed", "langchain"])
@pytest.mark.parametrize("failure", ["invalid", "transport", "timeout"])
def test_generation_failure_keeps_error_state_and_stage(support_io, monkeypatch, engine, failure):
    """验证 `(response.outcome, response.reasonCode)` 等于 `expected[failure]`，且 `response.failedStage` 等于 `'generate'`。"""
    from ai.app.llm.guard import DegradedResult
    from ai.app.rag.deadline import QueryDeadlineExceeded

    original_call = gate.call

    def model(purpose, *args, **kwargs):
        """返回 `original_call` 的结果 或 `DegradedResult` 的结果 或 `SimpleNamespace` 的结果。

        输入为 `purpose`、`args`、`kwargs`。
        下游调用 `ai.app.rag.deadline.QueryDeadlineExceeded`。
        """
        if purpose != "generate":
            return original_call(purpose, *args, **kwargs)
        if failure == "timeout":
            raise QueryDeadlineExceeded("test total budget")
        if failure == "transport":
            return DegradedResult(reason="private provider error detail", provider="ollama")
        return SimpleNamespace(content="unstructured response", total_tokens=3, provider="ollama")

    monkeypatch.setattr(gate, "call", model)
    response = pipeline.run_query(request(engine))
    expected = {"invalid": ("dependency_unavailable", "generation_invalid_response"),
                "transport": ("dependency_unavailable", "generation_degraded"),
                "timeout": ("timeout", "query_deadline_exceeded")}
    assert (response.outcome, response.reasonCode) == expected[failure]
    assert response.failedStage == "generate"
    assert response.answer == ""
    assert "private provider" not in response.model_dump_json()
    if failure != "timeout":
        assert response.evidenceSources == []
    generate_stage = next(item for item in response.stages if item.stage == "generate")
    assert generate_stage.success is False


@pytest.mark.parametrize("engine", ["fixed", "langchain"])
def test_support_outside_top_three_can_generate_without_changing_raw_sources(support_io, engine):
    """验证 `result.outcome` 等于 `'success'`，且 `[source.chunkId for source in result.sources]` 等于 `[1, 2, 3]`。"""
    result = pipeline.run_query(request(engine))
    assert result.outcome == "success"
    assert [source.chunkId for source in result.sources] == [1, 2, 3]
    assert [source.chunkId for source in result.evidenceSources] == [4]
    assert result.evidenceDecision.retrievedCount == 10
    assert result.evidenceDecision.acceptedCount == 1
    assert result.evidenceDecision.policyVersion.startswith("answer-support-")
    assert support_io["limits"] == [10]
    assert '"source_id":10' in support_io["review"][1]
    review_payload = json.loads(support_io["review"][1].split("\n", 1)[1])
    assert review_payload["original_request"] == result.originalQuery
    assert review_payload["question"] == result.rewrittenQuery
    assert review_payload["standalone_question"] == result.rewrittenQuery
    assert review_payload["retrieval_query"] == result.rewrittenQuery
    assert "采购合同在签署前由法务审核。" in support_io["generate"][0]
    assert "无关资料" not in support_io["generate"][0]


@pytest.mark.parametrize("engine", ["fixed", "langchain"])
@pytest.mark.parametrize("compression", ["none", "extractive"])
def test_source_name_survives_support_excerpting_and_compression(support_io, monkeypatch, engine, compression):
    """验证 `result.outcome` 等于 `'success'`，且 `reviewed_sources[3]['source_name']` 等于 `'采购合同审核规定.txt'`。"""
    from dataclasses import replace

    original_recall = pipeline.vector_retrieve

    def recall(scope, query, limit):
        """返回 列表。输入为 `scope`、`query`、`limit`。"""
        return [replace(candidate, source_name="采购合同审核规定.txt")
                for candidate in original_recall(scope, query, limit)]

    monkeypatch.setattr(pipeline, "vector_retrieve", recall)
    result = pipeline.run_query(request(engine, compressionMode=compression))

    assert result.outcome == "success", result.reasonCode
    reviewed_sources = json.loads(support_io["review"][1].split("\n", 1)[1])["sources"]
    assert reviewed_sources[3]["source_name"] == "采购合同审核规定.txt"
    assert "来源：采购合同审核规定.txt" in support_io["generate"][0]
    assert "采购合同在签署前由法务审核。" in support_io["generate"][0]
    assert "无关资料" not in support_io["generate"][0]


@pytest.mark.parametrize("engine", ["fixed", "langchain"])
def test_generation_receives_complete_qualifying_sentence(support_io, monkeypatch, engine):
    """验证 `result.outcome` 等于 `'success'`，且 `'仅在试点期内，员工可以远程办公。'` 属于 `support_io['generate'][0]`。"""
    from dataclasses import replace

    source = "仅在试点期内，员工可以远程办公。"
    original_recall = pipeline.vector_retrieve

    def recall(scope, query, limit):
        """返回 列表。输入为 `scope`、`query`、`limit`。"""
        return [replace(candidate, content=source) if candidate.id == 4 else candidate
                for candidate in original_recall(scope, query, limit)]

    def model(purpose, system, user, **kwargs):
        """返回 `SimpleNamespace` 的结果。输入为 `purpose`、`system`、`user`、`kwargs`。"""
        if purpose == "evidence_support":
            if '"sources"' not in user:
                content = {"resolved": True, "execution_requirements": [], "standalone_question": "员工在什么情况下可以远程办公？",
                           "elements": [{"id": "E1", "text": "员工远程办公适用条件"}]}
            else:
                content = {"alignment": "问题与来源的适用范围一致。", "question_fully_covered": True, "elements": [
                    {"supported": True, "citations": [
                        {"source_index": 4, "quote": "员工可以远程办公。"}]}]}
            return SimpleNamespace(content=json.dumps(content, ensure_ascii=False),
                                   total_tokens=12, degraded=False, finish_reason="stop")
        support_io["generate"].append(user)
        return SimpleNamespace(content='{"status":"success","answer":"仅在试点期内可以远程办公。"}', total_tokens=12,
                               prompt_tokens=7, completion_tokens=5, degraded=False,
                               reason="", provider="ollama")

    monkeypatch.setattr(pipeline, "vector_retrieve", recall)
    monkeypatch.setattr(gate, "call", model)
    result = pipeline.run_query(request(
        engine, question="员工在什么情况下可以远程办公？"))

    assert result.outcome == "success"
    assert "仅在试点期内，员工可以远程办公。" in support_io["generate"][0]


@pytest.mark.parametrize("engine", ["fixed", "langchain"])
def test_postprocess_cannot_remove_a_required_qualifier_before_generation(
        support_io, monkeypatch, engine):
    """验证 `(result.outcome, result.reasonCode)` 等于 `('dependency_unavailable', 'support_context_lost')`，且 `not support_io['generate']` 成立。"""
    from dataclasses import replace

    source = "仅在试点期内，员工可以远程办公。"
    original_recall = pipeline.vector_retrieve

    def recall(scope, query, limit):
        """返回 列表。输入为 `scope`、`query`、`limit`。"""
        return [replace(candidate, content=source) if candidate.id == 4 else candidate
                for candidate in original_recall(scope, query, limit)]

    def model(purpose, system, user, **kwargs):
        """返回 `SimpleNamespace` 的结果。输入为 `purpose`、`system`、`user`、`kwargs`。"""
        if purpose != "evidence_support":
            support_io["generate"].append(user)
            return SimpleNamespace(content="remote", total_tokens=12, prompt_tokens=7,
                                   completion_tokens=5, degraded=False, reason="",
                                   provider="ollama")
        if '"sources"' not in user:
            content = {"resolved": True, "execution_requirements": [], "standalone_question": "员工在什么情况下可以远程办公？",
                       "elements": [{"id": "E1", "text": "员工远程办公适用条件"}]}
        else:
            content = {"alignment": "问题与来源的适用范围一致。", "question_fully_covered": True, "elements": [
                {"supported": True, "citations": [
                    {"source_index": 4, "quote": "员工可以远程办公。"}]}]}
        return SimpleNamespace(content=json.dumps(content, ensure_ascii=False),
                               total_tokens=12, degraded=False, finish_reason="stop")

    def strip_qualifier(candidates, **kwargs):
        """返回 元组。输入为 `candidates`、`kwargs`。"""
        return ([replace(candidate, content="员工可以远程办公。") if candidate.id == 4 else candidate
                 for candidate in candidates], None)

    monkeypatch.setattr(pipeline, "vector_retrieve", recall)
    monkeypatch.setattr(gate, "call", model)
    monkeypatch.setattr(pipeline, "apply_postprocess", strip_qualifier)
    result = pipeline.run_query(request(
        engine, question="员工在什么情况下可以远程办公？", compressionMode="extractive"))

    assert (result.outcome, result.reasonCode) == ("dependency_unavailable", "support_context_lost")
    assert not support_io["generate"]


@pytest.mark.parametrize("engine", ["fixed", "langchain"])
@pytest.mark.parametrize("fallback", [False, True])
def test_rerank_fallback_is_visible_in_stage_metadata(support_io, monkeypatch, engine, fallback):
    """验证 `result.outcome` 等于 `'success'`，且 `all((source.scorer == 'embed_sim' for source in result.sources))` 成立。"""
    monkeypatch.setattr(pipeline, "rerank", lambda payload: RerankResponse(
        ids=[candidate.id for candidate in payload.candidates],
        scores=[1 - index / 20 for index in range(len(payload.candidates))],
        scorer="embed_sim", degraded=fallback,
        degradeReason="server_unreachable" if fallback else ""))
    result = pipeline.run_query(request(engine))
    assert result.outcome == "success"
    assert all(source.scorer == "embed_sim" for source in result.sources)
    rerank_stage = next(item for item in result.stages if item.stage == "rerank")
    assert rerank_stage.degraded is fallback
    assert rerank_stage.success is True
    assert rerank_stage.error == ""


@pytest.mark.parametrize("entry", ["fixed", "langchain", "stream"])
def test_generation_keeps_original_date_when_reviewer_omits_it(support_io, monkeypatch, entry):
    """验证 `events[-1]['data']['outcome']` 等于 `'success'`，且 `response.outcome` 等于 `'success'`。"""
    from ai.app.rag.rag_service import GenerationResult

    question = "在2032年3月9日，材料补齐期限是多少天？请保留单位。"
    standalone = "材料补齐期限是多少天？"
    source = "自2032年3月10日起，材料须在7天内补齐；生效前沿用2天的旧要求。"
    captured = []
    monkeypatch.setattr(pipeline, "vector_retrieve", lambda *args: [
        Candidate(id=1, document_id=1, knowledge_base_id=1, chunk_index=0, content=source)])

    def review(purpose, system, user, **kwargs):
        """返回 `SimpleNamespace` 的结果。输入为 `purpose`、`system`、`user`、`kwargs`。"""
        if purpose == "generate":
            captured.append(user)
            return SimpleNamespace(content='{"status":"success","answer":"2天。[资料1]"}',
                                   total_tokens=12, degraded=False, finish_reason="stop", provider="ollama")
        assert purpose == "evidence_support"
        if '"sources"' not in user:
            payload = {"resolved": True, "execution_requirements": ["请保留单位"],
                       "standalone_question": standalone,
                       "elements": [{"id": "E1", "text": "2032年3月9日材料补齐期限是多少天"}]}
        else:
            payload = {"alignment": "所问日期在生效日之前，旧规则明确。", "question_fully_covered": True,
                       "elements": [{"supported": True, "citations": [{"source_index": 1, "quote": source}]}]}
        return SimpleNamespace(content=json.dumps(payload, ensure_ascii=False), total_tokens=20,
                               degraded=False, finish_reason="stop")

    def generate(actual_question, contexts, history):
        """向 `generate_stream` 提供预设数据。返回 `GenerationResult` 的结果。输入为 `actual_question`、`contexts`、`history`。"""
        captured.append(actual_question)
        return GenerationResult("2天。[资料1]", 12, provider="ollama")

    def generate_stream(*args):
        """产出供调用方迭代的结果。输入为 `args`。下游调用 `generate`。"""
        result = generate(*args)
        yield "delta", result.answer
        yield "done", result

    monkeypatch.setattr(gate, "call", review)
    selected = request("fixed" if entry == "stream" else entry, question=question)
    if entry == "stream":
        events = list(pipeline.run_query_stream(selected, generate_stream=generate_stream))
        assert events[-1]["data"]["outcome"] == "success", events[-1]["data"]["reasonCode"]
    else:
        response = pipeline.run_query(selected)
        assert response.outcome == "success", response.reasonCode
    assert len(captured) == 1
    assert question in captured[0]
    assert standalone in captured[0]


def test_support_stream_generates_the_reviewed_standalone_question(support_io, monkeypatch):
    """验证 `events[-1]['event']` 等于 `'done'`，且 `evidence_stage < first_delta < generate_stage < sources_event < done_events[0]` 成立。"""
    from dataclasses import replace

    generated = []
    original_recall = pipeline.vector_retrieve

    def recall(scope, query, limit):
        """返回 列表。输入为 `scope`、`query`、`limit`。"""
        return [replace(candidate, source_name="采购合同审核规定.txt")
                for candidate in original_recall(scope, query, limit)]

    monkeypatch.setattr(pipeline, "vector_retrieve", recall)

    def generate_stream(question, contexts, history):
        """产出供调用方迭代的结果。输入为 `question`、`contexts`、`history`。下游调用 `ai.app.rag.rag_service.GenerationResult`。"""
        from ai.app.rag.rag_service import GenerationResult
        generated.append((question, [context.content for context in contexts],
                          [context.sourceName for context in contexts]))
        yield "delta", "签署前由法务审核。"
        yield "done", GenerationResult("签署前由法务审核。", 12, provider="ollama")

    events = list(pipeline.run_query_stream(
        request(question="谁负责审核？"), generate_stream=generate_stream))
    assert events[-1]["event"] == "done"
    evidence_stage = next(index for index, event in enumerate(events)
                          if event["event"] == "stage" and event["data"]["stage"] == "evidence_gate")
    sources_event = next(index for index, event in enumerate(events) if event["event"] == "sources")
    first_delta = next(index for index, event in enumerate(events) if event["event"] == "delta")
    generate_stage = next(index for index, event in enumerate(events)
                          if event["event"] == "stage" and event["data"]["stage"] == "generate")
    done_events = [index for index, event in enumerate(events) if event["event"] == "done"]
    terminal_events = [event for event in events if event["event"] in {"done", "error"}]
    assert evidence_stage < first_delta < generate_stage < sources_event < done_events[0]
    assert len(done_events) == 1 and len(terminal_events) == 1
    assert "采购合同由谁在何时审核？" in generated[0][0]
    assert "谁负责审核？" in generated[0][0]
    assert generated[0][1] == ["采购合同在签署前由法务审核。"]
    assert generated[0][2] == ["采购合同审核规定.txt"]
    assert "无关资料" not in str(generated[0][1])


def test_support_context_loss_stops_stream_before_any_delta(support_io):
    """验证 `events[-1]['event']` 等于 `'error'`，且 `events[-1]['data']['reasonCode']` 等于 `'support_context_lost'`。"""
    def generate_stream(*_args, **_kwargs):
        """产出供调用方迭代的结果。输入为 `_args`、`_kwargs`。"""
        yield "delta", "must-not-leak"

    events = list(pipeline.run_query_stream(
        request(compressionMode="extractive", tokenBudget=1),
        generate_stream=generate_stream))
    assert events[-1]["event"] == "error"
    assert events[-1]["data"]["reasonCode"] == "support_context_lost"
    assert not any(event["event"] == "delta" for event in events)
    assert sum(event["event"] in {"done", "error"} for event in events) == 1
    assert support_io["limits"] == [10]
    assert len(support_io["review"]) == 2
    reviewed_sources = json.loads(support_io["review"][1].split("\n", 1)[1])["sources"]
    assert len(reviewed_sources) == 10


@pytest.mark.parametrize("engine", ["fixed", "langchain"])
def test_support_opt_in_keeps_full_review_without_generation(support_io, engine):
    """验证 `(result.outcome, result.answer)` 等于 `('success', '')`，且 `[source.chunkId for source in result.sources]` 等于 `[1, 2, 3]`。"""
    result = pipeline.run_query(request(engine, generate=False, admitEvidence=True))
    assert (result.outcome, result.answer) == ("success", "")
    assert [source.chunkId for source in result.sources] == [1, 2, 3]
    assert [source.chunkId for source in result.evidenceSources] == [4]
    assert result.evidenceDecision.policyVersion.startswith("answer-support-")
    assert support_io["limits"] == [10]
    assert not support_io["generate"]
    assert any(stage.stage == "evidence_gate" for stage in result.stages)


@pytest.mark.parametrize("engine", ["fixed", "langchain"])
def test_support_opt_in_still_enforces_context_budget(support_io, engine):
    """验证 `not support_io['generate']` 成立。"""
    result = pipeline.run_query(request(
        engine, generate=False, admitEvidence=True, tokenBudget=1))
    assert (result.outcome, result.reasonCode) == (
        "dependency_unavailable", "support_context_capacity_exceeded")
    assert not support_io["generate"]


@pytest.mark.parametrize("entry", ["fixed", "langchain", "fixed_stream"])
def test_admission_only_does_not_require_a_generation_model(support_io, entry):
    """验证 `events[-1]['event']` 等于 `'done'`，且 `not any((event['event'] == 'delta' for event in events))` 成立。"""
    selected = request("langchain" if entry == "langchain" else "fixed",
                       generate=False, admitEvidence=True)
    selected.modelExecution.bindings.pop("generate")
    if entry == "fixed_stream":
        events = list(pipeline.run_query_stream(selected))
        result = events[-1]["data"]
        assert events[-1]["event"] == "done"
        assert not any(event["event"] == "delta" for event in events)
    else:
        result = pipeline.run_query(selected).model_dump()
    assert (result["outcome"], result["answer"]) == ("success", "")
    assert [source["chunkId"] for source in result["evidenceSources"]] == [4]
    assert not support_io["generate"]


@pytest.mark.parametrize("entry", ["fixed", "langchain", "fixed_stream"])
def test_retrieval_hint_does_not_replace_the_question_reviewed_for_support(support_io, entry):
    """验证 `events[-1]['event']` 等于 `'done'`，且 `result['outcome']` 等于 `'success'`。"""
    selected = request("langchain" if entry == "langchain" else "fixed",
                       generate=False, admitEvidence=True, retrievalQuery="采购合同")
    selected.modelExecution.bindings.pop("generate")
    if entry == "fixed_stream":
        events = list(pipeline.run_query_stream(selected))
        assert events[-1]["event"] == "done"
        result = events[-1]["data"]
    else:
        result = pipeline.run_query(selected).model_dump()
    assert result["outcome"] == "success"
    assert result["originalQuery"] == "采购合同由谁在何时审核？"
    assert result["rewrittenQuery"] == "采购合同"
    assert support_io["queries"] == ["采购合同"]
    assert "采购合同由谁在何时审核？" in support_io["review"][0]


@pytest.mark.parametrize("hint", ["   ", "x" * 4001])
def test_invalid_retrieval_hint_is_rejected_before_model_io(hint, support_io):
    """验证用例触发 `ValueError` 异常，并检查 `not support_io['limits']` 成立，且 `not support_io['review']` 成立。"""
    with pytest.raises(ValueError, match="retrievalQuery"):
        pipeline.run_query(request(retrievalQuery=hint))
    assert not support_io["limits"]
    assert not support_io["review"]


@pytest.mark.parametrize("engine", ["fixed", "langchain"])
def test_rewrite_uses_the_retrieval_hint_while_review_keeps_the_original_question(
        support_io, monkeypatch, engine):
    """验证 `result.originalQuery` 等于 `'采购合同由谁在何时审核？'`，且 `result.rewrittenQuery` 等于 `'合同审核'`。"""
    def rewrite_hint(value):
        """返回 `SimpleNamespace` 的结果。输入为 `value`。"""
        assert value.question == "采购合同"
        return SimpleNamespace(rewrittenQuery="合同审核", tokenCost=0)

    monkeypatch.setattr(pipeline, "rewrite", rewrite_hint)
    result = pipeline.run_query(request(engine, version="hybrid-rerank-rewrite",
                                        retrievalQuery="采购合同"))
    assert result.originalQuery == "采购合同由谁在何时审核？"
    assert result.rewrittenQuery == "合同审核"
    assert support_io["queries"] == ["合同审核"]
    assert "采购合同由谁在何时审核？" in support_io["review"][0]


@pytest.mark.parametrize("entry", ["fixed", "langchain", "fixed_stream"])
def test_refusal_preserves_the_actual_retrieval_hint(support_io, monkeypatch, entry):
    """验证 `result['outcome']` 等于 `'no_evidence'`，且 `result['originalQuery']` 等于 `'采购合同由谁在何时审核？'`。"""
    def unresolved(*args, **kwargs):
        """返回 `SimpleNamespace` 的结果。输入为 `args`、`kwargs`。"""
        return SimpleNamespace(content=json.dumps({
            "resolved": False, "execution_requirements": [], "standalone_question": "", "elements": []}),
            total_tokens=1, finish_reason="stop")

    monkeypatch.setattr(gate, "call", unresolved)
    selected = request("langchain" if entry == "langchain" else "fixed", retrievalQuery="采购合同")
    if entry == "fixed_stream":
        events = list(pipeline.run_query_stream(selected))
        result = events[-1]["data"]
    else:
        result = pipeline.run_query(selected).model_dump()
    assert result["outcome"] == "no_evidence"
    assert result["originalQuery"] == "采购合同由谁在何时审核？"
    assert result["rewrittenQuery"] == "采购合同"


@pytest.mark.parametrize("engine", ["fixed", "langchain"])
@pytest.mark.parametrize("generate,scope", [(False, [1]), (True, [])])
def test_search_and_empty_scope_skip_support_calls(support_io, engine, generate, scope):
    """验证 `not support_io['review'] and (not support_io['generate'])` 成立，且 `result.evidenceSources` 等于 `[]`。"""
    result = pipeline.run_query(request(engine, generate=generate, knowledgeBaseIds=scope))
    assert not support_io["review"] and not support_io["generate"]
    assert result.evidenceSources == []
    assert len(result.sources) == (3 if scope else 0)


@pytest.mark.parametrize("engine", ["fixed", "langchain"])
def test_compression_that_removes_required_quote_cannot_generate(support_io, engine):
    """验证 `(result.outcome, result.reasonCode)` 等于 `('dependency_unavailable', 'support_context_lost')`，且 `result.evidenceSources == [] and (not support_io['generate'])` 成立。"""
    result = pipeline.run_query(request(engine, compressionMode="extractive", tokenBudget=1))
    assert (result.outcome, result.reasonCode) == ("dependency_unavailable", "support_context_lost")
    assert result.evidenceSources == [] and not support_io["generate"]
    assert result.evidenceDecision.acceptedCount == 0
    assert result.evidenceDecision.rejectedCount == result.evidenceDecision.retrievedCount


@pytest.mark.parametrize("engine", ["fixed", "langchain"])
def test_uncompressed_support_must_fit_the_generation_budget(support_io, engine):
    """验证 `not support_io['generate']` 成立。"""
    result = pipeline.run_query(request(engine, tokenBudget=1))
    assert (result.outcome, result.reasonCode) == ("dependency_unavailable", "support_context_capacity_exceeded")
    assert not support_io["generate"]


@pytest.mark.parametrize("engine", ["fixed", "langchain"])
@pytest.mark.parametrize("failure", ["outage", "invalid_json", "truncated"])
def test_review_failure_is_dependency_failure(support_io, monkeypatch, engine, failure):
    """验证 `result.outcome` 等于 `'dependency_unavailable'`，且 `result.failedStage` 等于 `'evidence_gate'`。"""
    def fail(*args, **kwargs):
        """返回 `SimpleNamespace` 的结果。输入为 `args`、`kwargs`。"""
        if failure == "outage":
            raise RuntimeError("model offline")
        return SimpleNamespace(content="{" if failure == "invalid_json" else "{}",
                               finish_reason="length" if failure == "truncated" else "stop")

    monkeypatch.setattr(gate, "call", fail)
    result = pipeline.run_query(request(engine))
    assert result.outcome == "dependency_unavailable"
    assert result.failedStage == "evidence_gate"
    assert result.answer == "" and result.evidenceSources == []


@pytest.mark.parametrize("engine", ["fixed", "langchain"])
def test_partial_retrieval_cannot_claim_complete_support(support_io, monkeypatch, engine):
    """验证 `(result.outcome, result.reasonCode)` 等于 `('dependency_unavailable', 'partial_retrieval')`，且 `result.evidenceDecision.policyVersion.startswith('answer-support-')` 成立。"""
    def broken(*args):
        """触发 `ReadOnlyPgError`，交由上游处理。输入为 `args`。"""
        from ai.app.retrieval.db import ReadOnlyPgError
        raise ReadOnlyPgError("keyword offline")

    monkeypatch.setattr(pipeline, "keyword_retrieve", broken)
    result = pipeline.run_query(request(engine))
    assert (result.outcome, result.reasonCode) == ("dependency_unavailable", "partial_retrieval")
    assert result.evidenceDecision.policyVersion.startswith("answer-support-")
    assert not support_io["review"] and not support_io["generate"]


@pytest.mark.parametrize("engine", ["fixed", "langchain"])
def test_support_deadline_is_shared_with_retrieval(support_io, monkeypatch, engine):
    """验证 `(result.outcome, result.reasonCode)` 等于 `('timeout', 'query_deadline_exceeded')`，且 `result.failedStage` 等于 `'evidence_gate'`。"""
    from ai.app.rag.deadline import QueryDeadlineExceeded

    def expired(*args, **kwargs):
        """触发 `QueryDeadlineExceeded`，交由上游处理。输入为 `args`、`kwargs`。"""
        raise QueryDeadlineExceeded("budget spent")

    monkeypatch.setattr(gate, "call", expired)
    result = pipeline.run_query(request(engine))
    assert (result.outcome, result.reasonCode) == ("timeout", "query_deadline_exceeded")
    assert result.failedStage == "evidence_gate"


def test_support_readiness_depends_on_observed_reviewer_not_score_thresholds(monkeypatch):
    """验证 `result['reasonCode']` 等于 `'evidence_support_failed'`，且 `result['ready']` 为 `False`。"""
    from ai.app.observability import readiness
    monkeypatch.setattr(readiness, "RAG_EVIDENCE_MODE", "support", raising=False)
    monkeypatch.setattr(gate, "readiness", lambda purpose: {
        "status": "not_ready", "reasonCode": "evidence_support_failed"})
    with model_execution(request().modelExecution):
        result = readiness.ReadinessService._evidence_gate(
            {"scorer": "model", "status": "ready", "ready": True, "reasonCode": "none"},
            {"status": "ready", "ready": True, "reasonCode": "none"})
    assert result["reasonCode"] == "evidence_support_failed"
    assert result["ready"] is False


def test_invalid_review_cannot_report_ready_even_when_model_replies(monkeypatch):
    """验证 `result['ready']` 为 `False`，且 `result['reasonCode']` 等于 `'support_model_incomplete_output'`。"""
    from ai.app.observability import readiness
    monkeypatch.setattr(readiness, "RAG_EVIDENCE_MODE", "support")
    monkeypatch.setattr(gate, "readiness", lambda purpose: {
        "status": "ready", "reasonCode": "none"})
    monkeypatch.setattr("ai.app.rag.evidence_support.readiness", lambda: {
        "status": "not_ready", "reasonCode": "support_model_incomplete_output"})
    with model_execution(request().modelExecution):
        result = readiness.ReadinessService._evidence_gate({}, {})
    assert result["ready"] is False
    assert result["reasonCode"] == "support_model_incomplete_output"


@pytest.mark.parametrize("engine", ["fixed", "langchain"])
def test_partial_answer_support_refuses_generation(support_io, monkeypatch, engine):
    """验证 `(result.outcome, result.reasonCode)` 等于 `('no_evidence', 'support_incomplete')`，且 `result.failedStage` 为 `None`。"""
    original = gate.call

    def incomplete(purpose, system, user, **kwargs):
        """返回 `SimpleNamespace` 的结果 或 `original` 的结果。输入为 `purpose`、`system`、`user`、`kwargs`。"""
        if purpose == "evidence_support" and '"sources"' in user:
            return SimpleNamespace(content='{"alignment":"资料不足以支持全部所问要点。","question_fully_covered":true,"elements":[{"supported":false,"citations":[]}]}',
                                   finish_reason="stop", total_tokens=6)
        return original(purpose, system, user, **kwargs)

    monkeypatch.setattr(gate, "call", incomplete)
    result = pipeline.run_query(request(engine))
    assert (result.outcome, result.reasonCode) == ("no_evidence", "support_incomplete")
    assert result.failedStage is None
    assert result.evidenceDecision.acceptedCount == 0
    assert not support_io["generate"] and result.evidenceSources == []


@pytest.mark.parametrize("engine", ["fixed", "langchain"])
def test_partial_refusal_is_not_reclassified_by_nonverbatim_positive_quote(
        support_io, monkeypatch, engine):
    """验证 `(result.outcome, result.reasonCode)` 等于 `('no_evidence', 'support_incomplete')`，且 `result.failedStage` 为 `None`。"""
    original = gate.call

    def partial_refusal(purpose, system, user, **kwargs):
        """返回 `SimpleNamespace` 的结果 或 `original` 的结果。输入为 `purpose`、`system`、`user`、`kwargs`。"""
        if purpose == "evidence_support":
            if '"sources"' in user:
                content = {"alignment": "问题与来源的适用范围一致。", "question_fully_covered": True, "elements": [
                    {"supported": True, "citations": [
                        {"source_index": 4, "quote": "法务审核采购合同"}]},
                    {"supported": False, "citations": []},
                ]}
            else:
                content = {"resolved": True, "execution_requirements": [],
                           "standalone_question": "采购合同由谁审核，未休年假是否折现？",
                           "elements": [
                               {"id": "E1", "text": "采购合同审核责任人"},
                               {"id": "E2", "text": "未休年假折现政策"},
                           ]}
            return SimpleNamespace(content=json.dumps(content, ensure_ascii=False),
                                   finish_reason="stop", total_tokens=6)
        return original(purpose, system, user, **kwargs)

    monkeypatch.setattr(gate, "call", partial_refusal)
    result = pipeline.run_query(request(engine))

    assert (result.outcome, result.reasonCode) == ("no_evidence", "support_incomplete")
    assert result.failedStage is None
    assert result.evidenceDecision.acceptedCount == 0
    assert not support_io["generate"] and result.evidenceSources == []


@pytest.mark.parametrize("engine", ["fixed", "langchain"])
def test_parent_expansion_cannot_send_unreviewed_text_to_generate(support_io, monkeypatch, engine):
    """验证 `result.outcome` 等于 `'success'`，且 `'未经审查'` 不属于 `support_io['generate'][0]`。"""
    from dataclasses import replace
    monkeypatch.setattr(pipeline, "expand_parent_chunks", lambda candidates, scope, **kwargs: [
        replace(candidate, content=candidate.content + "未经审查的父块附加文字。")
        for candidate in candidates])
    result = pipeline.run_query(request(engine, smallToBigEnabled=True))
    assert result.outcome == "success"
    assert "未经审查" not in support_io["generate"][0]


@pytest.mark.parametrize("engine", ["fixed", "langchain"])
def test_sibling_quotes_keep_both_source_identities_after_real_parent_expansion(
        support_io, monkeypatch, engine):
    """验证 `result.outcome` 等于 `'success'`，且 `{source.chunkId for source in result.evidenceSources}` 等于 `{4, 5}`。"""
    from dataclasses import replace
    from ai.app.retrieval import retriever
    legal = "采购合同在签署前由法务审核。"
    finance = "付款前由财务审核。"
    original_recall, original_model = pipeline.vector_retrieve, gate.call

    def recall(*args):
        """返回 列表。输入为 `args`。"""
        return [replace(c, document_id=4, chunk_index=1, content=finance) if c.id == 5 else c
                for c in original_recall(*args)]

    def model(purpose, system, user, **kwargs):
        """返回 `original_model` 的结果 或 `SimpleNamespace` 的结果。输入为 `purpose`、`system`、`user`、`kwargs`。"""
        if purpose != "evidence_support":
            return original_model(purpose, system, user, **kwargs)
        if '"standalone_question"' not in user:
            content = {"resolved": True, "execution_requirements": [], "standalone_question": "合同签署和付款由谁审核？",
                       "elements": [{"id": "E1", "text": "签署审核"},
                                    {"id": "E2", "text": "付款审核"}]}
        else:
            content = {"alignment": "问题与来源的适用范围一致。", "question_fully_covered": True, "elements": [
                {"supported": True, "citations": [{"source_index": 4, "quote": legal}]},
                {"supported": True, "citations": [{"source_index": 5, "quote": finance}]}]}
        return SimpleNamespace(content=json.dumps(content, ensure_ascii=False),
                               finish_reason="stop", total_tokens=30)

    monkeypatch.setattr(pipeline, "vector_retrieve", recall)
    monkeypatch.setattr(gate, "call", model)
    monkeypatch.setattr(retriever.db, "query_rows", lambda *args: [
        {"child_id": cid, "document_id": 4, "knowledge_base_id": 1,
         "parent_chunk_id": 99, "content": legal + finance + "父块未核验附注。"}
        for cid in (4, 5)])
    result = pipeline.run_query(request(engine, smallToBigEnabled=True))
    assert result.outcome == "success", result.reasonCode
    assert {source.chunkId for source in result.evidenceSources} == {4, 5}
    assert "父块未核验附注" not in support_io["generate"][0]


@pytest.mark.parametrize("mode", ["", "permissive", "Support"])
def test_invalid_support_mode_fails_startup_validation(monkeypatch, mode):
    """验证用例触发 `RuntimeError` 异常。"""
    from ai.app import config
    monkeypatch.setattr(config, "RAG_EVIDENCE_MODE", mode)
    with pytest.raises(RuntimeError, match="RAG_EVIDENCE_MODE"):
        config.validate_rag_limits()


@pytest.mark.parametrize("engine", ["fixed", "langchain"])
def test_generation_dependency_details_stay_out_of_public_response(support_io, monkeypatch, engine):
    """验证 `result.outcome` 等于 `'dependency_unavailable'`，且 `'fake-secret-token'` 不属于 `result.model_dump_json()`。"""
    original = gate.call

    def model(purpose, system, user, **kwargs):
        """返回 `original` 的结果 或 `SimpleNamespace` 的结果。输入为 `purpose`、`system`、`user`、`kwargs`。"""
        if purpose != "generate":
            return original(purpose, system, user, **kwargs)
        return SimpleNamespace(content="", total_tokens=0, prompt_tokens=0, completion_tokens=0,
                               degraded=True, reason="final err: APIError: fake-secret-token",
                               provider="ollama")

    monkeypatch.setattr(gate, "call", model)
    result = pipeline.run_query(request(engine))
    assert result.outcome == "dependency_unavailable"
    assert "fake-secret-token" not in result.model_dump_json()
