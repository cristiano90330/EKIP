"""验证模型资源队列的等待、运行预算和取消行为。"""

import json
import logging
import threading
import time
from concurrent.futures import ThreadPoolExecutor

import httpx
import pytest

from ai.app.models.cancellation import cancellation_scope
from ai.app.models.runtime import ModelRuntime, ResourceLimits
from ai.app.models.schema import ModelConfig
from ai.app.models.transport import state
from ai.app.llm import telemetry
from ai.app.rag.deadline import QueryDeadlineExceeded, query_deadline


@pytest.mark.parametrize("stream", [False, True])
def test_queued_model_keeps_its_inference_budget(monkeypatch, stream, caplog):
    """验证 `entered.wait(1)` 成立，且 `runtime.resources._waiting` 等于 `1`。"""
    caplog.set_level(logging.INFO, logger="ekip.models.timing")
    runtime = ModelRuntime()
    runtime.resources = ResourceLimits(groups={"local-gpu": 1}, queue_limit=2)
    entered, release = threading.Event(), threading.Event()
    calls = []

    def handler(request):
        """返回 `httpx.Response` 的结果。输入为 `request`。"""
        name = json.loads(request.content)["model"]
        calls.append(name)
        if name == "busy-model":
            entered.set()
            assert release.wait(2)
        if name == "queued-stream":
            return httpx.Response(200, headers={"Content-Type": "text/event-stream"},
                                  text='data: {"choices":[{"delta":{"content":"OK"},'
                                       '"finish_reason":"stop"}]}\n\ndata: [DONE]\n\n')
        return httpx.Response(200, json={"results": [{"index": 0, "relevance_score": 0.9}]})

    def client(model):
        """返回 `attach` 的结果。输入为 `model`。下游调用 `ai.app.models.transport.state`。"""
        return state().attach(httpx.Client(transport=httpx.MockTransport(handler)))

    monkeypatch.setattr(runtime, "client", client)
    busy = ModelConfig(id="busy", type="rerank", protocol="cohere_rerank", source="local",
                       model="busy-model", baseUrl="http://models.invalid",
                       runtime={"timeoutMs": 1500})
    queued = ModelConfig(id="queued", type="rerank", protocol="cohere_rerank", source="local",
                         model="queued-model", baseUrl="http://models.invalid",
                         apiKey="timing-test-private-key",
                         runtime={"timeoutMs": 200})
    if stream:
        queued = ModelConfig(id="queued", type="chat", protocol="openai_chat", source="local",
                             model="queued-stream", baseUrl="http://models.invalid/v1",
                             capabilities={"streaming": True}, runtime={"timeoutMs": 200})

    def call_model(model):
        """返回 `results[-1].content` 或 `runtime.rerank` 的结果。

        输入为 `model`。
        下游调用 `ai.app.llm.telemetry.set_request_id`、`ai.app.rag.deadline.query_deadline`。
        """
        request_token = telemetry.set_request_id("timing-" + model.id)
        try:
            with query_deadline(2500):
                if model.type == "chat":
                    results = list(runtime.chat(model, "agent", [{"role": "user", "content": "hello"}],
                                                stream=True))
                    return results[-1].content
                return runtime.rerank(model, "question", ["document"])
        finally:
            telemetry.reset_request_id(request_token)

    with ThreadPoolExecutor(max_workers=2) as pool:
        first = pool.submit(call_model, busy)
        try:
            assert entered.wait(1)
            second = pool.submit(call_model, queued)
            end = time.monotonic() + 1
            while runtime.resources._waiting != 1 and time.monotonic() < end:
                time.sleep(0.001)
            assert runtime.resources._waiting == 1
            time.sleep(0.3)
        finally:
            release.set()
        assert first.result(timeout=2) == [0.9]
        assert second.result(timeout=2) == ("OK" if stream else [0.9])
    assert calls == ["busy-model", queued.model]
    assert runtime.resources._waiting == 0
    assert not runtime.resources._active
    events = [json.loads(record.getMessage().removeprefix("model_timing "))
              for record in caplog.records if record.name == "ekip.models.timing"]
    queued_events = [event for event in events if event["request_id"] == "timing-queued"]
    times = {event["phase"]: event["elapsed_ms"] for event in queued_events}
    assert times["resource_acquired"] - times["resource_wait_started"] >= 250
    assert (times["resource_acquired"] <= times["transport_started"]
            <= times["transport_finished"] <= times["attempt_finished"]
            <= times["cleanup_started"] <= times["cleanup_finished"])
    assert len({event["call_id"] for event in queued_events}) == 1
    assert all(event.get("model_id") == "queued" for event in queued_events
               if event["phase"] != "worker_started")
    assert "timing-test-private-key" not in caplog.text


@pytest.mark.parametrize("cancel", [False, True])
def test_queue_respects_request_deadline_and_cancellation(monkeypatch, cancel):
    """验证用例触发 `QueryDeadlineExceeded` 异常，并检查 `runtime.resources._waiting` 等于 `1`，且 `runtime.resources._waiting` 等于 `0`。"""
    runtime = ModelRuntime()
    runtime.resources = ResourceLimits(groups={"local-gpu": 1}, queue_limit=2)
    model = ModelConfig(id="rerank", type="rerank", protocol="cohere_rerank", source="local",
                        model="example", baseUrl="http://models.invalid",
                        runtime={"timeoutMs": 1500})
    called, cancelled = threading.Event(), threading.Event()
    monkeypatch.setattr(runtime, "_json", lambda *_: called.set())

    def rerank():
        """返回 `runtime.rerank` 的结果。

        下游调用 `ai.app.rag.deadline.query_deadline`、`ai.app.models.cancellation.cancellation_scope`。
        """
        with query_deadline(200), cancellation_scope(cancelled):
            return runtime.rerank(model, "question", ["document"])

    with ThreadPoolExecutor(max_workers=1) as pool:
        with runtime.resources.acquire(model):
            pending = pool.submit(rerank)
            end = time.monotonic() + 1
            while runtime.resources._waiting != 1 and time.monotonic() < end:
                time.sleep(0.001)
            assert runtime.resources._waiting == 1
            if cancel:
                cancelled.set()
            with pytest.raises(QueryDeadlineExceeded):
                pending.result(timeout=1)
            end = time.monotonic() + 1
            while runtime.resources._waiting and time.monotonic() < end:
                time.sleep(0.001)
            assert runtime.resources._waiting == 0
            assert runtime.resources._active == {"model:rerank": 1, "group:local-gpu": 1}
    assert not called.is_set()
    assert not runtime.resources._active


def test_inference_timeout_keeps_capacity_until_worker_exits():
    """验证用例触发 `QueryDeadlineExceeded` 异常，并检查 `entered.wait(1)` 成立，且 `runtime.resources._active` 等于 `{'model:rerank': 1, 'group:local-gpu': 1}`。"""
    runtime = ModelRuntime()
    runtime.resources = ResourceLimits(groups={"local-gpu": 1}, queue_limit=2)
    model = ModelConfig(id="rerank", type="rerank", protocol="cohere_rerank", source="local",
                        model="example", baseUrl="http://models.invalid",
                        runtime={"timeoutMs": 100})
    entered, release = threading.Event(), threading.Event()

    def operation():
        """返回 列表。"""
        entered.set()
        assert release.wait(2)
        return [0.9]

    def rerank():
        """返回 `runtime.invoke` 的结果。下游调用 `ai.app.rag.deadline.query_deadline`。"""
        with query_deadline(1500):
            return runtime.invoke(model, "rerank", operation)

    with ThreadPoolExecutor(max_workers=1) as pool:
        pending = pool.submit(rerank)
        try:
            assert entered.wait(1)
            with pytest.raises(QueryDeadlineExceeded):
                pending.result(timeout=0.8)
            assert runtime.resources._active == {"model:rerank": 1, "group:local-gpu": 1}
        finally:
            release.set()
    end = time.monotonic() + 1
    while runtime.resources._active and time.monotonic() < end:
        time.sleep(0.001)
    assert not runtime.resources._active
