"""验证重排接口、模型执行绑定及分数错误映射。"""

import json

import httpx
import pytest

from ai.app.models.context import model_execution
from ai.app.models.resolution import ModelConfigurationError
from ai.app.models.runtime import ModelCallError
from ai.app.models.schema import ModelExecution
from ai.app.rag.deadline import QueryDeadlineExceeded
from ai.app.rag.pipeline import _rerank_top
from ai.app.rerank import router
from ai.app.rerank.schema import RerankCandidate, RerankRequest
from ai.app.retrieval.retriever import Candidate
from ai.tests.test_managed_models import mock_runtime, selected


@pytest.fixture
def model():
    """为需要该夹具的用例准备输入和隔离状态。返回 `selected` 的结果。下游调用 `ai.tests.test_managed_models.selected`。"""
    return selected("cohere_rerank", source="local", compat={"scoreScale": "probability", "maxBatchSize": 20})


def test_empty_candidates_return_without_model_selection_or_io(monkeypatch):
    """验证 `(result.ids, result.scores, result.scorer)` 等于 `([], [], 'none')`。"""
    monkeypatch.setattr(router, "binding", lambda *_: pytest.fail("empty input must not select a model"))
    monkeypatch.setattr(router.runtime, "rerank", lambda *args: pytest.fail("empty input must not call a model"))
    result = router.rerank(RerankRequest(query="期限", candidates=[]))
    assert (result.ids, result.scores, result.scorer) == ([], [], "none")


def test_sorted_server_results_map_back_to_input_candidate_ids(monkeypatch, model):
    """验证 `result.ids` 等于 `[41, 7]`，且 `result.scores` 等于 `[0.1, 0.9]`。"""
    payloads = []

    def handler(request):
        """返回 `httpx.Response` 的结果。输入为 `request`。"""
        payloads.append(json.loads(request.content))
        return httpx.Response(200, json={"results": [
            {"index": 1, "relevance_score": 0.9}, {"index": 0, "relevance_score": 0.1}]})

    monkeypatch.setattr(router, "runtime", mock_runtime(monkeypatch, handler))
    with model_execution(ModelExecution(bindings={"rerank": model})):
        result = router.rerank(RerankRequest(query="补齐期限", candidates=[
            RerankCandidate(id=41, content="无关资料"), RerankCandidate(id=7, content="三个工作日")]))
    assert result.ids == [41, 7]
    assert result.scores == [0.1, 0.9]
    assert result.scorer == "model" and result.degraded is False
    assert payloads == [{"model": model.model, "query": "补齐期限",
                         "documents": ["无关资料", "三个工作日"], "top_n": 2}]


def test_rerank_stage_sorts_candidates_and_preserves_source_metadata(monkeypatch, model):
    """验证 `all((item.score_valid and item.score_source == 'rerank' for item in result))` 成立。"""
    runtime = mock_runtime(monkeypatch, lambda request: httpx.Response(200, json={"results": [
        {"index": 1, "relevance_score": 0.9}, {"index": 0, "relevance_score": 0.1}]}))
    monkeypatch.setattr(router, "runtime", runtime)
    candidates = [
        Candidate(id=41, document_id=11, knowledge_base_id=5, chunk_index=0,
                  content="无关资料", source_name="无关.txt", content_offset=9),
        Candidate(id=7, document_id=12, knowledge_base_id=5, chunk_index=1,
                  content="三个工作日", source_name="制度.txt", content_offset=17),
    ]
    with model_execution(ModelExecution(bindings={"rerank": model})):
        result = _rerank_top(candidates, "补齐期限", 2)
    assert [(item.id, item.score, item.source_name, item.content_offset) for item in result] == [
        (7, 0.9, "制度.txt", 17), (41, 0.1, "无关.txt", 9)]
    assert all(item.score_valid and item.score_source == "rerank" for item in result)


def test_missing_binding_is_an_explicit_configuration_failure():
    """验证用例触发 `ModelConfigurationError` 异常。"""
    with model_execution(ModelExecution(bindings={})):
        with pytest.raises(ModelConfigurationError):
            router.rerank(RerankRequest(query="期限", candidates=[RerankCandidate(id=1, content="正文")]))


@pytest.mark.parametrize("error", [ModelCallError("model_http_503"), QueryDeadlineExceeded("total budget")])
def test_model_failure_and_timeout_propagate_without_fake_scores(monkeypatch, model, error):
    """验证用例触发 `type(error)` 异常，并检查 `raised.value` 为 `error`。"""
    def failed(*args):
        """触发 `error`，交由上游处理。输入为 `args`。"""
        raise error

    monkeypatch.setattr(router.runtime, "rerank", failed)
    with model_execution(ModelExecution(bindings={"rerank": model})):
        with pytest.raises(type(error)) as raised:
            router.rerank(RerankRequest(query="期限", candidates=[RerankCandidate(id=1, content="正文")]))
    assert raised.value is error
