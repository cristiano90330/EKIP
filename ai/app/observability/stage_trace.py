"""记录各处理阶段的耗时、错误、Token 用量和追踪数据。"""

import logging
import time
from contextlib import contextmanager
from contextvars import ContextVar
from dataclasses import dataclass, field
from typing import Optional

from ai.app.config import (
    OTEL_ENABLED,
    OTEL_EXPORTER_OTLP_ENDPOINT,
    OTEL_SERVICE_NAME,
    RAG_STAGE_LOG_ENABLED,
)

logger = logging.getLogger("ekip.stage")

# 阶段名到 Java Trace 枚举名的映射。
STAGE_TO_TRACE_STAGE = {
    "rewrite": "QUERY_REWRITE",
    "vector_retrieval": "VECTOR_RETRIEVAL",
    "keyword_retrieval": "KEYWORD_RETRIEVAL",
    "fusion": "FUSION",
    "rerank": "RERANK",
    "evidence_gate": "EVIDENCE_GATE",
    "compression": "COMPRESSION",
    "context_organize": "COMPRESSION",
    "generate": "LLM_GENERATION",
}


@dataclass
class StageRecord:
    """一个阶段的一次执行记录。字段刻意对齐 Java `RagSpan`，便于两侧同表对比。"""
    stage: str
    duration_ms: int
    success: bool = True
    error: str = ""
    # 该阶段是否降级（如某路召回超时按空结果参与 RRF）——对齐 RagSpan.degraded
    degraded: bool = False
    # LLM 阶段的 token 用量（rewrite / generate）；非 LLM 阶段为 None
    total_tokens: Optional[int] = None


@dataclass
class _Pending:
    """`stage()` 上下文里可写的字段（token 只能在执行后才能知道，故先给句柄再回填）。"""
    stage: str
    total_tokens: Optional[int] = None
    degraded: bool = False
    error: str = ""


class StageCollector:
    """一次请求的阶段记录收集器（挂在 contextvar 上，请求结束即丢弃）。"""

    def __init__(self):
        """初始化请求的阶段记录列表，供流水线阶段按发生顺序追加。"""
        self.records: list[StageRecord] = []

    def add(self, rec: StageRecord) -> None:
        """将一个完成的阶段记录追加到当前收集器，供响应和摘要读取。"""
        self.records.append(rec)

    def summary(self) -> str:
        """把请求的阶段、耗时和失败信息拼成单行诊断摘要。"""
        parts = []
        for r in self.records:
            flag = ""
            if not r.success:
                flag += "!"
            if r.degraded:
                flag += "~"
            tok = f"/{r.total_tokens}t" if r.total_tokens else ""
            parts.append(f"{r.stage}={r.duration_ms}ms{tok}{flag}")
        return " ".join(parts)


_current: ContextVar[Optional[StageCollector]] = ContextVar("ekip_stage_collector", default=None)


def current() -> Optional[StageCollector]:
    """读取当前上下文绑定的阶段收集器，未启用收集时返回 None。"""
    return _current.get()


@contextmanager
def collecting():
    """为当前请求绑定独立阶段收集器，退出时恢复调用外层的记录上下文。

    上游为 `ai.app.rag.pipeline.run_query`、`ai.app.rag.pipeline._stream_events`。
    """
    collector = StageCollector()
    token = _current.set(collector)
    try:
        yield collector
    finally:
        _current.reset(token)


def record(stage_name: str, duration_ms: float, *, success: bool = True, error: str = "",
           degraded: bool = False, total_tokens: Optional[int] = None) -> StageRecord:
    """根据阶段名、耗时与结果构造 StageRecord，写入可用的当前收集器和追踪系统。

    下游调用 `_emit_otel`。
    """
    rec = StageRecord(
        stage=stage_name,
        duration_ms=int(duration_ms),
        success=success,
        error=error[:200],
        degraded=degraded,
        total_tokens=total_tokens,
    )
    collector = _current.get()
    if collector is not None:
        collector.add(rec)
    _emit_otel(rec)
    return rec


# 测量代码块的耗时并汇总手工标注的调用与用量信息，异常退出也记录失败阶段。
# 上游为 `ai.app.rag.pipeline._rerank_top`、`ai.app.rag.pipeline._fuse_fanout`。
@contextmanager
def stage(stage_name: str):
    """给一个阶段计时并记录。用法：

        with stage("rewrite") as pend:
            rw = rewrite(...)
            pend.total_tokens = rw.tokenCost
    """
    pend = _Pending(stage=stage_name)
    t0 = time.perf_counter()
    try:
        yield pend
    except Exception as e:  # noqa: BLE001 —— 记录失败后原样抛出，不改调用方语义
        record(stage_name, (time.perf_counter() - t0) * 1000.0,
               success=False, error=f"{type(e).__name__}: {e}",
               degraded=pend.degraded, total_tokens=pend.total_tokens)
        raise
    record(stage_name, (time.perf_counter() - t0) * 1000.0,
           success=pend.error == "", error=pend.error,
           degraded=pend.degraded, total_tokens=pend.total_tokens)


def log_summary(collector: Optional[StageCollector], *, question: str = "", extra: str = "") -> None:
    """在启用阶段日志且拥有收集器时写出轻量诊断摘要。

    上游为 `ai.app.rag.pipeline._run_development_test_stream`、`ai.app.rag.pipeline.run_query`。
    """
    if not RAG_STAGE_LOG_ENABLED or collector is None:
        return
    logger.info("rag.stages %s | q=%s%s", collector.summary(), question[:40], extra)


# --------- Optional OTel export; response stages and logs remain available on failure ---------

_tracer = None
_tracer_ready = False


def _get_tracer():
    """首次按环境配置初始化 OpenTelemetry tracer，配置不启用或初始化失败时返回空值。

    上游为 `_emit_otel`。
    """
    global _tracer, _tracer_ready
    if _tracer_ready:
        return _tracer
    _tracer_ready = True                      # 只判定一次：import 失败不该每次请求都重试
    if not OTEL_ENABLED:
        return None
    try:
        from opentelemetry import trace
        from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
        from opentelemetry.sdk.resources import Resource
        from opentelemetry.sdk.trace import TracerProvider
        from opentelemetry.sdk.trace.export import BatchSpanProcessor
    except ImportError as e:
        logger.warning(
            "OTEL_ENABLED=true 但 OpenTelemetry 依赖缺失（%s）→ 阶段埋点退化为「响应 stages[] + 结构化日志」，"
            "功能不受影响。装依赖：pip install opentelemetry-sdk opentelemetry-exporter-otlp-proto-http", e,
        )
        return None
    try:
        provider = TracerProvider(resource=Resource.create({"service.name": OTEL_SERVICE_NAME}))
        provider.add_span_processor(
            BatchSpanProcessor(OTLPSpanExporter(endpoint=OTEL_EXPORTER_OTLP_ENDPOINT))
        )
        trace.set_tracer_provider(provider)
        _tracer = trace.get_tracer("ekip.rag")
        logger.info("OTel 阶段埋点已启用：service=%s endpoint=%s",
                    OTEL_SERVICE_NAME, OTEL_EXPORTER_OTLP_ENDPOINT)
    except Exception as e:  # noqa: BLE001 —— exporter 初始化失败不该让服务起不来
        logger.warning("OTel 初始化失败（%s）→ 退化为「响应 stages[] + 结构化日志」", e)
        _tracer = None
    return _tracer


def _emit_otel(rec: StageRecord) -> None:
    """把阶段耗时、结果和用量转成追踪 span，未配置追踪时结束。

    上游为 `record`，下游调用 `_get_tracer`。
    """
    tracer = _get_tracer()
    if tracer is None:
        return
    try:
        # 用显式 start/end 而不是 start_as_current_span：后者量到的是「进入/离开 with」的耗时（≈0），
        # 而这里的 duration 是阶段真实耗时，必须原样写进 span。
        end_ns = time.time_ns()
        start_ns = end_ns - rec.duration_ms * 1_000_000
        span = tracer.start_span(rec.stage, start_time=start_ns)
        span.set_attribute("ekip.stage", rec.stage)
        span.set_attribute("ekip.trace_stage", STAGE_TO_TRACE_STAGE.get(rec.stage, rec.stage))
        span.set_attribute("ekip.duration_ms", rec.duration_ms)
        span.set_attribute("ekip.success", rec.success)
        span.set_attribute("ekip.degraded", rec.degraded)
        if rec.error:
            span.set_attribute("ekip.error", rec.error)
        if rec.total_tokens is not None:
            span.set_attribute("ekip.total_tokens", rec.total_tokens)
        span.end(end_time=end_ns)
    except Exception as e:  # noqa: BLE001 —— 埋点绝不能影响主链路
        logger.debug("OTel span 上报失败（忽略）：%s", e)
