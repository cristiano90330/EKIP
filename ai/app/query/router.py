"""接受带历史的提问并调用改写模型，输出供召回使用的独立问题和降级信息。"""

from ai.app.llm.guard import gate
from ai.app.llm.parse_utils import extract_json_obj
from ai.app.query.schema import ChatMessage, RewriteRequest, RewriteResponse

REWRITE_SYSTEM = """你是企业知识库多轮问答中的"查询改写器"。

你的任务：把【当前问题】改写为一条"独立、完整、可直接检索"的问题，让它在脱离对话历史的情况下也能被搜索引擎 / 向量检索理解。

判断是否需要改写：
- 当前问题含有指代（它、这个、那、刚才说的…）、省略（如"具体怎么做？"缺少主语）或依赖上文信息时 → 需要改写，把上下文补全为完整表述；
- 当前问题本身完整独立、无歧义 → 不要改写，原样返回。

只输出一个 JSON 对象，格式严格为：
{"rewrittenQuery": "...", "rewritten": true/false, "reason": "..."}
- rewrittenQuery：改写后的问题；不改写时与当前问题相同。
- rewritten：是否发生了改写。
- reason：一句话说明依据。
不要输出任何其它内容或解释。"""


def _history_text(messages: list[ChatMessage]) -> str:
    """将历史消息拼成改写提示可用的角色与正文文本。

    上游为 `rewrite`。
    """
    return "\n".join(f"{m.role}: {m.content}" for m in messages)


def _parse_rewrite(raw: str) -> tuple[str, bool, str] | None:
    """解析改写模型的 JSON，返回改写文本、是否改写与原因，格式异常时返回 None。

    上游为 `rewrite`，下游调用 `ai.app.llm.parse_utils.extract_json_obj`。
    """
    obj = extract_json_obj(raw)
    if obj:
        rewritten_query = obj.get("rewrittenQuery")
        if rewritten_query:
            return (
                str(rewritten_query),
                bool(obj.get("rewritten", False)),
                str(obj.get("reason", "") or ""),
            )
    return None


def rewrite(request: RewriteRequest):
    """接收当前问题及历史，在模型绑定内执行改写，返回问答召回可用的问题及诊断字段。

    上游为 `ai.app.rag.pipeline.rewrite_query`，下游调用 `ai.app.query.schema.RewriteResponse`、`_history_text`。
    """
    if not request.history:
        return RewriteResponse(
            originalQuery=request.question,
            rewrittenQuery=request.question,
            rewritten=False,
            reason="无对话历史，无需改写",
            tokenCost=0,
        )

    user_prompt = (
        f"【对话历史】\n{_history_text(request.history)}\n\n"
        f"【当前问题】\n{request.question}"
    )
    result = gate.call("rewrite", REWRITE_SYSTEM, user_prompt)
    guard_degraded = bool(getattr(result, "degraded", False))

    if guard_degraded:
        rewritten_query, rewritten, reason = request.question, False, "治理层降级，回退原样"
        degrade_reason = "guard_degraded"
    else:
        parsed = _parse_rewrite(result.content or "")
        if parsed is None:
            rewritten_query, rewritten, reason = request.question, False, "解析 LLM 输出失败，回退原样"
            degrade_reason = "guard_unparsable"
        else:
            rewritten_query, rewritten, reason = parsed
            degrade_reason = ""

    return RewriteResponse(
        originalQuery=request.question,
        rewrittenQuery=rewritten_query,
        rewritten=rewritten,
        reason=reason,
        tokenCost=result.total_tokens,
        degraded=bool(degrade_reason),
        degradeReason=degrade_reason,
    )
