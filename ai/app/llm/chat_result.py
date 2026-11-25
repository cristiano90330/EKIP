"""统一对话模型的正文、工具调用、结束原因与用量结构。"""

from dataclasses import dataclass


@dataclass
class ChatResult:
    """LLM 响应 + token 用量（部分供应商不返回 usage 时按 0 计）。"""
    content: str | None
    prompt_tokens: int
    completion_tokens: int
    # 首个生成 token 的候选分布 {token: logprob}（仅在显式要求 logprobs 时有值）。
    # 用途：把分类式重排模型（Qwen3-Reranker 只答 yes/no）的输出变成**连续分**
    # score = P(yes)/(P(yes)+P(no))（模型官方打分方式），而不是只有 0/1 两档。
    top_logprobs: dict[str, float] | None = None
    # 模型发起的工具调用，保持 OpenAI 兼容结构。
    # [{"id","type","function":{"name","arguments"}}]；没有工具调用时为 None。
    # 无工具调用时为 None，供上层 Agent 解析。
    tool_calls: list[dict] | None = None
    finish_reason: str | None = None

    @property
    def total_tokens(self) -> int:
        """按输入和输出用量计算本次模型调用的总 Token，供业务结果与阶段追踪读取。"""
        return self.prompt_tokens + self.completion_tokens
