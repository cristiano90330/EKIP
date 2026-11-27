"""调用生成模型并验证结构化回答，向上游提供同步结果或生成事件。"""

from dataclasses import dataclass
from typing import Literal

from pydantic import BaseModel, ConfigDict, ValidationError
from pydantic_core import from_json

from ai.app.llm.guard import gate
from ai.app.models.cancellation import check_cancelled
from ai.app.models.context import current_execution
from ai.app.rag.prompt_builder import ORDERED_SYSTEM_PROMPT, SYSTEM_PROMPT, build_prompt
from ai.app.rag.schema import ContextItem


# 只限制预览解码的缓冲。最终答案始终从模型终态独立校验。
_MAX_PREVIEW_JSON_CHARS = 64 * 1024


class _AnswerPayload(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    status: Literal["success", "no_evidence"]
    answer: str


class _OrderedAnswerPayload(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    result: tuple[Literal["success", "no_evidence"], str]


class _AnswerPreview:
    """从未完成的结构化输出读取临时正文，交给 LangChain SSE 的 delta 事件。"""

    def __init__(self, *, ordered: bool) -> None:
        self._ordered = ordered
        self._prefix = ""
        self._published = ""
        self._status_confirmed = False
        self._disabled = False

    def _fields(self, parsed):
        """读取已解码的状态和正文，容许流中只到达数组第一项。"""
        if not isinstance(parsed, dict):
            return None, None
        if not self._ordered:
            return parsed.get("status"), parsed.get("answer")
        result = parsed.get("result")
        if not isinstance(result, list) or not 1 <= len(result) <= 2:
            return None, None
        return result[0], result[1] if len(result) == 2 else None

    def feed(self, chunk: str) -> str:
        """接收模型原始片段，仅返回已解码且尚未展示的正文后缀。

        上游为 `RAGService.generate_stream`，最终校验由 `decode_generation` 完成。
        """
        if self._disabled:
            return ""
        if len(self._prefix) + len(chunk) > _MAX_PREVIEW_JSON_CHARS:
            self._prefix = ""
            self._published = ""
            self._disabled = True
            return ""
        self._prefix += chunk
        try:
            # 用完整字段确认状态，避免把尚未闭合的 "success" 当成许可。
            if not self._status_confirmed:
                complete_fields = from_json(self._prefix, allow_partial=True)
                if self._fields(complete_fields)[0] != "success":
                    return ""
                self._status_confirmed = True
            preview = from_json(self._prefix, allow_partial="trailing-strings")
        except ValueError:
            return ""

        status, answer = self._fields(preview)
        if status != "success" or not isinstance(answer, str) or not answer.startswith(self._published):
            return ""
        delta = answer[len(self._published):]
        self._published = answer
        return delta


@dataclass(frozen=True)
class GenerationResult:
    answer: str
    token_cost: int = 0
    outcome: Literal["success", "no_evidence", "dependency_unavailable"] = "success"
    reason_code: str = "none"
    provider: str = ""
    degrade_reason: str = ""


def generation_response_format(*, ordered: bool = False) -> dict:
    """返回生成模型使用的结构化响应格式，约束答案和失败原因字段。

    上游为 `ai.app.rag.langchain_pipeline.GuardedChatModel._generate`、`ai.app.rag.pipeline._fit_generation_input.cost`。
    """
    name, payload = ("rag_ordered_answer_v1", _OrderedAnswerPayload) if ordered else ("rag_answer_v1", _AnswerPayload)
    return {"type": "json_schema", "json_schema": {
        "name": name, "strict": True, "schema": payload.model_json_schema()}}


def generation_contract(*, provisional: bool = False) -> tuple[str, dict, bool]:
    """为实际生成和输入预算选同一套提示及格式。Ollama 的流式预览使用按位置固定的状态。"""
    execution = current_execution() if provisional else None
    model = execution.bindings.get("generate") if execution is not None else None
    ordered = model is not None and model.provider == "ollama"
    return (ORDERED_SYSTEM_PROMPT if ordered else SYSTEM_PROMPT,
            generation_response_format(ordered=ordered), ordered)


def decode_generation(result, *, ordered: bool = False) -> GenerationResult:
    """将模型原始结果解析为生成终态，保留正文、结果类型和用量。

    上游为 `ai.app.rag.langchain_pipeline.GuardedChatModel._generate`、`generate_with_usage`。
    """
    usage = {"token_cost": result.total_tokens, "provider": getattr(result, "provider", "") or ""}
    if getattr(result, "degraded", False):
        reason = getattr(result, "reason", "")
        return GenerationResult("", outcome="dependency_unavailable",
                                reason_code="generation_context_capacity_exceeded" if reason in {
                                    "model_context_capacity_exceeded", "model_prompt_budget_exceeded"} else "generation_degraded",
                                degrade_reason=getattr(result, "reason", "") or "", **usage)
    try:
        if getattr(result, "finish_reason", None) not in {None, "stop"}:
            raise ValueError("incomplete generation")
        if ordered:
            status, answer = _OrderedAnswerPayload.model_validate_json(result.content or "").result
        else:
            payload = _AnswerPayload.model_validate_json(result.content or "")
            status, answer = payload.status, payload.answer
        if status == "success" and not answer.strip():
            raise ValueError("empty successful answer")
    except (ValidationError, ValueError):
        return GenerationResult("", outcome="dependency_unavailable",
                                reason_code="generation_invalid_response", **usage)
    if status == "no_evidence":
        return GenerationResult("知识库中没有找到足够相关的信息。", outcome="no_evidence",
                                reason_code="generation_no_evidence", **usage)
    return GenerationResult(answer, **usage)


class RAGService:
    def generate_with_usage(self, question: str, contexts: list[ContextItem], history=None) -> GenerationResult:
        """用当前问题、已准入证据和历史构造同步生成提示，返回标准生成结果及模型用量。

        下游调用 `ai.app.rag.prompt_builder.build_prompt`、`decode_generation`。
        """
        prompt = build_prompt(question, contexts, history)
        return decode_generation(gate.call("generate", SYSTEM_PROMPT, prompt,
                                           response_format=generation_response_format()))

    def generate_stream(self, question: str, contexts: list[ContextItem], history=None, *, provisional=False):
        """用已准入证据构造生成提示，输出正文增量与经过完整校验的单一终态。

        上游为 `ai.app.rag.pipeline._stream_events`。启用 provisional 时先发送临时正文，
        关闭时仅发送经过校验的正文。下游调用 `gate.stream`、`decode_generation`。
        """
        prompt = build_prompt(question, contexts, history)
        system, response_format, ordered = generation_contract(provisional=provisional)
        iterator = gate.stream("generate", system, prompt, response_format=response_format)
        preview = _AnswerPreview(ordered=ordered) if provisional else None
        terminal = None
        try:
            for item in iterator:
                if provisional:
                    check_cancelled()
                if isinstance(item, str):
                    if preview is not None and terminal is None:
                        delta = preview.feed(item)
                        if delta:
                            yield ("delta", delta)
                else:
                    terminal = decode_generation(item, ordered=ordered)
        finally:
            iterator.close()
        if terminal is None:
            terminal = GenerationResult("", outcome="dependency_unavailable",
                                        reason_code="generation_invalid_response")
        if preview is None and terminal.outcome == "success":
            yield ("delta", terminal.answer)
        yield ("done", terminal)


rag_service = RAGService()
