"""验证依赖探针、主动观测与就绪状态的聚合方式。"""

from collections import OrderedDict
from time import monotonic

import pytest

from ai.app.models.context import model_execution
from ai.app.models.runtime import runtime
from ai.app.models.schema import ModelConfig, ModelExecution
from ai.app.observability import readiness
from ai.app.rag import evidence_support as support


@pytest.fixture
def review_model(monkeypatch):
    """为需要该夹具的用例准备输入和隔离状态。输入为 `monkeypatch`。返回 `ModelConfig` 的结果。下游调用 `ai.app.models.schema.ModelConfig`。"""
    monkeypatch.setattr(readiness, "RAG_EVIDENCE_MODE", "support")
    monkeypatch.setattr(support, "_review_health", OrderedDict(), raising=False)
    monkeypatch.setattr(runtime, "_health", OrderedDict())
    return ModelConfig(id="review-ready", type="chat", protocol="openai_chat", model="unit-model",
                       baseUrl="http://models.invalid/v1", capabilities={"json": True})


@pytest.mark.parametrize("valid", [True, False])
def test_support_readiness_uses_actual_validated_review(review_model, valid):
    """验证 `result['ready']` 为 `valid`，且 `result['status']` 等于 `'ready' if valid else 'not_ready'`。"""
    from ai.tests.test_evidence_support import candidate, decomposition, two_stage, support_reply, supported

    runtime.observe(review_model, "evidence_support", "ready")
    output = support_reply(supported()) if valid else {"content": "not JSON", "finish_reason": "stop"}
    with model_execution(ModelExecution(bindings={"evidence_support": review_model})):
        review = support.review("采购合同由谁在何时审核？", [],
                                [candidate(1, "采购合同在签署前由法务审核。")], monotonic() + 5,
                                caller=two_stage(decomp=decomposition(), adjudication=output))
        result = readiness.ReadinessService._evidence_gate({}, {})

    assert result["ready"] is valid
    assert result["status"] == ("ready" if valid else "not_ready")
    assert result["reasonCode"] == ("none" if valid else review.reason_code)
    assert result["policyVersion"] == support.POLICY_VERSION


def test_valid_refusal_proves_review_contract_ready(review_model):
    """验证 `review.state` 等于 `'rejected'`，且 `result['status'] == 'ready' and result['ready'] is True` 成立。"""
    from ai.tests.test_evidence_support import candidate, two_stage, support_reply, unsupported

    runtime.observe(review_model, "evidence_support", "ready")
    with model_execution(ModelExecution(bindings={"evidence_support": review_model})):
        review = support.review("采购合同由谁在何时审核？", [], [candidate(1, "未说明")], monotonic() + 5,
                                caller=two_stage(adjudication=support_reply(unsupported())))
        result = readiness.ReadinessService._evidence_gate({}, {})
    assert review.state == "rejected"
    assert result["status"] == "ready" and result["ready"] is True


def test_successful_transport_alone_cannot_prove_review_readiness(review_model):
    """验证 `result['status'] == 'unknown' and result['ready'] is None` 成立，且 `result['reasonCode']` 等于 `'support_review_not_observed'`。"""
    runtime.observe(review_model, "evidence_support", "ready")
    with model_execution(ModelExecution(bindings={"evidence_support": review_model})):
        result = readiness.ReadinessService._evidence_gate({}, {})
    assert result["status"] == "unknown" and result["ready"] is None
    assert result["reasonCode"] == "support_review_not_observed"


@pytest.mark.parametrize("change", [
    {"id": "other-user-model"}, {"revision": 2}, {"credentialVersion": 2}, {"apiKey": "rotated-test-only-key"},
])
def test_review_observations_are_scoped_to_immutable_model_identity(review_model, change):
    """验证 `support.readiness()['status']` 等于 `'ready'`，且 `result['status'] == 'unknown' and result['ready'] is None` 成立。"""
    with model_execution(ModelExecution(bindings={"evidence_support": review_model})):
        support._record_health(support.EvidenceSupportReview("accepted", "all_elements_supported"))
        assert support.readiness()["status"] == "ready"
    different = ModelConfig(**(review_model.model_dump() | change))
    runtime.observe(different, "evidence_support", "ready")
    with model_execution(ModelExecution(bindings={"evidence_support": different})):
        result = readiness.ReadinessService._evidence_gate({}, {})
    assert result["status"] == "unknown" and result["ready"] is None
    assert result["reasonCode"] == "support_review_not_observed"
    assert support.readiness()["reasonCode"] == "model_not_selected"


def test_review_readiness_expires_without_refreshing_model_io(review_model, monkeypatch):
    """验证 `result['status'] == 'unknown' and result['ready'] is None` 成立，且 `result['reasonCode']` 等于 `'support_review_observation_stale'`。"""
    clock = [100.0]
    monkeypatch.setattr(support, "monotonic", lambda: clock[0])
    runtime.observe(review_model, "evidence_support", "ready")
    with model_execution(ModelExecution(bindings={"evidence_support": review_model})):
        support._record_health(support.EvidenceSupportReview("accepted", "all_elements_supported"))
        clock[0] += 301
        result = readiness.ReadinessService._evidence_gate({}, {})
    assert result["status"] == "unknown" and result["ready"] is None
    assert result["reasonCode"] == "support_review_observation_stale"


def test_recent_transport_failure_overrides_prior_valid_review(review_model):
    """验证 `result['status'] == 'not_ready' and result['ready'] is False` 成立，且 `result['reasonCode']` 等于 `'model_connection_failed'`。"""
    with model_execution(ModelExecution(bindings={"evidence_support": review_model})):
        support._record_health(support.EvidenceSupportReview("accepted", "all_elements_supported"))
        runtime.observe(review_model, "evidence_support", "not_ready", "model_connection_failed")
        result = readiness.ReadinessService._evidence_gate({}, {})
    assert result["status"] == "not_ready" and result["ready"] is False
    assert result["reasonCode"] == "model_connection_failed"


def test_no_model_call_does_not_record_review_readiness(review_model):
    """验证 `result.state` 等于 `'rejected'`。"""
    with model_execution(ModelExecution(bindings={"evidence_support": review_model})):
        result = support.review("问题", [], [], monotonic() + 5,
                                caller=lambda *_: pytest.fail("empty sources must not call a model"))
        observed = support.readiness()
    assert result.state == "rejected"
    assert observed["status"] == "unknown" and observed["reasonCode"] == "support_review_not_observed"


def test_readiness_snapshot_performs_no_model_io(review_model, monkeypatch):
    """验证 `result['capabilities']['evidenceGate']['ready']` 为 `None`。"""
    monkeypatch.setattr(runtime, "chat", lambda *a, **k: pytest.fail("readiness must not call a model"))
    service = readiness.ReadinessService(probes={})
    try:
        with model_execution(ModelExecution(bindings={"evidence_support": review_model})):
            result = service.snapshot()
        assert result["capabilities"]["evidenceGate"]["ready"] is None
    finally:
        service.stop()


def test_combine_uses_failure_unknown_degraded_ready_precedence():
    """验证 `result == {'status': 'not_ready', 'ready': False, 'reasonCode': 'db_down'}` 成立。"""
    result = readiness._combine(
        {"status": "unknown", "ready": None, "reasonCode": "not_observed"},
        {"status": "degraded", "ready": False, "reasonCode": "fallback"},
        {"status": "not_ready", "ready": False, "reasonCode": "db_down"},
        reason="dependency_unavailable",
    )
    assert result == {"status": "not_ready", "ready": False, "reasonCode": "db_down"}


def test_overall_status_only_uses_business_capabilities():
    """验证 `readiness._overall_status(business, business, business) == 'not_ready'` 成立，且 `diagnostics['status']` 等于 `'ready'`。"""
    business = {"status": "not_ready"}
    diagnostics = {"status": "ready"}
    assert readiness._overall_status(business, business, business) == "not_ready"
    assert diagnostics["status"] == "ready"  # rerank/evidence diagnostics do not affect this calculation
    unknown = {"status": "unknown"}
    assert readiness._overall_status(unknown, unknown, unknown) == "unknown"
    assert readiness._overall_status({"status": "ready"}, unknown, business) == "degraded"


def test_ingest_probe_dependencies_include_database():
    """验证 `set(readiness._probe_functions())` 等于 `{'database', 'minio'}`。"""
    assert set(readiness._probe_functions()) == {"database", "minio"}


def test_evidence_gate_without_selected_model_is_unknown():
    """验证 `result['status']` 等于 `'unknown'`，且 `result['reasonCode']` 等于 `'model_not_selected'`。"""
    result = readiness.ReadinessService._evidence_gate(
        {"status":"unknown"},{"status":"unknown"})
    assert result["status"] == "unknown"
    assert result["reasonCode"] == "model_not_selected"


def test_minio_probe_disables_retries(monkeypatch):
    """验证 `pool_args['retries']` 等于 `0`。"""
    pool_args = {}

    class FakePool:
        def __init__(self, **kwargs):
            """更新 `pool_args` 的当前状态，供调用方继续处理。输入为 `kwargs`。"""
            pool_args.update(kwargs)

    class FakeMinio:
        def __init__(self, *args, **kwargs):
            """提供空操作实现，无返回值。输入为 `args`、`kwargs`。"""
            pass

        def bucket_exists(self, bucket):
            """返回 `True`。输入为 `bucket`。"""
            return True

    monkeypatch.setattr("urllib3.PoolManager", FakePool)
    monkeypatch.setattr("minio.Minio", FakeMinio)
    readiness._probe_minio()
    assert pool_args["retries"] == 0


def test_refresh_stores_probe_status_and_details():
    """验证 `snapshot['status']` 等于 `'degraded'`，且 `snapshot['scorer']` 等于 `'embed_sim'`。"""
    service = readiness.ReadinessService(probes={"rerank": lambda: readiness.ProbeResult(
        "degraded", "rerank_fallback_embed_sim", {"scorer": "embed_sim"})})
    try:
        service._refresh()
        future = service._inflight["rerank"]
        future.result(timeout=2)
        service._refresh()
        snapshot = service._observed("rerank")
        assert snapshot["status"] == "degraded"
        assert snapshot["scorer"] == "embed_sim"
    finally:
        service.stop()


# ---------------------------------------------------------------- 快照与实链路一致性


# 输入为 `service`、`name`。
# 上游为 `test_traffic_success_heals_a_failed_probe_snapshot`、`test_probe_timeout_is_reported_as_unverified_not_unavailable`。
def _run_probe_once(service, name):
    """跑完一轮探针采集（提交 → 结束 → 收集）；探针异常由 _refresh 记账，这里只负责推进轮次。"""
    service._refresh()
    try:
        service._inflight[name].result(timeout=5)
    except Exception:  # noqa: BLE001 - 预期内：探针失败本身就是被测行为
        pass
    service._refresh()


def test_traffic_success_heals_a_failed_probe_snapshot():
    """回归：检索/生成刚成功，/ready 却稳定回失败快照。"""

    def failing():
        """触发 `RuntimeError`，交由上游处理。"""
        raise RuntimeError("embedding backend unreachable")

    service = readiness.ReadinessService(probes={"embedding": failing})
    try:
        _run_probe_once(service, "embedding")
        assert service._observed("embedding")["status"] == "not_ready"

        # 真实请求成功 = 链路确实通了，必须能把快照改回来
        service.observe("embedding", "ready")
        snapshot = service._observed("embedding")
        assert snapshot["status"] == "ready"
        assert snapshot["reasonCode"] == "none"
    finally:
        service.stop()


def test_probe_timeout_is_reported_as_unverified_not_unavailable():
    """慢 ≠ 坏：单次探针超时不能报成 <name>_unavailable，也不能直接下成不可用。"""

    def slow():
        """触发 `TimeoutError`，交由上游处理。"""
        raise TimeoutError("probe budget exhausted")

    service = readiness.ReadinessService(probes={"embedding": slow})
    try:
        _run_probe_once(service, "embedding")
        snapshot = service._observed("embedding")
        assert snapshot["reasonCode"] == "embedding_probe_timeout"
        assert snapshot["status"] == "unknown", "一次超时只能说明没测出来"
    finally:
        service.stop()


def test_sustained_probe_timeouts_escalate_to_degraded():
    """连续超时才说明它确实一直慢。"""

    def slow():
        """触发 `TimeoutError`，交由上游处理。"""
        raise TimeoutError("probe budget exhausted")

    service = readiness.ReadinessService(probes={"embedding": slow})
    try:
        for _ in range(readiness.PROBE_TIMEOUT_ESCALATE_AFTER):
            _run_probe_once(service, "embedding")
        snapshot = service._observed("embedding")
        assert snapshot["status"] == "degraded"
        assert snapshot["reasonCode"] == "embedding_probe_timeout"
    finally:
        service.stop()


def test_probe_timeout_does_not_override_fresh_traffic_success():
    """探针与业务共用 GPU：刚被真实请求证实的可用性，不该被一次探针超时推翻。"""

    def slow():
        """触发 `TimeoutError`，交由上游处理。"""
        raise TimeoutError("probe budget exhausted")

    service = readiness.ReadinessService(probes={"embedding": slow})
    try:
        service.observe("embedding", "ready")
        _run_probe_once(service, "embedding")
        snapshot = service._observed("embedding")
        assert snapshot["status"] == "ready", "超时是弱证据，不能覆盖更硬的成功证据"
        assert snapshot["reasonCode"] == "embedding_probe_timeout", "但要如实标明这轮没测出来"
    finally:
        service.stop()


def test_real_probe_failure_still_overrides_traffic_success():
    """硬故障（连接被拒）不受抑制规则保护：真实请求成功过也要立刻报 not_ready。"""

    def unreachable():
        """触发 `ConnectionRefusedError`，交由上游处理。"""
        raise ConnectionRefusedError("connection refused")

    service = readiness.ReadinessService(probes={"embedding": unreachable})
    try:
        service.observe("embedding", "ready")
        _run_probe_once(service, "embedding")
        assert service._observed("embedding")["status"] == "not_ready"
        assert service._observed("embedding")["reasonCode"] == "embedding_unavailable"
    finally:
        service.stop()


def test_stale_observation_keeps_last_status_instead_of_unknown():
    # max_age 为负：观测一写入就算过期，确定性覆盖「过期」分支
    """验证 `snapshot['status']` 等于 `'ready'`，且 `snapshot['reasonCode']` 等于 `'embedding_observation_stale'`。"""
    service = readiness.ReadinessService(probes={"embedding": lambda: None}, max_age_s=-1.0)
    try:
        service.observe("embedding", "ready")
        snapshot = service._observed("embedding")
        assert snapshot["status"] == "ready", "过期只说明没新证据，不等于依赖坏了"
        assert snapshot["reasonCode"] == "embedding_observation_stale"
    finally:
        service.stop()


def test_observe_ignores_unknown_probe_names():
    """通过 `service.stop` 核对本用例构造的状态。"""
    service = readiness.ReadinessService(probes={"embedding": lambda: None})
    try:
        service.observe("not_a_probe", "ready")
        assert service._observed("not_a_probe") == {
            "status": "unknown", "ready": None, "reasonCode": "probe_not_run"}
    finally:
        service.stop()


def test_model_readiness_is_scoped_to_the_selected_snapshot():
    """验证 `runtime.readiness('generate')['status']` 等于 `'unknown'`，且 `runtime.readiness('generate')['status']` 等于 `'ready'`。"""
    from ai.app.models.context import model_execution
    from ai.app.models.runtime import runtime
    from ai.app.models.schema import ModelConfig, ModelExecution
    model = ModelConfig(id="readiness-test", type="chat", protocol="openai_chat",
                        model="example", baseUrl="https://invalid.local/v1",
                        semanticFingerprint="readiness-semantic")
    runtime.observe(model,"generate","ready")
    assert runtime.readiness("generate")["status"] == "unknown"
    with model_execution(ModelExecution(bindings={"generate":model})):
        assert runtime.readiness("generate")["status"] == "ready"
        model.credentialVersion += 1
        assert runtime.readiness("generate")["status"] == "unknown"
