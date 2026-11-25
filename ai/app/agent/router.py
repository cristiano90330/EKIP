"""接收 Java Agent 的对话消息与工具定义，返回模型决策、事件流或历史摘要。"""

from ai.app.models.context import managed_endpoint, model_execution, iterate_in_context, binding
"""提供 Agent 决策与会话摘要的单次模型调用，并统一交给 ModelGate 治理。

Java 负责 Agent 回合、工具执行和记忆；本模块只适配请求/响应并返回 provider、用量和降级状态。
决策有同步与流式两个入口：同步入口一次给出完整决策，流式入口先逐块吐正文、最后用 final 帧
给出 tool_calls / finish_reason / usage——两入口的治理规则完全相同（同一个 gate）。
"""
import json

from fastapi import APIRouter
from fastapi.responses import StreamingResponse

from ai.app.agent.schema import (
    AgentChatMessage,
    AgentDecideRequest,
    AgentDecideResponse,
    SummarizeRequest,
    SummarizeResponse,
)
from ai.app.llm.guard import DegradedResult, gate
from ai.app.rag.deadline import QueryDeadlineExceeded

router = APIRouter(prefix="/agent", tags=["agent"])


def _encode_sse(event: str, data: dict) -> str:
    """将事件名和载荷编码为完整 SSE 帧，供 decide_stream 的事件生成器发送。

    上游为 `unmanaged_events`。
    """
    return f"event: {event}\ndata: {json.dumps(data, ensure_ascii=False, separators=(',', ':'))}\n\n"


def _to_openai_message(m: AgentChatMessage) -> dict:
    """将 Java 发送的 Agent 消息转换为模型协议的字典，保留工具调用及结果关联 ID。

    上游为 `decide`、`decide_stream`。
    """
    msg: dict = {"role": m.role, "content": m.content if m.content is not None else ""}
    if m.tool_calls:
        msg["tool_calls"] = [tc.model_dump(exclude_none=True) for tc in m.tool_calls]
    if m.tool_call_id is not None:
        msg["tool_call_id"] = m.tool_call_id
    return msg


# 接收 AgentDecideRequest，将历史和工具定义交给托管模型，返回 Java 可解析的 Agent 决策。
# 下游调用 `_to_openai_message`、`ai.app.llm.guard.gate.call_messages`。
@router.post("/decide", response_model=AgentDecideResponse)
@managed_endpoint(budget_ms=30000)
def decide(request: AgentDecideRequest):    # 同步模型调用由 FastAPI 放入工作线程执行。
    """执行一次受治理的决策调用；降级状态作为响应返回，由 Java 侧决定回合处置。"""
    messages = [_to_openai_message(m) for m in request.messages]
    result = gate.call_messages(
        "agent", messages, tools=request.tools or None, timeout_s=request.timeout_s)

    provider = getattr(result, "provider", "") or ""
    if isinstance(result, DegradedResult):
        return AgentDecideResponse(
            degraded=True,
            degrade_reason=result.reason,
            provider_used=provider,
        )
    return AgentDecideResponse(
        content=result.content,
        tool_calls=result.tool_calls or [],
        prompt_tokens=result.prompt_tokens,
        completion_tokens=result.completion_tokens,
        provider_used=provider,
        finish_reason=getattr(result, "finish_reason", None) or "",
    )


# 接收 Agent 决策请求，经模型流式传输输出增量事件及终态，供 Java Agent 网关消费。
# 下游调用 `_to_openai_message`、`ai.app.models.context.model_execution`。
@router.post("/decide/stream")
def decide_stream(request: AgentDecideRequest):    # 同步模型调用由 FastAPI 放入工作线程执行。
    """一次受治理的流式决策：正文逐块下发，最后恰好一帧 final 或 error。

    帧约定（与 /rag/query/stream 同风格）：
      - `delta`：`{"text": str}`，正文增量（模型发起工具调用前的内容是「引子」，由调用方判定）；
      - `final`：与同步响应同形，含 `tool_calls` / `finish_reason` / usage / degraded；
      - `error`：流在首个 delta 之后中断时发出，调用方应保留已收到的正文。
    降级不抛异常，仍以 `final` 帧（degraded=true）表达，由 Java 决定回合处置。
    """
    messages = [_to_openai_message(m) for m in request.messages]

    # Validate the required selection before streaming headers are committed.
    with model_execution(request.modelExecution):
        binding("agent")

    def unmanaged_events():
        """驱动本次模型调用，逐帧产生内容、工具调用与最终决策事件。

        上游为 `events`，下游调用 `ai.app.llm.guard.gate.call_messages_stream`、`_encode_sse`。
        """
        try:
            for item in gate.call_messages_stream(
                    "agent", messages, tools=request.tools or None, timeout_s=request.timeout_s):
                if isinstance(item, str):
                    yield _encode_sse("delta", {"text": item})
                    continue
                provider = getattr(item, "provider", "") or ""
                if isinstance(item, DegradedResult):
                    yield _encode_sse("final", {
                        "degraded": True,
                        "degrade_reason": item.reason,
                        "provider_used": provider,
                    })
                    return
                yield _encode_sse("final", {
                    "content": item.content,
                    "tool_calls": item.tool_calls or [],
                    "prompt_tokens": item.prompt_tokens,
                    "completion_tokens": item.completion_tokens,
                    "provider_used": provider,
                    "finish_reason": getattr(item, "finish_reason", None) or "",
                    # 成功帧也显式带上 degraded=false：Java 侧按同一份结构解析，
                    # 「字段缺失」与「明确未降级」不应靠默认值区分。
                    "degraded": False,
                })
                return
        except QueryDeadlineExceeded:
            yield _encode_sse("error", {"code":"timeout", "reasonCode":"model_timeout", "message":"model_stream_timeout"})
        except Exception:                # noqa: BLE001 —— 流已开始，只能发终态 error
            yield _encode_sse("error", {"code": "dependency_unavailable", "message": "model_stream_failed"})

    def events():
        """在已绑定的模型执行上下文中推进 unmanaged_events，供 StreamingResponse 消费。

        上游为 `decide_stream`，下游调用 `ai.app.models.context.model_execution`。
        """
        with model_execution(request.modelExecution):
            yield from unmanaged_events()

    return StreamingResponse(iterate_in_context(events()), media_type="text/event-stream",
                             headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})


# 将待整理的历史消息交给摘要用途绑定，返回供 Java Agent 保存的摘要及用量。
# 下游调用 `ai.app.llm.guard.gate.call`、`ai.app.agent.schema.SummarizeResponse`。
@router.post("/summarize", response_model=SummarizeResponse)
@managed_endpoint(budget_ms=30000)
def summarize(request: SummarizeRequest):    # 同步模型调用由 FastAPI 放入工作线程执行。
    """压缩 Agent 会话摘要；降级作为响应返回，由 Java 协调器决定是否改走降级归档。

    ``finish_reason`` 必须原样带回：摘要撞到输出上限时内容仍然非空、读起来也像摘要，
    Java 侧只有靠它才能识别「这段摘要被截断了」。
    """
    result = gate.call("summarize", request.system_prompt, request.user_prompt)

    provider = getattr(result, "provider", "") or ""
    if isinstance(result, DegradedResult):
        return SummarizeResponse(
            degraded=True,
            degrade_reason=result.reason,
            provider_used=provider,
        )
    return SummarizeResponse(
        content=result.content,
        prompt_tokens=result.prompt_tokens,
        completion_tokens=result.completion_tokens,
        provider_used=provider,
        finish_reason=(result.finish_reason or ""),
    )
