"""管理检索使用的只读 PostgreSQL 连接池，限制语句时长并清理异常连接。"""

import logging
import threading

logger = logging.getLogger("ekip.retrieval.db")

from ai.app.rag.deadline import QueryDeadlineExceeded, remaining_seconds


class ReadOnlyPgError(Exception):
    """只读数据库连接或查询失败，供管线区分数据库依赖故障。"""


# 连接池延迟创建；初始化锁和借还锁分别保护创建与受预算约束的借还操作。
_pool = None
_pool_lock = threading.Lock()
_pool_borrow_lock = threading.Lock()


def _build_pool():
    """使用检索专用配置创建只读数据库连接池，供连接管理入口复用。

    上游为 `_get_pool`，下游调用 `ai.app.config.validate_pg_readonly_config`。
    """
    import psycopg2
    from psycopg2 import pool as pgpool

    from ai.app.config import (
        PG_READONLY_DB,
        PG_READONLY_HOST,
        PG_READONLY_PASSWORD,
        PG_READONLY_PORT,
        PG_READONLY_USER,
        validate_pg_readonly_config,
    )

    validate_pg_readonly_config()

    return pgpool.ThreadedConnectionPool(
        minconn=1,
        maxconn=4,
        host=PG_READONLY_HOST,
        port=PG_READONLY_PORT,
        dbname=PG_READONLY_DB,
        user=PG_READONLY_USER,
        password=PG_READONLY_PASSWORD,
        options="-c default_transaction_read_only=on",
        connect_timeout=1,
    )


def _get_pool():
    """懒初始化并返回只读连接池，确保并发请求复用同一池。

    上游为 `_connection`，下游调用 `_build_pool`。
    """
    global _pool
    if _pool is None:
        with _pool_lock:
            if _pool is None:
                _pool = _build_pool()
    return _pool


# 从池中获取可用于只读查询的连接，并按失败状态决定回收方式。
# 上游为 `query_rows`，下游调用 `ai.app.rag.deadline.remaining_seconds`、`ai.app.rag.deadline.QueryDeadlineExceeded`。
def _connection():
    """检查查询剩余预算后借用连接，预算已耗尽则抛出 deadline 异常。

    驱动建连超时按整秒计算；预算不足一秒时，只允许借用已有空闲连接，不启动新的网络连接。
    空闲检查与借出在同一把锁内完成，并与归还使用相同的锁。
    """
    remaining = remaining_seconds()
    if remaining is not None and remaining <= 0:
        raise QueryDeadlineExceeded("query deadline expired before acquiring PG connection")
    if remaining is not None and remaining < 1.0 and _pool is None:
        raise QueryDeadlineExceeded("insufficient query budget for a new PG connection")
    pool = _get_pool()
    with _pool_borrow_lock:
        if remaining is not None and remaining < 1.0 and not pool._pool:
            raise QueryDeadlineExceeded("insufficient query budget for a new PG connection")
        return pool.getconn()


def _putconn(conn, close=False):
    """按当前连接的健康状态将其归还池中或关闭。

    上游为 `query_rows`，下游调用 `_get_pool`。
    """
    pool = _get_pool()
    with _pool_borrow_lock:
        pool.putconn(conn, close=close)


# 在只读连接执行带占位参数的 SQL，返回映射行，超时或连接故障时按契约抛出错误。
# 上游为 `ai.app.observability.readiness._probe_database`，下游调用 `_connection`、`ai.app.rag.deadline.remaining_seconds`。
def query_rows(sql, params=None):
    """借用只读连接，将 SQL 与参数交给游标执行，并把结果转换为字典列表。

    有查询 deadline 时设置事务局部 statement_timeout；超时保持 deadline 语义，其他故障包装为 ReadOnlyPgError。
    成功 SELECT 同样会开启事务，因此归还前 rollback 结束只读事务；执行或清理失败时丢弃连接。
    连接清理失败只记录日志，不覆盖已经产生的查询结果或原始异常。
    """
    from psycopg2.extras import RealDictCursor

    try:
        conn = _connection()
    except QueryDeadlineExceeded:
        raise
    except Exception as e:  # noqa: BLE001
        logger.warning("只读 PG 连接失败：%s", e)
        raise ReadOnlyPgError("只读 PG 不可用") from e

    query_succeeded = False
    try:
        with conn.cursor(cursor_factory=RealDictCursor) as cur:
            remaining = remaining_seconds()
            if remaining is not None:
                if remaining <= 0:
                    raise QueryDeadlineExceeded("query deadline expired before PG statement")

                # 给当前事务设置语句超时，既保留全链路预算，也避免配置残留影响连接池的下一次借用。
                statement_timeout_ms = max(1, int(remaining * 1000))
                cur.execute("SELECT set_config('statement_timeout', %s, true)",
                            (f"{statement_timeout_ms}ms",))
            cur.execute(sql, params)
            rows = [dict(r) for r in cur.fetchall()]
        query_succeeded = True
        return rows
    except QueryDeadlineExceeded:
        raise
    except Exception as e:  # noqa: BLE001
        logger.warning("只读 PG 查询失败：%s", e)
        if getattr(e, "pgcode", None) == "57014" and remaining_seconds() is not None:
            raise QueryDeadlineExceeded("PostgreSQL statement_timeout expired") from e
        remaining = remaining_seconds()
        if remaining is not None and remaining <= 0:
            raise QueryDeadlineExceeded("query deadline expired during PG statement") from e
        raise ReadOnlyPgError("只读 PG 查询失败") from e
    finally:
        # rollback 用于结束本次只读事务；失败连接通过池的归还接口关闭，避免泄漏借用槽位。
        discard = not query_succeeded
        if query_succeeded:
            try:
                conn.rollback()
            except Exception:  # noqa: BLE001
                discard = True
                logger.warning("只读 PG 查询后清理事务失败，连接将丢弃", exc_info=True)

        try:
            _putconn(conn, close=discard)
        except Exception:  # noqa: BLE001 —— cleanup must not replace a query result/error
            logger.warning("只读 PG 连接归还失败，尝试关闭归还", exc_info=True)
            try:
                _putconn(conn, close=True)
            except Exception:  # noqa: BLE001
                logger.error("只读 PG 连接关闭归还失败，连接池状态可能需要人工检查", exc_info=True)
