"""执行对话、嵌入和重排请求，处理协议差异、资源队列、重试和结构校验。"""

import hashlib
import json
import logging
import math
import os
import threading
import time
from collections import OrderedDict
from contextlib import contextmanager, nullcontext
from typing import Any, Dict
from uuid import uuid4

import httpx
from openai import OpenAI, Stream, omit, APIError, APIStatusError, APIConnectionError, APITimeoutError

from ai.app.llm.chat_result import ChatResult
from ai.app.models.context import binding
from ai.app.models.cancellation import check_cancelled, wait_or_cancel
from ai.app.models.resource_leases import RedisGroupLeases, ResourceCoordinationError
from ai.app.models.resolution import ModelConfigurationError, effective_compat, endpoint
from ai.app.models.schema import ModelConfig
from ai.app.models.transport import CapacityExceeded, run_bounded, stream_bounded, state as io_state
from ai.app.rag.deadline import (QueryDeadlineExceeded, bounded_timeout, current_deadline_at,
                                 deadline_expired, inherit_deadline, remaining_seconds)

logger = logging.getLogger("ekip.models")


class ModelCallError(RuntimeError):
    def __init__(self, code: str, *, transient=False):
        """保存稳定失败原因和是否可重试，供运行时及 HTTP 异常映射共用。"""
        super().__init__(code)
        self.code = code
        self.transient = transient


def identity(model: ModelConfig) -> str:
    """根据模型配置构造运行时身份，供模型客户端、熔断与观测区分模型版本。

    上游为 `ai.app.embedding.embedding_service.EmbeddingService.embed_batch`、`ai.app.llm.guard.ModelGate._run`。
    """
    payload = model.model_dump(mode="json", exclude={"apiKey"})
    # Credential rotation isolates transports too, without ever storing/logging cleartext keys.
    key_hash = hashlib.sha256((model.apiKey.get_secret_value() if model.apiKey else "").encode()).hexdigest()
    return hashlib.sha256((json.dumps(payload, sort_keys=True, ensure_ascii=False) + key_hash).encode()).hexdigest()


class ResourceLimits:
    """Model capacities and deployment device capacities are independent."""

    def __init__(self, groups=None, queue_limit=None, *, shared=None):
        """校验设备分组配额和等待队列上限，建立模型与资源组的占用计数。"""
        if groups is None:
            try:
                groups = json.loads(os.getenv("MODEL_RESOURCE_GROUPS") or '{"local-gpu":1}')
            except (TypeError, ValueError):
                raise ModelConfigurationError("invalid resource group configuration") from None
        if not isinstance(groups, dict) or any(not isinstance(k, str) or isinstance(v, bool)
                or not isinstance(v, int) or v < 1 for k, v in groups.items()):
            raise ModelConfigurationError("invalid resource group capacity")
        self.groups = dict(groups)
        try:
            self.queue_limit = int(os.getenv("MODEL_RESOURCE_QUEUE_LIMIT", "8")) if queue_limit is None else queue_limit
        except (TypeError, ValueError):
            raise ModelConfigurationError("invalid model resource queue limit") from None
        if isinstance(self.queue_limit, bool) or not isinstance(self.queue_limit, int) or not 0 <= self.queue_limit <= 256:
            raise ModelConfigurationError("invalid model resource queue limit")
        redis_url = os.getenv("MODEL_RESOURCE_REDIS_URL", "").strip()
        namespace = os.getenv("MODEL_RESOURCE_REDIS_NAMESPACE", "").strip() or "ekip"
        self._shared = shared if shared is not None else (
            RedisGroupLeases.connect(redis_url, namespace) if redis_url else None)
        self._condition = threading.Condition()
        self._active, self._waiting = {}, 0

    @contextmanager
    def acquire(self, model):
        """在请求预算内申请模型及资源组槽位，退出时归还配额。

        输入为当前模型配置，逻辑同时读取模型的并发上限和部署的共享资源组配额。
        由 ModelRuntime._attempt 调用。等待期间检测取消和总时限，结束时唤醒其他等待者。
        下游调用 `ai.app.models.cancellation.check_cancelled`、`ai.app.rag.deadline.remaining_seconds`。
        """
        capacities = {"model:" + model.id: model.runtime.maxConcurrency}
        group = model.runtime.resourceGroup.strip() or ("local-gpu" if model.source == "local" else "")
        if group:
            if group not in self.groups:
                raise ModelConfigurationError("model resource group is not configured")
            capacities["group:" + group] = self.groups[group]
        shared_slot = self._shared_slot(group, model.runtime.timeoutMs) if group and self._shared else nullcontext()
        with self._local_slot(capacities), shared_slot:
            self._check_waiting_request()
            yield

    @staticmethod
    def _check_waiting_request():
        """在尝试占用及进入模型调用前检查取消和请求预算。"""
        check_cancelled()
        if deadline_expired():
            raise QueryDeadlineExceeded("model resource wait exceeded total budget")

    def _wait_for(self, try_acquire):
        """在同一进程等待计数下，等待本地槽位或 Redis 共享槽位。"""
        self._check_waiting_request()
        if try_acquire():
            return
        with self._condition:
            if self._waiting >= self.queue_limit:
                raise ModelCallError("model_resource_capacity_rejected", transient=True)
            self._waiting += 1
        try:
            while True:
                self._check_waiting_request()
                remaining = remaining_seconds()
                with self._condition:
                    self._condition.wait(timeout=min(remaining, 0.1) if remaining is not None else 0.1)
                self._check_waiting_request()
                if try_acquire():
                    return
        finally:
            with self._condition:
                self._waiting -= 1

    @contextmanager
    def _local_slot(self, capacities):
        """在条件锁内同时申请并归还本进程的模型和资源组槽位。"""
        def try_acquire():
            if any(self._active.get(key, 0) >= limit for key, limit in capacities.items()):
                return False
            for key in capacities:
                self._active[key] = self._active.get(key, 0) + 1
            return True

        with self._condition:
            self._wait_for(try_acquire)
        try:
            yield
        finally:
            with self._condition:
                for key in capacities:
                    self._active[key] -= 1
                    if self._active[key] == 0:
                        del self._active[key]
                self._condition.notify_all()

    @contextmanager
    def _shared_slot(self, group, attempt_timeout_ms):
        """持有共享租约。进程意外退出时，租约在尝试上限加宽限期后回收。"""
        token = uuid4().hex
        ttl_ms = attempt_timeout_ms + 30_000
        try:
            self._wait_for(lambda: self._shared.try_acquire(group, self.groups[group], token, ttl_ms))
        except ResourceCoordinationError as exc:
            raise ModelCallError(str(exc), transient=True) from None
        try:
            yield
        finally:
            self._shared.release(group, token)


class ModelRuntime:
    def __init__(self):
        """初始化 HTTP 客户端、用途熔断、状态观测和本机资源限制。"""
        self.resources = ResourceLimits()
        self._lock = threading.RLock()
        self._failures = {}
        self._health = OrderedDict()

    def client(self, model):
        """为模型复用或创建对应的 HTTP 客户端，供同步与流式协议请求共享连接。

        上游为 `_json`，下游调用 `ai.app.models.transport.state`、`identity`。
        """
        state = io_state()
        key = identity(model)
        if state is not None and key in state.clients:
            return state.clients[key]
        client = httpx.Client(follow_redirects=False, timeout=None)
        if state is not None:
            state.clients[key] = state.attach(client)
        return client

    def _headers(self, model):
        """构造模型 HTTP 请求头，附加供应商凭据及当前请求关联标识。

        上游为 `_json`、`_openai_events`。
        """
        headers = {"Content-Type": "application/json", "Accept": "application/json"}
        if model.apiKey and model.apiKey.get_secret_value():
            headers["Authorization"] = "Bearer " + model.apiKey.get_secret_value()
        elif model.source == "remote" and model.provider in {"openai", "aliyun", "cohere"}:
            raise ModelConfigurationError("credential_not_configured")
        return headers

    def observe(self, model, purpose, status, reason="none"):
        """保存指定模型用途的最近一次操作状态，供就绪能力观测读取。

        上游为 `_success`，下游调用 `identity`。
        """
        key = purpose + ":" + identity(model)
        with self._lock:
            self._health[key] = {"status": status, "reasonCode": reason, "at": time.monotonic(),
                                 "modelId": model.id, "revision": model.revision,
                                 "model": model.model, "protocol": model.protocol, "purpose": purpose}
            self._health.move_to_end(key)
            while len(self._health) > 256:
                self._health.popitem(last=False)

    def observations(self):
        """返回运行时模型观测的快照，供 ReadinessService 汇总。"""
        with self._lock:
            return [{key: value for key, value in item.items() if key != "at"}
                    | {"ageMs": int((time.monotonic() - item["at"]) * 1000)}
                    for item in self._health.values()]

    def readiness(self, purpose):
        """按用途返回运行时记录的最近观测供模型门控和应用就绪状态读取。

        下游调用 `ai.app.models.context.binding`、`identity`。
        """
        try:
            model = binding(purpose)
        except ModelConfigurationError:
            return {"status": "unknown", "reasonCode": "model_not_selected"}
        with self._lock:
            observed = self._health.get(purpose + ":" + identity(model))
        if observed is None:
            return {"status": "unknown", "reasonCode": "model_not_observed"}
        return {k: v for k, v in observed.items() if k != "at"}

    def _breaker(self, model, purpose):
        """按模型身份和用途取对应的熔断器，供同步与流式调用统一判定。

        上游为 `invoke`，下游调用 `identity`。
        """
        key = purpose + ":" + identity(model)
        with self._lock:
            count, until = self._failures.get(key, (0, 0))
        if until > time.monotonic():
            raise ModelCallError("model_circuit_open")
        return key

    def _success(self, key, model, purpose):
        """在模型调用成功时更新对应的熔断和可用性观测记录。

        上游为 `invoke`，下游调用 `observe`。
        """
        with self._lock:
            self._failures.pop(key, None)
        self.observe(model, purpose, "ready")

    def _failure(self, key, model, purpose, code):
        """按运行时失败原因更新模型用途的熔断与最近观测数据。

        上游为 `invoke`，下游调用 `observe`。
        """
        with self._lock:
            count = self._failures.get(key, (0, 0))[0] + 1
            self._failures[key] = (count, time.monotonic() + 30 if count >= 3 else 0)
            if len(self._failures) > 512:
                self._failures.pop(next(iter(self._failures)))
        self.observe(model, purpose, "not_ready", code)

    @contextmanager
    def _attempt(self, model, purpose, attempt):
        """围绕一次模型尝试计算预算、申请资源并记录阶段耗时，供 invoke 及流式调用复用。

        上游为 `_chat_stream`，下游调用 `ai.app.rag.deadline.current_deadline_at`、`ai.app.models.transport.state`。
        """
        outer = current_deadline_at()
        # Admission consumes the request budget. Inference timing starts after admission.
        wait_end = outer if outer is not None else time.monotonic() + model.runtime.timeoutMs / 1000
        execution = io_state()
        if execution is not None:
            execution.mark("resource_wait_started", model_id=model.id, revision=model.revision,
                           purpose=purpose, attempt=attempt)
        try:
            with inherit_deadline(wait_end):
                with self.resources.acquire(model):
                    if execution is not None:
                        execution.mark("resource_acquired")
                    attempt_end = time.monotonic() + model.runtime.timeoutMs / 1000
                    with inherit_deadline(min(attempt_end, outer) if outer is not None else attempt_end):
                        if execution is not None:
                            with execution.lock:
                                execution.deadline_at = current_deadline_at()
                        try:
                            yield
                        finally:
                            if execution is not None:
                                with execution.lock:
                                    execution.deadline_at = None
        finally:
            if execution is not None:
                execution.mark("attempt_finished")

    def _backoff(self, model, attempt):
        """根据模型配置和尝试次数等待退避，并检查请求是否已取消或超时。

        上游为 `invoke`，下游调用 `ai.app.rag.deadline.remaining_seconds`、`ai.app.rag.deadline.QueryDeadlineExceeded`。
        """
        delay = model.runtime.backoffMs / 1000 * (2 ** attempt)
        remaining = remaining_seconds()
        if remaining is not None and delay >= remaining:
            raise QueryDeadlineExceeded("model retry backoff exceeds total budget")
        wait_or_cancel(delay)
        if deadline_expired():
            raise QueryDeadlineExceeded("model retry exhausted total budget")

    def invoke(self, model, purpose, operation):
        """在请求预算内完成同步模型调用，返回操作结果或抛出稳定错误。

        输入为模型配置、用途名和实际发送请求的操作。
        由 chat、embed、rerank 调用，通过 _attempt 管理单次资源与时限，经 transport.run_bounded 执行。
        只在预算内重试暂时故障，熔断与观测读取最终状态。
        下游调用 `ai.app.models.cancellation.check_cancelled`、`ai.app.rag.deadline.deadline_expired`。
        """
        check_cancelled()
        if deadline_expired():
            raise QueryDeadlineExceeded("model execution exceeded total budget")
        key = self._breaker(model, purpose)
        started = time.monotonic()
        for attempt in range(model.runtime.retries + 1):
            try:
                def execute():
                    """在本次操作的资源和时限范围内调用注入操作，返回完成结果或超时异常。

                    下游调用 `_attempt`、`operation`。
                    """
                    with self._attempt(model, purpose, attempt + 1):
                        result = operation()
                        if deadline_expired():
                            raise QueryDeadlineExceeded("model attempt exhausted its budget")
                        return result

                result = run_bounded(execute, model.runtime.timeoutMs / 1000
                                     if current_deadline_at() is None else None)
                self._success(key, model, purpose)
                logger.info("model_call id=%s revision=%s purpose=%s protocol=%s outcome=ok elapsed_ms=%d attempts=%d",
                            model.id, model.revision, purpose, model.protocol,
                            int((time.monotonic() - started) * 1000), attempt + 1)
                return result
            except CapacityExceeded:
                raise ModelCallError("model_executor_capacity_rejected", transient=True) from None
            except ModelConfigurationError:
                raise
            except (ModelCallError, QueryDeadlineExceeded) as exc:
                check_cancelled()
                if deadline_expired():
                    self._failure(key, model, purpose, "model_total_timeout")
                    raise QueryDeadlineExceeded("model execution exceeded total budget") from None
                code = exc.code if isinstance(exc, ModelCallError) else "model_timeout"
                transient = exc.transient if isinstance(exc, ModelCallError) else True

                # 只重试尚有请求预算的暂时故障。终态失败计入用途熔断与观测。
                if attempt < model.runtime.retries and transient:
                    self._backoff(model, attempt)
                    continue
                self._failure(key, model, purpose, code)
                if isinstance(exc, QueryDeadlineExceeded) or code == "model_timeout":
                    raise QueryDeadlineExceeded("model execution exceeded its budget") from None
                raise ModelCallError(code, transient=transient) from None

    def _json(self, model, payload):
        """将模型载荷发送到解析后的 HTTP 端点，校验状态和响应对象后返回 JSON。

        上游为 `chat`，下游调用 `ai.app.models.transport.state`、`client`。
        """
        execution = io_state()
        try:
            if execution is not None:
                execution.mark("transport_started")
            with self.client(model).stream("POST", endpoint(model), json=payload,
                                           headers=self._headers(model), timeout=bounded_timeout(600)) as response:
                if io_state() is not None:
                    io_state().attach(response)
                self._status(response)
                content = bytearray()
                for chunk in response.iter_bytes():
                    if deadline_expired():
                        raise QueryDeadlineExceeded("model response exceeded budget")
                    content.extend(chunk)
                    if len(content) > 64 * 1024 * 1024:
                        raise ModelCallError("model_response_too_large")
                result = json.loads(content)
                if not isinstance(result, dict) or "error" in result:
                    raise ModelCallError("model_invalid_response")
                return result
        except (ModelCallError, ModelConfigurationError, QueryDeadlineExceeded):
            raise
        except httpx.TimeoutException:
            raise ModelCallError("model_timeout", transient=True) from None
        except httpx.RequestError:
            raise ModelCallError("model_connection_failed", transient=True) from None
        except (ValueError, TypeError, KeyError):
            raise ModelCallError("model_invalid_response") from None
        finally:
            if execution is not None:
                execution.mark("transport_finished")

    @staticmethod
    def _status(response):
        """根据模型 HTTP 响应决定调用是否失败，并将可重试状态映射为运行时异常。

        上游为 `_json`、`_openai_events`。
        """
        if response.status_code >= 300:
            status = response.status_code
            raise ModelCallError("model_http_" + str(status), transient=status == 429 or status >= 500)

    def chat(self, model, purpose, messages, *, tools=None, stream=False, max_tokens=None,
             response_format=None, logprobs=False):
        """将对话消息及可选工具、输出规格转换为模型请求，按 stream 返回同步结果或迭代器。

        下游调用 `chat_payload`、`ai.app.models.transport.stream_bounded`。
        """
        payload = chat_payload(model, messages, tools=tools, stream=stream,
                               max_tokens=max_tokens, response_format=response_format, logprobs=logprobs)
        if stream:
            return stream_bounded(lambda: self._chat_stream(model, purpose, payload),
                                  model.runtime.timeoutMs / 1000 if current_deadline_at() is None else None)
        result = self.invoke(model, purpose, lambda: parse_chat(model, self._json(model, payload), logprobs=logprobs))
        result.provider = model.provider
        result.model = model.model
        result.model_id = model.id
        result.model_revision = model.revision
        return result

    def _openai_events(self, model, payload):
        """读取 OpenAI 兼容流式响应并按帧解析正文、工具片段和结束信号。

        上游为 `_chat_stream`，下游调用 `_headers`、`ai.app.models.resolution.endpoint`。
        """
        headers = self._headers(model)
        # A provider callable supports intentionally unauthenticated local endpoints without
        # inventing a placeholder key or allowing the SDK to read host credentials.
        client = OpenAI(api_key=lambda: headers.get("Authorization", "").removeprefix("Bearer "),
                        base_url=endpoint(model), http_client=self.client(model), max_retries=0,
                        organization="", project="", admin_api_key="", webhook_secret="", default_headers={
                            **headers, "Authorization": headers.get("Authorization", omit),
                            "OpenAI-Organization": omit, "OpenAI-Project": omit})
        execution = io_state()
        try:
            if execution is not None:
                execution.mark("transport_started")
            with client.post(endpoint(model), body=payload, cast_to=Dict[str, Any], stream=True,
                             stream_cls=Stream[Dict[str, Any]], options={"timeout": bounded_timeout(600),
                                 "headers": {"Authorization": headers.get("Authorization", omit)}}) as stream:
                if io_state() is not None:
                    io_state().attach(stream, stream_response=stream.response)
                self._status(stream.response)
                yield from stream
        except APITimeoutError:
            raise ModelCallError("model_timeout", transient=True) from None
        except APIStatusError as exc:
            raise ModelCallError("model_http_" + str(exc.status_code),
                                 transient=exc.status_code == 429 or exc.status_code >= 500) from None
        except APIConnectionError:
            raise ModelCallError("model_connection_failed", transient=True) from None
        except (APIError, ValueError):
            raise ModelCallError("model_invalid_stream") from None
        finally:
            if execution is not None:
                execution.mark("transport_finished")

    def _chat_stream(self, model, purpose, payload):
        """在重试与时限约束内执行流式聊天，累积模型片段供上游消费并交付完整用量。

        上游为 `chat`，下游调用 `_breaker`、`_attempt`。
        """
        key = self._breaker(model, purpose)
        for attempt in range(model.runtime.retries + 1):
            emitted = False
            try:
                with self._attempt(model, purpose, attempt + 1):
                    parts, texts, usage, finish = {}, [], {}, None
                    for item in self._openai_events(model, payload):
                        if deadline_expired():
                            raise QueryDeadlineExceeded("stream exceeded model budget")
                        check_cancelled()
                        if not isinstance(item, dict) or item.get("error"):
                            raise ModelCallError("model_invalid_stream")
                        usage = item.get("usage") or usage
                        choices = item.get("choices") or []
                        if not choices:
                            continue
                        choice = choices[0]
                        delta = choice.get("delta") or {}
                        text = delta.get("content") or ""
                        for call in delta.get("tool_calls") or []:
                            merge_tool_fragment(parts, call)
                        if choice.get("finish_reason") is not None:
                            finish = choice["finish_reason"]
                        if text:
                            if not isinstance(text, str):
                                raise ModelCallError("model_invalid_stream")
                            texts.append(text)
                            emitted = True
                            yield text
                    if finish is None:
                        raise ModelCallError("model_stream_incomplete")
                    calls = [normalize_tool_call(parts[index], index) for index in sorted(parts)]
                    result = ChatResult("".join(texts) or None, int(usage.get("prompt_tokens") or 0),
                                        int(usage.get("completion_tokens") or 0),
                                        tool_calls=calls or None, finish_reason=finish)
                    result.provider, result.model = model.provider, model.model
                    result.model_id, result.model_revision = model.id, model.revision
                self._success(key, model, purpose)
                yield result
                return
            except (httpx.RequestError, ModelCallError, QueryDeadlineExceeded, ValueError, KeyError, TypeError, AttributeError) as exc:
                check_cancelled()
                if deadline_expired():
                    self._failure(key, model, purpose, "model_total_timeout")
                    raise QueryDeadlineExceeded("model stream exceeded total budget") from None
                code = exc.code if isinstance(exc, ModelCallError) else "model_stream_failed"
                transient = isinstance(exc, (httpx.RequestError, QueryDeadlineExceeded)) or getattr(exc, "transient", False)
                if not emitted and transient and attempt < model.runtime.retries:
                    self._backoff(model, attempt)
                    continue
                self._failure(key, model, purpose, code)
                if isinstance(exc, (QueryDeadlineExceeded, httpx.TimeoutException)) or code == "model_timeout":
                    raise QueryDeadlineExceeded("model stream exceeded its budget") from None
                raise ModelCallError(code) from None

    def embed(self, model, texts, *, input_type="document"):
        """根据模型协议和 input_type 发起向量调用，返回满足数量及维度约束的向量列表。

        下游调用 `ai.app.models.resolution.effective_compat`、`invoke`。
        """
        if not texts:
            return []
        compat = effective_compat(model)
        instruction = compat.get("queryInstruction" if input_type == "query" else "documentInstruction", "")
        prepared = [instruction + text for text in texts]

        def operation():
            """分批发送正文，按响应索引恢复输入顺序并校验维度，返回合并后的向量。

            下游调用 `_json`、`validate_vectors`。
            """
            vectors = []
            size = min(compat["embeddingBatchSize"], 1024)
            for offset in range(0, len(prepared), size):
                batch = prepared[offset:offset + size]
                body = {"model": model.model, "input": batch}
                if compat.get("supportsDimensionOverride"):
                    body["dimensions"] = model.dimension
                if "inputType" in compat:
                    body["input_type"] = compat["inputType"]
                data = self._json(model, body)
                items = data.get("data")
                if not isinstance(items, list):
                    raise ModelCallError("embedding_invalid_response")
                by_index = {}
                for item in items:
                    if not isinstance(item, dict):
                        raise ModelCallError("embedding_invalid_response")
                    index = item.get("index")
                    if isinstance(index, bool) or not isinstance(index, int) or index in by_index:
                        raise ModelCallError("embedding_invalid_index")
                    by_index[index] = item.get("embedding")
                if set(by_index) != set(range(len(batch))):
                    raise ModelCallError("embedding_invalid_index")
                rows = [by_index[index] for index in range(len(batch))]
                vectors.extend(validate_vectors(rows, len(batch), model.dimension))
            return vectors

        return self.invoke(model, "embedding", operation)

    def rerank(self, model, query, documents):
        """将问题和候选文档交给重排协议，返回按请求候选位置对齐的分数。

        下游调用 `ai.app.models.resolution.effective_compat`、`invoke`。
        """
        if not documents:
            return []
        compat = effective_compat(model)

        def operation():
            """分批发送问题和候选文本，按协议解析响应并把整理后的分数返回给运行时包装器。

            下游调用 `_json`、`parse_scores`。
            """
            scores = []
            size = min(compat["maxBatchSize"], 1000)
            for offset in range(0, len(documents), size):
                batch = documents[offset:offset + size]
                if model.protocol == "dashscope_rerank":
                    body = {"model": model.model, "input": {"query": query, "documents": batch},
                            "parameters": {"top_n": len(batch), "return_documents": False}}
                else:
                    body = {"model": model.model, "query": query, "documents": batch, "top_n": len(batch)}
                data = self._json(model, body)
                if model.protocol == "dashscope_rerank":
                    data = data.get("output") or {}
                scores.extend(parse_scores(data.get("results"), len(batch), compat["scoreScale"]))
            return scores

        return self.invoke(model, "rerank", operation)


def validate_vectors(rows, count, dimension):
    """校验模型返回的向量数量、维度和有限数值，返回可供向量索引使用的向量。

    上游为 `operation`。
    """
    if not isinstance(rows, list) or len(rows) != count:
        raise ModelCallError("embedding_count_mismatch")
    result = []
    for vector in rows:
        if not isinstance(vector, list) or len(vector) != dimension:
            raise ModelCallError("embedding_dimension_mismatch")
        if any(isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) for value in vector):
            raise ModelCallError("embedding_invalid_vector")
        if not any(value != 0 for value in vector):
            raise ModelCallError("embedding_zero_vector")
        result.append([float(value) for value in vector])
    return result


def parse_scores(rows, count, scale):
    """将重排结果按候选索引还原为分数列表，并检查数量、索引及标度是否有效。

    上游为 `operation`。
    """
    if not isinstance(rows, list) or len(rows) != count:
        raise ModelCallError("rerank_invalid_mapping")
    scores = {}
    for row in rows:
        if not isinstance(row, dict):
            raise ModelCallError("rerank_invalid_mapping")
        index = row.get("index")
        value = row.get("relevance_score", row.get("score"))
        if (isinstance(index, bool) or not isinstance(index, int) or index not in range(count)
                or index in scores or isinstance(value, bool) or not isinstance(value, (int, float))
                or not math.isfinite(value)):
            raise ModelCallError("rerank_invalid_mapping")
        if scale == "logit":
            value = 1 / (1 + math.exp(-value)) if value >= 0 else math.exp(value) / (1 + math.exp(value))
        if not 0 <= value <= 1:
            raise ModelCallError("rerank_invalid_score")
        scores[index] = float(value)
    return [scores[index] for index in range(count)]


def normalize_tool_call(call, index):
    """将单个模型工具调用整理为统一的索引、ID、名称和参数结构。

    上游为 `parse_chat`、`_chat_stream`。
    """
    if not isinstance(call, dict):
        raise ModelCallError("model_invalid_tool_call")
    function = call.get("function") or {}
    if not isinstance(function, dict) or not isinstance(function.get("name"), str) or not function["name"].strip():
        raise ModelCallError("model_invalid_tool_call")
    arguments = function.get("arguments", "")
    if not isinstance(arguments, str):
        raise ModelCallError("model_invalid_tool_call")
    if call.get("id") is not None and not isinstance(call["id"], str):
        raise ModelCallError("model_invalid_tool_call")
    return {"id": call.get("id") or "call_" + str(index), "type": "function",
            "function": {"name": function.get("name"), "arguments": arguments}}


def merge_tool_fragment(parts, fragment):
    """按工具调用索引把新增名称和参数片段累计到流式结果。

    上游为 `_chat_stream`。
    """
    index = fragment.get("index", 0)
    if isinstance(index, bool) or not isinstance(index, int) or not 0 <= index <= 1000:
        raise ModelCallError("model_invalid_tool_call")
    slot = parts.setdefault(index, {"id": "call_" + str(index), "type": "function",
                                    "function": {"name": "", "arguments": ""}})
    if fragment.get("id"):
        slot["id"] = fragment["id"]
    function = fragment.get("function") or {}
    for key in ("name", "arguments"):
        value = function.get(key) or ""
        if not isinstance(value, str):
            raise ModelCallError("model_invalid_tool_call")
        slot["function"][key] += value


def chat_payload(model, messages, *, tools=None, stream=False, max_tokens=None, response_format=None, logprobs=False):
    """根据模型能力与兼容项组装会话、工具和可选生成参数，返回供应商请求字典。

    上游为 `chat`，下游调用 `ai.app.models.resolution.effective_compat`。
    """
    compat = effective_compat(model)
    if tools and not model.capabilities.get("tools"):
        raise ModelConfigurationError("model_tools_not_supported")
    if stream and not model.capabilities.get("streaming"):
        raise ModelConfigurationError("model_streaming_not_supported")
    if response_format and not model.capabilities.get("json"):
        raise ModelConfigurationError("model_json_not_supported")
    output_limit = min(value for value in (max_tokens, model.maxOutputTokens) if value is not None) if any(
        value is not None for value in (max_tokens, model.maxOutputTokens)) else None
    body = {"model": model.model, "messages": messages, "stream": stream}
    if tools:
        body["tools"] = tools
    thinking = compat.get("enableThinking")
    if not stream and compat.get("thinkingDisableOnNonStream"):
        thinking = False
    if not compat.get("omitTemperature"):
        body["temperature"] = 0
    if output_limit is not None:
        body[compat["maxTokensField"]] = output_limit
    if tools and model.provider != "ollama":
        body["tool_choice"] = "auto"
    if response_format:
        body["response_format"] = response_format
    if thinking is not None:
        if model.provider == "ollama":
            body["reasoning_effort"] = "medium" if thinking else "none"
        else:
            body["enable_thinking"] = thinking
    if stream and compat.get("supportsStreamUsage"):
        body["stream_options"] = {"include_usage": True}
    if logprobs:
        body.update(logprobs=True, top_logprobs=20)
    return body


def parse_chat(model, data, *, logprobs=False):
    """从模型响应中提取正文、工具调用、终止信号及用量，返回 ChatResult。

    上游为 `chat`，下游调用 `normalize_tool_call`。
    """
    try:
        choice = data["choices"][0]
        message = choice["message"]
        usage = data.get("usage") or {}
        finish = choice.get("finish_reason")
        probability_data = (choice.get("logprobs") or {}).get("content")
        content = message.get("content")
        if content is not None and not isinstance(content, str):
            raise ModelCallError("model_invalid_response")
        calls = [normalize_tool_call(call, index) for index, call in enumerate(message.get("tool_calls") or [])]
        probabilities = None
        if logprobs and probability_data:
            probabilities = {item["token"]: float(item["logprob"])
                             for item in probability_data[0].get("top_logprobs") or []}
        return ChatResult(content, int(usage.get("prompt_tokens") or 0), int(usage.get("completion_tokens") or 0),
                          top_logprobs=probabilities, tool_calls=calls or None, finish_reason=finish)
    except (KeyError, TypeError, ValueError, IndexError, AttributeError):
        raise ModelCallError("model_invalid_response") from None


runtime = ModelRuntime()
