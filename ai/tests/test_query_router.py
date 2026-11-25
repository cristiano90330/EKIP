"""验证改写与检索接口的返回结构和降级处理。"""

import unittest
from unittest import mock

from ai.app.llm.guard import DegradedResult
from ai.app.llm.chat_result import ChatResult
from ai.app.query.router import rewrite
from ai.app.query.schema import ChatMessage, RewriteRequest

QUESTION = "它的检索速度怎么样？"


def _req(history: bool = True) -> RewriteRequest:
    """向 `test_no_history_is_not_degraded` 提供预设数据。返回 `RewriteRequest` 的结果。

    输入为 `history: bool`。
    上游为 `test_guard_degraded_marks_degraded`，下游调用 `ai.app.query.schema.ChatMessage`。
    """
    return RewriteRequest(
        question=QUESTION,
        history=[ChatMessage(role="user", content="Qwen3-Embedding-4B 支持多少维？")] if history else [],
    )


class TestRewriteDegraded(unittest.TestCase):

    def test_no_history_is_not_degraded(self):
        """无历史走双重短路：不发 LLM 请求，自然也不算降级。"""
        with mock.patch("ai.app.query.router.gate") as gate:
            resp = rewrite(_req(history=False))
        self.assertFalse(resp.degraded)
        self.assertEqual(resp.degradeReason, "")
        self.assertFalse(resp.rewritten)
        gate.call.assert_not_called()

    @mock.patch("ai.app.query.router.gate")
    def test_guard_degraded_marks_degraded(self, gate):
        """通过 `mock.patch` 核对本用例构造的状态。"""
        gate.call.return_value = DegradedResult(None, 0, 0, reason="circuit open")
        resp = rewrite(_req())
        self.assertTrue(resp.degraded)
        self.assertEqual(resp.degradeReason, "guard_degraded")
        self.assertEqual(resp.rewrittenQuery, QUESTION)   # 保守回退原样
        self.assertFalse(resp.rewritten)

    @mock.patch("ai.app.query.router.gate")
    def test_unparsable_output_marks_degraded(self, gate):
        """guard 没判降级、但输出压根解析不出 JSON —— 这一步同样没生效，不能算正常。"""
        gate.call.return_value = ChatResult(content="抱歉，我不太确定该怎么改", prompt_tokens=5, completion_tokens=5)
        resp = rewrite(_req())
        self.assertTrue(resp.degraded)
        self.assertEqual(resp.degradeReason, "guard_unparsable")
        self.assertEqual(resp.rewrittenQuery, QUESTION)

    @mock.patch("ai.app.query.router.gate")
    def test_normal_rewrite_not_degraded(self, gate):
        """通过 `mock.patch` 核对本用例构造的状态。"""
        gate.call.return_value = ChatResult(
            content='{"rewrittenQuery": "Qwen3-Embedding-4B 的检索速度怎么样？", '
                    '"rewritten": true, "reason": "补全指代"}',
            prompt_tokens=20, completion_tokens=10)
        resp = rewrite(_req())
        self.assertFalse(resp.degraded)
        self.assertEqual(resp.degradeReason, "")
        self.assertTrue(resp.rewritten)
        self.assertEqual(resp.rewrittenQuery, "Qwen3-Embedding-4B 的检索速度怎么样？")

    @mock.patch("ai.app.query.router.gate")
    def test_model_says_no_rewrite_needed_is_not_degraded(self, gate):
        """模型自判「原问题已完整」：rewritten=False 但不是降级 —— 这两个 bool 必须正交。"""
        gate.call.return_value = ChatResult(
            content='{"rewrittenQuery": "pgvector 支持哪些索引类型？", '
                    '"rewritten": false, "reason": "问题本身完整独立"}',
            prompt_tokens=20, completion_tokens=10)
        resp = rewrite(_req())
        self.assertFalse(resp.degraded)
        self.assertFalse(resp.rewritten)


if __name__ == "__main__":
    unittest.main()
