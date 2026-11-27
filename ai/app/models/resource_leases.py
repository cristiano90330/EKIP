"""用 Redis 租约协调多个 AI 进程对同一模型资源组的占用。"""

from hashlib import sha256
import logging
from urllib.parse import urlsplit

from redis import Redis
from redis.exceptions import RedisError

from ai.app.models.resolution import ModelConfigurationError


logger = logging.getLogger("ekip.models")

# 用 Redis 时钟计算到期时间，检查容量与登记租约在一次原子操作中完成。
# 配额和最后一份租约同时过期，混合容量配置不能在仍有租约时继续发放槽位。
_ACQUIRE = """
local now = redis.call('TIME')
local now_ms = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000)
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now_ms)
local active = redis.call('ZCARD', KEYS[1])
local limit = tonumber(ARGV[1])
if active > 0 and tonumber(redis.call('GET', KEYS[2])) ~= limit then
    return -1
end
if redis.call('ZSCORE', KEYS[1], ARGV[2]) then
    return 1
end
if active >= limit then
    return 0
end
redis.call('ZADD', KEYS[1], now_ms + tonumber(ARGV[3]), ARGV[2])
local latest = redis.call('ZREVRANGE', KEYS[1], 0, 0, 'WITHSCORES')[2]
redis.call('PEXPIREAT', KEYS[1], latest)
redis.call('SET', KEYS[2], limit, 'PXAT', latest)
return 1
"""


class ResourceCoordinationError(RuntimeError):
    """Redis 未确认租约时，运行时需拒绝发起模型调用。"""


class RedisGroupLeases:
    """为每个资源组登记独立的、按持有者标识释放的租约。"""

    def __init__(self, client: Redis, namespace: str):
        self._client = client
        self._namespace = namespace

    @classmethod
    def connect(cls, url: str, namespace: str) -> "RedisGroupLeases":
        """构造短超时的 Redis 客户端，首次资源申请时建立连接。"""
        try:
            parsed = urlsplit(url)
            if parsed.scheme not in {"redis", "rediss"} or not parsed.hostname:
                raise ValueError
            client = Redis.from_url(url, socket_connect_timeout=0.5, socket_timeout=0.5)
        except ValueError:
            raise ModelConfigurationError("invalid model resource Redis URL") from None
        return cls(client, namespace)

    def _keys(self, group: str) -> tuple[str, str]:
        """按命名空间和组定位租约与容量，两键使用同一 Redis 哈希槽。"""
        digest = sha256(f"{self._namespace}\0{group}".encode()).hexdigest()
        prefix = f"ekip:model-resource:{{{digest}}}"
        return f"{prefix}:leases", f"{prefix}:capacity"

    def try_acquire(self, group: str, capacity: int, token: str, ttl_ms: int) -> bool:
        """申请共享槽位，返回是否取得。Redis 失败和容量冲突由上游处理。"""
        try:
            result = self._client.eval(_ACQUIRE, 2, *self._keys(group), capacity, token, ttl_ms)
        except RedisError:
            raise ResourceCoordinationError("model_resource_coordination_unavailable") from None
        if result == -1:
            raise ModelConfigurationError("inconsistent shared model resource capacity")
        return result == 1

    def release(self, group: str, token: str) -> None:
        """只归还本次调用的槽位。连接故障后由已登记的到期时间回收。"""
        try:
            self._client.zrem(self._keys(group)[0], token)
        except RedisError:
            logger.warning("model_resource_lease_release_deferred")
