"""验证生成流的正文增量和终态结构。"""

import unittest
from unittest import mock

from ai.app.llm.guard import DegradedResult
from ai.app.llm.chat_result import ChatResult
from ai.app.rag.rag_service import RAGService
from ai.app.rag.schema import ContextItem


class TestGenerateStream(unittest.TestCase):
    def _with_gate_stream(self, items):
        """向 `test_validated_answer_delta_then_typed_done` 提供预设数据。返回 `mock.patch` 的结果。

        输入为 `items`。
        上游为 `test_degraded_yields_empty_answer_and_reason`、`test_missing_terminal_never_publishes_raw_json`。
        """
        def stream(*args, **kwargs):
            """产出供调用方迭代的结果。输入为 `args`、`kwargs`。"""
            self.assertEqual(kwargs["response_format"]["type"], "json_schema")
            yield from items

        return mock.patch("ai.app.rag.rag_service.gate.stream", side_effect=stream)

    def test_validated_answer_delta_then_typed_done(self):
        """通过 `self.assertEqual` 核对本用例构造的状态。"""
        svc = RAGService()
        content = '{"status":"success","answer":"你好"}'
        items = [content[:30], content[30:], ChatResult(content=content, prompt_tokens=3, completion_tokens=2)]
        with self._with_gate_stream(items):
            out = list(svc.generate_stream("q", [ContextItem(content="c")]))
        self.assertEqual(out[0], ("delta", "你好"))
        kind, payload = out[-1]
        self.assertEqual(kind, "done")
        self.assertEqual(payload.answer, "你好")
        self.assertEqual(payload.token_cost, 5)
        self.assertEqual(payload.outcome, "success")
        self.assertEqual(len(out), 2)

    def test_degraded_yields_empty_answer_and_reason(self):
        """通过 `self.assertEqual` 核对本用例构造的状态。"""
        svc = RAGService()
        items = [DegradedResult(None, 0, 0, reason="boom")]
        items[0].provider = "ollama"
        with self._with_gate_stream(items):
            out = list(svc.generate_stream("q", [ContextItem(content="c")]))
        kind, result = out[-1]
        self.assertEqual(kind, "done")
        self.assertEqual(result.answer, "")
        self.assertEqual(result.outcome, "dependency_unavailable")
        self.assertEqual(result.degrade_reason, "boom")
        self.assertEqual(len(out), 1)

    def test_missing_terminal_never_publishes_raw_json(self):
        """通过 `self.assertEqual` 核对本用例构造的状态。"""
        with self._with_gate_stream(['{"status":"success","answer":"半段']):
            out = list(RAGService().generate_stream("q", [ContextItem(content="c")]))
        self.assertEqual(len(out), 1)
        self.assertEqual(out[0][1].reason_code, "generation_invalid_response")

    def test_close_releases_underlying_model_stream(self):
        """通过 `self.assertEqual` 核对本用例构造的状态。"""
        closed = []

        def model(*args, **kwargs):
            """产出供调用方迭代的结果。输入为 `args`、`kwargs`。"""
            try:
                yield '{"status":"success","answer":"完整"}'
                raise TimeoutError("stream failed before terminal")
            finally:
                closed.append(True)

        with mock.patch("ai.app.rag.rag_service.gate.stream", model):
            with self.assertRaises(TimeoutError):
                list(RAGService().generate_stream("q", [ContextItem(content="c")]))
        self.assertEqual(closed, [True])


if __name__ == "__main__":
    unittest.main()
