"""聚合 PostgreSQL、对象存储及模型观测，输出入库、检索和回答能力的就绪快照。"""

import threading
from concurrent.futures import Future, ThreadPoolExecutor
from dataclasses import dataclass, field
from time import monotonic

from ai.app.config import (
    MINIO_ACCESS_KEY,
    MINIO_BUCKET,
    MINIO_ENDPOINT,
    MINIO_SECRET_KEY,
    RAG_EVIDENCE_POLICY_VERSION,
    RAG_EVIDENCE_MODE,
)

PROBE_TIMEOUT_MS = 1500

PROBE_INTERVAL_SECONDS = 15.0
PROBE_MAX_AGE_SECONDS = 60.0

# 连续多少次探针超时才把能力降级。一次超时只说明「这一轮没测出来」（GPU 换模型、模型冷启动），
# 连续多次才说明「它确实一直慢」。约等于 45s 的容忍窗口。
PROBE_TIMEOUT_ESCALATE_AFTER = 3


@dataclass(frozen=True)
class ProbeResult:
    status: str
    reason: str = "none"
    details: dict = field(default_factory=dict)


@dataclass(frozen=True)
class Observation:
    """一次观测：状态 + 原因码 + 发生时刻 + 附加字段 + 证据来源。"""

    status: str
    reason: str
    at: float
    details: dict = field(default_factory=dict)
    source: str = "probe"


def _probe_database() -> None:
    """在专用探针预算内执行一次只读查询，失败时让刷新器按依赖原因分类。

    下游调用 `ai.app.rag.deadline.query_deadline`、`ai.app.retrieval.db.query_rows`。
    """
    from ai.app.rag.deadline import query_deadline
    from ai.app.retrieval.db import query_rows

    with query_deadline(PROBE_TIMEOUT_MS):
        query_rows("SELECT 1 FROM document_chunk LIMIT 0")


def _probe_minio() -> None:
    """检查应用配置的桶是否可访问，网络、权限或对象配置异常由探针刷新器收敛为观测状态。"""
    from minio import Minio
    from urllib3 import PoolManager, Timeout

    client = Minio(
        MINIO_ENDPOINT,
        access_key=MINIO_ACCESS_KEY,
        secret_key=MINIO_SECRET_KEY,
        secure=False,
        http_client=PoolManager(timeout=Timeout(connect=0.75, read=0.75), retries=0),
    )
    if not client.bucket_exists(MINIO_BUCKET):
        raise RuntimeError("configured bucket is unavailable")


def _probe_functions() -> dict[str, object]:
    """返回后台就绪提升器需要调度的数据库及对象存储探针。

    上游为 `__init__`。
    """
    return {
        "database": _probe_database,
        "minio": _probe_minio,
    }


def _unknown(reason: str = "probe_not_run") -> dict:
    """为尚无近期观测的依赖构造带原因码的未知状态。

    上游为 `_observed`。
    """
    return {"status": "unknown", "ready": None, "reasonCode": reason}


# 根据给定依赖名和探针异常区分超时与不可用，并生成带时间戳的观测。
# 上游为 `_refresh`，下游调用 `ai.app.rag.deadline.is_timeout_exception`。
def _classify_probe_error(name: str, exc: BaseException) -> Observation:
    """探针失败分两档：慢（degraded）与不可达（not_ready）。

    这个区分是本模块最重要的口径之一：``embedding_unavailable`` 会让运维去查 Ollama 是否挂了，
    而实际原因可能只是探针在模型换入换出时超时。慢不等于坏。
    """
    from ai.app.rag.deadline import is_timeout_exception

    if is_timeout_exception(exc):
        return Observation("degraded", f"{name}_probe_timeout", monotonic(), {}, "probe")
    return Observation("not_ready", f"{name}_unavailable", monotonic(), {}, "probe")


class ReadinessService:
    """Run one bounded probe per dependency and reuse recent results between polls."""

    def __init__(self, probes=None, *, interval_s=PROBE_INTERVAL_SECONDS,
                 max_age_s=PROBE_MAX_AGE_SECONDS):
        """初始化依赖探针、观察时限、后台线程和最近观测缓存，供 start 及 snapshot 使用。

        下游调用 `_probe_functions`。
        """
        self._probes = _probe_functions() if probes is None else probes
        self._interval_s = interval_s
        self._max_age_s = max_age_s
        self._max_workers = max(1, len(self._probes))
        self._executor = ThreadPoolExecutor(max_workers=self._max_workers,
                                            thread_name_prefix="ekip-readiness")
        self._lock = threading.Lock()
        self._results: dict[str, Observation] = {}
        self._timeout_streak: dict[str, int] = {}
        self._inflight: dict[str, Future] = {}
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None

    def start(self) -> None:
        """幂等启动就绪刷新线程，在应用启动时开始读取依赖状态。"""
        with self._lock:
            if self._thread is not None and self._thread.is_alive():
                return
            if getattr(self._executor, "_shutdown", False):
                self._executor = ThreadPoolExecutor(max_workers=self._max_workers,
                                                    thread_name_prefix="ekip-readiness")
            self._stop.clear()
            self._thread = threading.Thread(target=self._run, name="ekip-readiness-refresh", daemon=True)
            self._thread.start()

    def stop(self) -> None:
        """通知刷新线程退出并关闭探针线程池，供 FastAPI 生命周期结束时调用。"""
        self._stop.set()
        thread = self._thread
        if thread is not None:
            thread.join(timeout=0.1)
        self._executor.shutdown(wait=False, cancel_futures=True)

    # 写入业务路径主动产生的依赖观测及原因，在成功时重置该依赖的连续探针超时计数。
    def observe(self, name: str, status: str, reason: str = "none", details=None) -> None:
        """记录一次**真实流量**观测（后写覆盖）。

        Embedding / rerank / 生成的成功都是「这条链路此刻确实通着」的直接证据，比后台探针更能
        代表用户实际会遇到的状态。只回传成功、失败交给探针发现：单次请求失败可能只是超时或
        单候选异常，让它去翻转整条能力状态会造成快照抖动。
        """
        if name not in self._probes:
            return
        with self._lock:
            self._results[name] = Observation(status, reason, monotonic(), details or {}, "traffic")
            # 真实请求成功一次就证明它此刻是活的：清零「连续超时」计数，别让旧超时继续累积
            if status == "ready":
                self._timeout_streak[name] = 0

    def _run(self) -> None:
        """在启用期间按固定间隔刷新探针，并让一轮刷新异常不影响后续周期。

        下游调用 `_refresh`。
        """
        while not self._stop.is_set():
            try:
                self._refresh()
            except Exception:  # noqa: BLE001 - 刷新线程不能因单轮异常永久停摆
                pass
            self._stop.wait(self._interval_s)

    def _refresh(self) -> None:
        """在一个刷新周期回收已完成探针并为缺少在途工作的依赖提交新探针。

        上游为 `_run`，下游调用 `_record_probe_outcome`、`_classify_probe_error`。
        """
        with self._lock:
            for name, future in list(self._inflight.items()):
                if not future.done():
                    continue
                del self._inflight[name]
                try:
                    outcome = future.result()
                except Exception as exc:  # noqa: BLE001 - 只有稳定的原因码能离开这个边界
                    self._record_probe_outcome(name, _classify_probe_error(name, exc))
                else:
                    if isinstance(outcome, ProbeResult):
                        self._record_probe_outcome(name, Observation(
                            outcome.status, outcome.reason, monotonic(), outcome.details, "probe"))
                    else:
                        self._record_probe_outcome(
                            name, Observation("ready", "none", monotonic(), {}, "probe"))

            # 已占用的探针槽位继续等待完成，避免慢依赖让每轮定时刷新累积新的探针工作。
            for name, probe in self._probes.items():
                if name not in self._inflight:
                    self._inflight[name] = self._executor.submit(probe)

    def _record_probe_outcome(self, name: str, outcome: Observation) -> None:
        """在调用方持锁时保存依赖探针结果。单次超时保留此前可用状态及原观测时间，连续超时可降级，明确故障即时记录。

        上游为 `_refresh`。
        """
        previous = self._results.get(name)
        if not outcome.reason.endswith("_probe_timeout"):
            self._timeout_streak[name] = 0
            self._results[name] = outcome
            return

        streak = self._timeout_streak.get(name, 0) + 1
        self._timeout_streak[name] = streak
        if previous is None:
            status = "degraded" if streak >= PROBE_TIMEOUT_ESCALATE_AFTER else "unknown"
            self._results[name] = Observation(status, outcome.reason, monotonic(), {}, "probe")
            return
        if previous.status == "ready" and streak < PROBE_TIMEOUT_ESCALATE_AFTER:
            # 保留上一次观测的时刻：不把旧证据刷成新的，过期仍会如实报 *_observation_stale
            self._results[name] = Observation(
                previous.status, outcome.reason, previous.at, previous.details, previous.source)
            return
        self._results[name] = outcome

    def _observed(self, name: str) -> dict:
        """读取指定依赖的最近状态，区分从未观测及观测过期，返回快照字段。

        上游为 `snapshot`，下游调用 `_unknown`。
        """
        with self._lock:
            value = self._results.get(name)
        if value is None:
            return _unknown()
        if monotonic() - value.at > self._max_age_s:
            # 过期只是「没新证据」，不是「坏了」：保留最后一次状态，用原因码标明它已陈旧。
            return {"status": value.status, "ready": value.status == "ready",
                    "reasonCode": f"{name}_observation_stale", **value.details}
        return {"status": value.status, "ready": value.status == "ready",
                "reasonCode": value.reason, **value.details}

    def snapshot(self) -> dict:
        """汇总当前依赖观测和模型能力，返回 /ready 需要的入库、检索和回答状态。

        下游调用 `_observed`、`ai.app.llm.guard.gate.readiness`。
        """
        from ai.app.llm.guard import gate

        database = self._observed("database")
        embedding = gate.readiness("embedding")
        embedding.setdefault("ready", None if embedding["status"] == "unknown" else embedding["status"] == "ready")
        minio = self._observed("minio")
        search = _combine(database, embedding, reason="search_dependency_unavailable")
        ingest = _combine(database, minio, embedding, reason="ingest_dependency_unavailable")
        rerank = self._rerank_capability()
        evidence_gate = self._evidence_gate(rerank, embedding)
        generation = gate.readiness("generate")
        answer = _combine(search, evidence_gate, generation, reason="answer_dependency_unavailable")
        capabilities = {
            "ingest": ingest,
            "search": search,
            "answer": answer,
            "evidenceGate": evidence_gate,
            "rerank": rerank,
        }
        overall = _overall_status(ingest, search, answer)
        from ai.app.models.runtime import runtime
        return {"status": overall, "capabilities": capabilities, "models":runtime.observations()}

    def _rerank_capability(self) -> dict:
        """从重排用途的模型状态派生就绪、原因与时间字段，供问答能力聚合。

        上游为 `snapshot`，下游调用 `ai.app.models.runtime.runtime.readiness`。
        """
        from ai.app.models.runtime import runtime
        result = runtime.readiness("rerank")
        return result | {"ready": None if result["status"] == "unknown" else result["status"] == "ready",
                         "scorer": "unknown" if result["status"] == "unknown" else "model"}

    @staticmethod
    def _evidence_gate(rerank, embedding) -> dict:
        """按当前模型执行快照及证据模式构造证据准入能力，缺失阈值或模型选择时明确原因。

        上游为 `snapshot`，下游调用 `ai.app.models.context.current_execution`、`_combine`。
        """
        from ai.app.models.context import current_execution, evidence_threshold
        from ai.app.llm.guard import gate
        if current_execution() is None:
            return {"status":"unknown","ready":None,"reasonCode":"model_not_selected",
                    "policyVersion":RAG_EVIDENCE_POLICY_VERSION,"scorer":"unknown"}
        if RAG_EVIDENCE_MODE == "support":
            from ai.app.rag.evidence_support import POLICY_VERSION, readiness as review_readiness
            observed = _combine(gate.readiness("evidence_support"), review_readiness(),
                                reason="evidence_support_unavailable")
            return {**observed, "scorer": "evidence_support", "policyVersion": POLICY_VERSION}
        threshold = evidence_threshold("model")
        if threshold is None:
            return {"status":"not_ready","ready":False,"reasonCode":"threshold_not_configured",
                    "policyVersion":RAG_EVIDENCE_POLICY_VERSION,"scorer":"model"}
        return {**rerank,"policyVersion":RAG_EVIDENCE_POLICY_VERSION,"scorer":"model"}


def _combine(*dependencies, reason: str) -> dict:
    """把一组依赖状态收敛成支持指定操作的能力判定，保留未知或失败原因。

    上游为 `snapshot`、`_evidence_gate`。
    """
    precedence = {"ready": 0, "degraded": 1, "unknown": 2, "not_ready": 3}
    failed = [dependency for dependency in dependencies if dependency["status"] != "ready"]
    if failed:
        dependency = max(failed, key=lambda item: precedence.get(item["status"], 3))
        status = dependency["status"]
        return {"status": status, "ready": None if status == "unknown" else False,
                "reasonCode": dependency.get("reasonCode") or reason}
    return {"status": "ready", "ready": True, "reasonCode": "none"}


def _overall_status(ingest: dict, search: dict, answer: dict) -> str:
    """合并入库、检索及回答能力的状态，返回服务级就绪、降级、未知或不可用。

    上游为 `snapshot`。
    """
    statuses = [capability["status"] for capability in (ingest, search, answer)]
    if all(status == "ready" for status in statuses):
        return "ready"
    if all(status == "unknown" for status in statuses):
        return "unknown"
    if any(status == "ready" for status in statuses):
        return "degraded"
    return "not_ready"


readiness_service = ReadinessService()
