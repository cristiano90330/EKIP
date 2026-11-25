"""定义问题改写的历史输入、结果和诊断字段。"""

from ai.app.models.schema import ModelExecution
from pydantic import BaseModel


class ChatMessage(BaseModel):
    role: str
    content: str


class RewriteRequest(BaseModel):
    modelExecution: ModelExecution | None = None

    question: str
    history: list[ChatMessage] = []


class RewriteResponse(BaseModel):
    originalQuery: str
    rewrittenQuery: str
    rewritten: bool
    reason: str
    # 本次改写的 LLM token 用量（prompt + completion），无历史时为 0
    tokenCost: int = 0
    # 与模型主动选择不改写相区分的治理降级状态。
    degraded: bool = False
    degradeReason: str = ""
