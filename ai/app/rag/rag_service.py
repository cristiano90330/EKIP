"""调用生成模型并验证结构化回答，向上游提供同步结果或生成事件。"""

from dataclasses import dataclass
from typing import Literal

from pydantic import BaseModel, ConfigDict, ValidationError

from ai.app.llm.guard import gate
from ai.app.rag.prompt_builder import build_prompt, SYSTEM_PROMPT
from ai.app.rag.schema import ContextItem


class _AnswerPayload(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    status: Literal["success", "no_evidence"]
    answer: str


@dataclass(frozen=True)
class GenerationResult:
    answer: str
    token_cost: int = 0
    outcome: Literal["success", "no_evidence", "dependency_unavailable"] = "success"
    reason_code: str = "none"
    provider: str = ""
    degrade_reason: str = ""


def generation_response_format() -> dict:
    """返回生成模型使用的结构化响应格式，约束答案和失败原因字段。

    上游为 `ai.app.rag.langchain_pipeline.GuardedChatModel._generate`、`ai.app.rag.pipeline._fit_generation_input.cost`。
    """
    return {"type": "json_schema", "json_schema": {
        "name": "rag_answer_v1", "strict": True, "schema": _AnswerPayload.model_json_schema()}}


def decode_generation(result) -> GenerationResult:
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
        payload = _AnswerPayload.model_validate_json(result.content or "")
        if payload.status == "success" and not payload.answer.strip():
            raise ValueError("empty successful answer")
    except (ValidationError, ValueError):
        return GenerationResult("", outcome="dependency_unavailable",
                                reason_code="generation_invalid_response", **usage)
    if payload.status == "no_evidence":
        return GenerationResult("知识库中没有找到足够相关的信息。", outcome="no_evidence",
                                reason_code="generation_no_evidence", **usage)
    return GenerationResult(payload.answer, **usage)


class RAGService:
    def generate_with_usage(self, question: str, contexts: list[ContextItem], history=None) -> GenerationResult:
        """用当前问题、已准入证据和历史构造同步生成提示，返回标准生成结果及模型用量。

        下游调用 `ai.app.rag.prompt_builder.build_prompt`、`decode_generation`。
        """
        prompt = build_prompt(question, contexts, history)
        return decode_generation(gate.call("generate", SYSTEM_PROMPT, prompt,
                                           response_format=generation_response_format()))

    def generate_stream(self, question: str, contexts: list[ContextItem], history=None):
        """用已准入证据构造生成提示，先输出正文增量，再交付单一生成终态及用量。

        下游调用 `ai.app.rag.prompt_builder.build_prompt`、`ai.app.llm.guard.gate.stream`。
        """
        prompt = build_prompt(question, contexts, history)
        iterator = gate.stream("generate", SYSTEM_PROMPT, prompt,
                               response_format=generation_response_format())
        terminal = None
        try:
            for item in iterator:
                if not isinstance(item, str):
                    terminal = decode_generation(item)
        finally:
            iterator.close()
        if terminal is None:
            terminal = GenerationResult("", outcome="dependency_unavailable",
                                        reason_code="generation_invalid_response")
        if terminal.outcome == "success":
            yield ("delta", terminal.answer)
        yield ("done", terminal)


rag_service = RAGService()
