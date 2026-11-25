# -*- coding: utf-8 -*-
"""验证候选多样性、证据压缩及文件扩展的边界。"""

import pytest

from ai.app.llm.tokens import count_tokens, truncate_to_tokens
from ai.app.models.schema import ModelExecution
from ai.app.rag import pipeline
from ai.app.rag.postprocess import (
    apply_postprocess,
    char_bigrams,
    compress_extractive,
    jaccard,
    mmr_select,
)
from ai.app.rag.schema import RagQueryRequest
from ai.app.retrieval.retriever import Candidate


def _cand(cid, content, score=None, doc=1, idx=0):
    """向 `test_mmr_drops_near_duplicate_in_favor_of_diverse` 提供预设数据。返回 `Candidate` 的结果。

    输入为 `cid`、`content`、`score`、`doc`、`idx`。
    上游为 `test_mmr_lambda_one_degrades_to_plain_topk`。
    """
    return Candidate(id=cid, document_id=doc, knowledge_base_id=1, chunk_index=idx,
                     content=content, score=score)


# ---------- 字符二元组 / Jaccard ----------

def test_char_bigrams_folds_whitespace():
    """验证 `char_bigrams('报 销 凭 证')` 等于 `char_bigrams('报销凭证')`。"""
    assert char_bigrams("报 销 凭 证") == char_bigrams("报销凭证")


def test_jaccard_zero_when_either_empty():
    """两个空内容不算「重复」（与 Java MmrSelector.jaccard 一致）。"""
    assert jaccard(set(), set()) == 0.0
    assert jaccard({"报销"}, set()) == 0.0
    # inter=1、union=2+1-1=2 → 0.5
    assert jaccard({"报销", "销凭"}, {"报销"}) == pytest.approx(0.5)


# ---------- MMR ----------

def test_mmr_drops_near_duplicate_in_favor_of_diverse():
    """近重复块（同一段话的重叠切块）应被换掉：这就是 MMR 存在的理由。"""
    dup_a = "报销必须提供合法有效的发票，发票抬头为公司全称，并附付款记录与说明材料等原始凭证。"
    dup_b = dup_a[:30] + "。"                   # 与 dup_a 高度重叠
    other = "年假需提前三个工作日申请，由直属主管审批后生效。"
    picked = mmr_select([_cand(1, dup_a, 0.9), _cand(2, dup_b, 0.89), _cand(3, other, 0.6)], 2, 0.7)
    ids = [c.id for c in picked]
    assert ids[0] == 1                          # 首个仍取相关分最高者
    assert 3 in ids, f"近重复块该被换成不重复的候选，实际 {ids}"


def test_mmr_lambda_one_degrades_to_plain_topk():
    """λ=1 退化为纯按分取 Top-K（反冗余项被关掉）。"""
    a = _cand(1, "AAAAAAAA", 0.9)
    b = _cand(2, "AAAAAAAA", 0.8)               # 与 a 完全相同
    c = _cand(3, "BBBBBBBB", 0.7)
    assert [x.id for x in mmr_select([a, b, c], 2, 1.0)] == [1, 2]


def test_mmr_returns_input_order_when_candidates_not_enough():
    """候选数 ≤ topK 时去重无意义 → 原序返回（已按分降序）。"""
    cs = [_cand(1, "甲", 0.9), _cand(2, "乙", 0.8)]
    assert [c.id for c in mmr_select(cs, 5, 0.7)] == [1, 2]


def test_mmr_empty_and_zero_topk():
    """验证 `mmr_select([], 5, 0.7)` 等于 `[]`，且 `mmr_select([_cand(1, '甲', 0.9)], 0, 0.7)` 等于 `[]`。"""
    assert mmr_select([], 5, 0.7) == []
    assert mmr_select([_cand(1, "甲", 0.9)], 0, 0.7) == []


# ---------- 抽取式压缩 ----------

def test_truncate_never_exceeds_budget():
    """硬预算的底座：truncate 的结果必须 ≤ 预算（否则「压完还是超」自相矛盾）。"""
    text = "报销必须提供合法有效的发票" * 20
    for budget in (1, 5, 17, 100):
        out = truncate_to_tokens(text, budget)
        assert count_tokens(out) <= budget or out == "", (budget, count_tokens(out))


def test_extractive_respects_hard_budget():
    """tokensAfter <= budget 是**保证**，不是「尽量」。"""
    cands = [_cand(i, "报销必须提供合法有效的发票，并附付款记录。" * 5) for i in range(1, 6)]
    stats = compress_extractive("报销凭证", cands, token_budget=200)
    assert stats.mode == "extractive"
    assert stats.tokens_after <= 200
    assert stats.tokens_before > stats.tokens_after
    assert stats.ratio < 1.0


def test_extractive_keeps_first_sentence_as_fallback():
    """整块无相关句时兜底保留第一句，避免信息全丢。"""
    c = _cand(1, "完全无关的一句话。另一句也无关。")
    stats = compress_extractive("年假申请流程", [c], token_budget=1500)
    assert len(stats.candidates) == 1
    assert "完全无关的一句话" in stats.candidates[0].content


def test_extractive_preserves_source_fields():
    """压缩只换 content，来源字段必须原样保留（否则来源链断掉、评测 recall 口径也乱）。"""
    src = _cand(7, "报销凭证需要发票。", score=0.88, doc=42, idx=3)
    stats = compress_extractive("报销凭证", [src], token_budget=1500)
    out = stats.candidates[0]
    assert (out.id, out.document_id, out.knowledge_base_id, out.chunk_index) == (7, 42, 1, 3)
    assert out.score == 0.88
    assert out is not src                      # 必须复制，不能就地改（同批候选可能被别处持有）


def test_extractive_preserves_score_provenance_and_validity():
    """验证 `(out.score_source, out.rerank_scorer, out.score_valid)` 等于 `('rerank', 'model', True)`，且 `out.rerank_degraded` 为 `True`。"""
    src = _cand(8, "报销凭证需要发票。", score=0.88)
    src.score_source = "rerank"
    src.rerank_scorer = "model"
    src.score_valid = True
    src.rerank_degraded = True
    src.score_error = ""
    out = compress_extractive("报销凭证", [src], token_budget=1500).candidates[0]
    assert (out.score_source, out.rerank_scorer, out.score_valid) == ("rerank", "model", True)
    assert out.rerank_degraded is True
    assert out.score_error == ""


def test_extractive_empty_input():
    """验证 `stats.candidates == [] and stats.tokens_before == 0 and (stats.ratio == 1.0)` 成立。"""
    stats = compress_extractive("q", [], token_budget=100)
    assert stats.candidates == [] and stats.tokens_before == 0 and stats.ratio == 1.0


# ---------- apply_postprocess 的接线与顺序 ----------

def test_apply_postprocess_mmr_only_on_rerank_versions():
    """MMR 只对 rerank 档生效（RRF 分 ~0.016 量级，与重排分不同量纲，混用会让 λ 失去意义）。

    注意 `apply_postprocess` **不做 top-k 截断**——那是检索阶段（`_retrieve`/Java `retrieveByVersion`）
    的职责，后处理只可能在 MMR/压缩里减内容。所以 hybrid 档这里应当**原样返回**。
    """
    a = _cand(1, "报表凭证发票", 0.9)
    b = _cand(2, "报表凭证发票", 0.8)
    c = _cand(3, "年假申请流程", 0.7)
    # hybrid 档：MMR 不生效 → 原序原样（含第 3 条）
    out_hybrid, _ = apply_postprocess([a, b, c], query="报表", top_k=2, token_budget=1500,
                                      version="hybrid", mmr_enabled=True, mmr_lambda=0.7,
                                      compression_mode="none")
    assert [x.id for x in out_hybrid] == [1, 2, 3]
    # hybrid-rerank 档：MMR 生效 → 近重复被换成不重复的候选
    out_rr, _ = apply_postprocess([a, b, c], query="报表", top_k=2, token_budget=1500,
                                  version="hybrid-rerank", mmr_enabled=True, mmr_lambda=0.7,
                                  compression_mode="none")
    assert [x.id for x in out_rr] == [1, 3]


def test_apply_postprocess_rejects_unknown_compression_mode():
    """未知压缩档响亮失败——不静默按 none 跑（那会让报告标着一个档位、实际按另一个跑）。"""
    with pytest.raises(ValueError, match="非法 compression.mode"):
        apply_postprocess([_cand(1, "甲")], query="甲", top_k=5, token_budget=100,
                          version="hybrid", mmr_enabled=False, mmr_lambda=0.7,
                          compression_mode="llm")


# ---------- 管线端到端接线 ----------

def test_pipeline_returns_compression_stats(monkeypatch):
    """压缩开启时响应要带 compression（Java 侧拿它填评测报告的压缩三列）。"""
    monkeypatch.setattr(pipeline, "_retrieve", lambda *a, **k: [_cand(1, "报销凭证发票付款记录。" * 5)])
    req = RagQueryRequest(knowledgeBaseIds=[1], question="报销凭证", version="hybrid",
                          generate=False, compressionMode="extractive", tokenBudget=50,
                          modelExecution=ModelExecution())
    resp = pipeline.run_query(req)
    assert resp.outcome == "success"
    assert resp.compression is not None
    assert resp.compression.mode == "extractive"
    assert resp.compression.tokensAfter <= 50
    assert "compression" in [s.stage for s in resp.stages]


def test_pipeline_no_compression_stage_when_mode_none(monkeypatch):
    """不压缩就不记 compression 阶段——与 Java 侧一致，否则两侧阶段名单对不齐。"""
    monkeypatch.setattr(pipeline, "_retrieve", lambda *a, **k: [_cand(1, "甲")])
    resp = pipeline.run_query(RagQueryRequest(knowledgeBaseIds=[1], question="q",
                                              version="hybrid", generate=False,
                                              modelExecution=ModelExecution()))
    assert resp.outcome == "success"
    assert resp.compression is None
    assert "compression" not in [s.stage for s in resp.stages]
