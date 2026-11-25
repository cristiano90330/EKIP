"""验证从问题提取的范围术语及其正文匹配条件。"""

import pytest

from ai.app.rag import evidence_support as support


@pytest.mark.parametrize(("question", "expected"), [
    ("内部代号KX-603所指项目发生设备遗失后，应在多少小时内报告？",
     ["KX-603"]),
    ("按现行层级规则，栖岭项目每人每天的市内交通费上限是多少元？",
     ["栖岭项目"]),
    ("市内交通费上限是多少元？", []),
    ("杭州市的交通费上限是多少元？", ["杭州市"]),
    ("柏川项目的采购合同由谁审核？", ["柏川项目"]),
    ("查阅柏川项目资料，告诉我唯一核验代号并附原文引用。", ["柏川项目"]),
    ("使用 read_document 的 page 模式读取柏川项目资料，返回唯一核验代号。", ["柏川项目"]),
    ("请完整查阅柏川项目资料，给出最后一节的核验代号。", ["柏川项目"]),
    ("请逐字引用柏川项目资料中的核验代号。", ["柏川项目"]),
    ("阅读项目的核验代号是什么？", ["阅读项目"]),
])
def test_requested_scope_terms_keep_only_named_scope_entities(question, expected):
    """验证 `support.requested_scope_terms(question)` 等于 `expected`。"""
    assert support.requested_scope_terms(question) == expected


def _decomposition(question: str, element: str) -> dict:
    """向 `test_interval_question_does_not_treat_recurrence_once_as_a_separate_answer` 提供预设数据。返回 包含 `resolved`、`execution_requirements`、`standalone_question`、`elements` 的字典。

    输入为 `question: str`、`element: str`。
    上游为 `test_interval_question_still_requires_a_separately_requested_count`。
    """
    return {
        "resolved": True,
        "execution_requirements": ["请保留数字和单位"],
        "standalone_question": question,
        "elements": [{"id": "E1", "text": element}],
    }


@pytest.mark.parametrize("once", ["一次", "1次"])
def test_interval_question_does_not_treat_recurrence_once_as_a_separate_answer(
        once: str):
    """验证 `error` 为 `None`，且 `parsed['elements']` 等于 `value['elements']`。"""
    question = f"望舒项目的备份介质需要每隔多少天核验{once}？请保留数字和单位。"
    value = _decomposition(question, "望舒项目备份介质核验的周期（天）")

    parsed, error = support.validate_decomposition(value, question=question)

    assert error is None
    assert parsed["elements"] == value["elements"]


def test_interval_question_still_requires_a_separately_requested_count():
    """验证 `parsed` 为 `None`，且 `error` 等于 `'decomposition_question_coverage_incomplete'`。"""
    question = "望舒项目的备份介质需要每隔多少天核验两次？"
    value = _decomposition(question, "望舒项目备份介质核验的周期（天）")

    parsed, error = support.validate_decomposition(value, question=question)

    assert parsed is None
    assert error == "decomposition_question_coverage_incomplete"
