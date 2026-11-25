"""验证引用支撑对限定条件和正文语境的判断。"""

from time import monotonic
import json
import pytest

from ai.app.rag import evidence_support as support
from ai.app.retrieval.retriever import Candidate
from ai.tests.test_evidence_support import decomposition, support_reply, supported


@pytest.mark.parametrize("prefix", [
    "本说明适用于栖岭项目，自2026年12月11日起生效。",
    "内部代号KX-603专指栖岭项目。本规则适用于栖岭项目。",
    "栖岭项目属于研发项目。",
    "本规定不适用于外包项目。",
])
def test_bound_fact_retains_adjacent_scope_date_and_alias_sentences(prefix):
    """验证 `support._bound_source_span(source, '附件须在9个工作日内补齐')` 等于 `prefix + fact`。"""
    fact = "附件须在9个工作日内补齐。"
    source = prefix + fact + "无关事项的额度为888元。"
    assert support._bound_source_span(source, "附件须在9个工作日内补齐") == prefix + fact


@pytest.mark.parametrize("prefix", [
    "最近登记的设备编号为ABC。",
    "本说明适用于栖岭项目。\n\n",
    "本说明适用于栖岭项目。\n  \n",
    "本说明适用于栖岭项目。无关物资需要清点。",
])
def test_unrelated_prefixes_and_other_paragraphs_are_not_promoted(prefix):
    """验证 `support._bound_source_span(prefix + fact + '无关尾部。', '附件须在9个工作日内补齐') == fact` 成立。"""
    fact = "附件须在9个工作日内补齐。"
    assert support._bound_source_span(prefix + fact + "无关尾部。", "附件须在9个工作日内补齐") == fact


def test_postprocessing_must_not_drop_the_effective_date_from_an_admitted_fact():
    """验证 `result.state` 等于 `'accepted'`，且 `support.support_fragments(result, [candidate])` 等于 `[{'source_id': 1, 'content': prefix + fact}]`。"""
    prefix = "本说明适用于栖岭项目，自2026年12月11日起生效。"
    fact = "报销附件须在9个工作日内补齐；"
    source = prefix + fact + "生效前沿用3个工作日的旧要求。"
    question = "栖岭项目在2027年1月的报销附件补齐期限是多少个工作日？"
    responses = iter([
        decomposition(question, standalone_question=question),
        support_reply(supported(quote="报销附件须在9个工作日内补齐")),
    ])
    candidate = Candidate(1, 101, 11, 0, source, source_name="栖岭附件说明.txt")
    result = support.review(question, [], [candidate], monotonic() + 5,
                            caller=lambda *_: next(responses))

    assert result.state == "accepted"
    assert support.support_fragments(result, [candidate]) == [{"source_id": 1, "content": prefix + fact}]
    stripped = Candidate(1, 101, 11, 0, fact, source_name="栖岭附件说明.txt")
    assert not support.preserves_support(result, [stripped])


def test_prefix_binding_is_bounded_to_three_sentences():
    """通过 `join` 核对本用例构造的状态。"""
    prefixes = ["本规则自2020年1月1日起生效。", "内部代号KX-603专指栖岭项目。",
                "栖岭项目属于研发项目。", "本规定适用于研发项目。"]
    fact = "附件须在9个工作日内补齐。"
    assert support._bound_source_span("".join(prefixes) + fact, "附件须在9个工作日内补齐") == "".join(prefixes[-3:]) + fact


def test_date_explanation_and_verbatim_quote_do_not_become_extra_answer_slots():
    """验证 `calls[0]['question']` 等于 `knowledge_question`，且 `calls[1]['original_request']` 等于 `question`。"""
    knowledge_question = "截至2030年5月31日，栖岭项目附件应在几个工作日内补齐？紧急申请是否豁免？"
    requirement = "请说明日期依据并附原文引用"
    question = knowledge_question + requirement + "。"
    prefix = "栖岭项目自2030年6月1日起附件须在9个工作日内补齐；"
    old_rule = "生效前的补齐期限为4个工作日"
    exception = "紧急申请不豁免，仍须遵守当期补齐期限。"
    candidate = Candidate(1, 101, 11, 0, prefix + old_rule + "。" + exception)
    calls = []

    def caller(system, user, deadline):
        """返回 `decomposition` 的结果 或 `support_reply` 的结果。

        输入为 `system`、`user`、`deadline`。
        下游调用 `ai.tests.test_evidence_support.supported`。
        """
        payload = json.JSONDecoder().raw_decode(user[user.index("{"):])[0]
        calls.append(payload)
        if system == support.DECOMPOSITION_SYSTEM:
            return decomposition(*payload["question"].strip("？").split("？"),
                                 standalone_question=payload["question"])
        return support_reply(supported(quote=old_rule), supported(quote="紧急申请不豁免"))

    result = support.review(question, [], [candidate], monotonic() + 5, caller=caller)

    assert calls[0]["question"] == knowledge_question
    assert calls[1]["original_request"] == question
    assert calls[1]["execution_requirements"] == [requirement]
    assert (result.state, result.reason_code) == ("accepted", "all_elements_supported")
    assert any(span["content"] == prefix + old_rule + "。" for span in result.required_spans)
