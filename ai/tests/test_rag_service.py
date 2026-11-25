"""验证生成服务组织提示、解析结构化结果并转换降级状态。"""

import json
from types import SimpleNamespace

import pytest

from ai.app.query.schema import ChatMessage
from ai.app.rag.prompt_builder import build_prompt
from ai.app.rag.rag_service import rag_service
from ai.app.rag.schema import ContextItem


class _Ok:
    """正常响应：非降级，主 provider 服务。"""

    content = '{"status":"success","answer":"正常答案"}'
    total_tokens = 30
    degraded = False
    provider = "deepseek"


class _Degraded:
    """降级响应：预算超限直接降级（guard.py:134 的 DegradedResult 语义）。"""

    total_tokens = 0
    degraded = True
    reason = "prompt 超预算直接降级"
    provider = "ollama"


def _ctx(content: str, **meta) -> ContextItem:
    """向 `test_generate_normal_returns_no_degradation` 提供预设数据。返回 `ContextItem` 的结果。

    输入为 `content: str`、`meta`。
    上游为 `test_generate_degraded_exposes_reason_and_provider`。
    """
    return ContextItem(content=content, **meta)


def test_generate_normal_returns_no_degradation(monkeypatch):
    """验证 `result.answer` 等于 `'正常答案'`，且 `result.token_cost` 等于 `30`。"""
    monkeypatch.setattr("ai.app.rag.rag_service.gate.call", lambda *a, **k: _Ok())

    result = rag_service.generate_with_usage(
        "问题", [_ctx("上下文")])

    assert result.answer == "正常答案"
    assert result.token_cost == 30
    assert result.outcome == "success"
    assert result.reason_code == "none"
    assert result.provider == "deepseek"


def test_generate_degraded_exposes_reason_and_provider(monkeypatch):
    """验证 `result.answer` 等于 `''`，且 `result.token_cost` 等于 `0`。"""
    monkeypatch.setattr("ai.app.rag.rag_service.gate.call", lambda *a, **k: _Degraded())

    result = rag_service.generate_with_usage(
        "问题", [_ctx("上下文")] * 10)

    # answer 保持空串（兜底文案归 Java 侧），降级信息单独透出
    assert result.answer == ""
    assert result.token_cost == 0
    assert result.outcome == "dependency_unavailable"
    assert result.degrade_reason == "prompt 超预算直接降级"
    assert result.provider == "ollama"


def test_build_prompt_adds_source_labels():
    """3.1 P1-10：来源元数据拼进 prompt——每条资料带 [资料N] 与来源标注行，LLM 可引用来源。"""
    prompt = build_prompt("问题", [
        _ctx("Qwen3 维度 2560", headingPath="3.1 > 3.1.2", pageNo=5, sourceType="paragraph"),
        _ctx("无元数据上下文"),
    ])

    assert "[资料1]" in prompt
    assert "来源：3.1 > 3.1.2，第 5 页，paragraph" in prompt
    assert "[资料2]" in prompt
    # 无元数据时不拼来源行（不产生空标注）
    assert "来源：\n" not in prompt
    # 回答要求里加了「引用时标注来源」，把「能引用」变成显式指令
    assert "引用知识库内容时，请标注来源" in prompt


def test_generate_sends_sourced_contexts_to_gate(monkeypatch):
    """3.1 P1-10：gate 收到的 prompt 里包含来源标注（跨进程契约的落地证据）。"""
    captured = {}

    def fake_call(_, system, prompt, **kwargs):
        """返回 `_Ok` 的结果。输入为 `_`、`system`、`prompt`、`kwargs`。"""
        captured["prompt"] = prompt
        assert kwargs["response_format"]["type"] == "json_schema"
        return _Ok()

    monkeypatch.setattr("ai.app.rag.rag_service.gate.call", fake_call)

    rag_service.generate_with_usage("问题", [
        _ctx("检索速度相关内容", headingPath="2.4", pageNo=12, sourceType="table"),
    ])

    assert "来源：2.4，第 12 页，table" in captured["prompt"]
    assert "检索速度相关内容" in captured["prompt"]


def test_generate_with_history_puts_conversation_into_prompt(monkeypatch):
    """3.1 P1-6（2026-09-19）：多轮生成把对话历史交给 LLM——此前 history 只给 rewrite 用，
    生成阶段「传了不读」，检索指标变好掩盖了答案质量的折扣。"""
    captured = {}

    def fake_call(_, system, prompt, **kwargs):
        """返回 `_Ok` 的结果。输入为 `_`、`system`、`prompt`、`kwargs`。"""
        captured["prompt"] = prompt
        return _Ok()

    monkeypatch.setattr("ai.app.rag.rag_service.gate.call", fake_call)

    rag_service.generate_with_usage(
        "它的检索速度怎么样？",
        [_ctx("检索延迟 70ms")],
        [
            ChatMessage(role="user", content="Qwen3-Embedding-4B 的维度是多少？"),
            ChatMessage(role="assistant", content="2560 维"),
        ],
    )

    prompt = captured["prompt"]
    assert "【对话历史】" in prompt
    assert "user: Qwen3-Embedding-4B 的维度是多少？" in prompt
    assert "assistant: 2560 维" in prompt
    assert prompt.index("【对话历史】") < prompt.index("【知识库内容】")


def test_generate_without_history_has_no_history_section(monkeypatch):
    """单轮（history 缺省）时 prompt 里没有【对话历史】段——与 P1-6 之前一致。"""
    captured = {}

    def fake_call(_, system, prompt, **kwargs):
        """返回 `_Ok` 的结果。输入为 `_`、`system`、`prompt`、`kwargs`。"""
        captured["prompt"] = prompt
        return _Ok()

    monkeypatch.setattr("ai.app.rag.rag_service.gate.call", fake_call)

    rag_service.generate_with_usage("问题", [_ctx("上下文")], [])

    assert "【对话历史】" not in captured["prompt"]


def test_generation_refusal_has_explicit_outcome(monkeypatch):
    """验证 `result.outcome` 等于 `'no_evidence'`，且 `result.reason_code` 等于 `'generation_no_evidence'`。"""
    def model(*args, **kwargs):
        """返回 `SimpleNamespace` 的结果。输入为 `args`、`kwargs`。"""
        return SimpleNamespace(content=json.dumps({
            "status": "no_evidence", "answer": "仅有星河资料，无法回答海岳问题。[资料1]"}),
            total_tokens=20, provider="ollama", finish_reason="stop")

    monkeypatch.setattr("ai.app.rag.rag_service.gate.call", model)
    result = rag_service.generate_with_usage("海岳项目几日补齐？", [
        _ctx("3 个工作日内补齐。", sourceName="星河项目报销规则.txt")])

    assert result.outcome == "no_evidence"
    assert result.reason_code == "generation_no_evidence"
    assert "[资料1]" not in result.answer
    assert result.token_cost == 20
    assert result.provider == "ollama"


@pytest.mark.parametrize("content,finish", [
    ("无法回答", "stop"),
    ('{"status":"other","answer":"x"}', "stop"),
    ('{"status":"success","answer":"  "}', "stop"),
    ('{"status":"success","answer":42}', "stop"),
    ('{"status":"success","answer":"x","extra":true}', "stop"),
    ('{"status":"success","answer":"x"}', "length"),
    ('{"status":"success","answer":"x"}', "content_filter"),
])
def test_invalid_generation_is_dependency_failure(monkeypatch, content, finish):
    """验证 `result.outcome` 等于 `'dependency_unavailable'`，且 `result.reason_code` 等于 `'generation_invalid_response'`。"""
    monkeypatch.setattr("ai.app.rag.rag_service.gate.call", lambda *a, **k: SimpleNamespace(
        content=content, total_tokens=7, provider="ollama", finish_reason=finish))

    result = rag_service.generate_with_usage("问题", [_ctx("内容")])

    assert result.outcome == "dependency_unavailable"
    assert result.reason_code == "generation_invalid_response"
    assert result.answer == ""
