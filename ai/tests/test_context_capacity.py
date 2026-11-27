"""验证模型输入预算、段落去重和上下文整理事件的边界。"""

import json
from time import monotonic
from types import SimpleNamespace

import pytest

from ai.app.llm.context_input import context_progress
from ai.app.llm.purposes import get_purpose
from ai.app.llm.tokens import input_tokens
from ai.app.models.context import model_execution
from ai.app.models.schema import ModelConfig, ModelExecution
from ai.app.rag import evidence_support as support
from ai.app.retrieval.retriever import Candidate


def execution(window=8192):
    """向 `test_uses_selected_window_and_output_reserve` 提供预设数据。返回 `ModelExecution` 的结果。

    输入为 `window`。
    上游为 `test_oversized_review_deduplicates_before_admission_and_keeps_conditions`，下游调用 `ai.app.models.schema.ModelConfig`。
    """
    model = ModelConfig(type="chat", protocol="openai_chat", model="unit",
                        contextWindow=window, maxOutputTokens=512)
    return ModelExecution(bindings={"agent": model, "generate": model, "evidence_support": model})


def reply(value):
    """向 `decomposition` 提供预设数据。返回 `SimpleNamespace` 的结果。输入为 `value`。上游为 `verdict`、`caller`。"""
    return SimpleNamespace(content=json.dumps(value, ensure_ascii=False), total_tokens=3,
                           finish_reason="stop", degraded=False)


QUESTION = "截至2030年5月31日，柏川项目材料缺失时应在几个工作日内补齐？请附引用。"
FACT = "柏川项目自2030年6月1日起材料缺失时应在5个工作日内补齐，生效前应在3个工作日内补齐。"


def decomposition():
    """向 `caller` 提供预设数据。返回 `reply` 的结果。"""
    return reply({"resolved": True, "execution_requirements": ["请附引用"],
        "standalone_question": QUESTION, "elements": [{"id": "E1", "text": QUESTION}]})


def verdict(quote=FACT, accepted=True):
    """向 `caller` 提供预设数据。返回 `reply` 的结果。输入为 `quote`、`accepted`。"""
    return reply({"alignment": "所问日期在生效日之前，使用旧规则。", "question_fully_covered": True,
        "elements": [{"supported": accepted, "citations":
            [{"source_index": 1, "quote": quote}] if accepted else []}]})


@pytest.mark.parametrize("window", [8192, 65536])
def test_uses_selected_window_and_output_reserve(window):
    """验证 `get_purpose('agent').budget_tokens` 等于 `window - 512`，且 `get_purpose('evidence_support').budget_tokens` 等于 `window - 512`。"""
    with model_execution(execution(window)):
        assert get_purpose("agent").budget_tokens == window - 512
        assert get_purpose("evidence_support").budget_tokens == window - 512
    assert input_tokens([{"role": "user", "content": "问"}], response_format={"schema": "规则" * 100}) > 100


def test_oversized_review_deduplicates_before_admission_and_keeps_conditions():
    """验证 `result.state` 等于 `'accepted'`，且 `result.required_source_ids` 等于 `[11]`。"""
    sources = [Candidate(11, 1, 1, 0, FACT + "\n\n" + ("例行归档须保留原件以备核查。\n\n" * 700))]
    calls, progress = [], []

    def caller(system, user, deadline):
        """返回 `decomposition` 的结果 或 `verdict` 的结果。输入为 `system`、`user`、`deadline`。"""
        calls.append(user)
        if len(calls) == 1:
            return decomposition()
        assert QUESTION in user and FACT in user
        assert user.count("例行归档须保留原件以备核查。") == 1
        return verdict()

    with model_execution(execution()), context_progress(progress.append):
        result = support.review(QUESTION, [], sources, monotonic() + 10, caller=caller)
    assert result.state == "accepted", result.reason_code
    assert result.required_source_ids == [11]
    assert result.required_spans[0]["content"] == FACT
    assert len(calls) == 2
    assert progress[0]["inputTokensBefore"] > progress[0]["inputBudget"]
    assert progress[-1]["inputTokensAfter"] <= progress[-1]["inputBudget"]
    assert progress[-1]["method"] == "deduplicate"


def test_batch_extracts_are_rechecked_together_not_admitted_individually():
    """验证 `2 <= len(extracts) <= 4` 成立，且 `result.state` 等于 `'rejected'`。"""
    contrary = "柏川项目材料缺失时不得补交，必须重新提交申请。"
    # Different short paragraphs: they cannot be removed by exact deduplication.
    noise = "\n\n".join(f"第{i}项归档记录供工作人员按编号核对附件并妥善保存原件。" for i in range(170))
    sources = [Candidate(11, 1, 1, 0, FACT + "\n\n" + noise),
               Candidate(12, 2, 1, 0, contrary + "\n\n" + noise)]
    calls, extracts, progress = [], [], []

    def caller(system, user, deadline):
        """返回 `decomposition` 的结果 或 `reply` 的结果 或 `verdict` 的结果。输入为 `system`、`user`、`deadline`。"""
        calls.append(system)
        if system == support.DECOMPOSITION_SYSTEM:
            return decomposition()
        if system == support.EXTRACT_SYSTEM:
            assert user.startswith("本批待摘录原文与知识问题如下：")
            payload = json.JSONDecoder().raw_decode(user[user.index("{"):])[0]
            assert next(iter(payload)) == "sources"
            quotes = []
            for source in payload["sources"]:
                for fact in (FACT, contrary):
                    if fact in source["content"]:
                        quotes.append({"source_index": source["source_index"], "quote": fact})
            extracts.append(quotes)
            return reply({"complete": True, "quotes": quotes})
        assert FACT in user and contrary in user and QUESTION in user
        return verdict(accepted=False)

    with model_execution(execution()), context_progress(progress.append):
        result = support.review(QUESTION, [], sources, monotonic() + 10, caller=caller)
    assert 2 <= len(extracts) <= 4
    original_batches = {index for index, quotes in enumerate(extracts)
                        if any(item["quote"] == FACT for item in quotes)}
    contrary_batches = {index for index, quotes in enumerate(extracts)
                        if any(item["quote"] == contrary for item in quotes)}
    assert original_batches and contrary_batches
    assert original_batches.isdisjoint(contrary_batches)
    assert result.state == "rejected", result.reason_code
    assert result.required_spans == []
    assert calls[-1] == support.SUPPORT_SYSTEM
    assert progress[-1]["method"] == "batch_extract"
    assert progress[-1]["batchCount"] == len(extracts)


def test_new_long_document_combines_verbatim_evidence_from_separate_batches():
    """两批长文中的独立问答要点均被复核，获准引用仍来自两份原始资料。"""
    question = "雾岬站的盐雾样本交接时，接收人应核对什么，以及归档由谁负责？"
    facts = (
        "雾岬站交接盐雾样本时，接收人应核对封签编号和温度读数。",
        "雾岬站盐雾样本完成交接后，由巡检组负责归档。",
    )
    noise = "\n\n".join(
        f"第{i}处库位的备品须填写领用记录，领取后将包装材料交还管理处。"
        for i in range(170)
    )
    sources = [
        Candidate(index, index, 1, 0, fact + "\n\n" + noise)
        for index, fact in enumerate(facts, 21)
    ]
    extracts, progress = [], []

    def caller(system, user, deadline):
        if system == support.DECOMPOSITION_SYSTEM:
            return reply({
                "resolved": True, "execution_requirements": [], "standalone_question": question,
                "elements": [
                    {"id": "E1", "text": "雾岬站盐雾样本交接时接收人应核对的项目"},
                    {"id": "E2", "text": "雾岬站盐雾样本归档负责人"},
                ],
            })
        if system == support.EXTRACT_SYSTEM:
            payload = json.JSONDecoder().raw_decode(user[user.index("{"):])[0]
            quotes = [{"source_index": source["source_index"], "quote": fact}
                      for source in payload["sources"]
                      for fact in facts if fact in source["content"]]
            extracts.append({quote["source_index"] for quote in quotes})
            return reply({"complete": True, "quotes": quotes})

        assert system == support.SUPPORT_SYSTEM
        assert all(fact in user for fact in facts)
        return reply({
            "alignment": "两处条文分别说明核对项目与归档负责人。",
            "question_fully_covered": True,
            "elements": [
                {"supported": True, "citations": [{"source_index": index, "quote": fact}]}
                for index, fact in enumerate(facts, 1)
            ],
        })

    with model_execution(execution()), context_progress(progress.append):
        result = support.review(question, [], sources, monotonic() + 10, caller=caller)

    assert {1} in extracts and {2} in extracts and {1, 2} not in extracts
    assert progress[-1]["method"] == "batch_extract"
    assert (result.state, result.reason_code) == ("accepted", "all_elements_supported")
    assert result.required_source_ids == [21, 22]
    assert result.required_spans == [
        {"source_id": index, "content": fact} for index, fact in enumerate(facts, 21)
    ]


def test_required_question_does_not_fit_and_never_calls_model():
    """验证 `(result.state, result.reason_code)` 等于 `('unavailable', 'support_context_capacity_exceeded')`。"""
    with model_execution(execution()):
        result = support.review("完整条件" * 3000, [], [Candidate(1, 1, 1, 0, FACT)],
            monotonic() + 10, caller=lambda *args: pytest.fail("must not call an oversized prompt"))
    assert (result.state, result.reason_code) == ("unavailable", "support_context_capacity_exceeded")


def test_batch_limit_is_planned_before_extract_calls():
    """验证 `result.reason_code` 等于 `'support_context_batch_limit_exceeded'`，且 `count[0]` 等于 `1`。"""
    count = [0]

    def caller(*args):
        """返回 `decomposition` 的结果。输入为 `args`。"""
        count[0] += 1
        assert count[0] == 1
        return decomposition()

    with model_execution(execution()):
        result = support.review(QUESTION, [], [Candidate(1, 1, 1, 0, "不重复正文" * 10000)],
            monotonic() + 10, caller=caller)
    assert result.reason_code == "support_context_batch_limit_exceeded"
    assert count[0] == 1
