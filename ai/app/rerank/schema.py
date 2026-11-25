"""定义重排文档、请求和分数响应的字段。"""

from ai.app.models.schema import ModelExecution
from pydantic import BaseModel, Field


class RerankCandidate(BaseModel):
    id: int
    content: str


class RerankRequest(BaseModel):
    modelExecution: ModelExecution | None = None

    query: str
    candidates: list[RerankCandidate]


class RerankResponse(BaseModel):
    # 与 candidates 顺序一致的 0~1 相关度分
    scores: list[float]
    # 与 scores 按候选顺序一一对应的 ID。
    ids: list[int] = []
    # 标记本步骤是否使用了降级结果。
    degraded: bool = False
    # 固定短码（server_unreachable / server_bad_response / embed_sim_failed ...），不是自由文本：
    # 它要当 Micrometer 的 tag 用，中文长句会撑爆基数、也没法写告警规则。
    degradeReason: str = ""
    # 本次分数的真实来源：model | embed_sim | none（none = 全 1 常数，等于没排）。
    scorer: str = "model"
    # 整步仍在运行但未能解析分数的候选数。
    parseFailures: int = 0
    # 与 parseFailures 对应的候选 id；这些分数只用于保序，不得进入 evidence gate。
    unscoredIds: list[int] = Field(default_factory=list)
