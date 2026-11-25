"""验证模型协议的请求字段、响应转换与能力边界。"""

import json
from pathlib import Path

import httpx
import pytest

from ai.app.models.runtime import ModelCallError
from ai.tests.test_managed_models import mock_runtime, selected


def test_generation_stream_passes_response_schema_through_gate(monkeypatch):
    """验证 `captured[0]['response_format']` 等于 `schema`，且 `captured[0]['stream']` 为 `True`。"""
    from ai.app.llm.guard import ModelGate
    from ai.app.models.context import model_execution
    from ai.app.models.schema import ModelExecution
    from ai.app.rag.rag_service import generation_response_format

    captured = []
    content = '{"status":"success","answer":"答案"}'
    wire = 'data: ' + json.dumps({"choices": [{"delta": {"content": content}}]}) + '\n\n'
    wire += 'data: {"choices":[{"delta":{},"finish_reason":"stop"}]}\n\ndata: [DONE]\n\n'

    def handler(request):
        """返回 `httpx.Response` 的结果。输入为 `request`。"""
        captured.append(json.loads(request.content))
        return httpx.Response(200, content=wire)

    runtime = mock_runtime(monkeypatch, handler)
    monkeypatch.setattr("ai.app.llm.guard.runtime", runtime)
    model = selected(capabilities={"json": True, "streaming": True})
    schema = generation_response_format()
    with model_execution(ModelExecution(bindings={"generate": model})):
        items = list(ModelGate().stream("generate", "system", "question", response_format=schema))

    assert captured[0]["response_format"] == schema
    assert captured[0]["stream"] is True
    assert items[-1].content == content


@pytest.mark.parametrize("finish", ["stop", "length", "content_filter", None])
def test_chat_finish_content_usage_and_model_identity_are_preserved(monkeypatch, finish):
    """通过 `runtime.chat` 核对本用例构造的状态。"""
    calls = []
    response = {"choices": [{"message": {"content": "中文答案"}, "finish_reason": finish}],
                "usage": {"prompt_tokens": 7, "completion_tokens": 3}}

    def handler(request):
        """返回 `httpx.Response` 的结果。输入为 `request`。"""
        calls.append(json.loads(request.content))
        return httpx.Response(200, json=response)

    runtime, model = mock_runtime(monkeypatch, handler), selected()
    result = runtime.chat(model, "agent", [{"role": "user", "content": "问题"}], max_tokens=17)
    assert (result.content, result.finish_reason, result.prompt_tokens, result.completion_tokens) == ("中文答案", finish, 7, 3)
    assert (result.model_id, result.model_revision, result.provider) == (model.id, model.revision, model.provider)
    assert calls == [{"model": "example", "messages": [{"role": "user", "content": "问题"}],
                      "stream": False, "temperature": 0, "max_tokens": 17}]


def test_native_tool_call_arguments_remain_json_text(monkeypatch):
    """验证 `result.content is None and result.tool_calls == [call]` 成立。"""
    call = {"id": "c1", "type": "function", "function": {"name": "lookup", "arguments": '{"query":"年假"}'}}
    runtime = mock_runtime(monkeypatch, lambda request: httpx.Response(200, json={
        "choices": [{"message": {"content": None, "tool_calls": [call]}, "finish_reason": "tool_calls"}]}))
    result = runtime.chat(selected(capabilities={"tools": True}), "agent", [{"role": "user", "content": "问题"}])
    assert result.content is None and result.tool_calls == [call]


@pytest.mark.parametrize("status", [302, 400, 401, 429, 503])
@pytest.mark.parametrize("stream", [False, True])
def test_http_error_codes_and_retry_policy_are_fixed(monkeypatch, status, stream):
    """验证用例触发 `ModelCallError` 异常，并检查 `len(requests)` 等于 `1`。"""
    requests = []

    def handler(request):
        """返回 `httpx.Response` 的结果。输入为 `request`。"""
        requests.append(request)
        return httpx.Response(status, headers={"Location": "https://redirect.invalid"}, json={"error": "private upstream details"})

    runtime = mock_runtime(monkeypatch, handler)
    with pytest.raises(ModelCallError, match=f"^model_http_{status}$"):
        result = runtime.chat(selected(), "agent", [{"role": "user", "content": "x"}], stream=stream)
        if stream:
            list(result)
    assert len(requests) == 1


def test_stream_requires_terminal_finish_and_keeps_deltas_on_failure(monkeypatch):
    """验证用例触发 `ModelCallError` 异常，并检查 `next(stream)` 等于 `'partial'`。"""
    wire = 'data: {"choices":[{"delta":{"content":"partial"}}]}\n\ndata: [DONE]\n\n'
    runtime = mock_runtime(monkeypatch, lambda request: httpx.Response(200, content=wire))
    stream = runtime.chat(selected(), "agent", [{"role": "user", "content": "x"}], stream=True)
    assert next(stream) == "partial"
    with pytest.raises(ModelCallError, match="^model_stream_incomplete$"):
        next(stream)


@pytest.mark.parametrize("index", [True, "0", 1, -1])
def test_embedding_index_validation_is_not_weakened(monkeypatch, index):
    """验证用例触发 `ModelCallError` 异常。"""
    runtime = mock_runtime(monkeypatch, lambda request: httpx.Response(200, json={
        "data": [{"index": index, "embedding": [1, 2]}]}))
    with pytest.raises(ModelCallError, match="^embedding_invalid_index$"):
        runtime.embed(selected("openai_embedding"), ["x"])


def test_only_four_protocols_are_advertised_and_retired_protocols_are_rejected():
    """验证用例触发 `ValidationError` 异常，并检查 `{item['id'] for item in metadata['protocols']}` 等于 `set(contract['supportedProtocols'])`。"""
    from pydantic import ValidationError
    from ai.app.models.schema import ModelConfig
    from ai.app.models.resolution import provider_metadata
    metadata = provider_metadata()
    contract = json.loads((Path(__file__).resolve().parents[2] / "tests/fixtures/model-protocol-contract.json").read_text(encoding="utf-8"))
    assert {item["id"] for item in metadata["protocols"]} == set(contract["supportedProtocols"])
    for protocol in ("ollama_chat", "ollama_embedding"):
        with pytest.raises(ValidationError):
            ModelConfig(type="chat" if protocol.endswith("chat") else "embedding", protocol=protocol,
                        model="retired", dimension=1024)


def test_local_ollama_uses_compatible_endpoint_without_unsupported_tool_choice(monkeypatch):
    """验证 `resolve(model)['endpoint']` 等于 `'http://localhost:11434/v1/chat/completions'`，且 `'tool_choice'` 不属于 `json.loads(requests[0].content)`。"""
    from ai.app.models.resolution import resolve
    requests = []

    def handler(request):
        """返回 `httpx.Response` 的结果。输入为 `request`。"""
        requests.append(request)
        return httpx.Response(200, json={"choices":[{"message":{"content":"OK"},"finish_reason":"stop"}]})

    runtime = mock_runtime(monkeypatch, handler)
    model = selected(provider="ollama", source="local", baseUrl="", capabilities={"tools":True})
    runtime.chat(model, "agent", [{"role":"user","content":"x"}], tools=[{"type":"function","function":{"name":"lookup"}}])
    assert resolve(model)["endpoint"] == "http://localhost:11434/v1/chat/completions"
    assert "tool_choice" not in json.loads(requests[0].content)
    assert model.runtime.resourceGroup == "local-gpu"


@pytest.mark.parametrize("provider", ["ollama", "generic", "aliyun"])
@pytest.mark.parametrize("thinking", [False, True])
@pytest.mark.parametrize("stream", [False, True])
def test_thinking_control_uses_the_selected_provider_field(provider, thinking, stream):
    """验证 `payload['reasoning_effort']` 等于 `'medium' if thinking else 'none'`，且 `'enable_thinking'` 不属于 `payload`。"""
    from ai.app.models.runtime import chat_payload
    model = selected(provider=provider, capabilities={"thinking": True, "streaming": True},
                     compat={"enableThinking": thinking})
    payload = chat_payload(model, [{"role": "user", "content": "x"}], stream=stream)
    if provider == "ollama":
        assert payload["reasoning_effort"] == ("medium" if thinking else "none")
        assert "enable_thinking" not in payload
    else:
        assert payload["enable_thinking"] is thinking
        assert "reasoning_effort" not in payload


@pytest.mark.parametrize("stream", [False, True])
def test_ollama_nonstream_thinking_override_keeps_stream_setting(stream):
    """验证 `payload['reasoning_effort']` 等于 `'medium' if stream else 'none'`。"""
    from ai.app.models.runtime import chat_payload
    model = selected(provider="ollama", capabilities={"thinking": True, "streaming": True},
                     compat={"enableThinking": True, "thinkingDisableOnNonStream": True})
    payload = chat_payload(model, [{"role": "user", "content": "x"}], stream=stream)
    assert payload["reasoning_effort"] == ("medium" if stream else "none")


def test_native_tool_arguments_must_be_json_text_not_a_native_provider_object(monkeypatch):
    """验证用例触发 `ModelCallError` 异常。"""
    runtime = mock_runtime(monkeypatch, lambda request: httpx.Response(200,json={"choices":[{"message":{
        "tool_calls":[{"id":"c1","function":{"name":"lookup","arguments":{"query":"x"}}}]}}]}))
    with pytest.raises(ModelCallError, match="model_invalid_tool_call"):
        runtime.chat(selected(), "agent", [{"role":"user","content":"x"}])


def test_sdk_sse_decoder_handles_multiline_data_and_does_not_inherit_credentials(monkeypatch):
    """验证 `result[0] == '你好' and result[-1].content == '你好'` 成立，且 `str(requests[0].url)` 等于 `'http://models.invalid/v1/chat/completions'`。"""
    monkeypatch.setenv("OPENAI_API_KEY", "must-not-be-consumed")
    monkeypatch.setenv("OPENAI_ADMIN_KEY", "must-not-be-consumed")
    monkeypatch.setenv("OPENAI_ORG_ID", "must-not-be-consumed")
    monkeypatch.setenv("OPENAI_PROJECT_ID", "must-not-be-consumed")
    monkeypatch.setenv("OPENAI_BASE_URL", "https://wrong.invalid")
    requests = []
    wire = 'data: {"choices":\ndata: [{"delta":{"content":"你好"}}]}\n\ndata: {"choices":[{"delta":{},"finish_reason":"stop"}]}\n\ndata: [DONE]\n\n'

    def handler(request):
        """返回 `httpx.Response` 的结果。输入为 `request`。"""
        requests.append(request)
        return httpx.Response(200, content=wire)

    runtime = mock_runtime(monkeypatch, handler)
    result = list(runtime.chat(selected(), "agent", [{"role":"user","content":"x"}], stream=True))
    assert result[0] == "你好" and result[-1].content == "你好"
    assert str(requests[0].url) == "http://models.invalid/v1/chat/completions"
    assert not any(header in requests[0].headers for header in ("authorization","openai-organization","openai-project"))


def test_sdk_stream_uses_snapshot_key_and_never_follows_redirect(monkeypatch):
    """验证用例触发 `ModelCallError` 异常，并检查 `len(requests)` 等于 `1`，且 `requests[0].headers['authorization']` 等于 `'Bearer snapshot-test-key'`。"""
    monkeypatch.setenv("OPENAI_API_KEY", "unrelated-host-key")
    requests = []

    def handler(request):
        """返回 `httpx.Response` 的结果。输入为 `request`。"""
        requests.append(request)
        return httpx.Response(302,headers={"Location":"https://other.invalid"})

    runtime = mock_runtime(monkeypatch, handler)
    with pytest.raises(ModelCallError,match="^model_http_302$"):
        list(runtime.chat(selected(apiKey="snapshot-test-key"),"agent",[{"role":"user","content":"x"}],stream=True))
    assert len(requests) == 1
    assert requests[0].headers["authorization"] == "Bearer snapshot-test-key"
