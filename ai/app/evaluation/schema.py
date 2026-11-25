"""定义裁判模型的评测输入与评分结果。"""

from ai.app.models.schema import ModelExecution
from pydantic import BaseModel


class GenerateJudgeRequest(BaseModel):
    modelExecution: ModelExecution | None = None

    question: str = ""
    answer: str
    contexts: list[str] = []
    groundTruth: str = ""


class GenerateJudgeResponse(BaseModel):
    faithfulness: float | None = None
    correctness: float | None = None
    relevance: float | None = None
    contextRelevance: float | None = None
    # 本次裁判（faithfulness + correctness [+ relevance][+ contextRelevance]）的 LLM token 用量
    tokenCost: int = 0
