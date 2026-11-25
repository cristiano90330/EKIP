"""验证只读连接池、失败回收及语句截止时间的处理。"""

import pytest

from ai.app.retrieval import db


class FakeCursor:
    def __init__(self, connection):
        """创建实例并初始化 `connection`，供后续方法共享。输入为 `connection`。"""
        self.connection = connection

    def __enter__(self):
        """返回 `self`。"""
        return self

    def __exit__(self, exc_type, exc, tb):
        """返回 `False`。输入为 `exc_type`、`exc`、`tb`。"""
        return False

    def execute(self, sql, params):
        """触发 `RuntimeError`，交由上游处理。输入为 `sql`、`params`。"""
        if self.connection.fail_queries:
            raise RuntimeError("simulated query failure")

    def fetchall(self):
        """返回 列表。"""
        return [{"value": 7}]


class FakeConnection:
    def __init__(self):
        """创建实例并初始化 `fail_queries`、`closed`、`rollback_count`，供后续方法共享。"""
        self.fail_queries = True
        self.closed = False
        self.rollback_count = 0

    def cursor(self, cursor_factory=None):
        """返回 `FakeCursor` 的结果。输入为 `cursor_factory`。"""
        return FakeCursor(self)

    def rollback(self):
        """在当前作用域内完成状态变更，无直接返回值。"""
        self.rollback_count += 1

    def close(self):
        """在当前作用域内完成状态变更，无直接返回值。"""
        self.closed = True


class FakePool:
    def __init__(self, maxconn=4):
        """创建实例并初始化 `maxconn`、`used`、`idle`、`connections`，供后续方法共享。输入为 `maxconn`。"""
        self.maxconn = maxconn
        self.used = set()
        self.idle = []
        self.connections = []
        self.fail_new_connections = True

    def getconn(self):
        """返回 `conn`。"""
        if self.idle:
            conn = self.idle.pop()
        else:
            if len(self.used) >= self.maxconn:
                raise RuntimeError("simulated pool exhausted")
            conn = FakeConnection()
            conn.fail_queries = self.fail_new_connections
            self.connections.append(conn)
        self.used.add(conn)
        return conn

    def putconn(self, conn, close=False):
        """将当前记录追加到 `self.idle`，供调用方后续读取。输入为 `conn`、`close`。"""
        assert conn in self.used
        self.used.remove(conn)
        if close:
            conn.close()
        else:
            self.idle.append(conn)


def test_failed_queries_discard_pool_slot_and_later_query_recovers(monkeypatch):
    """验证用例触发 `db.ReadOnlyPgError` 异常，并检查 `not pool.used` 成立，且 `len(pool.connections)` 等于 `attempt + 1`。"""
    pool = FakePool(maxconn=4)
    monkeypatch.setattr(db, "_pool", pool)

    for attempt in range(8):
        with pytest.raises(db.ReadOnlyPgError, match="只读 PG 查询失败"):
            db.query_rows("SELECT value")
        assert not pool.used
        assert len(pool.connections) == attempt + 1
        assert pool.connections[-1].closed

    pool.fail_new_connections = False
    assert db.query_rows("SELECT value") == [{"value": 7}]
    assert not pool.used
    assert pool.idle
    assert pool.idle[0].rollback_count == 1


def test_missing_dedicated_credentials_fail_validation(monkeypatch):
    """验证用例触发 `RuntimeError` 异常。"""
    from ai.app import config

    monkeypatch.setattr(config, "PG_READONLY_USER", "")

    with pytest.raises(RuntimeError, match="PG_READONLY_USER and PG_READONLY_PASSWORD are required"):
        config.validate_pg_readonly_config()
