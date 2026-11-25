# -*- coding: utf-8 -*-
"""验证 AI 路由对配置、调用、超时与未处理错误的响应协议。"""

import logging

import pytest
from fastapi import HTTPException
from fastapi.testclient import TestClient

from ai.app import main as main_module
from ai.app.parser import router as parser_module
from ai.app.parser import pipeline as parser_pipeline
from ai.tests.conftest import AUTH_HEADERS

# raise_server_exceptions=False：Starlette 的 ServerErrorMiddleware 发完 500 响应后**仍会 re-raise**，
# 默认的 TestClient 会把异常抛进测试用例里，这样就断不到响应体了。
client = TestClient(main_module.app, raise_server_exceptions=False, headers=AUTH_HEADERS)


# ---------- B1：logging 配置 ----------

def test_root_logger_configured_to_info():
    """root 必须是 INFO 且至少有一个 handler，否则 INFO 级埋点会被 lastResort 的 WARNING 门槛吞掉。"""
    root = logging.getLogger()
    assert root.level == logging.INFO
    assert root.handlers, "root logger 无 handler → 日志只能落到 logging.lastResort（门槛 WARNING）"


def test_business_loggers_have_no_own_handler():
    """业务 logger 不挂自己的 handler，只靠向 root 传播一次。

    这是「同一行不重复打印」的结构前提：一条记录只有一条 handler 路径。
    （重复打印的常见真凶是给命名空间 logger 和 root 各挂一个 handler。）
    """
    for name in ("ekip.models", "ekip.app", "ai.app.embedding.embedding_service"):
        assert logging.getLogger(name).handlers == [], f"{name} 不该有自己的 handler"
        assert logging.getLogger(name).getEffectiveLevel() == logging.INFO


def test_configure_logging_is_idempotent():
    """重复调用不叠加 handler（模块被重复 import / 运维已用 --log-config 配过 root 的情形）。"""
    root = logging.getLogger()
    before = list(root.handlers)
    main_module._configure_logging()
    main_module._configure_logging()
    assert root.handlers == before
    assert root.level == logging.INFO


# ---------- B2：全局异常处理器 ----------

_SECRET = "SECRET_INTERNAL_DETAIL_8f3a2b"


# 输入为 `monkeypatch`、`exc`。
# 上游为 `test_unhandled_exception_returns_500_without_leaking_detail`、`test_unhandled_exception_body_carries_request_id`。
def _make_route_raise(monkeypatch, exc):
    """让 /parser/object 在 chunk_blocks 这一步抛出未预期异常（该阶段不在 B3 的分类捕获范围内）。"""
    # 前两步放行（下载 no-op、解析返回空 block 列表），好让异常精确落在 chunk_blocks 上
    noop_minio = type("S", (), {"download_file": staticmethod(lambda b, o, p: None)})()
    monkeypatch.setattr(parser_module, "minio_service", noop_minio)
    monkeypatch.setattr(parser_pipeline, "_parse_by_ext", lambda ext, path: [])
    monkeypatch.setattr(parser_pipeline, "chunk_blocks", lambda *a, **k: (_ for _ in ()).throw(exc))


def test_unhandled_exception_returns_500_without_leaking_detail(monkeypatch, caplog):
    """未预期异常 → 500 + 结构化体；响应里**不含**原始异常文本；完整堆栈只进日志。"""
    caplog.set_level(logging.INFO)
    _make_route_raise(monkeypatch, RuntimeError(_SECRET))

    resp = client.post(
        "/parser/object",
        json={"documentId": 11, "objectName": "x.pdf", "bucketName": "b", "fileExtension": "pdf"},
        headers={"X-Request-ID": "rid-500-test"},
    )

    assert resp.status_code == 500
    body = resp.json()
    assert body["detail"] == "服务内部错误"
    assert _SECRET not in resp.text, "全局 handler 不得回传 str(e)（与 Java 侧 GENERIC_MESSAGE 同一条原则）"
    assert "Traceback" not in resp.text

    # 堆栈必须进日志，否则这条 500 在日志里查不到根因（原实现正是这样丢的）
    err_records = [r for r in caplog.records if r.levelno >= logging.ERROR]
    assert err_records, "未预期异常必须记 error 级日志"
    assert any(r.exc_info for r in err_records), "error 日志必须带 exc_info（完整堆栈）"
    assert _SECRET in caplog.text, "原始异常文本应出现在日志里（而不是响应里）"
    # 我们这一侧只记一条，不自己重复打堆栈。
    # 注意：Starlette 的 ServerErrorMiddleware 发完 500 响应后仍会 re-raise，真实 uvicorn 下
    # `uvicorn.error` 会另外再打一份「Exception in ASGI application」的堆栈——那是另一个 logger
    # 的另一条消息（不是同一行重复），属框架行为，不在本断言范围内。
    assert len([r for r in err_records if r.name == "ekip.app"]) == 1


def test_unhandled_exception_body_carries_request_id(monkeypatch):
    """500 响应体带 request_id，便于与 Java 侧 document_task 对账。"""
    _make_route_raise(monkeypatch, RuntimeError("boom"))
    resp = client.post(
        "/parser/object",
        json={"documentId": 12, "objectName": "x.pdf", "bucketName": "b", "fileExtension": "pdf"},
        headers={"X-Request-ID": "rid-trace-42"},
    )
    assert resp.status_code == 500
    assert resp.json()["request_id"] == "rid-trace-42"


def test_http_exception_is_not_swallowed_by_global_handler(monkeypatch):
    """B2 明确要求**不加** HTTPException handler：业务状态码与 detail 必须原样透出。

    若误加了 handler，422/503 会被兜成 500 → Java 判 TRANSIENT → 永久失败又被白重试 3 次。
    """
    monkeypatch.setattr(
        parser_module, "minio_service",
        type("S", (), {"download_file": staticmethod(lambda b, o, p: None)})(),
    )

    resp = client.post("/parser/object", json={
        "documentId": 13, "objectName": "x.exe", "bucketName": "b", "fileExtension": "exe"})
    assert resp.status_code == 422
    assert "不支持的文档类型" in resp.json()["detail"]


def test_minio_stage_treats_any_failure_as_transient_503(monkeypatch):
    """MinIO 下载阶段是**毯式** `except Exception → 503`，不像解析阶段那样给 HTTPException 开后门。

    理由：`minio_service.download_file` 只会抛 minio 自己的异常（S3Error / MaxRetryError），
    永远不会抛 HTTPException，所以这里没有需要透出的业务状态码。
    这条断言钉住的是当前实现的实际行为，改动它要同步改这里。
    """
    monkeypatch.setattr(
        parser_module, "minio_service",
        type("S", (), {"download_file": staticmethod(
            lambda b, o, p: (_ for _ in ()).throw(HTTPException(status_code=404, detail="对象不存在")))})(),
    )
    resp = client.post("/parser/object", json={
        "documentId": 14, "objectName": "gone.pdf", "bucketName": "b", "fileExtension": "pdf"})
    # 连 HTTPException(404) 也被归入 503，理由见 docstring
    assert resp.status_code == 503
    assert "对象存储不可达" in resp.json()["detail"]


@pytest.mark.parametrize("exc", [ValueError("bad"), KeyError("k"), OSError("io")])
def test_various_unexpected_types_all_become_500(monkeypatch, exc):
    """任何未预期类型都落同一个兜底，不因异常类型不同而泄露不同信息。"""
    _make_route_raise(monkeypatch, exc)
    resp = client.post("/parser/object", json={
        "documentId": 15, "objectName": "x.pdf", "bucketName": "b", "fileExtension": "pdf"})
    assert resp.status_code == 500
    assert resp.json()["detail"] == "服务内部错误"


# ---------- 3.1 P0-6：/metrics 开关接线 ----------
# 注（2026-09-30，B 类默认值生产化）：METRICS_ENABLED 的**代码默认值已改为 true**
# （config.py）。下面两个用例都 monkeypatch 显式取值，钉的是「开关两个方向都活」
# （false→404 / true→Prometheus 文本），不是默认值本身——默认值刻意不在这里断言：
# config.py 在 import 期读环境变量并加载 ai/.env、仓库根 .env，reload 式断言会随机器状态漂移。

def test_metrics_disabled_returns_404(monkeypatch):
    """METRICS_ENABLED=false（显式关闭，非默认值）→ /metrics 404。"""
    monkeypatch.setattr(main_module, "METRICS_ENABLED", False)
    resp = client.get("/metrics")
    assert resp.status_code == 404


def test_metrics_endpoint_serves_prometheus_text(monkeypatch):
    """METRICS_ENABLED=true（默认值，此处显式钉住）→ /metrics 返回 Prometheus 文本格式。

    client 默认带 X-Internal-Token（conftest.AUTH_HEADERS）——/metrics 挂在 _AUTH 下，
    无令牌是 401 而不是 200/404，「默认开」不等于对外暴露。
    """
    monkeypatch.setattr(main_module, "METRICS_ENABLED", True)
    resp = client.get("/metrics")
    assert resp.status_code == 200
    assert "text/plain" in resp.headers.get("content-type", "")
    assert "# HELP" in resp.text or "# TYPE" in resp.text
    assert "process_" in resp.text or "python_" in resp.text or "python_info" in resp.text
