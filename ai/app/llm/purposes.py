"""定义模型用途名称和调用属性，供运行时及绑定校验共享。"""

from dataclasses import dataclass
from ai.app.models.context import binding

PURPOSE_NAMES = {"generate", "rewrite", "rerank", "judge", "agent", "summarize", "evidence_support"}

@dataclass(frozen=True)
class PurposeConfig:
    purpose: str
    provider: str
    model: str
    timeout_s: float
    budget_tokens: int
    max_tokens: int | None
    cache: bool = False
    temperature: float = 0.0
    retries: int = 0
    allow_fallback: bool = False
    json_output: bool = False


def get_purpose(name):
    """用用途名称查询模型调用配置，为门控层提供该用途的默认行为。

    上游为 `ai.app.llm.guard.ModelGate._run`，下游调用 `ai.app.models.context.binding`。
    """
    if name not in PURPOSE_NAMES:
        raise ValueError("unknown model purpose")
    model = binding(name)
    output = {"generate": 1024, "rewrite": 256, "summarize": 300,
              "judge": 256, "evidence_support": 512}.get(name, model.maxOutputTokens)
    if output is not None and model.maxOutputTokens is not None:
        output = min(output, model.maxOutputTokens)
    budget = max(1, (model.contextWindow or 8192) - (output or 1024))
    return PurposeConfig(name, model.provider, model.model, model.runtime.timeoutMs / 1000,
                         budget, output, cache=name in {"generate", "rewrite"},
                         retries=model.runtime.retries, json_output=name == "evidence_support")
