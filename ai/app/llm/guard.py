"""根据模型用途调用请求绑定，统一流式及同步调用的降级结果。"""

import hashlib
import json
import threading
import time
import logging
logger = logging.getLogger(__name__)

from collections import OrderedDict
from dataclasses import dataclass

from ai.app.llm.purposes import get_purpose
from ai.app.llm.tokens import input_tokens
from ai.app.models.context import binding
from ai.app.models.runtime import runtime, identity, ModelCallError
from ai.app.rag.deadline import QueryDeadlineExceeded, inherit_deadline, current_deadline_at, deadline_expired
from ai.app.models.cancellation import check_cancelled

@dataclass
class DegradedResult:
    content: str | None = None
    prompt_tokens: int = 0
    completion_tokens: int = 0
    degraded: bool = True
    reason: str = ""
    attempts: int = 0
    fallback_used: str = ""
    provider: str = ""

    @property
    def total_tokens(self):
        """合并降级对象的提示与生成 Token 计数，保留与正常模型结果一致的用量访问方式。"""
        return self.prompt_tokens + self.completion_tokens


class ModelGate:
    def __init__(self):
        """初始化用途模型的结果缓存及并发锁，供同步路由复用满足缓存条件的结果。"""
        self._cache = OrderedDict()
        self._lock = threading.RLock()

    def readiness(self, purpose):
        """查询用途对应的模型运行时观测，供依赖就绪接口展示。

        下游调用 `ai.app.models.runtime.runtime.readiness`。
        """
        return runtime.readiness(purpose)

    def _run(self, purpose, messages, tools=None, timeout_s=None, response_format=None, logprobs=False,
             allow_cache=True):
        """为已绑定用途执行同步对话调用，整理模型用量并把可预期失败交给上游作为降级结果。

        上游为 `call`，下游调用 `ai.app.models.cancellation.check_cancelled`、`ai.app.rag.deadline.deadline_expired`。
        """
        check_cancelled()
        if deadline_expired():
            raise QueryDeadlineExceeded("model execution exceeded total budget")
        model = binding(purpose)
        cfg = get_purpose(purpose)
        resolved_format = response_format or ({"type": "json_object"} if cfg.json_output else None)
        estimate = input_tokens(messages, tools, resolved_format)
        if estimate > cfg.budget_tokens:
            logger.warning(
                "[DEBUG-support-budget] purpose=%s estimate=%d budget=%d",
                purpose, estimate, cfg.budget_tokens)
            return DegradedResult(reason="model_context_capacity_exceeded", provider=model.provider)
        cache_key = None
        if allow_cache and cfg.cache and not tools and not logprobs:
            material = json.dumps([identity(model), purpose, messages, resolved_format], sort_keys=True, ensure_ascii=False)
            cache_key = hashlib.sha256(material.encode()).hexdigest()
            with self._lock:
                if cache_key in self._cache:
                    self._cache.move_to_end(cache_key)
                    return self._cache[cache_key]
        try:
            deadline = current_deadline_at()
            if timeout_s is not None:
                proposed = time.monotonic() + timeout_s
                deadline = proposed if deadline is None else min(deadline, proposed)
            with inherit_deadline(deadline):
                result = runtime.chat(model, purpose, messages, tools=tools, max_tokens=cfg.max_tokens,
                                      response_format=resolved_format, logprobs=logprobs)
        except ModelCallError as exc:
            return DegradedResult(reason=exc.code, provider=model.provider)
        if cache_key is not None:
            with self._lock:
                self._cache[cache_key] = result
                self._cache.move_to_end(cache_key)
                while len(self._cache) > 512:
                    self._cache.popitem(last=False)
        return result

    def call(self, purpose, system_prompt, user_prompt, timeout_s=None, logprobs=False, response_format=None):
        """用系统提示与用户提示构造两条消息，返回模型同步结果或降级对象。

        下游调用 `_run`。
        """
        return self._run(purpose, [{"role":"system","content":system_prompt},
                                  {"role":"user","content":user_prompt}], timeout_s=timeout_s,
                         response_format=response_format, logprobs=logprobs)

    def call_messages(self, purpose, messages, tools=None, timeout_s=None, allow_cache=True):
        """接收已构造的历史消息与工具定义，返回模型同步结果或降级对象。

        下游调用 `_run`。
        """
        return self._run(purpose, messages, tools=tools, timeout_s=timeout_s, allow_cache=allow_cache)

    def stream(self, purpose, system_prompt=None, user_prompt=None, timeout_s=None, *, messages=None,
               tools=None, allow_cache=True, response_format=None):
        """将提示或已有消息交给托管流式模型，向调用方产出模型事件，并以受控结果报告失败。

        上游为 `call_messages_stream`，下游调用 `ai.app.models.cancellation.check_cancelled`、`ai.app.rag.deadline.deadline_expired`。
        """
        check_cancelled()
        if deadline_expired():
            raise QueryDeadlineExceeded("model execution exceeded total budget")
        messages = messages if messages is not None else [{"role":"system","content":system_prompt or ""},
                                                         {"role":"user","content":user_prompt or ""}]
        model, cfg = binding(purpose), get_purpose(purpose)
        response_format = response_format or ({"type": "json_object"} if cfg.json_output else None)
        if input_tokens(messages, tools, response_format) > cfg.budget_tokens:
            yield DegradedResult(reason="model_context_capacity_exceeded", provider=model.provider)
            return
        emitted = False
        deadline = current_deadline_at()
        if timeout_s is not None:
            proposed = time.monotonic() + timeout_s
            deadline = proposed if deadline is None else min(deadline, proposed)
        iterator = None
        try:
            with inherit_deadline(deadline):
                iterator = runtime.chat(model, purpose, messages, tools=tools, stream=True,
                                        max_tokens=cfg.max_tokens, response_format=response_format)
                for item in iterator:
                    emitted = emitted or isinstance(item, str)
                    yield item
        except ModelCallError as exc:
            if emitted:
                raise
            yield DegradedResult(reason=exc.code, provider=model.provider)
        finally:
            if iterator is not None:
                iterator.close()

    def call_messages_stream(self, purpose, messages, tools=None, timeout_s=None):
        """将会话历史及工具定义直接交给流式模型入口，向 Agent 决策路由传回事件。

        下游调用 `stream`。
        """
        yield from self.stream(purpose, messages=messages, tools=tools, timeout_s=timeout_s, allow_cache=False)


gate = ModelGate()
