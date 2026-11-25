# -*- coding: utf-8 -*-
"""验证 Agent 决策、流式事件和摘要网关的结构与失败映射。"""

import json

from fastapi.testclient import TestClient

from ai.app import main as main_module
from ai.app.llm.guard import DegradedResult
from ai.app.llm.chat_result import ChatResult
from ai.tests.conftest import AUTH_HEADERS

# 业务路由自 2026-09-29 起要求 X-Internal-Token（ai/app/security.py）；
# 本文件测的是 Agent 路由本身，故统一带上令牌，鉴权自身由 test_internal_auth.py 覆盖。
client = TestClient(main_module.app, headers=AUTH_HEADERS)


def post_agent_request(path, *, json):
    # 路由契约使用 Java 授权的显式快照；只模拟 gate，不依赖旧 dotenv 模型选择。
    """向 `test_decide_returns_tool_calls_with_usage` 提供预设数据。返回 `client.post` 的结果。

    输入为 `path`、`json`。
    上游为 `test_messages_reach_openai_shape`、`test_empty_tools_become_none`。
    """
    model = {"type":"chat", "protocol":"openai_chat", "provider":"generic",
             "model":"contract-agent", "baseUrl":"https://model.invalid/v1",
             "capabilities":{"tools":True,"streaming":True}}
    return client.post(path, json={"modelExecution":{"bindings":{"agent":model,"summarize":model}}, **json})


# 输入为 `monkeypatch`、`result`。
# 上游为 `test_messages_reach_openai_shape`、`test_empty_tools_become_none`。
def _patch_gate(monkeypatch, result):
    """替换 gate.call_messages，返回它收到的入参。"""
    seen = {}

    def fake_call_messages(purpose, messages, tools=None, timeout_s=None):
        """返回 `result`。输入为 `purpose`、`messages`、`tools`、`timeout_s`。"""
        seen["purpose"] = purpose
        seen["messages"] = messages
        seen["tools"] = tools
        seen["timeout_s"] = timeout_s
        return result

    monkeypatch.setattr("ai.app.agent.router.gate.call_messages", fake_call_messages)
    return seen


def test_decide_returns_tool_calls_with_usage(monkeypatch):
    """验证 `resp.status_code` 等于 `200`，且 `body['tool_calls']` 等于 `calls`。"""
    calls = [{"id": "call_1", "type": "function",
              "function": {"name": "knowledge_search", "arguments": "{\"query\":\"年假\"}"}}]
    seen = _patch_gate(monkeypatch, ChatResult(content=None, prompt_tokens=1300,
                                               completion_tokens=24, tool_calls=calls))
    resp = post_agent_request("/agent/decide", json={
        "messages": [
            {"role": "system", "content": "你是知识库助手"},
            {"role": "user", "content": "年假有几天？"},
        ],
        "tools": [{"type": "function", "function": {"name": "knowledge_search"}}],
    })
    assert resp.status_code == 200
    body = resp.json()
    assert body["tool_calls"] == calls
    assert body["prompt_tokens"] == 1300
    assert body["completion_tokens"] == 24
    assert body["degraded"] is False
    # purpose 由服务端钉死为 agent：调用方无权选择治理档
    assert seen["purpose"] == "agent"


def test_messages_reach_openai_shape(monkeypatch):
    """归一化口径必须与 Java 直连路径逐字段一致，否则「切治理层」顺带改变了发给供应商的报文。

    三条硬要求：content 为 null → 空串（不是缺键）、tool_calls 只在非空时带、
    role=tool 才带 tool_call_id。
    """
    seen = _patch_gate(monkeypatch, ChatResult(content="答案", prompt_tokens=1, completion_tokens=1))
    post_agent_request("/agent/decide", json={
        "messages": [
            {"role": "system", "content": "sys"},
            {"role": "user", "content": "q"},
            {"role": "assistant", "content": None,
             "tool_calls": [{"id": "c1", "type": "function",
                             "function": {"name": "knowledge_search", "arguments": "{}"}}]},
            {"role": "tool", "content": "tool output", "tool_call_id": "c1"},
        ],
        "tools": [{"type": "function"}],
    })
    assert seen["messages"] == [
        {"role": "system", "content": "sys"},
        {"role": "user", "content": "q"},
        {"role": "assistant", "content": "",
         "tool_calls": [{"id": "c1", "type": "function",
                         "function": {"name": "knowledge_search", "arguments": "{}"}}]},
        {"role": "tool", "content": "tool output", "tool_call_id": "c1"},
    ]
    assert seen["tools"] == [{"type": "function"}]


def test_empty_tools_become_none(monkeypatch):
    """空 tools 列表 → 传 None：契约里「不带该字段」与「带空数组」不是一回事。"""
    seen = _patch_gate(monkeypatch, ChatResult(content="a", prompt_tokens=1, completion_tokens=1))
    post_agent_request("/agent/decide", json={"messages": [{"role": "user", "content": "q"}]})
    assert seen["tools"] is None


def test_degraded_returns_200_with_reason(monkeypatch):
    """治理层失败的出口是 200 + degraded=true，**不是 500**：Java 据此给兜底文案。"""
    _patch_gate(monkeypatch, DegradedResult(None, 0, 0, reason="circuit open（主 provider 熔断，降级也失败）"))
    resp = post_agent_request("/agent/decide", json={"messages": [{"role": "user", "content": "q"}]})
    assert resp.status_code == 200
    body = resp.json()
    assert body["degraded"] is True
    assert "circuit open" in body["degrade_reason"]
    assert body["content"] is None
    assert body["tool_calls"] == []


def test_timeout_override_passed_through(monkeypatch):
    """验证 `seen['timeout_s']` 等于 `45.0`。"""
    seen = _patch_gate(monkeypatch, ChatResult(content="a", prompt_tokens=1, completion_tokens=1))
    post_agent_request("/agent/decide", json={
        "messages": [{"role": "user", "content": "q"}], "timeout_s": 45.0})
    assert seen["timeout_s"] == 45.0


def test_missing_messages_is_422():
    """请求体校验仍由 FastAPI 负责：缺 messages → 422，不该被治理层的「永不抛」吞成 200。"""
    resp = post_agent_request("/agent/decide", json={})
    assert resp.status_code == 422


def test_arguments_stay_a_json_string_end_to_end(monkeypatch):
    """arguments 是一段 **JSON 文本**（不是对象），全程不被二次解析。

    若哪天有人在这里 `json.loads(arguments)`，Java 侧 `ToolExecutor` 收到的就不再是字符串，
    工具参数解析会全线失败——这条把形状钉在 HTTP 出口上。
    """
    _patch_gate(monkeypatch, ChatResult(
        content=None, prompt_tokens=2, completion_tokens=3,
        tool_calls=[{"id": "c", "type": "function",
                     "function": {"name": "t", "arguments": '{"k":"v"}'}}]))
    resp = post_agent_request("/agent/decide", json={"messages": [{"role": "user", "content": "q"}]})
    arguments = json.loads(resp.text)["tool_calls"][0]["function"]["arguments"]
    assert isinstance(arguments, str)
    assert json.loads(arguments) == {"k": "v"}


# ---------- 3.1 P1-8：/agent/summarize ----------

# 输入为 `monkeypatch`、`result`。
# 上游为 `test_summarize_degraded_is_200_with_reason`、`test_summarize_exposes_finish_reason`。
def _patch_summarize_gate(monkeypatch, result):
    """摘要走的是**已存在**的 gate.call()——本端点没有新增治理代码，这条本身就是断言。"""
    seen = {}

    def fake_call(purpose, system_prompt, user_prompt, timeout_s=None, logprobs=False):
        """返回 `result`。输入为 `purpose`、`system_prompt`、`user_prompt`、`timeout_s`、`logprobs`。"""
        seen["purpose"] = purpose
        seen["system_prompt"] = system_prompt
        seen["user_prompt"] = user_prompt
        return result

    monkeypatch.setattr("ai.app.agent.router.gate.call", fake_call)
    return seen


def test_summarize_uses_two_text_gate_path(monkeypatch):
    """验证 `resp.status_code` 等于 `200`，且 `body['content']` 等于 `'要点：用户问了差旅标准'`。"""
    seen = _patch_summarize_gate(
        monkeypatch, ChatResult(content="要点：用户问了差旅标准", prompt_tokens=900, completion_tokens=30))
    resp = post_agent_request("/agent/summarize", json={
        "system_prompt": "你是会话摘要助手……",
        "user_prompt": "[已有摘要]\n旧要点\n\nuser: 差旅标准\nassistant: 上限 500\n",
    })
    assert resp.status_code == 200
    body = resp.json()
    assert body["content"] == "要点：用户问了差旅标准"
    assert (body["prompt_tokens"], body["completion_tokens"]) == (900, 30)
    assert body["degraded"] is False
    # purpose 由服务端钉死为 summarize，两段文本原样透传
    assert seen["purpose"] == "summarize"
    assert seen["system_prompt"].startswith("你是会话摘要助手")
    assert "已有摘要" in seen["user_prompt"]


def test_summarize_degraded_is_200_with_reason(monkeypatch):
    """Java 侧据此抛 AgentException → Coordinator 写降级归档并推进游标（不再原地重试同一批）。"""
    _patch_summarize_gate(monkeypatch, DegradedResult(None, 0, 0, reason="circuit open（主 provider 熔断，降级也失败）"))
    resp = post_agent_request("/agent/summarize", json={"system_prompt": "s", "user_prompt": "u"})
    assert resp.status_code == 200
    body = resp.json()
    assert body["degraded"] is True
    assert "circuit open" in body["degrade_reason"]
    assert body["content"] is None


def test_summarize_missing_fields_are_422():
    """通过 `post_agent_request` 核对本用例构造的状态。"""
    assert post_agent_request("/agent/summarize", json={"system_prompt": "只有半截"}).status_code == 422


def test_summarize_exposes_finish_reason(monkeypatch):
    """摘要撞输出上限时内容仍然非空、读起来也像摘要，Java 只能靠这个字段识别截断。

    缺它的时候，被截断的摘要会被当成完整摘要落库，而它此后就是那批历史的唯一记忆。
    """
    _patch_summarize_gate(
        monkeypatch, ChatResult(content="要点：用户问差旅", prompt_tokens=900,
                                completion_tokens=300, finish_reason="length"))
    body = post_agent_request("/agent/summarize", json={"system_prompt": "s", "user_prompt": "u"}).json()
    assert body["finish_reason"] == "length"
    assert body["degraded"] is False, "截断不是治理层降级，两者由不同的判据处理"


def test_summarize_finish_reason_defaults_to_empty(monkeypatch):
    """供应商不返回 finish_reason 时给空串，Java 侧按「无法判定截断」处理，不误判为正常。"""
    _patch_summarize_gate(monkeypatch, ChatResult(content="要点", prompt_tokens=10, completion_tokens=2))
    body = post_agent_request("/agent/summarize", json={"system_prompt": "s", "user_prompt": "u"}).json()
    assert body["finish_reason"] == ""


# ---------- finish_reason 透传与流式决策（Java ReAct 循环依赖的两个契约） ----------

def test_decide_exposes_finish_reason(monkeypatch):
    """Java 的 Analyze 阶段靠 finish_reason 区分「自然停止 / 输出截断 / 内容过滤」。

    缺这个字段时 Java 只能退化为「无工具 + 有正文 ⇒ 最终答案」，
    被截断的答案会被当成完整答案交付 —— 所以字段名与取值必须在契约测试里钉住。
    """
    _patch_gate(monkeypatch, ChatResult(content="前半段", prompt_tokens=10, completion_tokens=3,
                                        finish_reason="length"))
    resp = post_agent_request("/agent/decide", json={"messages": [{"role": "user", "content": "q"}]})
    assert resp.status_code == 200
    assert resp.json()["finish_reason"] == "length"


def test_decide_stream_emits_delta_then_final(monkeypatch):
    """流式决策的帧序列：若干 delta，最后恰好一个 final（含 tool_calls 与 finish_reason）。

    这里刻意让模型「先说一句话再发工具调用」：那正是 Java 侧要把 delta 认定为「引子」
    而不是答案的场景，帧顺序错了前端就会把引子留在答案区。
    """
    calls = [{"id": "call_1", "type": "function",
              "function": {"name": "knowledge_search", "arguments": "{\"query\":\"年假\"}"}}]

    def fake_stream(purpose, messages, tools=None, timeout_s=None):
        """产出供调用方迭代的结果。输入为 `purpose`、`messages`、`tools`、`timeout_s`。下游调用 `ai.app.llm.chat_result.ChatResult`。"""
        assert purpose == "agent"
        yield "我先查一下"
        yield ChatResult(content="我先查一下", prompt_tokens=12, completion_tokens=4,
                         tool_calls=calls, finish_reason="tool_calls")

    monkeypatch.setattr("ai.app.agent.router.gate.call_messages_stream", fake_stream)
    resp = post_agent_request("/agent/decide/stream", json={
        "messages": [{"role": "user", "content": "年假有几天？"}],
        "tools": [{"type": "function", "function": {"name": "knowledge_search"}}],
    })
    assert resp.status_code == 200

    frames = [frame for frame in resp.text.split("\n\n") if frame.strip()]
    events = [frame.splitlines()[0].removeprefix("event: ") for frame in frames]
    assert events == ["delta", "final"], events

    final = json.loads(frames[-1].splitlines()[1].removeprefix("data: "))
    assert final["tool_calls"] == calls
    assert final["finish_reason"] == "tool_calls"
    assert final["degraded"] is False


def test_streaming_tool_call_fragments_are_merged_by_index():
    """验证 `merged[0]['id']` 等于 `'call_1'`，且 `merged[0]['function']['name']` 等于 `'knowledge_search'`。"""
    from ai.app.models.runtime import merge_tool_fragment, normalize_tool_call

    parts: dict[int, dict] = {}
    merge_tool_fragment(parts, {"index": 0, "id": "call_1",
                                "function": {"name": "knowledge_search", "arguments": '{"query"'}})
    merge_tool_fragment(parts, {"index": 0, "function": {"arguments": ':"年假"}'}})
    merge_tool_fragment(parts, {"index": 1, "id": "call_2",
                                "function": {"name": "knowledge_ask", "arguments": '{"question":"x"}'}})

    merged = [normalize_tool_call(parts[index], index) for index in sorted(parts)]
    assert merged[0]["id"] == "call_1"
    assert merged[0]["function"]["name"] == "knowledge_search"
    assert merged[0]["function"]["arguments"] == '{"query":"年假"}'
    assert merged[1]["function"]["arguments"] == '{"question":"x"}'
