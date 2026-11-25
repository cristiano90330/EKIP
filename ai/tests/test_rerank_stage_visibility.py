"""验证重排阶段及其分数来源在链路记录中的可见性。"""

import unittest
from unittest import mock

from ai.app.observability.stage_trace import collecting
from ai.app.rag.pipeline import _rerank_top
from ai.app.rerank.schema import RerankResponse
from ai.app.retrieval.retriever import Candidate


def _candidate(cid):
    """向 `test_degraded_rerank_is_recorded_on_stage` 提供预设数据。返回 `Candidate` 的结果。

    输入为 `cid`。
    上游为 `test_healthy_rerank_records_no_degradation`。
    """
    return Candidate(id=cid, document_id=10, knowledge_base_id=1, chunk_index=cid,
                     content=f"c{cid}")


class TestRerankStageVisibility(unittest.TestCase):
    def test_degraded_rerank_is_recorded_on_stage(self):
        """通过 `self.assertTrue` 核对本用例构造的状态。"""
        resp = RerankResponse(scores=[1.0], ids=[1], degraded=True,
                              degradeReason="server_unreachable", scorer="embed_sim")
        with mock.patch("ai.app.rag.pipeline.rerank", return_value=resp):
            with collecting() as collector:
                _rerank_top([_candidate(1)], "q", 1)
        rec = [r for r in collector.records if r.stage == "rerank"][0]
        self.assertTrue(rec.degraded)
        self.assertTrue(rec.success)      # 只标降级，不改 success 语义（ruling R2）

    def test_healthy_rerank_records_no_degradation(self):
        """通过 `self.assertEqual` 核对本用例构造的状态。"""
        resp = RerankResponse(scores=[0.9], ids=[1], degraded=False,
                              degradeReason="", scorer="model")
        with mock.patch("ai.app.rag.pipeline.rerank", return_value=resp):
            with collecting() as collector:
                _rerank_top([_candidate(1)], "q", 1)
        rec = [r for r in collector.records if r.stage == "rerank"][0]
        self.assertFalse(rec.degraded)
        self.assertEqual(rec.error, "")


if __name__ == "__main__":
    unittest.main()
