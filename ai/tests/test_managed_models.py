"""验证模型绑定、配置解析、模型请求及运行时协议转换。"""

import asyncio
import json
import threading
import time
from concurrent.futures import ThreadPoolExecutor

import httpx
import pytest
from fastapi.testclient import TestClient

from ai.app.embedding.embedding_service import EmbeddingService
from ai.app.embedding.errors import EmbeddingDimensionError, EmbeddingUnavailableError
from ai.app.llm.guard import ModelGate
from ai.app.main import app
from ai.app.models.cancellation import ModelCancellationMiddleware, cancellation_scope
from ai.app.models.context import binding, current_execution, evidence_threshold, model_execution, iterate_in_context
from ai.app.models.resolution import ModelConfigurationError, provider_metadata, resolve
from ai.app.models.runtime import ModelCallError, ModelRuntime, ResourceLimits, identity
from ai.app.models.schema import ModelConfig, ModelExecution
from ai.app.rag.deadline import QueryDeadlineExceeded, current_deadline_at, query_deadline
from ai.app.retrieval.retriever import fan_out_legs
from ai.tests.conftest import AUTH_HEADERS


def selected(protocol="openai_chat", **overrides):
    """向 `test_retained_protocol_adapters_use_resolved_payload_and_reorder_by_index` 提供预设数据。返回 `ModelConfig` 的结果。

    输入为 `protocol`、`overrides`。
    上游为 `test_invalid_embedding_never_populates_cache`。
    """
    kind = "chat" if protocol.endswith("chat") else "embedding" if protocol.endswith("embedding") else "rerank"
    values = dict(id="model-a", semanticFingerprint="semantic-a", type=kind, name="test",
                  provider="generic", protocol=protocol, source="remote", model="example",
                  baseUrl="http://models.invalid/v1", capabilities={"streaming": True} if kind == "chat" else {})
    if protocol == "dashscope_rerank":
        values["baseUrl"] = "https://dashscope.invalid"
    if kind == "embedding":
        values["dimension"] = 2
    values.update(overrides)
    return ModelConfig(**values)


def mock_runtime(monkeypatch, handler):
    """向 `test_retained_protocol_adapters_use_resolved_payload_and_reorder_by_index` 提供预设数据。返回 `runtime`。

    输入为 `monkeypatch`、`handler`。
    上游为 `test_invalid_embedding_never_populates_cache`，下游调用 `ai.app.models.runtime.ModelRuntime`。
    """
    runtime = ModelRuntime()

    def client(model):
        """返回 `attach` 的结果。输入为 `model`。下游调用 `ai.app.models.transport.state`。"""
        from ai.app.models.transport import state
        return state().attach(httpx.Client(transport=httpx.MockTransport(handler), follow_redirects=False))

    monkeypatch.setattr(runtime, "client", client)
    return runtime


@pytest.mark.parametrize("protocol", ["openai_chat", "openai_embedding", "cohere_rerank", "dashscope_rerank"])
def test_retained_protocol_adapters_use_resolved_payload_and_reorder_by_index(monkeypatch, protocol):
    """验证 `result.content == 'OK' and result.total_tokens == 4` 成立，且 `runtime.embed(model, ['a', 'b'])` 等于 `[[1.0, 2.0], [2.0, 1.0]]`。"""
    captured = []

    def handler(request):
        """返回 `httpx.Response` 的结果。输入为 `request`。"""
        body = json.loads(request.content)
        captured.append((request, body))
        if protocol == "openai_chat":
            return httpx.Response(200, json={"choices":[{"message":{"content":"OK"},"finish_reason":"stop"}],
                                            "usage":{"prompt_tokens":3,"completion_tokens":1}})
        if protocol == "openai_embedding":
            return httpx.Response(200, json={"data":[{"index":1,"embedding":[2,1]}, {"index":0,"embedding":[1,2]}]})
        rows = [{"index":1,"relevance_score":0.2},{"index":0,"relevance_score":0.9}]
        return httpx.Response(200, json={"output":{"results":rows}} if protocol == "dashscope_rerank" else {"results":rows})

    runtime, model = mock_runtime(monkeypatch, handler), selected(protocol, apiKey="test-secret")
    purpose = {"chat":"agent","embedding":"embedding","rerank":"rerank"}[model.type]
    with model_execution(ModelExecution(bindings={purpose:model})):
        if model.type == "chat":
            result = runtime.chat(model, purpose, [{"role":"user","content":"hello"}], max_tokens=5)
            assert result.content == "OK" and result.total_tokens == 4
        elif model.type == "embedding":
            assert runtime.embed(model, ["a","b"]) == [[1.,2.],[2.,1.]]
        else:
            assert runtime.rerank(model, "question", ["a","b"]) == [0.9,0.2]
    request, body = captured[0]
    assert str(request.url) == resolve(model)["endpoint"]
    assert request.headers["Authorization"] == "Bearer test-secret"
    assert body["model"] == "example"
    if protocol == "dashscope_rerank":
        assert body["input"] == {"query":"question","documents":["a","b"]}


@pytest.mark.parametrize("bad", [[[0,0]], [[float("nan"),1]], [[1]], [[True,1]]])
def test_invalid_embedding_never_populates_cache(monkeypatch, bad):
    """验证用例触发 `ModelCallError` 异常。"""
    runtime = mock_runtime(monkeypatch, lambda request: httpx.Response(200,json={"data":[{"index":0,"embedding":bad[0]}]}))
    model = selected("openai_embedding")
    with model_execution(ModelExecution(bindings={"embedding":model})), pytest.raises(ModelCallError):
        runtime.embed(model,["a"])


def test_embedding_deduplicates_and_reuses_successful_managed_results(monkeypatch):
    """验证用例触发 `EmbeddingUnavailableError` 异常，并检查 `service.embed_batch(['same', 'same'])` 等于 `[[1, 2], [1, 2]]`，且 `service.embed_batch(['same'])` 等于 `[[1, 2]]`。"""
    batches = []

    def handler(request):
        """返回 `httpx.Response` 的结果。输入为 `request`。"""
        texts = json.loads(request.content)["input"]
        batches.append(texts)
        if len(batches) == 1:
            return httpx.Response(200, json={"data": []})
        return httpx.Response(200, json={"data": [{"index": 0, "embedding": [1, 2]}]})

    runtime = mock_runtime(monkeypatch, handler)
    monkeypatch.setattr("ai.app.embedding.embedding_service.runtime", runtime)
    service = EmbeddingService()
    model = selected("openai_embedding")
    with model_execution(ModelExecution(bindings={"embedding": model})):
        with pytest.raises(EmbeddingUnavailableError):
            service.embed_batch(["same", "same"])
        assert service.embed_batch(["same", "same"]) == [[1, 2], [1, 2]]
        assert service.embed_batch(["same"]) == [[1, 2]]
    assert batches == [["same"], ["same"]]


@pytest.mark.parametrize(("failure", "status", "message"), [
    (EmbeddingUnavailableError("upstream down"), 503, "嵌入服务不可用"),
    (EmbeddingDimensionError("unexpected dimension"), 422, "嵌入维度配置错误"),
])
def test_managed_embedding_http_errors_remain_distinct(monkeypatch, failure, status, message):
    """验证 `response.status_code` 等于 `status`，且 `message` 属于 `response.json()['detail']`。"""
    def fail(texts):
        """触发 `failure`，交由上游处理。输入为 `texts`。下游调用 `ai.app.models.context.binding`。"""
        assert binding("embedding").id == "model-a"
        raise failure

    monkeypatch.setattr("ai.app.embedding.router.embedding_service.embed_batch", fail)
    execution = ModelExecution(bindings={"embedding": selected("openai_embedding")})
    response = TestClient(app, headers=AUTH_HEADERS).post("/embedding", json={
        "texts": ["a"], "modelExecution": execution.model_dump(mode="json"),
    })
    assert response.status_code == status
    assert message in response.json()["detail"]


def test_error_response_and_validation_never_echo_credentials(monkeypatch):
    """验证用例触发 `ModelCallError` 异常，并检查 `str(failure.value)` 等于 `'model_http_401'`，且 `secret not in repr(model) and secret not in model.model_dump_json()` 成立。"""
    secret = "do-not-echo-this-secret"
    model = selected(apiKey=secret)
    runtime = mock_runtime(monkeypatch, lambda request: httpx.Response(401,text=secret))
    with model_execution(ModelExecution(bindings={"agent":model})), pytest.raises(ModelCallError) as failure:
        runtime.chat(model,"agent",[{"role":"user","content":"x"}])
    assert str(failure.value) == "model_http_401"
    assert secret not in repr(model) and secret not in model.model_dump_json()
    client = TestClient(app,headers=AUTH_HEADERS)
    payload = model.model_dump(mode="json") | {"apiKey":secret,"runtime":{"timeoutMs":"invalid"}}
    response = client.post("/models/resolve",json={"model":payload})
    assert response.status_code == 400 and secret not in response.text


def test_selected_credentials_do_not_follow_redirects(monkeypatch):
    """验证用例触发 `ModelCallError` 异常，并检查 `len(calls)` 等于 `1`。"""
    calls = []

    def redirect(request):
        """返回 `httpx.Response` 的结果。输入为 `request`。"""
        calls.append(str(request.url))
        return httpx.Response(302,headers={"Location":"https://attacker.invalid"})

    runtime, model = mock_runtime(monkeypatch,redirect), selected(apiKey="secret")
    with model_execution(ModelExecution(bindings={"agent":model})), pytest.raises(ModelCallError, match="model_http_302"):
        runtime.chat(model,"agent",[{"role":"user","content":"x"}])
    assert len(calls) == 1


def test_missing_binding_is_only_required_when_consumed():
    """验证用例触发 `ModelConfigurationError` 异常，并检查 `current_execution()` 不为 `None`，且 `current_execution()` 为 `None`。"""
    with model_execution(ModelExecution(bindings={} )):
        assert current_execution() is not None
        with pytest.raises(ModelConfigurationError, match="missing model execution binding"):
            binding("generate")
    assert current_execution() is None


def test_calibration_requires_same_nonempty_semantic_fingerprint():
    """验证 `evidence_threshold('model')` 等于 `0.7`，且 `evidence_threshold('model')` 为 `None`。"""
    model = selected("cohere_rerank",calibration={"semanticFingerprint":"semantic-a","threshold":0.7,"reference":"fixture-v1"})
    with model_execution(ModelExecution(bindings={"rerank":model})):
        assert evidence_threshold("model") == 0.7
        model.revision += 1
        model.credentialVersion += 1
        assert evidence_threshold("model") == 0.7
        model.semanticFingerprint = "changed"
        assert evidence_threshold("model") is None


def test_cache_and_breaker_identity_changes_for_purpose_secret_and_semantics(monkeypatch):
    """验证用例触发 `ModelCallError` 异常，并检查 `len(calls)` 等于 `2`，且 `identity(model)` 不等于 `before`。"""
    from ai.app.models import runtime as module
    calls = []

    def handler(request):
        """返回 `httpx.Response` 的结果。输入为 `request`。"""
        calls.append(request)
        return httpx.Response(200,json={"choices":[{"message":{"content":"OK"},"finish_reason":"stop"}]})

    runtime, model, gate = mock_runtime(monkeypatch,handler), selected(), ModelGate()
    monkeypatch.setattr(module,"runtime",runtime)
    monkeypatch.setattr("ai.app.llm.guard.runtime",runtime)
    with model_execution(ModelExecution(bindings={"generate":model,"rewrite":model})):
        gate.call("generate","s","u")
        gate.call("generate","s","u")
        gate.call("rewrite","s","u")
        assert len(calls) == 2
        before = identity(model)
        model.credentialVersion += 1
        assert identity(model) != before
        gate.call("generate","s","u")
        model.semanticFingerprint = "new-space"
        gate.call("generate","s","u")
        assert len(calls) == 4
    for _ in range(3):
        runtime._failure("agent:"+identity(model),model,"agent","failed")
    with pytest.raises(ModelCallError,match="model_circuit_open"):
        runtime._breaker(model,"agent")
    assert runtime._breaker(model,"generate")


def test_managed_response_schema_is_part_of_the_model_cache_key(monkeypatch):
    """验证 `gate.call('rewrite', 'system', 'question', response_format=schema).content == '{}'` 成立，且 `formats` 等于 `schemas`。"""
    formats = []

    def handler(request):
        """返回 `httpx.Response` 的结果。输入为 `request`。"""
        formats.append(json.loads(request.content)["response_format"])
        return httpx.Response(200, json={"choices": [{"message": {"content": "{}"}, "finish_reason": "stop"}]})

    runtime = mock_runtime(monkeypatch, handler)
    monkeypatch.setattr("ai.app.llm.guard.runtime", runtime)
    model = selected(capabilities={"json": True})
    schemas = [{"type": "json_schema", "json_schema": {"name": name, "schema": {"type": "object"}}}
               for name in ("first", "second")]
    gate = ModelGate()
    with model_execution(ModelExecution(bindings={"rewrite": model})):
        for schema in [*schemas, schemas[0]]:
            assert gate.call("rewrite", "system", "question", response_format=schema).content == "{}"
    assert formats == schemas


def test_independent_thread_contexts_retain_snapshot_and_total_deadline():
    """验证 `result.results['a'][0] == result.results['b'][0] == model.id` 成立，且 `result.results['a'][1]` 小于或等于 `end`。"""
    model = selected()
    with model_execution(ModelExecution(bindings={"agent":model},budgetMs=2000)):
        end = current_deadline_at()
        result = fan_out_legs({"a":lambda:[binding("agent").id,current_deadline_at()],
                               "b":lambda:[binding("agent").id,current_deadline_at()]},timeout_ms=1000)
        assert result.results["a"][0] == result.results["b"][0] == model.id
        assert result.results["a"][1] <= end

        def sequence():
            """向 `test_independent_thread_contexts_retain_snapshot_and_total_deadline` 提供预设数据。产出供调用方迭代的结果。

            下游调用 `ai.app.models.context.model_execution`、`ai.app.models.schema.ModelExecution`。
            """
            with model_execution(ModelExecution(bindings={"agent":model})):
                yield binding("agent").id
                yield binding("agent").id

        iterator = iterate_in_context(sequence())
    with ThreadPoolExecutor(max_workers=2) as pool:
        assert pool.submit(next,iterator).result() == model.id
        assert pool.submit(next,iterator).result() == model.id
        pool.submit(iterator.close).result()
    assert current_execution() is None


class SlowBody(httpx.SyncByteStream):
    def __init__(self, chunks, delay=0.2):
        """创建实例并初始化 `chunks`、`delay`、`closed`，供后续方法共享。输入为 `chunks`、`delay`。"""
        self.chunks, self.delay, self.closed = chunks, delay, threading.Event()

    def __iter__(self):
        """产出供调用方迭代的结果。"""
        for chunk in self.chunks:
            if self.closed.wait(self.delay):
                return
            yield chunk

    def close(self):
        """更新 `self.closed` 的当前状态，供调用方继续处理。"""
        self.closed.set()


@pytest.mark.parametrize("stream",[False,True])
def test_slow_response_body_is_bounded_by_total_deadline_and_closes(monkeypatch,stream):
    """验证用例触发 `QueryDeadlineExceeded` 异常，并检查 `time.monotonic() - started` 小于 `0.8`，且 `body.closed.wait(1)` 成立。"""
    body = SlowBody([b'data: {"choices":[{"delta":{"content":"x"}}]}\n\n'] if stream else [b'{"choices":[] }'])
    runtime = mock_runtime(monkeypatch, lambda request:httpx.Response(200,stream=body))
    model = selected(runtime={"timeoutMs":100})
    started = time.monotonic()
    with model_execution(ModelExecution(bindings={"agent":model},budgetMs=1000)), pytest.raises(QueryDeadlineExceeded):
        result = runtime.chat(model,"agent",[{"role":"user","content":"x"}],stream=stream)
        if stream:
            list(result)
    assert time.monotonic()-started < 0.8
    assert body.closed.wait(1)


def test_cancelled_model_wait_closes_transport_without_waiting_for_model_timeout(monkeypatch):
    """验证用例触发 `QueryDeadlineExceeded` 异常，并检查 `entered.wait(1)` 成立，且 `body.closed.wait(1)` 成立。"""
    entered, cancelled = threading.Event(), threading.Event()
    body = SlowBody([b'{}'],delay=10)

    def handler(request):
        """返回 `httpx.Response` 的结果。输入为 `request`。"""
        entered.set()
        return httpx.Response(200,stream=body)

    runtime, model = mock_runtime(monkeypatch,handler), selected()

    def run():
        """返回 `runtime.chat` 的结果。

        下游调用 `ai.app.models.cancellation.cancellation_scope`、`ai.app.models.context.model_execution`。
        """
        with cancellation_scope(cancelled), model_execution(ModelExecution(bindings={"agent":model})):
            return runtime.chat(model,"agent",[{"role":"user","content":"x"}])

    with ThreadPoolExecutor(max_workers=1) as pool:
        pending = pool.submit(run)
        assert entered.wait(1)
        cancelled.set()
        with pytest.raises(QueryDeadlineExceeded):
            pending.result(timeout=1)
    assert body.closed.wait(1)


def test_local_resource_group_capacity_is_shared_across_models_and_queue_is_bounded():
    """验证 `not entered.is_set()` 成立，且 `sum(limits._active.values())` 等于 `2`。"""
    limits = ResourceLimits(groups={"local-gpu":1},queue_limit=0)
    a, b = selected("openai_chat", provider="ollama", source="local", baseUrl="http://ollama.invalid/v1"), selected("openai_embedding",id="embed",source="local",runtime={"maxConcurrency":8})
    entered = threading.Event()
    with model_execution(ModelExecution(bindings={"agent":a})):
        with limits.acquire(a):
            def reject():
                """更新 `entered` 的当前状态，供调用方继续处理。下游调用 `ai.app.rag.deadline.query_deadline`。"""
                with query_deadline(1000), pytest.raises(ModelCallError,match="model_resource_capacity_rejected"):
                    with limits.acquire(b):
                        entered.set()

            with ThreadPoolExecutor(max_workers=1) as pool:
                pool.submit(reject).result(timeout=2)
    assert not entered.is_set()
    with limits.acquire(b):
        assert sum(limits._active.values()) == 2
    assert not any(limits._active.values())


def test_disconnect_middleware_cancels_sync_inference_context():
    """验证 `seen` 等于 `[True]`。"""
    seen = []

    async def scenario():
        """委托 `ModelCancellationMiddleware` 完成当前操作，无直接返回值。

        上游为 `test_disconnect_middleware_cancels_sync_inference_context`。
        """
        incoming = asyncio.Queue()
        await incoming.put({"type":"http.request","body":b"{}","more_body":False})

        async def inner(scope,receive,send):
            """将当前记录追加到 `seen`，供调用方后续读取。

            输入为 `scope`、`receive`、`send`。
            下游调用 `ai.app.models.cancellation.check_cancelled`。
            """
            await receive()
            await incoming.put({"type":"http.disconnect"})
            await asyncio.sleep(0.01)
            from ai.app.models.cancellation import check_cancelled
            with pytest.raises(QueryDeadlineExceeded):
                check_cancelled()
            seen.append(True)

        await ModelCancellationMiddleware(inner)({"type":"http","path":"/agent/decide"},incoming.get,lambda x:None)

    asyncio.run(scenario())
    assert seen == [True]


def test_catalog_has_chat_embedding_and_distinct_rerank_without_quota_claims():
    """验证 `{item['type'] for item in catalog}` 等于 `{'chat', 'embedding', 'rerank'}`，且 `all((item['verified'] is False for item in catalog))` 成立。"""
    catalog = provider_metadata()["models"]
    assert {item["type"] for item in catalog} == {"chat","embedding","rerank"}
    assert all(item["verified"] is False for item in catalog)
    assert not any("quota" in item or "dimension" in item for item in catalog)


def test_retries_share_total_deadline_and_default_is_zero(monkeypatch):
    """验证用例触发 `ModelCallError` 异常，并检查 `len(calls)` 等于 `1`，且 `len(calls)` 等于 `2`。"""
    calls = []

    def failing(request):
        """返回 `httpx.Response` 的结果。输入为 `request`。"""
        calls.append(request)
        return httpx.Response(503,json={"error":"unavailable"})

    runtime, model = mock_runtime(monkeypatch,failing), selected()
    with model_execution(ModelExecution(bindings={"agent":model})), pytest.raises(ModelCallError):
        runtime.chat(model,"agent",[{"role":"user","content":"x"}])
    assert len(calls) == 1
    model.runtime.retries, model.runtime.backoffMs = 3, 1000
    with model_execution(ModelExecution(bindings={"agent":model},budgetMs=100)), pytest.raises(QueryDeadlineExceeded):
        runtime.chat(model,"agent",[{"role":"user","content":"x"}])
    assert len(calls) == 2


def test_stream_tools_are_assembled_and_terminal_required(monkeypatch):
    """验证 `result.finish_reason == 'tool_calls' and result.total_tokens == 5` 成立。"""
    chunks = [
        {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"lookup","arguments":"{"}}]}}]},
        {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"}"}}]},"finish_reason":"tool_calls"}]},
        {"choices":[],"usage":{"prompt_tokens":2,"completion_tokens":3}},
    ]
    raw = "".join("data: "+json.dumps(item)+"\n\n" for item in chunks)+"data: [DONE]\n\n"
    runtime = mock_runtime(monkeypatch,lambda request:httpx.Response(200,content=raw))
    model = selected(capabilities={"tools":True,"streaming":True})
    with model_execution(ModelExecution(bindings={"agent":model})):
        result = list(runtime.chat(model,"agent",[{"role":"user","content":"x"}],stream=True))[-1]
    assert result.tool_calls == [{"id":"c1","type":"function","function":{"name":"lookup","arguments":"{}"}}]
    assert result.finish_reason == "tool_calls" and result.total_tokens == 5


def test_preview_and_readiness_never_call_models(monkeypatch):
    """验证 `set(_probe_functions())` 等于 `{'database', 'minio'}`，且 `service.snapshot()['capabilities']['rerank']['status']` 等于 `'unknown'`。"""
    from ai.app.models.runtime import runtime
    from ai.app.observability.readiness import ReadinessService, _probe_functions
    monkeypatch.setattr(runtime,"client",lambda *args:pytest.fail("readiness must not request a model"))
    assert set(_probe_functions()) == {"database","minio"}
    service = ReadinessService(probes={})
    try:
        assert service.snapshot()["capabilities"]["rerank"]["status"] == "unknown"
        assert resolve(selected())["endpoint"].endswith("/chat/completions")
    finally:
        service.stop()


def test_internal_routes_bind_snapshots_for_agent_summary_and_embedding(monkeypatch):
    """验证 `response.status_code == 200 and response.json()['content'] == 'OK'` 成立，且 `response.status_code == 200 and response.json()['embeddings'] == [[1.0, 2.0]]` 成立。"""
    seen = []

    def handler(request):
        """返回 `httpx.Response` 的结果。输入为 `request`。"""
        body = json.loads(request.content)
        seen.append(body["model"])
        if str(request.url).endswith("/embeddings"):
            return httpx.Response(200,json={"data":[{"index":0,"embedding":[1,2]}]})
        if body.get("stream"):
            return httpx.Response(200,content='data: {"choices":[{"delta":{"content":"OK"}}]}\n\ndata: {"choices":[{"delta":{},"finish_reason":"stop"}]}\n\ndata: [DONE]\n\n')
        return httpx.Response(200,json={"choices":[{"message":{"content":"OK"},"finish_reason":"stop"}]})

    runtime = mock_runtime(monkeypatch,handler)
    monkeypatch.setattr("ai.app.llm.guard.runtime",runtime)
    monkeypatch.setattr("ai.app.embedding.embedding_service.runtime",runtime)
    client = TestClient(app,headers=AUTH_HEADERS)
    model = selected(model="route-agent",id="route-agent")
    execution = ModelExecution(bindings={"agent":model}).model_dump(mode="json")
    response = client.post("/agent/decide",json={"messages":[{"role":"user","content":"hello"}],"modelExecution":execution})
    assert response.status_code == 200 and response.json()["content"] == "OK"
    stream = client.post("/agent/decide/stream",json={"messages":[{"role":"user","content":"hello"}],"modelExecution":execution})
    assert stream.status_code == 200 and "event: final" in stream.text and "event: delta" in stream.text
    model = selected(model="route-summary",id="route-summary")
    response = client.post("/agent/summarize",json={"system_prompt":"s","user_prompt":"u",
        "modelExecution":ModelExecution(bindings={"summarize":model}).model_dump(mode="json")})
    assert response.status_code == 200 and response.json()["content"] == "OK"
    model = selected("openai_embedding",model="route-embedding",id="route-embedding")
    response = client.post("/embedding",json={"texts":["route-test"],
        "modelExecution":ModelExecution(bindings={"embedding":model}).model_dump(mode="json")})
    assert response.status_code == 200 and response.json()["embeddings"] == [[1.,2.]]
    assert seen == ["route-agent","route-agent","route-summary","route-embedding"]
    assert current_execution() is None


def test_missing_production_binding_returns_dependency_failure_and_management_is_protected():
    """验证 `TestClient(app).get('/models/providers').status_code` 等于 `401`，且 `client.get('/models/providers').status_code` 等于 `200`。"""
    client = TestClient(app,headers=AUTH_HEADERS)
    response = client.post("/agent/decide",json={"messages":[{"role":"user","content":"hello"}]})
    assert response.status_code == 503 and response.json()["detail"]["reasonCode"] == "model_not_configured"
    assert TestClient(app).get("/models/providers").status_code == 401
    assert client.get("/models/providers").status_code == 200


def test_old_dotenv_model_variables_are_not_parsed(monkeypatch):
    """验证 `not hasattr(config, 'LLM_MODEL')` 成立，且 `not hasattr(config, 'OLLAMA_EMBED_MODEL')` 成立。"""
    import importlib
    from ai.app import config
    for key in ("VECTOR_DIM","AGENT_LLM_TIMEOUT_S","LLM_TIMEOUT_S","LLM_MAX_RETRIES",
                "RERANK_SERVER_TIMEOUT_S","LLM_BACKOFF_BASE_MS","LLM_SEED"):
        monkeypatch.setenv(key,"invalid-old-value")
    importlib.reload(config)
    assert not hasattr(config, "LLM_MODEL")
    assert not hasattr(config, "OLLAMA_EMBED_MODEL")
    assert not hasattr(config, "LLM_MAX_RETRIES")
