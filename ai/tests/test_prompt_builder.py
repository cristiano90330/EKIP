"""验证问答提示里的证据、历史截断和来源标签。"""

from ai.app.llm.tokens import count_tokens
from ai.app.query.schema import ChatMessage
from ai.app.rag.prompt_builder import _history_block, build_prompt
from ai.app.rag.schema import ContextItem

def _msg(role: str, content: str) -> ChatMessage:
    """向 `test_history_rendered_before_knowledge_contexts` 提供预设数据。返回 `ChatMessage` 的结果。

    输入为 `role: str`、`content: str`。
    上游为 `test_history_budget_drops_oldest_messages`。
    """
    return ChatMessage(role=role, content=content)


def test_empty_history_omits_history_section():
    """验证 `build_prompt('问题', contexts, []) == build_prompt('问题', contexts, None) == prompt` 成立，且 `'【对话历史】'` 不属于 `prompt`。"""
    contexts = [ContextItem(content="内容A")]
    prompt = build_prompt("问题", contexts)
    assert build_prompt("问题", contexts, []) == build_prompt("问题", contexts, None) == prompt
    assert "【对话历史】" not in prompt


def test_history_rendered_before_knowledge_contexts():
    """有历史时插入【对话历史】段，且排在【知识库内容】之前（按时间顺序给模型看）。"""
    prompt = build_prompt("它的检索速度怎么样？", [ContextItem(content="检索延迟 70ms")], [
        _msg("user", "Qwen3-Embedding-4B 的维度是多少？"),
        _msg("assistant", "2560 维"),
    ])

    assert "【对话历史】" in prompt
    assert "user: Qwen3-Embedding-4B 的维度是多少？" in prompt
    assert "assistant: 2560 维" in prompt
    assert prompt.index("【对话历史】") < prompt.index("【知识库内容】")
    # 历史段之外的部分不受影响
    assert "【用户问题】" in prompt and "它的检索速度怎么样？" in prompt


def test_source_name_identifies_a_sentence_excerpt_after_its_heading_is_removed():
    """验证 `'来源：星河项目报销规则-演示.txt，text'` 属于 `prompt`，且 `'[资料1]' in prompt and '3 个工作日' in prompt` 成立。"""
    prompt = build_prompt(
        "根据星河项目制度说明，材料应在几个工作日内补齐？",
        [ContextItem(content="材料缺失时，申请人应在 3 个工作日内补齐。",
                     sourceName="星河项目报销规则-演示.txt", sourceType="text")])

    assert "来源：星河项目报销规则-演示.txt，text" in prompt
    assert "[资料1]" in prompt and "3 个工作日" in prompt


def test_history_budget_drops_oldest_messages():
    """超额度丢「更早」的消息——最近一轮必须在，很早的那条必须被丢掉。"""
    old = _msg("assistant", "很早的回答" * 30)
    new = _msg("user", "新问题")

    block = _history_block([old, new], budget_tokens=30)

    assert "user: 新问题" in block
    assert "很早的回答" not in block
    assert count_tokens(block) <= 30


def test_newest_message_truncated_when_alone_over_budget():
    """最新一条自己就超额度 → 截断它（宁可给半句，也不要一条历史都没有），额度仍是硬的。"""
    block = _history_block([_msg("assistant", "很长的回答" * 50)], budget_tokens=40)

    assert block != ""
    assert block.startswith("assistant: ")
    assert count_tokens(block) <= 40


def test_zero_budget_drops_all_history():
    """额度为 0 = 不装历史（而不是装一条超额的）。"""
    assert _history_block([_msg("user", "问题")], budget_tokens=0) == ""


def test_blank_content_messages_are_skipped():
    """空内容的消息不产生 "user: " 这样的空行。"""
    block = _history_block([_msg("user", "  "), _msg("assistant", "有效回答")], budget_tokens=100)

    assert block == "assistant: 有效回答"
