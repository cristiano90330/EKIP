"""调用已绑定的裁判模型比较问答结果，并将模型输出转换为评分响应。"""

from ai.app.models.context import managed_endpoint
"""模型质量评估路由：接收 Java 计算的答案与证据，由 LLM 裁判评分。

检索指标由 Java 侧计算；请求和响应字段采用与 Java DTO 对齐的 camelCase。
"""
import re

from fastapi import APIRouter

from ai.app.evaluation.schema import (
    GenerateJudgeRequest,
    GenerateJudgeResponse,
)
from ai.app.llm.guard import gate

router = APIRouter(prefix="/evaluation", tags=["evaluation"])


# ---------- LLM 裁判：Faithfulness / Correctness ----------

FAITHFULNESS_SYSTEM = """你是 RAG 评测裁判，评估【答案】对【知识库内容】的忠实程度（Faithfulness）。

判断标准：
1. 答案中的每个关键信息是否都能在知识库内容中找到依据；
2. 答案是否编造了知识库中不存在的信息；
3. 若知识库信息不足，答案是否如实说明而未编造。

只输出一个 0 到 100 的整数分数（100 = 完全忠实、无编造；0 = 完全编造、无依据），不要输出任何其它内容。"""

CORRECTNESS_SYSTEM = """你是 RAG 评测裁判，评估【答案】与【标准答案】的吻合程度（Correctness）。

判断标准：
1. 答案与标准答案在核心事实上是否一致；
2. 答案是否遗漏标准答案中的关键点；
3. 答案是否包含与标准答案矛盾的内容。

只输出一个 0 到 100 的整数分数（100 = 完全一致；0 = 完全不符），不要输出任何其它内容。"""


RELEVANCE_SYSTEM = """你是 RAG 评测裁判，评估【答案】对【问题】的切题程度（Answer Relevance）。

判断标准：
1. 答案是否围绕问题主题展开，直接回答所问；
2. 是否给出问题所问的关键信息，而非泛泛而谈或答非所问；
3. 即使答案内容正确，若严重跑题也应给低分。

只输出一个 0 到 100 的整数分数（100 = 完全切题；0 = 完全跑题），不要输出任何其它内容。"""


CONTEXT_RELEVANCE_SYSTEM = """你是 RAG 评测裁判，评估【知识库内容】与【问题】的相关程度（Context Relevance）。

判断标准：
1. 检索到的资料是否围绕问题主题，包含回答问题所需的关键信息；
2. 是否包含大量与问题无关、冗余或噪音的内容；
3. 即使没有直接答案，资料应仍与问题主题相关。

只输出一个 0 到 100 的整数分数（100 = 完全相关；0 = 完全无关），不要输出任何其它内容。"""


# 将裁判提示提交给模型用途绑定，返回可解析的评分与本次调用用量。
# 上游为 `judge_generation`，下游调用 `ai.app.llm.guard.gate.call`。
def _judge_score_and_tokens(system_prompt: str, user_prompt: str) -> tuple[float | None, int]:
    """调 LLM 打分并归一化到 0~1；返回 (分数, token 用量)。解析失败返回 (None, 已用 tokens)。

    降级（degraded）时当作无分（None），并累加 0 token。
    """
    result = gate.call("judge", system_prompt, user_prompt)
    if getattr(result, "degraded", False):
        return None, 0
    raw = result.content or ""
    match = re.search(r"\d+(?:\.\d+)?", raw)
    if not match:
        return None, result.total_tokens
    value = float(match.group())
    # 兼容两种输出：0.85（直接 0~1）或 85 / 85分（0~100 再归一化）
    if value > 1.0:
        value /= 100.0
    return max(0.0, min(1.0, value)), result.total_tokens


# 接收生成评测请求，组织裁判输入并将评分与模型用量返回给 Java 评分入口。
# 下游调用 `_judge_score_and_tokens`、`ai.app.evaluation.schema.GenerateJudgeResponse`。
@router.post("/generate", response_model=GenerateJudgeResponse)
@managed_endpoint(budget_ms=30000)
def judge_generation(request: GenerateJudgeRequest):
    context_text = "\n\n".join(
        f"[资料{i + 1}]\n{content}"
        for i, content in enumerate(request.contexts)
    ) or "（无上下文）"

    faithfulness, f_tokens = _judge_score_and_tokens(
        FAITHFULNESS_SYSTEM,
        f"【知识库内容】\n{context_text}\n\n【答案】\n{request.answer}\n\n请给出忠实度分数：",
    )

    correctness, c_tokens = _judge_score_and_tokens(
        CORRECTNESS_SYSTEM,
        f"【标准答案】\n{request.groundTruth or '（未提供）'}\n\n【答案】\n{request.answer}\n\n请给出正确性分数：",
    )

    relevance = None
    r_tokens = 0
    if request.question:
        relevance, r_tokens = _judge_score_and_tokens(
            RELEVANCE_SYSTEM,
            f"【问题】\n{request.question}\n\n【答案】\n{request.answer}\n\n请给出切题度分数：",
        )

    context_relevance = None
    cr_tokens = 0
    if request.contexts:
        context_relevance, cr_tokens = _judge_score_and_tokens(
            CONTEXT_RELEVANCE_SYSTEM,
            f"【问题】\n{request.question}\n\n【知识库内容】\n{context_text}\n\n请给出资料与问题的相关度分数：",
        )

    return GenerateJudgeResponse(
        faithfulness=faithfulness,
        correctness=correctness,
        relevance=relevance,
        contextRelevance=context_relevance,
        tokenCost=f_tokens + c_tokens + r_tokens + cr_tokens,
    )
