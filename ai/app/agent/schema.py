"""定义 Agent 对话消息、工具调用、决策和摘要的跨服务数据结构。"""

from ai.app.models.schema import ModelExecution
# -*- coding: utf-8 -*-
"""定义 Agent 决策和摘要端点的数据契约，字段采用 OpenAI 风格的 snake_case。"""
from typing import Any, Optional

from pydantic import BaseModel


class ToolCallFunction(BaseModel):
    name: Optional[str] = None

    # OpenAI 的约定：arguments 是**一段 JSON 文本**，不是对象。
    arguments: Optional[str] = None


class ToolCall(BaseModel):
    id: Optional[str] = None

    type: str = "function"

    function: Optional[ToolCallFunction] = None


class AgentChatMessage(BaseModel):
    """一条对话消息。role ∈ system / user / assistant / tool。

    content 可为 null（assistant 只发工具调用时就是 null）；缺省也按 null 处理，
    发给供应商前统一归一成空字符串（与 Java 直连路径同一口径）。
    """

    role: str

    content: Optional[str] = None

    tool_calls: Optional[list[ToolCall]] = None

    # role=tool 时必填：对应上一轮某个 tool_calls[].id
    tool_call_id: Optional[str] = None


class AgentDecideRequest(BaseModel):
    modelExecution: ModelExecution | None = None

    """一次 Agent 决策的输入：完整消息列表 + 工具 Schema。

    Java 在授权后提供 modelExecution；模型、凭证和运行策略来自该不可变快照。
    Python 仅在实际调用用途时要求对应绑定，不读取旧 dotenv 选型。
    """

    messages: list[AgentChatMessage]

    # OpenAI tools schema，原样透传给供应商；空列表 = 本轮不给工具
    tools: list[dict[str, Any]] = []

    # 本次调用的超时覆盖（None = 用 agent 档默认）。用途与 gate.call 的同名参数一致：
    # 当前调用可覆盖用途默认超时，其他治理选项保持不变。
    timeout_s: Optional[float] = None


class AgentDecideResponse(BaseModel):
    """一次决策的输出：文本答案 或 一批工具调用，外加治理层状态。

    degraded=true 时 content/tool_calls 均为空——**这是 200 而不是 500**：
    预算超限 / 熔断打开 / 降级链走完仍失败都属于「服务知道自己在降级」，
    Java 侧据此给用户一句可读的兜底文案，而不是弹一个通用错误码。
    """

    content: Optional[str] = None

    tool_calls: list[dict[str, Any]] = []

    prompt_tokens: int = 0

    completion_tokens: int = 0

    degraded: bool = False

    degrade_reason: str = ""

    # 实际服务请求的 provider 名称。
    provider_used: str = ""

    # 供应商的 finish_reason 原文（stop / length / content_filter / tool_calls …）。
    # Java 侧据此区分「正常停止」「输出上限截断」「内容过滤」三条终止路径；
    # 拿不到时留空串，Java 退化为「无工具 + 正文非空 ⇒ 最终答案」，不产生行为回退。
    finish_reason: str = ""


# ---------- 会话摘要 ----------

class SummarizeRequest(BaseModel):
    modelExecution: ModelExecution | None = None

    """摘要的两个输入段（与 `gate.call(purpose, system, user)` 同形，不另造口径）。

    与 /agent/decide 的区别只有这一层：摘要是纯文本压缩，没有 messages/tools，
    所以 Python 侧复用的是**已存在**的 `gate.call()`，不新增治理代码。
    """

    system_prompt: str

    user_prompt: str


class SummarizeResponse(BaseModel):
    """摘要结果 + 治理层状态。语义与 AgentDecideResponse 里的同名字段一致。"""

    content: Optional[str] = None

    prompt_tokens: int = 0

    completion_tokens: int = 0

    degraded: bool = False

    degrade_reason: str = ""

    provider_used: str = ""

    # 供应商 finish_reason（stop / length …）。Java 侧据此判定「摘要是否被输出上限截断」：
    # 截断摘要读起来像正常摘要，但后半段永久丢失，而它此后是该批历史的唯一记忆，
    # 因此 Java 把 length / max_tokens / max_output_tokens 视为不可用并改走降级归档。
    finish_reason: str = ""
