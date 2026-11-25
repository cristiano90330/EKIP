"""验证证据拆解、引用定位、正文覆盖和准入失败边界。"""

import json
from time import monotonic

import pytest

from ai.app.rag import evidence_support as support
from ai.app.models.context import model_execution
from ai.app.models.schema import ModelConfig, ModelExecution
from ai.app.rag.deadline import QueryDeadlineExceeded
from ai.app.retrieval.retriever import Candidate


@pytest.fixture
def selected_reviewer():
    """为需要该夹具的用例准备输入和隔离状态。

    产出供调用方迭代的结果。
    下游调用 `ai.app.models.schema.ModelConfig`、`ai.app.models.context.model_execution`。
    """
    model = ModelConfig(id="review-health-unit", type="chat", protocol="openai_chat",
                        model="unit-model", baseUrl="http://models.invalid/v1")
    with model_execution(ModelExecution(bindings={"evidence_support": model})):
        yield


def candidate(identity, text, *, source_name=None):
    """向 `test_agent_reading_instruction_does_not_become_a_source_scope` 提供预设数据。返回 `Candidate` 的结果。

    输入为 `identity`、`text`、`source_name`。
    上游为 `test_compound_task_reviews_all_business_facts_without_proving_assistant_actions`。
    """
    return Candidate(identity, identity + 100, 11, 0, text, score=1.0, source_name=source_name)


def reply(payload, *, finish_reason="stop", **extra):
    """向 `decomposition` 提供预设数据。返回 包含 `content`、`finish_reason`、`total_tokens` 的字典。

    输入为 `payload`、`finish_reason`、`extra`。
    上游为 `support_reply`、`citation_repair_reply`。
    """
    return {"content": json.dumps(payload, ensure_ascii=False),
            "finish_reason": finish_reason, "total_tokens": 7, **extra}


def decomposition(*texts, standalone_question="采购合同由谁在何时审核？", resolved=True,
                  execution_requirements=()):
    """向 `two_stage` 提供预设数据。返回 `reply` 的结果。

    输入为 `standalone_question`、`resolved`、`execution_requirements`、`texts`。
    上游为 `test_agent_reading_instruction_does_not_become_a_source_scope`。
    """
    elements = [{"id": f"E{index}", "text": text}
                for index, text in enumerate(texts or (standalone_question,), start=1)]
    if not resolved:
        standalone_question, elements = "", []
    return reply({"resolved": resolved, "execution_requirements": list(execution_requirements),
                  "standalone_question": standalone_question,
                  "elements": elements})


def support_reply(*judgments, covered=True, alignment="问题对象与资料适用范围一致。"):
    """向 `two_stage` 提供预设数据。返回 `reply` 的结果。

    输入为 `covered`、`alignment`、`judgments`。
    上游为 `test_agent_reading_instruction_does_not_become_a_source_scope`。
    """
    return reply({"alignment": alignment, "question_fully_covered": covered, "elements": list(judgments)})


def citation_repair_reply(quote):
    """向 `test_quote_repair_changes_only_the_target_quote_and_preserves_other_judgments` 提供预设数据。返回 `reply` 的结果。

    输入为 `quote`。
    上游为 `test_ambiguous_quote_repair_error_fails_closed`。
    """
    return reply({"quote": quote})


def supported(source_index=1, quote="采购合同在签署前由法务审核。"):
    """向 `two_stage` 提供预设数据。返回 包含 `supported`、`citations` 的字典。

    输入为 `source_index`、`quote`。
    上游为 `test_agent_reading_instruction_does_not_become_a_source_scope`、`test_compound_task_reviews_all_business_facts_without_proving_assistant_actions`。
    """
    return {"supported": True, "citations": [{"source_index": source_index, "quote": quote}]}


def unsupported():
    """向 `test_compound_task_reviews_all_business_facts_without_proving_assistant_actions` 提供预设数据。返回 包含 `supported`、`citations` 的字典。

    上游为 `test_coverage_can_be_confirmed_from_an_exact_single_slot_only_for_refusal`、`test_every_element_must_be_supported_for_acceptance`。
    """
    return {"supported": False, "citations": []}


def two_stage(*, decomp=None, adjudication=None):
    """委托 `supported` 完成当前操作，无直接返回值。

    输入为 `decomp`、`adjudication`。
    上游为 `test_agent_reading_instruction_does_not_become_a_source_scope`，下游调用 `decomposition`、`support_reply`。
    """
    outputs = iter([decomp or decomposition(), adjudication or support_reply(supported())])
    return lambda *args: next(outputs)


@pytest.mark.parametrize(("body_scope", "expected"), [
    ("本条仅适用于柏川项目。", "accepted"),
    ("本条仅适用于岚峰项目。", "rejected"),
    ("", "rejected"),
])
def test_agent_reading_instruction_does_not_become_a_source_scope(body_scope, expected):
    """验证 `result.state` 等于 `expected`，且 `result.reason_code` 等于 `'support_scope_unproven'`。"""
    question = "查阅柏川项目资料，告诉我唯一核验代号并附原文引用。"
    text = body_scope + "唯一核验代号为QZ4187-LX73。"
    result = support.review(question, [], [candidate(1, text, source_name="柏川项目资料.txt")],
        monotonic() + 5, caller=two_stage(
            decomp=decomposition("柏川项目的唯一核验代号", standalone_question="柏川项目的唯一核验代号是什么？",
                                 execution_requirements=["查阅资料", "附原文引用"]),
            adjudication=support_reply(supported(quote=text))))

    assert result.state == expected
    if expected == "rejected":
        assert result.reason_code == "support_scope_unproven"


@pytest.mark.parametrize("include_business_process", [False, True])
def test_compound_task_reviews_all_business_facts_without_proving_assistant_actions(
        include_business_process):
    """验证 `result.state` 等于 `'rejected' if include_business_process else 'accepted'`，且 `len(calls)` 等于 `2`。"""
    question = "先列文档，再读取文档7，回答材料缺失时需几个工作日内补齐，并附引用。"
    slots = ["材料缺失时的补齐期限"]
    judgments = [supported(quote="材料缺失时应在 3 个工作日内补齐。")]
    if include_business_process:
        question += "另说明报销审批流程的三个步骤。"
        slots.append("报销审批流程的三个步骤")
        judgments.append(unsupported())
    outputs = iter([
        decomposition(*slots, standalone_question="；".join(slots),
                      execution_requirements=["列文档", "读取文档7", "附引用"]),
        support_reply(*judgments),
    ])
    calls = []

    def model(system, user, deadline):
        """将当前记录追加到 `calls`，供调用方后续读取。输入为 `system`、`user`、`deadline`。"""
        calls.append((system, json.JSONDecoder().raw_decode(user.split("\n", 1)[1])[0]))
        return next(outputs)

    result = support.review(question, [], [candidate(1, "材料缺失时应在 3 个工作日内补齐。")],
                            monotonic() + 5, caller=model)

    assert result.state == ("rejected" if include_business_process else "accepted")
    assert len(calls) == 2
    assert all("执行要求" in system and "业务流程步骤" in system for system, _ in calls)
    assert calls[0][1]["question"] == question
    assert calls[1][1]["original_request"] == question
    assert calls[1][1]["question"] == result.standalone_question
    assert calls[1][1]["elements"] == [{"id": f"E{index}", "text": text}
                                        for index, text in enumerate(slots, start=1)]
    assert calls[1][1]["execution_requirements"] == ["列文档", "读取文档7", "附引用"]


@pytest.mark.parametrize("requirements", [None, "列文档", [1], [""], ["  "], ["x" * 4001], ["查原文"] * 9])
def test_invalid_execution_requirements_fail_before_support(requirements):
    """验证 `result.state` 等于 `'unavailable'`，且 `result.reason_code` 等于 `'decomposition_execution_requirements_invalid'`。"""
    value = json.loads(decomposition()["content"])
    value["execution_requirements"] = requirements
    outputs = []

    def model(*args):
        """返回 `reply` 的结果。输入为 `args`。"""
        outputs.append(args)
        return reply(value)

    result = support.review("采购合同由谁在何时审核？", [], [candidate(1, "由法务审核")],
                            monotonic() + 5, caller=model)

    assert result.state == "unavailable"
    assert result.reason_code == "decomposition_execution_requirements_invalid"
    assert len(outputs) == 1


def test_formatting_count_is_preserved_as_an_action_without_becoming_evidence():
    """验证 `result.state` 等于 `'accepted'`，且 `result.elements` 等于 `[{'id': 'E1', 'text': '材料缺失时的补齐期限'}]`。"""
    question = "请用3条简短说明回答材料缺失时需在几个工作日内补齐，并附引用。"
    calls = []
    outputs = iter([
        decomposition("材料缺失时的补齐期限", execution_requirements=["用3条简短说明回答", "附引用"]),
        support_reply(supported(quote="材料缺失时应在 3 个工作日内补齐。")),
    ])

    def model(system, user, deadline):
        """将当前记录追加到 `calls`，供调用方后续读取。输入为 `system`、`user`、`deadline`。"""
        calls.append(json.JSONDecoder().raw_decode(user.split("\n", 1)[1])[0])
        return next(outputs)

    result = support.review(question, [], [candidate(1, "材料缺失时应在 3 个工作日内补齐。")],
                            monotonic() + 5, caller=model)

    assert result.state == "accepted"
    assert result.elements == [{"id": "E1", "text": "材料缺失时的补齐期限"}]
    assert calls[1]["original_request"] == question
    assert calls[1]["question"] == result.standalone_question
    assert calls[1]["execution_requirements"] == ["用3条简短说明回答", "附引用"]


def test_support_schema_requires_bounded_alignment_assessment():
    """验证 `'alignment'` 属于 `schema['required']`，且 `schema['properties']['alignment']` 等于 `{'type': 'string', 'minLength': 1, 'maxLength': 160}`。"""
    sources = support.source_payload([candidate(1, "采购合同在签署前由法务审核。")])
    elements = [{"id": "E1", "text": "采购合同由谁在何时审核？"}]
    schema = support.support_response_format(elements, sources)["json_schema"]["schema"]

    assert "alignment" in schema["required"]
    assert schema["properties"]["alignment"] == {"type": "string", "minLength": 1, "maxLength": 160}
    value = json.loads(support_reply(supported())["content"])
    judgments, error = support.validate_support(value, elements, sources)
    assert error is None and judgments[0]["supported"] is True


@pytest.mark.parametrize("assessment", [None, False, "", "  ", "x" * 161])
def test_invalid_alignment_assessment_is_protocol_failure(assessment):
    """验证 `(result.state, result.reason_code)` 等于 `('unavailable', 'support_alignment_invalid')`。"""
    result = support.review("采购合同由谁在何时审核？", [],
                            [candidate(1, "采购合同在签署前由法务审核。")], monotonic() + 5,
                            caller=two_stage(adjudication=support_reply(supported(), alignment=assessment)))
    assert (result.state, result.reason_code) == ("unavailable", "support_alignment_invalid")


def test_missing_alignment_assessment_is_protocol_failure():
    """验证 `(result.state, result.reason_code)` 等于 `('unavailable', 'support_schema_invalid')`。"""
    output = reply({"question_fully_covered": True, "elements": [supported()]})
    result = support.review("采购合同由谁在何时审核？", [],
                            [candidate(1, "采购合同在签署前由法务审核。")], monotonic() + 5,
                            caller=two_stage(adjudication=output))
    assert (result.state, result.reason_code) == ("unavailable", "support_schema_invalid")


def test_source_name_reaches_review_but_citations_still_require_original_content():
    """验证 `sources[0]['source_name']` 等于 `source.source_name`，且 `error` 等于 `'support_element_1_citation_1_not_verbatim'`。"""
    source = candidate(1, "材料缺失时应在 3 个工作日内补齐。")
    source.source_name = "星河项目报销规则-演示.txt"
    sources = support.source_payload([source])

    assert sources[0]["source_name"] == source.source_name
    _, error = support.validate_support(
        {"alignment": "资料名称与问题对象一致。", "question_fully_covered": True,
         "elements": [supported(quote=source.source_name)]},
        [{"id": "E1", "text": "材料补齐期限"}], sources)
    assert error == "support_element_1_citation_1_not_verbatim"


def test_full_recall_window_and_whole_question_are_reviewed_and_quotes_preserved():
    """验证 `result.state` 等于 `'accepted'`，且 `result.reason_code` 等于 `'all_elements_supported'`。"""
    candidates = [candidate(index, "通用介绍") for index in range(1, 11)]
    candidates[7].content = "前言\n采购合同在\t签署前由法务审核。\n附录"
    calls = []

    def caller(system, user, deadline):
        """返回 `decomposition` 的结果 或 `support_reply` 的结果。输入为 `system`、`user`、`deadline`。下游调用 `supported`。"""
        calls.append((system, user, deadline))
        if len(calls) == 1:
            return decomposition("采购合同审核的责任人与时间")
        return support_reply(supported(8))

    deadline = monotonic() + 5
    result = support.review("采购合同由谁在何时审核？", [], candidates, deadline,
                            caller=caller, retrieval_query="采购合同 法务 审核时间")
    assert result.state == "accepted"
    assert result.reason_code == "all_elements_supported"
    assert result.required_source_ids == [8]
    assert result.total_tokens == 14
    assert len(calls) == 2 and {call[2] for call in calls} == {deadline}
    assert result.policy_version == "answer-support-citation-gate-v30-question-and-evidence"
    assert support.preserves_support(result, candidates)
    assert not support.preserves_support(result, candidates[:3])
    fragments = support.support_fragments(result, candidates)
    assert fragments == [{"source_id": 8, "content": "采购合同在\t签署前由法务审核。"}]
    first_payload = json.JSONDecoder().raw_decode(calls[0][1].split(chr(10), 1)[1])[0]
    second_payload = json.loads(calls[1][1].split(chr(10), 1)[1])
    assert first_payload["question"] == "采购合同由谁在何时审核？"
    assert len(second_payload["sources"]) == 10
    assert second_payload["original_request"] == first_payload["question"]
    assert second_payload["question"] == result.standalone_question
    assert second_payload["standalone_question"] == result.standalone_question
    assert second_payload["retrieval_query"] == "采购合同 法务 审核时间"
    assert second_payload["history"] == []
    assert "score" not in second_payload["sources"][7]
    assert support.preserves_support(result, [{"chunkId": 8, "content": fragments[0]["content"]}])


def test_second_stage_sees_history_and_rewrite_only_as_context_and_cannot_fill_missing_fact():
    """验证 `result.state` 等于 `'rejected'`，且 `result.reason_code` 等于 `'support_incomplete'`。"""
    history = [{"role": "assistant", "content": "有人声称应由行政部审核"}]
    requests = []

    def caller(system, user, deadline):
        """将当前记录追加到 `requests`，供调用方后续读取。输入为 `system`、`user`、`deadline`。下游调用 `decomposition`、`support_reply`。"""
        requests.append((system, user))
        return decomposition("合同审核责任人？") if len(requests) == 1 else support_reply(unsupported())

    result = support.review("合同由谁审核？", history, [candidate(1, "采购合同须审核")],
                            monotonic() + 5, caller=caller, retrieval_query="谁负责审核？")
    assert result.state == "rejected"
    assert result.reason_code == "support_incomplete"
    first_payload = json.JSONDecoder().raw_decode(requests[0][1].split(chr(10), 1)[1])[0]
    second_payload = json.loads(requests[1][1].split(chr(10), 1)[1])
    assert first_payload["history"] == history
    assert second_payload["original_request"] == "合同由谁审核？"
    assert second_payload["question"] == result.standalone_question
    assert second_payload["history"] == history
    assert second_payload["retrieval_query"] == "谁负责审核？"
    assert second_payload["sources"][0]["content"] == "采购合同须审核"
    assert "retrieval_query" in requests[1][0] and "sources[].content" in requests[1][0]
    assert "行政部" in requests[1][1]  # visible as context, not usable as evidence


def test_unresolved_history_refuses_without_running_support_stage():
    """验证 `(result.state, result.reason_code)` 等于 `('rejected', 'context_unresolved')`，且 `len(calls)` 等于 `1`。"""
    calls = []

    def unresolved(*args):
        """返回 `decomposition` 的结果。输入为 `args`。"""
        calls.append(args)
        return decomposition(resolved=False)

    result = support.review("那这个呢？", [], [candidate(1, "由法务审核")], monotonic() + 5,
                            caller=unresolved)
    assert (result.state, result.reason_code) == ("rejected", "context_unresolved")
    assert len(calls) == 1
    result = support.review("那这个呢？", [], [], monotonic() + 5, caller=unresolved)
    assert (result.state, result.reason_code) == ("rejected", "no_match")
    assert len(calls) == 1


def test_coverage_guard_rejects_omitted_answer_dimension_even_if_listed_slot_is_supported():
    """验证 `(result.state, result.reason_code)` 等于 `('rejected', 'support_question_coverage_incomplete')`，且 `result.required_source_ids` 等于 `[]`。"""
    calls = iter([decomposition("采购合同由谁审核？"),
                  support_reply(supported(quote="采购合同由法务审核"), covered=False)])
    result = support.review("采购合同由谁在何时审核？", [],
                            [candidate(1, "采购合同由法务审核")], monotonic() + 5,
                            caller=lambda *args: next(calls))
    assert (result.state, result.reason_code) == (
        "rejected", "support_question_coverage_incomplete")
    assert result.required_source_ids == []
    assert result.judgments[0]["supported"] is True
    assert not support.preserves_support(result, [candidate(1, "采购合同由法务审核")])


@pytest.mark.parametrize(("slot", "supported_by_source", "reason"), [
    ("栖岭项目由哪家供应商承接？", False, "support_incomplete"),
    ("项目由哪家供应商承接？", False, "support_question_coverage_incomplete"),
    ("栖岭项目由哪家供应商承接？", True, "support_question_coverage_incomplete"),
])
def test_coverage_can_be_confirmed_from_an_exact_single_slot_only_for_refusal(slot, supported_by_source, reason):
    """验证 `(result.state, result.reason_code)` 等于 `('rejected', reason)`，且 `result.required_spans` 等于 `[]`。"""
    question = "栖岭项目由哪家供应商承接？"
    source = "栖岭项目的交接须留下记录。"
    verdict = supported(quote=source) if supported_by_source else unsupported()
    result = support.review(question, [], [candidate(1, source)], monotonic() + 5, caller=two_stage(
        decomp=decomposition(slot, standalone_question=question),
        adjudication=support_reply(verdict, covered=False),
    ))
    assert (result.state, result.reason_code) == ("rejected", reason)
    assert result.required_spans == []


def test_related_quote_without_requested_maximum_days_cannot_be_admitted():
    """验证 `(result.state, result.reason_code)` 等于 `('rejected', 'support_requested_quantity_missing')`，且 `not support.preserves_support(result, [candidate(1, source)])` 成立。"""
    source = "审批单须注明申请原因、权限范围与使用时限。"
    calls = iter([decomposition("临时账号最长可以开通多少天"),
                  support_reply(supported(quote=source))])
    result = support.review(
        "临时账号最长可以开通多少天？", [], [candidate(1, source)], monotonic() + 5,
        caller=lambda *args: next(calls))

    assert (result.state, result.reason_code) == (
        "rejected", "support_requested_quantity_missing")
    assert not support.preserves_support(result, [candidate(1, source)])


def test_each_requested_measurement_needs_its_own_explicit_value():
    """验证 `(result.state, result.reason_code)` 等于 `('rejected', 'support_requested_quantity_missing')`。"""
    question = "高安全口令至少多少位、包含几类字符、特殊符号至少要有几个？"
    source = "高安全等级系统口令长度不少于十二位，且须包含全部四类字符。"
    calls = iter([decomposition("口令长度至少多少位", "字符类别数", "特殊符号数量至少"),
                  support_reply(supported(quote=source), supported(quote=source),
                                supported(quote=source))])
    result = support.review(question, [], [candidate(1, source)], monotonic() + 5,
                            caller=lambda *args: next(calls))

    assert (result.state, result.reason_code) == (
        "rejected", "support_requested_quantity_missing")


def test_all_explicit_requested_measurements_remain_admissible():
    """验证 `(result.state, result.reason_code)` 等于 `('accepted', 'all_elements_supported')`。"""
    question = "高安全口令至少多少位、包含几类字符、特殊符号至少要有几个？"
    source = "高安全口令长度不少于十二位，包含全部四类字符，其中至少三个为特殊符号。"
    calls = iter([decomposition("口令长度至少多少位", "字符类别数", "特殊符号数量至少"),
                  support_reply(supported(quote=source), supported(quote=source),
                                supported(quote=source))])
    result = support.review(question, [], [candidate(1, source)], monotonic() + 5,
                            caller=lambda *args: next(calls))

    assert (result.state, result.reason_code) == ("accepted", "all_elements_supported")


def test_decomposition_must_keep_each_dimension_of_a_listed_question():
    """验证 `len(calls)` 等于 `1`。"""
    question = "高安全口令至少多少位、包含几类字符、特殊符号至少要有几个？"
    calls = []

    def caller(system, user, deadline):
        """返回 `decomposition` 的结果 或 `support_reply` 的结果。输入为 `system`、`user`、`deadline`。下游调用 `supported`。"""
        calls.append((system, user))
        if len(calls) == 1:
            return decomposition("口令长度", "字符类别数")
        return support_reply(supported(), supported())

    result = support.review(question, [], [candidate(1, "高安全口令长度不少于十二位")],
                            monotonic() + 5, caller=caller)

    assert (result.state, result.reason_code) == (
        "unavailable", "decomposition_question_coverage_incomplete")
    assert len(calls) == 1


@pytest.mark.parametrize(("question", "element"), [
    ("员工最多可申请10天远程办公吗？", "员工可申请10天远程办公"),
    ("禁止在生产环境中直接修改数据吗？", "在生产环境中直接修改数据"),
])
def test_decomposition_must_preserve_question_range_and_negation(question, element):
    """通过 `decomposition` 核对本用例构造的状态。"""
    result = support.review(
        question, [], [candidate(1, "员工可以申请10天远程办公。")], monotonic() + 5,
        caller=lambda *_: decomposition(element))

    assert (result.state, result.reason_code) == (
        "unavailable", "decomposition_question_coverage_incomplete")


@pytest.mark.parametrize(("question", "elements"), [
    ("高安全等级系统口令至少多少位、须包含几类字符？", [
        "高安全等级系统口令的最小长度（位数）",
        "高安全等级系统口令须包含的字符类别数量",
    ]),
    ("员工最多可申请10天远程办公吗？", ["员工最大申请10天远程办公"]),
    ("埃菲尔铁塔于哪一年正式向公众开放？", ["埃菲尔铁塔正式向公众开放的年份"]),
    ("一个标准圆的面积如何用半径计算？", ["标准圆面积与半径的计算公式"]),
    ("涉密数据备份至少要保留多少年？", ["涉密数据备份的最短保留年限"]),
    ("试用期最长一年吗？", ["试用期最长时长一年"]),
])
def test_decomposition_accepts_equivalent_minimum_and_maximum_wording(question, elements):
    """验证 `error` 为 `None`，且 `parsed['elements']` 等于 `value['elements']`。"""
    value = {"resolved": True, "execution_requirements": [], "standalone_question": question,
             "elements": [{"id": f"E{index}", "text": text}
                          for index, text in enumerate(elements, start=1)]}
    parsed, error = support.validate_decomposition(value, question=question)
    assert error is None
    assert parsed["elements"] == value["elements"]


@pytest.mark.parametrize(("question", "element"), [
    ("员工最多可申请10天远程办公吗？", "员工最多可申请远程办公"),
    ("涉密数据备份至少要保留多少年？", "涉密数据备份保留年限"),
    ("试用期最长一年吗？", "试用期时长"),
])
def test_decomposition_still_rejects_omitted_range_or_explicit_quantity(question, element):
    """验证 `parsed` 为 `None`，且 `error` 等于 `'decomposition_question_coverage_incomplete'`。"""
    value = {"resolved": True, "execution_requirements": [], "standalone_question": question,
             "elements": [{"id": "E1", "text": element}]}
    parsed, error = support.validate_decomposition(value, question=question)
    assert parsed is None
    assert error == "decomposition_question_coverage_incomplete"


@pytest.mark.parametrize("elements", [
    [{"id": "E1", "text": "申请材料份数"}],
    [{"id": "E1", "text": "审批完成时限"}],
])
def test_decomposition_rejects_either_omitted_conjoined_dimension(elements):
    """验证 `parsed` 为 `None`，且 `error` 等于 `'decomposition_question_coverage_incomplete'`。"""
    question = "需要几份申请材料和审批完成时限？"
    value = {"resolved": True, "execution_requirements": [], "standalone_question": question,
             "elements": elements}

    parsed, error = support.validate_decomposition(value, question=question)

    assert parsed is None
    assert error == "decomposition_question_coverage_incomplete"

    complete = {"resolved": True, "execution_requirements": [], "standalone_question": question,
                "elements": [{"id": "E1", "text": "申请材料份数"},
                             {"id": "E2", "text": "审批完成时限"}]}
    parsed, error = support.validate_decomposition(complete, question=question)
    assert error is None
    assert parsed is not None


def test_repeated_quote_is_repaired_to_a_unique_complete_source_span():
    """验证 `result.state` 等于 `'accepted'`，且 `result.judgments[0]['citations'][0]['quote']` 等于 `'仅试点部门可以远程办公'`。"""
    source = "仅试点部门可以远程办公。其余部门可以远程办公。"
    calls = []

    def caller(system, user, deadline):
        """返回 `decomposition` 的结果 或 `support_reply` 的结果 或 `citation_repair_reply` 的结果。

        输入为 `system`、`user`、`deadline`。
        下游调用 `supported`。
        """
        calls.append((system, user))
        if len(calls) == 1:
            return decomposition("试点部门远程办公条件")
        if len(calls) == 2:
            return support_reply(supported(quote="可以远程办公"))
        return citation_repair_reply("仅试点部门可以远程办公")

    result = support.review("试点部门何时可以远程办公？", [], [candidate(1, source)],
                            monotonic() + 5, caller=caller)

    assert result.state == "accepted"
    assert result.judgments[0]["citations"][0]["quote"] == "仅试点部门可以远程办公"
    assert support.support_fragments(result, [candidate(1, source)]) == [
        {"source_id": 1, "content": "仅试点部门可以远程办公。"}]
    assert len(calls) == 3


def test_quote_repair_changes_only_the_target_quote_and_preserves_other_judgments():
    """验证 `result.state` 等于 `'accepted'`，且 `[item['supported'] for item in result.judgments]` 等于 `[True, True]`。"""
    source = "采购合同在签署前由法务审核。发票由财务审核。"
    calls = iter([
        decomposition("合同审核责任人", "发票审核责任人"),
        support_reply(supported(quote="采购合同由法务审核"),
                      supported(quote="发票由财务审核。")),
        citation_repair_reply("采购合同在签署前由法务审核"),
    ])

    result = support.review("合同和发票分别由谁审核？", [], [candidate(1, source)],
                            monotonic() + 5, caller=lambda *args: next(calls))

    assert result.state == "accepted"
    assert [item["supported"] for item in result.judgments] == [True, True]
    assert result.judgments[0]["citations"][0]["quote"] == "采购合同在签署前由法务审核"
    assert result.judgments[1]["citations"][0]["quote"] == "发票由财务审核。"


def test_ambiguous_quote_repair_error_fails_closed():
    """验证 `result.state` 等于 `'unavailable'`，且 `result.reason_code` 等于 `'support_element_1_citation_1_ambiguous'`。"""
    source = "仅试点部门可以远程办公。其余部门可以远程办公。"
    calls = iter([
        decomposition("试点部门远程办公条件"),
        support_reply(supported(quote="可以远程办公")),
        citation_repair_reply("可以远程办公"),
    ])
    result = support.review("试点部门何时可以远程办公？", [], [candidate(1, source)],
                            monotonic() + 5, caller=lambda *args: next(calls))

    assert result.state == "unavailable"
    assert result.reason_code == "support_element_1_citation_1_ambiguous"
    assert result.required_source_ids == []


@pytest.mark.parametrize(("question", "source"), [
    ("那人力资源部最迟几天内要通知？", "人力资源部在员工离岗之日起两个工作日内通知 IT 部门。"),
    ("那最迟几天上报合规部？", "须在三个工作日内上报合规部备案。"),
    ("这种改动需要几个人评审？", "涉及支付和用户数据的变更，必须由两名评审人评审。"),
    ("这种改动需要几名评审人？", "涉及支付和用户数据的变更，必须由两人评审。"),
])
def test_explicit_workday_and_person_unit_aliases_allow_exact_supported_quotes(question, source):
    """验证 `(result.state, result.reason_code)` 等于 `('accepted', 'all_elements_supported')`。"""
    calls = iter([decomposition(question), support_reply(supported(quote=source))])
    result = support.review(question, [], [candidate(1, source)], monotonic() + 5,
                            caller=lambda *args: next(calls))

    assert (result.state, result.reason_code) == ("accepted", "all_elements_supported")


def test_explicit_project_scope_is_not_inferred_from_an_unscoped_source():
    """验证 `(result.state, result.reason_code)` 等于 `('rejected', 'support_scope_unproven')`，且 `result.required_source_ids` 等于 `[]`。"""
    question = "依据柏川项目制度，报销材料缺失时申请人应在几个工作日内补齐？"
    source = "材料缺失时，申请人须在2个工作日内补齐。"
    calls = iter([
        decomposition(question),
        support_reply(supported(quote=source),
                      alignment="资料虽未说明项目范围，但该规则应适用于柏川项目。"),
    ])

    result = support.review(question, [], [candidate(
        1, source, source_name="柏川项目报销细则.txt")], monotonic() + 5,
                            caller=lambda *args: next(calls))

    assert (result.state, result.reason_code) == ("rejected", "support_scope_unproven")
    assert result.required_source_ids == []
    assert result.required_spans == []


def test_conflicting_same_scope_measurements_cannot_be_resolved_by_model_choice():
    """验证 `(result.state, result.reason_code)` 等于 `('rejected', 'support_conflict_unresolved')`，且 `result.judgments` 等于 `[{'id': 'E1', 'supported': False, 'citations': []}]`。"""
    question = "依据柏川项目制度，报销材料缺失时申请人应在几个工作日内补齐？"
    sources = [
        candidate(1, "本细则适用于柏川项目。材料缺失时，申请人须在2个工作日内补齐。",
                  source_name="柏川项目报销细则甲.txt"),
        candidate(2, "本细则适用于柏川项目。材料缺失时，申请人须在6个工作日内补齐。",
                  source_name="柏川项目报销细则乙.txt"),
    ]
    calls = []

    def model(*args):
        """返回 `decomposition` 的结果 或 `support_reply` 的结果。输入为 `args`。"""
        calls.append(args)
        if len(calls) == 1:
            return decomposition(question)
        # The 4B baseline sometimes emits unsupported=true/false inconsistently
        # and attaches a citation to an unsupported judgment. The deterministic
        # conflict gate must reject before that malformed support response can
        # turn a clear conflict into an unavailable outcome.
        return support_reply({"supported": False, "citations": [
            {"source_index": 1, "quote": "材料缺失时，申请人须在2个工作日内补齐。"}
        ]})

    result = support.review(question, [], sources, monotonic() + 5,
                            caller=model)

    assert (result.state, result.reason_code) == ("rejected", "support_conflict_unresolved")
    assert result.judgments == [{"id": "E1", "supported": False, "citations": []}]
    assert len(calls) == 1
    assert result.required_source_ids == []
    assert result.required_spans == []


def test_fine_rule_precedes_institution_when_scoped_measurements_conflict():
    """验证 `(result.state, result.reason_code)` 等于 `('accepted', 'all_elements_supported')`，且 `result.required_source_ids` 等于 `[2]`。"""
    question = "依据柏川项目制度，报销材料缺失时申请人应在几个工作日内补齐？"
    policy = candidate(1, "本制度适用于柏川项目。材料缺失时，申请人须在2个工作日内补齐。",
                       source_name="柏川项目报销制度.txt")
    detail = candidate(2, "本细则适用于柏川项目。材料缺失时，申请人须在6个工作日内补齐。",
                       source_name="柏川项目报销细则.txt")
    calls = iter([
        decomposition(question),
        support_reply(supported(2, "材料缺失时，申请人须在6个工作日内补齐。")),
    ])

    result = support.review(question, [], [policy, detail], monotonic() + 5,
                            caller=lambda *args: next(calls))

    assert (result.state, result.reason_code) == ("accepted", "all_elements_supported")
    assert result.required_source_ids == [2]


def test_fine_rule_precedence_replaces_lower_ranked_institution_citation():
    """验证 `(result.state, result.reason_code)` 等于 `('accepted', 'all_elements_supported')`，且 `result.required_source_ids` 等于 `[2]`。"""
    question = "依据柏川项目制度，报销材料缺失时申请人应在几个工作日内补齐？"
    policy = candidate(1, "本制度适用于柏川项目。材料缺失时，申请人须在2个工作日内补齐。",
                       source_name="柏川项目报销制度.txt")
    detail = candidate(2, "本细则适用于柏川项目。材料缺失时，申请人须在6个工作日内补齐。",
                       source_name="柏川项目报销细则.txt")
    calls = iter([
        decomposition(question),
        support_reply(supported(1, "材料缺失时，申请人须在2个工作日内补齐。")),
    ])

    result = support.review(question, [], [policy, detail], monotonic() + 5,
                            caller=lambda *args: next(calls))

    assert (result.state, result.reason_code) == ("accepted", "all_elements_supported")
    assert result.judgments[0]["citations"] == [{
        "source_index": 2,
        "quote": "材料缺失时，申请人须在6个工作日内补齐。",
        "source_id": 2,
    }]
    assert result.required_source_ids == [2]


def test_explicit_universal_scope_can_support_a_named_project():
    """验证 `(result.state, result.reason_code)` 等于 `('accepted', 'all_elements_supported')`。"""
    question = "依据柏川项目制度，报销材料缺失时申请人应在几个工作日内补齐？"
    source = "所有项目统一执行：材料缺失时，申请人须在2个工作日内补齐。"
    calls = iter([decomposition(question), support_reply(supported(quote=source))])

    result = support.review(question, [], [candidate(1, source)], monotonic() + 5,
                            caller=lambda *args: next(calls))

    assert (result.state, result.reason_code) == ("accepted", "all_elements_supported")


def test_explicit_project_exclusion_does_not_establish_applicability():
    """验证 `(result.state, result.reason_code)` 等于 `('rejected', 'support_scope_unproven')`。"""
    question = "依据柏川项目制度，报销材料缺失时申请人应在几个工作日内补齐？"
    source = "本制度不适用于柏川项目。材料缺失时，申请人须在2个工作日内补齐。"
    calls = iter([decomposition(question), support_reply(supported(quote=source))])

    result = support.review(question, [], [candidate(1, source)], monotonic() + 5,
                            caller=lambda *args: next(calls))

    assert (result.state, result.reason_code) == ("rejected", "support_scope_unproven")


def test_new_and_old_values_in_one_explicitly_dated_source_are_not_cross_source_conflict():
    """验证 `(result.state, result.reason_code)` 等于 `('accepted', 'all_elements_supported')`。"""
    question = "柏川项目在2026年10月材料缺失时须在几个工作日内补齐？"
    source = (
        "柏川项目2026年9月1日起执行新规则，材料缺失时，申请人须在2个工作日内补齐。"
        "此前适用的旧规则为7个工作日。"
    )
    quote = "材料缺失时，申请人须在2个工作日内补齐。"
    calls = iter([decomposition(question), support_reply(supported(quote=quote))])

    result = support.review(question, [], [candidate(1, source)], monotonic() + 5,
                            caller=lambda *args: next(calls))

    assert (result.state, result.reason_code) == ("accepted", "all_elements_supported")


def test_history_resolved_project_scope_is_accepted_for_deictic_follow_up():
    """验证 `(result.state, result.reason_code)` 等于 `('accepted', 'all_elements_supported')`。"""
    question = "那该项目报销材料缺失时，应在几个工作日内补齐？"
    history = [
        {"role": "user", "content": "接下来只讨论柏川项目的报销规则。"},
        {"role": "assistant", "content": "好的，后续问题以柏川项目为讨论对象。"},
    ]
    source = "本制度适用于柏川项目。材料缺失时，申请人须在2个工作日内补齐。"
    standalone = "柏川项目报销材料缺失时需在几个工作日内补齐？"
    calls = iter([
        decomposition("柏川项目报销材料缺失时的补齐期限", standalone_question=standalone),
        support_reply(supported(quote="申请人须在2个工作日内补齐。")),
    ])

    result = support.review(question, history, [candidate(1, source)], monotonic() + 5,
                            caller=lambda *args: next(calls))

    assert (result.state, result.reason_code) == ("accepted", "all_elements_supported")


@pytest.mark.parametrize(("question", "source"), [
    ("这种改动需要几位评审？", "涉及支付和用户数据的变更，必须由两人评审。"),
    ("这种改动需要几个人评审？", "涉及支付和用户数据的变更，必须由两位评审。"),
    ("审批需要几次？", "审批需要两个工作日完成。"),
])
def test_quantity_aliases_do_not_merge_distinct_units(question, source):
    """验证 `(result.state, result.reason_code)` 等于 `('rejected', 'support_requested_quantity_missing')`。"""
    calls = iter([decomposition(question), support_reply(supported(quote=source))])
    result = support.review(question, [], [candidate(1, source)], monotonic() + 5,
                            caller=lambda *args: next(calls))

    assert (result.state, result.reason_code) == (
        "rejected", "support_requested_quantity_missing")


def test_v13_prompt_names_false_admissions_unit_aliases_and_process_tool_mismatch():
    """验证 `phrase` 属于 `support.SUPPORT_SYSTEM`，且 `'三个独立要点'` 属于 `support.DECOMPOSITION_SYSTEM`。"""
    for phrase in ("使用时限", "远程办公天数", "会议频率", "特殊符号数量", "是否折现"):
        assert phrase in support.SUPPORT_SYSTEM
    assert "三个独立要点" in support.DECOMPOSITION_SYSTEM
    assert "工作日" in support.SUPPORT_SYSTEM and "人/名" in support.SUPPORT_SYSTEM
    assert "位”“个”“次”" in support.SUPPORT_SYSTEM
    assert "CI 静态扫描工具" in support.SUPPORT_SYSTEM
    assert "流程/技术环节" in support.SUPPORT_SYSTEM
    assert "GitLab Code Review 平台" in support.SUPPORT_SYSTEM
    assert "CI 静态扫描必须使用哪款工具" in support.SUPPORT_SYSTEM
    assert "禁止把 question 或 element 中的背景词、对象词补进 quote" in support.CITATION_REPAIR_SYSTEM
    assert "最短连续原文" in support.CITATION_REPAIR_SYSTEM
    assert "唯一定位" in support.CITATION_REPAIR_SYSTEM
    assert "细则优先于制度" in support.SUPPORT_SYSTEM


def test_every_element_must_be_supported_for_acceptance():
    """验证 `result.state` 等于 `'rejected'`，且 `result.reason_code` 等于 `'support_incomplete'`。"""
    calls = iter([decomposition("审核责任人", "审核时间"),
                  support_reply(supported(quote="由法务审核"), unsupported())])
    result = support.review("采购合同由谁在何时审核？", [],
                            [candidate(1, "采购合同在签署前由法务审核")], monotonic() + 5,
                            caller=lambda *args: next(calls))
    assert result.state == "rejected"
    assert result.reason_code == "support_incomplete"
    assert result.required_source_ids == [1]
    assert not support.preserves_support(result, [candidate(1, "采购合同在签署前由法务审核")])


@pytest.mark.parametrize("judgment,reason", [
    (supported(quote="在签署前由法务审核"), "support_element_1_citation_1_not_verbatim"),
    (supported(0), "support_element_1_citation_1_source_index_invalid"),
    (supported(True), "support_element_1_citation_1_source_index_invalid"),
    ({"supported": True, "citations": []}, "support_element_1_missing_citation"),
    ({"supported": False, "citations": [{"source_index": 1, "quote": "由法务审核"}]},
     "support_element_1_unsupported_has_citation"),
    ({"supported": "true", "citations": []}, "support_element_1_supported_not_boolean"),
])
def test_invalid_citation_or_judgment_fails_closed(judgment, reason):
    """验证 `result.state` 等于 `'unavailable'`，且 `result.reason_code` 等于 `reason`。"""
    calls = iter([decomposition(), support_reply(judgment),
                  citation_repair_reply("法务审核采购合同")])
    result = support.review("采购合同由谁审核？", [], [candidate(1, "采购合同由法务审核")],
                            monotonic() + 5, caller=lambda *args: next(calls))
    assert result.state == "unavailable"
    assert result.reason_code == reason
    assert result.required_source_ids == []


def test_nonverbatim_citation_gets_one_repair_and_only_exact_retry_is_accepted():
    """验证 `(result.state, result.reason_code)` 等于 `('accepted', 'all_elements_supported')`，且 `result.required_source_ids` 等于 `[8]`。"""
    source = "采购合同在签署前由法务审核。"
    calls = []

    def caller(system, user, deadline):
        """返回 `decomposition` 的结果 或 `support_reply` 的结果 或 `citation_repair_reply` 的结果。

        输入为 `system`、`user`、`deadline`。
        下游调用 `supported`。
        """
        calls.append((system, user, deadline))
        if len(calls) == 1:
            return decomposition()
        if len(calls) == 2:
            return support_reply(supported(quote="合同由法务审核"))
        return citation_repair_reply("采购合同在签署前由法务审核")

    deadline = monotonic() + 5
    result = support.review("采购合同由谁审核？", [], [candidate(8, source)], deadline,
                            caller=caller)

    assert (result.state, result.reason_code) == ("accepted", "all_elements_supported")
    assert result.required_source_ids == [8]
    assert result.total_tokens == 21
    assert len(calls) == 3
    assert all(call[2] == deadline for call in calls)
    assert "合同由法务审核" in calls[2][1]
    assert calls[2][0] == support.CITATION_REPAIR_SYSTEM
    repair_payload = json.loads(calls[2][1].split("\n", 1)[1])
    assert repair_payload == {
        "element": {"id": "E1", "text": "采购合同由谁在何时审核？"},
        "source_index": 1,
        "source_content": source,
        "invalid_quote": "合同由法务审核",
    }


def test_partial_refusal_skips_citation_retry_and_preserves_other_judgments():
    """验证 `(result.state, result.reason_code)` 等于 `('rejected', 'support_incomplete')`，且 `[item['supported'] for item in result.judgments]` 等于 `[True, False, True]`。"""
    sources = [
        candidate(8, "采购合同在签署前由法务审核。"),
        candidate(12, "发票由财务审核。"),
    ]
    calls = []
    outputs = iter([
        decomposition("采购合同审核责任人", "远程审批是否允许", "发票审核责任人"),
        support_reply(
            supported(1, "合同由法务审核"),
            unsupported(),
            supported(2, "发票由财务审核。"),
        ),
    ])

    def caller(system, user, deadline):
        """将当前记录追加到 `calls`，供调用方后续读取。输入为 `system`、`user`、`deadline`。"""
        calls.append((system, user, deadline))
        return next(outputs)

    result = support.review(
        "采购合同由谁审核、是否允许远程审批、发票由谁审核？", [], sources,
        monotonic() + 5, caller=caller)

    assert (result.state, result.reason_code) == ("rejected", "support_incomplete")
    assert [item["supported"] for item in result.judgments] == [True, False, True]
    assert result.judgments[0]["citations"] == [{
        "source_index": 1,
        "quote": "合同由法务审核",
        "source_id": 8,
    }]
    assert result.judgments[1]["citations"] == []
    assert result.judgments[2]["citations"] == [{
        "source_index": 2,
        "quote": "发票由财务审核。",
        "source_id": 12,
    }]
    assert result.required_source_ids == [8, 12]
    assert len(calls) == 2
    assert calls[1][0] == support.SUPPORT_SYSTEM


def test_nonverbatim_citation_still_fails_closed_after_one_invalid_repair():
    """验证 `result.state` 等于 `'unavailable'`，且 `result.reason_code` 等于 `'support_element_1_citation_1_not_verbatim'`。"""
    calls = iter([decomposition(),
                  support_reply(supported(quote="合同由法务审核")),
                  citation_repair_reply("法务审核采购合同")])

    result = support.review(
        "采购合同由谁审核？", [], [candidate(8, "采购合同在签署前由法务审核")],
        monotonic() + 5, caller=lambda *args: next(calls))

    assert result.state == "unavailable"
    assert result.reason_code == "support_element_1_citation_1_not_verbatim"
    assert result.required_source_ids == []


def test_nonverbatim_positive_citation_does_not_override_explicit_partial_refusal():
    """验证 `(result.state, result.reason_code)` 等于 `('rejected', 'support_incomplete')`，且 `result.judgments[1]['supported']` 为 `False`。"""
    outputs = iter([
        decomposition("入职满十年员工年假天数", "未休年假是否按工资折现"),
        support_reply(supported(quote="入职满十年享受十天"), unsupported()),
    ])
    calls = []

    def caller(system, user, deadline):
        """将当前记录追加到 `calls`，供调用方后续读取。输入为 `system`、`user`、`deadline`。"""
        calls.append(system)
        return next(outputs)

    result = support.review(
        "入职满十年的员工年假天数以及未休年假是否按工资折现？", [],
        [candidate(46, "年假：入职满一年享受五天，满十年享受十天，满二十年享受十五天")],
        monotonic() + 5, caller=caller)

    assert (result.state, result.reason_code) == ("rejected", "support_incomplete")
    assert result.judgments[1]["supported"] is False
    assert len(calls) == 2


def test_explicit_partial_refusal_does_not_hide_invalid_citation_structure():
    """通过 `monotonic` 核对本用例构造的状态。"""
    outputs = iter([
        decomposition("入职满十年员工年假天数", "未休年假是否按工资折现"),
        support_reply(supported(99, quote="入职满十年享受十天"), unsupported()),
    ])
    result = support.review(
        "入职满十年的员工年假天数以及未休年假是否按工资折现？", [],
        [candidate(46, "年假：入职满一年享受五天，满十年享受十天，满二十年享受十五天")],
        monotonic() + 5, caller=lambda *args: next(outputs))

    assert (result.state, result.reason_code) == (
        "unavailable", "support_element_1_citation_1_source_index_invalid")


def test_nonverbatim_retry_is_skipped_when_the_shared_deadline_expires(monkeypatch):
    """验证用例触发 `support.QueryDeadlineExceeded` 异常，并检查 `len(calls)` 等于 `2`。"""
    clock = [100.0]
    monkeypatch.setattr(support, "monotonic", lambda: clock[0])
    calls = []

    def caller(system, user, deadline):
        """返回 `decomposition` 的结果 或 `support_reply` 的结果。输入为 `system`、`user`、`deadline`。下游调用 `supported`。"""
        calls.append((system, user, deadline))
        if len(calls) == 1:
            return decomposition()
        clock[0] = 106.0
        return support_reply(supported(quote="合同由法务审核"))

    with pytest.raises(support.QueryDeadlineExceeded):
        support.review("采购合同由谁审核？", [],
                       [candidate(8, "采购合同在签署前由法务审核")], 105.0,
                       caller=caller)

    assert len(calls) == 2


def test_cal_p04_refuses_missing_policy_without_repairing_irrelevant_citation():
    """验证 `(result.state, result.reason_code)` 等于 `('rejected', 'support_incomplete')`，且 `result.judgments[0]['citations'][0]['quote']` 等于 `'入职满十年享受十天'`。"""
    source = ("年假：入职满一年享受五天，满十年享受十天，满二十年享受十五天；"
             "年假须提前三个工作日申请。")
    outputs = iter([
        decomposition("入职满十年员工年假天数", "未休年假是否按工资折现"),
        support_reply(supported(quote="入职满十年享受十天"), unsupported()),
    ])
    calls = []

    def caller(system, user, deadline):
        """将当前记录追加到 `calls`，供调用方后续读取。输入为 `system`、`user`、`deadline`。"""
        calls.append(user)
        return next(outputs)

    result = support.review(
        "入职满十年的员工年假天数以及未休年假是否按工资折现？", [],
        [candidate(46, source)], monotonic() + 5,
        caller=caller)

    assert (result.state, result.reason_code) == ("rejected", "support_incomplete")
    assert result.judgments[0]["citations"][0]["quote"] == "入职满十年享受十天"
    assert result.judgments[1]["supported"] is False
    assert result.total_tokens == 14
    assert len(calls) == 2


def test_quote_repair_rechecks_negation_instead_of_trimming_it_from_the_citation():
    """验证 `(result.state, result.reason_code)` 等于 `('unavailable', 'support_citation_repair_invalid')`，且 `result.judgments` 等于 `[]`。"""
    source = "年假政策：满十年享受十天。"
    calls = []
    outputs = iter([
        decomposition("未满十年员工年假天数"),
        support_reply(supported(quote="未满十年享受十天")),
        citation_repair_reply(""),
    ])

    def caller(system, user, deadline):
        """将当前记录追加到 `calls`，供调用方后续读取。输入为 `system`、`user`、`deadline`。"""
        calls.append(user)
        return next(outputs)

    result = support.review(
        "未满十年的员工年假天数是多少？", [], [candidate(46, source)],
        monotonic() + 5, caller=caller)

    assert (result.state, result.reason_code) == (
        "unavailable", "support_citation_repair_invalid")
    assert result.judgments == []
    assert len(calls) == 3
    assert "未满十年享受十天" in calls[2]


def test_wrong_judgment_count_and_duplicate_json_keys_are_protocol_failures(selected_reviewer):
    """验证 `(result.state, result.reason_code)` 等于 `('unavailable', 'support_element_count_invalid')`，且 `result.reason_code` 等于 `'support_json_invalid'`。"""
    calls = iter([decomposition("责任人", "时间"), support_reply(supported())])
    result = support.review("采购合同由谁在何时审核？", [],
                            [candidate(1, "采购合同在签署前由法务审核")], monotonic() + 5,
                            caller=lambda *args: next(calls))
    assert (result.state, result.reason_code) == ("unavailable", "support_element_count_invalid")

    duplicate = {"content": '{"resolved":true,"resolved":false,"standalone_question":"","elements":[]}',
                 "finish_reason": "stop"}
    result = support.review("合同由谁审核？", [], [candidate(1, "采购合同须审核")], monotonic() + 5,
                            caller=lambda *args: duplicate)
    assert result.reason_code == "support_json_invalid"
    assert support.readiness()["reasonCode"] == "support_json_invalid"


@pytest.mark.parametrize("output", [
    reply({"resolved": False, "execution_requirements": [], "standalone_question": "", "elements": []}, finish_reason="length"),
    reply({"resolved": True, "execution_requirements": [], "standalone_question": "问题", "elements": [{"id": "E1", "text": "要点"}]},
          degraded=True),
    {"content": '{"evil":1,"evil":2}', "finish_reason": "stop"},
])
def test_invalid_or_incomplete_model_output_never_becomes_normal_no_evidence(output):
    """验证 `result.state` 等于 `'unavailable'`，且 `result.reason_code` 不属于 `{'no_match', 'support_incomplete', 'context_unresolved'}`。"""
    result = support.review("由谁审核？", [], [candidate(1, "由法务审核")], monotonic() + 5,
                            caller=lambda *args: output)
    assert result.state == "unavailable"
    assert result.reason_code not in {"no_match", "support_incomplete", "context_unresolved"}


def test_model_io_timeout_after_shared_deadline_remains_query_timeout(monkeypatch, selected_reviewer):
    """验证用例触发 `QueryDeadlineExceeded` 异常，并检查 `support.readiness()['status']` 等于 `'not_ready'`，且 `support.readiness()['reasonCode']` 等于 `'support_review_deadline_exceeded'`。"""
    clock = [100.0]
    monkeypatch.setattr(support, "monotonic", lambda: clock[0])

    def caller(*args):
        """触发 `TimeoutError`，交由上游处理。输入为 `args`。"""
        clock[0] = 106.0
        raise TimeoutError("IO expired")

    with pytest.raises(QueryDeadlineExceeded):
        support.review("采购合同由谁审核？", [], [candidate(1, "由法务审核")], 105.0, caller=caller)
    assert support.readiness()["status"] == "not_ready"
    assert support.readiness()["reasonCode"] == "support_review_deadline_exceeded"


def test_default_calls_pass_separate_strict_schemas_with_dynamic_bounds(monkeypatch, selected_reviewer):
    """验证 `result.state` 等于 `'accepted'`，且 `len(calls)` 等于 `2`。"""
    from ai.app.llm import guard

    calls = []
    outputs = iter([decomposition("责任人", "审核时间"),
                    support_reply(supported(2, "由法务审核"), supported(2, "签署前"))])

    def model(purpose, system, user, **kwargs):
        """将当前记录追加到 `calls`，供调用方后续读取。输入为 `purpose`、`system`、`user`、`kwargs`。"""
        calls.append((purpose, system, user, kwargs))
        return next(outputs)

    monkeypatch.setattr(guard.gate, "call", model)
    candidates = [candidate(10, "采购合同"), candidate(11, "采购合同由法务审核，签署前完成")]
    result = support.review("采购合同由谁在何时审核？", [], candidates, monotonic() + 5,
                            retrieval_query="采购合同审核")
    assert result.state == "accepted"
    assert len(calls) == 2
    first_schema = calls[0][3]["response_format"]["json_schema"]
    second_schema = calls[1][3]["response_format"]["json_schema"]
    assert first_schema["name"] == "answer_support_decomposition_v18"
    assert second_schema["name"] == "answer_support_judgments_v16"
    assert calls[0][0] == calls[1][0] == "evidence_support"
    assert first_schema["strict"] is True
    assert set(first_schema["schema"]["required"]) == {
        "resolved", "execution_requirements", "standalone_question", "elements"}
    assert second_schema["strict"] is True
    assert second_schema["schema"]["properties"]["elements"]["minItems"] == 2
    assert second_schema["schema"]["properties"]["elements"]["maxItems"] == 2
    citation_schema = second_schema["schema"]["properties"]["elements"]["items"][
        "properties"]["citations"]["items"]["properties"]["source_index"]
    assert citation_schema["enum"] == [1, 2]
    assert "retrieval_query" in calls[1][2]
    assert '"question":"采购合同由谁在何时审核？"' in calls[1][2]
