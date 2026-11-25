"""验证阶段追踪字段、状态聚合和跨线程上下文。"""

from dataclasses import asdict
from pathlib import Path
import runpy
from unittest.mock import Mock

import pytest

from ai.app.observability import stage_trace


@pytest.fixture(autouse=True)
def isolated_tracer(monkeypatch):
    """为需要该夹具的用例准备输入和隔离状态。输入为 `monkeypatch`。"""
    monkeypatch.setattr(stage_trace, "_tracer", None)
    monkeypatch.setattr(stage_trace, "_tracer_ready", False)
    monkeypatch.setattr(stage_trace, "OTEL_ENABLED", False)
    monkeypatch.setattr(stage_trace, "RAG_STAGE_LOG_ENABLED", True)


def test_stage_fields_and_log_summary_are_fixed_without_export(caplog):
    """验证 `collector.summary()` 等于 `'rerank=12ms/7t~ generate=9ms!'`，且 `'rag.stages rerank=12ms/7t~ generate=9ms!'` 属于 `caplog.text`。"""
    with caplog.at_level("INFO", logger="ekip.stage"), stage_trace.collecting() as collector:
        stage_trace.record("rerank", 12.9, degraded=True, total_tokens=7)
        stage_trace.record("generate", 9, success=False, error="test dependency failure")
        stage_trace.log_summary(collector, question="contract question")
    assert [asdict(record) for record in collector.records] == [
        {"stage":"rerank","duration_ms":12,"success":True,"error":"","degraded":True,"total_tokens":7},
        {"stage":"generate","duration_ms":9,"success":False,"error":"test dependency failure","degraded":False,"total_tokens":None},
    ]
    assert collector.summary() == "rerank=12ms/7t~ generate=9ms!"
    assert "rag.stages rerank=12ms/7t~ generate=9ms!" in caplog.text
    assert stage_trace.current() is None


def test_collectors_do_not_leak_between_requests():
    """验证 `[record.stage for record in first.records]` 等于 `['fusion']`，且 `[record.stage for record in second.records]` 等于 `['generate']`。"""
    with stage_trace.collecting() as first:
        stage_trace.record("fusion", 1)
    with stage_trace.collecting() as second:
        stage_trace.record("generate", 2)
    assert [record.stage for record in first.records] == ["fusion"]
    assert [record.stage for record in second.records] == ["generate"]


def test_business_exception_is_not_replaced_by_trace_recording():
    """验证用例触发 `ValueError` 异常，并检查 `caught.value` 为 `failure`，且 `collector.records[0].success` 为 `False`。"""
    failure = ValueError("business failure")
    with stage_trace.collecting() as collector, pytest.raises(ValueError) as caught:
        with stage_trace.stage("generate"):
            raise failure
    assert caught.value is failure
    assert collector.records[0].success is False
    assert collector.records[0].error == "ValueError: business failure"


def test_disabled_export_never_constructs_exporter(monkeypatch):
    """验证 `len(collector.records)` 等于 `1`。"""
    from opentelemetry.exporter.otlp.proto.http import trace_exporter
    exporter = Mock(side_effect=AssertionError("must not create exporter"))
    monkeypatch.setattr(trace_exporter, "OTLPSpanExporter", exporter)
    with stage_trace.collecting() as collector:
        stage_trace.record("rewrite", 3)
    exporter.assert_not_called()
    assert len(collector.records) == 1


def test_export_failure_does_not_remove_response_stage_or_log(monkeypatch, caplog):
    """验证 `collector.records[0].success` 为 `True`，且 `'generate=5ms'` 属于 `caplog.text`。"""
    tracer = Mock()
    tracer.start_span.side_effect = RuntimeError("fake exporter unavailable")
    monkeypatch.setattr(stage_trace, "_get_tracer", lambda: tracer)
    with caplog.at_level("INFO", logger="ekip.stage"), stage_trace.collecting() as collector:
        stage_trace.record("generate", 5)
        stage_trace.log_summary(collector)
    assert collector.records[0].success is True
    assert "generate=5ms" in caplog.text


def test_exporter_initialization_failure_does_not_break_stage(monkeypatch):
    """验证 `collector.records[0].stage` 等于 `'rewrite'`，且 `stage_trace._get_tracer()` 为 `None`。"""
    from opentelemetry.exporter.otlp.proto.http import trace_exporter
    monkeypatch.setattr(stage_trace, "OTEL_ENABLED", True)
    monkeypatch.setattr(trace_exporter, "OTLPSpanExporter", Mock(side_effect=RuntimeError("fake init failure")))
    with stage_trace.collecting() as collector:
        stage_trace.record("rewrite", 8)
    assert collector.records[0].stage == "rewrite"
    assert stage_trace._get_tracer() is None


@pytest.mark.parametrize(("setting", "enabled"), [(None, False), ("true", True), ("false", False)])
def test_export_is_opt_in_while_metrics_and_stage_logs_stay_enabled(monkeypatch, setting, enabled):
    # Avoid reading either real dotenv, and don't reload shared runtime configuration.
    """验证 `values['OTEL_ENABLED']` 为 `enabled`，且 `values['METRICS_ENABLED']` 为 `True`。"""
    monkeypatch.setattr("dotenv.load_dotenv", lambda *args, **kwargs: False)
    for key in ("OTEL_ENABLED", "METRICS_ENABLED", "RAG_STAGE_LOG_ENABLED"):
        monkeypatch.delenv(key, raising=False)
    if setting is not None:
        monkeypatch.setenv("OTEL_ENABLED", setting)
    values = runpy.run_path(str(Path(__file__).resolve().parents[1] / "app/config.py"))
    assert values["OTEL_ENABLED"] is enabled
    assert values["METRICS_ENABLED"] is True
    assert values["RAG_STAGE_LOG_ENABLED"] is True
