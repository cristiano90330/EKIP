"""检查独立模型运行实例的共享 Redis 配额、过期回收和错误边界。"""

from concurrent.futures import ThreadPoolExecutor
from threading import Event
from time import monotonic, sleep
from unittest.mock import Mock
from uuid import uuid4

import pytest
from redis.exceptions import ConnectionError

from ai.app.models.cancellation import cancellation_scope
from ai.app.models.resolution import ModelConfigurationError
from ai.app.models.resource_leases import RedisGroupLeases
from ai.app.models.runtime import ModelCallError, ModelRuntime, ResourceLimits
from ai.app.models.schema import ModelConfig
from ai.app.rag.deadline import QueryDeadlineExceeded, query_deadline


@pytest.fixture
def coordinators():
    """创建两套 Redis 客户端，隔离进程内状态并共用一个测试服务端。"""
    fakeredis = pytest.importorskip("fakeredis")
    pytest.importorskip("lupa")
    server = fakeredis.FakeServer()
    namespace = uuid4().hex
    return tuple(RedisGroupLeases(fakeredis.FakeRedis(server=server), namespace) for _ in range(2))


def model(model_id, *, group="local-gpu", source="local"):
    """为模型运行时构造不连接外部服务的资源配置。"""
    return ModelConfig(id=model_id, type="chat", protocol="openai_chat", source=source,
                       model="synthetic", baseUrl="http://models.invalid/v1",
                       runtime={"resourceGroup": group, "timeoutMs": 1000})


def test_instances_share_the_group_but_not_unrelated_groups(coordinators):
    """同一设备额度阻止另一实例进入，另一资源组和释放后的调用继续可用。"""
    groups = {"local-gpu": 1, "other": 1}
    first = ResourceLimits(groups, queue_limit=0, shared=coordinators[0])
    second = ResourceLimits(groups, queue_limit=0, shared=coordinators[1])
    with first.acquire(model("chat-one")):
        with pytest.raises(ModelCallError, match="model_resource_capacity_rejected"):
            with second.acquire(model("chat-two")):
                pytest.fail("an active shared GPU slot must block another process")
        with second.acquire(model("other", group="other")):
            assert second._active["group:other"] == 1
    with second.acquire(model("chat-two")):
        assert second._active["group:local-gpu"] == 1


def test_expiry_and_release_only_free_the_owners_lease(coordinators):
    """失联进程的槽位会自然过期，随后旧进程的迟到释放不会清理新槽位。"""
    first, second = coordinators
    assert first.try_acquire("gpu", 1, "old", 20)
    end = monotonic() + 1
    while not second.try_acquire("gpu", 1, "new", 5000):
        assert monotonic() < end
        sleep(0.01)
    first.release("gpu", "old")
    assert not first.try_acquire("gpu", 1, "extra", 5000)
    second.release("gpu", "new")
    assert first.try_acquire("gpu", 1, "extra", 5000)


def test_rejects_mismatched_live_capacity(coordinators):
    """组内存在活动租约时，另一套容量配置不能多发许可。"""
    first, second = coordinators
    assert first.try_acquire("gpu", 1, "first", 5000)
    with pytest.raises(ModelConfigurationError, match="inconsistent shared"):
        second.try_acquire("gpu", 2, "second", 5000)
    first.release("gpu", "first")
    assert second.try_acquire("gpu", 2, "second", 5000)


def test_shared_admission_uses_the_request_budget_and_cancels(coordinators):
    """共享槽位等待服从总预算，取消和超时后不会遗留进程内计数。"""
    first = ResourceLimits({"local-gpu": 1}, shared=coordinators[0])
    second = ResourceLimits({"local-gpu": 1}, shared=coordinators[1])
    with first.acquire(model("first")), ThreadPoolExecutor(max_workers=1) as executor:
        with query_deadline(50), pytest.raises(QueryDeadlineExceeded):
            with second.acquire(model("later")):
                pytest.fail("shared wait must time out")
        cancelled = Event()

        def request_slot():
            with query_deadline(1000), cancellation_scope(cancelled), second.acquire(model("later")):
                pytest.fail("cancelled work must not enter a model")

        pending = executor.submit(request_slot)
        end = monotonic() + 0.5
        while not second._waiting and monotonic() < end:
            sleep(0.005)
        assert second._waiting == 1
        cancelled.set()
        with pytest.raises(QueryDeadlineExceeded, match="cancelled"):
            pending.result(timeout=1)
    assert not second._active
    assert not second._waiting
    with second.acquire(model("later")):
        assert second._active


def test_redis_unavailable_does_not_start_the_model():
    """Redis 故障拒绝共享组调用，独立且未分组的远程模型继续使用本地限制。"""
    client = Mock()
    client.eval.side_effect = ConnectionError("unavailable")
    runtime = ModelRuntime()
    runtime.resources = ResourceLimits({"local-gpu": 1}, shared=RedisGroupLeases(client, "tests"))
    operation = Mock(return_value="ran")
    with pytest.raises(ModelCallError, match="model_resource_coordination_unavailable"):
        runtime.invoke(model("gpu"), "generate", operation)
    operation.assert_not_called()
    assert not runtime.resources._active
    assert runtime.invoke(model("cloud", group="", source="remote"), "generate", operation) == "ran"
    assert client.eval.call_count == 1
