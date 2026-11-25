"""验证问答阶段探针及模型输入容量整理的观测输出。"""

import importlib.util
from io import BytesIO
from pathlib import Path
from types import SimpleNamespace
from urllib.error import HTTPError

import pytest

_path = Path(__file__).resolve().parents[2] / "scripts/rag-pipeline-validation/compare_retrieval_engines.py"
_spec = importlib.util.spec_from_file_location("rag_engine_probe", _path)
probe = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(probe)


class FakeBackend:
    def __init__(self, *, source_kb=1, empty=False, actual_engine=None):
        """创建实例并初始化 `traces`、`source_kb`、`empty`、`actual_engine`，供后续方法共享。输入为 `source_kb`、`empty`、`actual_engine`。"""
        self.traces = []
        self.source_kb = source_kb
        self.empty = empty
        self.actual_engine = actual_engine
        self.calls = []

    def get(self, path, parameters=None):
        """返回 包含 `code`、`data` 的字典。输入为 `path`、`parameters`。"""
        self.calls.append((path, parameters))
        if path == "/knowledge-base/list":
            return {"code": 200, "data": [{"id": 1}]}
        if path == "/observability/trace":
            return {"code": 200, "data": list(self.traces)}
        if path.startswith("/observability/trace/"):
            return {"code": 200, "data": next(
                trace for trace in self.traces if trace["traceId"] == path.rsplit("/", 1)[1])}
        if path == "/knowledge/search":
            engine = parameters["pipelineEngine"]
            self.traces.append({
                "traceId": f"{len(self.traces) + 1:032x}", "entrypoint": "search",
                "originalQuery": parameters["query"].strip(),
                "pipelineEngine": self.actual_engine or engine,
                "retrievalVersion": "hybrid-rerank", "outcome": "success", "reasonCode": "none",
                "spans": [{"stage": "VECTOR_RETRIEVAL", "durationMs": 10, "success": True}],
            })
            return [] if self.empty else [{"id": 101, "documentId": 10,
                "knowledgeBaseId": self.source_kb, "chunkIndex": 0,
                "content": "private-source-content", "distance": 0.12}]
        raise AssertionError(f"unexpected path: {path}")


@pytest.mark.parametrize("base", [
    "http://example.com:8080", "https://127.0.0.1:8080", "http://127.0.0.1",
    "http://user:token@127.0.0.1:8080", "http://127.0.0.1:8080/path",
    "http://127.0.0.1:8080?token=secret", "http://127.0.0.1:8080#fragment",
    "http://127.0.0.1:99999", "http://127.0.0.1:0",
])
def test_probe_credential_destination_is_direct_loopback_only(base):
    """验证用例触发 `probe.ProbeError` 异常。"""
    with pytest.raises(probe.ProbeError):
        probe.Client(base, "test-only-token", 1)


def test_probe_refuses_credential_redirects():
    """验证用例触发 `probe.ProbeError` 异常。"""
    with pytest.raises(probe.ProbeError, match="redirect refused"):
        probe.NoRedirect().redirect_request(None, None, 302, "found", {}, "http://example.com")


def test_probe_http_errors_never_print_token_or_remote_body():
    """验证用例触发 `probe.ProbeError` 异常，并检查 `str(caught.value)` 等于 `'backend returned HTTP 403 for /knowledge-base/list'`，且 `'private' not in str(caught.value) and 'test-only-token' not in str(caught.value)` 成立。"""
    client = probe.Client("http://127.0.0.1:8080", "test-only-token", 1)

    def fail(*args, **kwargs):
        """触发 `HTTPError`，交由上游处理。输入为 `args`、`kwargs`。"""
        raise HTTPError("http://private/token", 403, "private-secret-detail", {},
                        BytesIO(b"private-secret-detail"))

    client.opener = SimpleNamespace(open=fail)
    with pytest.raises(probe.ProbeError) as caught:
        client.get("/knowledge-base/list")
    assert str(caught.value) == "backend returned HTTP 403 for /knowledge-base/list"
    assert "private" not in str(caught.value) and "test-only-token" not in str(caught.value)


def test_probe_verifies_actual_trace_and_reports_hashes_without_source_text():
    """验证 `report['passed']` 成立，且 `[run['pipelineEngine'] for run in report['runs']]` 等于 `['fixed', 'langchain']`。"""
    client = FakeBackend()
    report = probe.compare(client, "报销额度？", 5)
    assert report["passed"]
    assert [run["pipelineEngine"] for run in report["runs"]] == ["fixed", "langchain"]
    assert report["sameSourcesAndContent"] and report["sameRetrievalStrategy"]
    assert "private-source-content" not in str(report)
    assert all(path.startswith(("/knowledge/search", "/knowledge-base/list", "/observability/trace"))
               for path, _ in client.calls)


def test_probe_empty_result_does_not_pass_live_retrieval():
    """验证 `report['sameSourcesAndContent'] and (not report['passed'])` 成立。"""
    report = probe.compare(FakeBackend(empty=True), "报销额度？", 5)
    assert report["sameSourcesAndContent"] and not report["passed"]


def test_probe_outside_authorized_scope_is_rejected():
    """验证用例触发 `probe.ProbeError` 异常。"""
    with pytest.raises(probe.ProbeError, match="outside the observed authorized scope"):
        probe.compare(FakeBackend(source_kb=2), "报销额度？", 5)


def test_probe_cannot_pass_if_server_ignores_requested_engine():
    """验证用例触发 `probe.ProbeError` 异常。"""
    with pytest.raises(probe.ProbeError, match="actual pipeline"):
        probe.compare(FakeBackend(actual_engine="fixed"), "报销额度？", 5)
