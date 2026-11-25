"""管理模型传输线程、流式队列及关闭状态，落实超时和取消边界。"""

import json
import logging
from concurrent.futures import ThreadPoolExecutor, TimeoutError as FutureTimeout
from contextvars import ContextVar, copy_context
from queue import Empty, Full, Queue
from threading import BoundedSemaphore, Event, Lock, Thread
from time import monotonic
from uuid import uuid4

from ai.app.llm.telemetry import get_request_id
from ai.app.rag.deadline import QueryDeadlineExceeded, current_deadline_at
from ai.app.models.cancellation import check_cancelled

_WORKERS = ThreadPoolExecutor(max_workers=8, thread_name_prefix="model-io")
_SLOTS = BoundedSemaphore(16)
_STATE = ContextVar("model_io_state", default=None)
logger = logging.getLogger("ekip.models.timing")


class CapacityExceeded(RuntimeError):
    pass


class IOState:
    def __init__(self):
        """为本次模型传输初始化取消信号、资源句柄、阶段记录和时限状态。"""
        self.cancelled = Event()
        self.lock = Lock()
        self.closers = []
        self.clients = {}
        self.deadline_at = None
        self.call_id = uuid4().hex
        self.request_id = get_request_id()
        self.started_at = monotonic()
        self.timing_labels = {}

    def mark(self, phase, **labels):
        """更新某个传输阶段的名称及标签，保留上游统计资源等待与推理用时所需的信息。"""
        with self.lock:
            self.timing_labels.update(labels)
            event = {"call_id": self.call_id, "request_id": self.request_id,
                     "phase": phase, "elapsed_ms": round((monotonic() - self.started_at) * 1000, 3),
                     **self.timing_labels}
        logger.info("model_timing %s", json.dumps(event, ensure_ascii=True, sort_keys=True))

    def remaining_seconds(self, caller_deadline):
        """在调用者预算和已记录的传输截止时间中取较早者，返回可用于后续等待的秒数。

        下游调用 `ai.app.rag.deadline.current_deadline_at`。
        """
        with self.lock:
            attempt_deadline = self.deadline_at
        deadlines = [value for value in (caller_deadline, attempt_deadline, current_deadline_at())
                     if value is not None]
        return min(deadlines) - monotonic() if deadlines else None

    def attach(self, value):
        """登记实际 HTTP 资源供超时或断连时关闭，避免取消已经发生后继续持有连接。

        下游调用 `ai.app.rag.deadline.QueryDeadlineExceeded`。
        """
        with self.lock:
            self.closers.append(value)
        if self.cancelled.is_set():
            value.close()
            raise QueryDeadlineExceeded("model execution was cancelled")
        return value

    def close(self):
        """从状态中取出 HTTP 资源并关闭，供传输调用及取消入口共同清理。

        上游为 `run`。
        """
        with self.lock:
            values, self.closers = self.closers, []
        for value in reversed(values):
            try:
                value.close()
            except Exception:
                pass

    def cancel(self):
        """标记当前传输已取消并请求关闭连接，使运行中的工作线程能够退出。"""
        self.cancelled.set()
        # Closing an SDK transport can itself wait for a socket lock.
        Thread(target=self.close, daemon=True, name="model-io-cancel").start()


def state():
    """返回当前模型工作线程的传输状态，供实际 HTTP 阶段登记可关闭的资源。

    上游为 `ai.app.models.runtime.ModelRuntime.client`、`ai.app.models.runtime.ModelRuntime._attempt`。
    """
    return _STATE.get()


def _submit(fn, io):
    """将携带请求上下文的操作提交到受限线程池，并在任务结束后归还容量。

    上游为 `run_bounded`、`stream_bounded`。
    """
    if not _SLOTS.acquire(blocking=False):
        raise CapacityExceeded("model_executor_capacity_rejected")
    ctx = copy_context()

    def run():
        """在工作线程绑定 IO 状态后执行调用方传入的操作，并在退出时清除状态与资源。"""
        token = _STATE.set(io)
        try:
            io.mark("worker_started")
            return fn()
        finally:
            io.mark("cleanup_started")
            io.close()
            io.mark("cleanup_finished")
            _STATE.reset(token)

    try:
        # 在线程池恢复调用方复制的上下文，让模型工作线程继承请求标识、截止时间和取消信号。
        future = _WORKERS.submit(ctx.run, run)
    except Exception:
        _SLOTS.release()
        raise

    def release_slot(_):
        """在线程池 Future 结束后归还传输槽位，供后续调用申请。"""
        _SLOTS.release()
        io.mark("worker_released")

    future.add_done_callback(release_slot)
    return future


def run_bounded(fn, timeout=None):
    """在受限传输线程执行同步模型操作，到达截止时间时取消并清理 IO 资源。

    上游为 `ai.app.models.runtime.ModelRuntime.invoke`，下游调用 `_submit`、`ai.app.models.cancellation.check_cancelled`。
    """
    io = IOState()
    future = _submit(fn, io)
    end = None if timeout is None else monotonic() + timeout
    try:
        while True:
            check_cancelled()
            remaining = io.remaining_seconds(end)
            if remaining is not None and remaining <= 0:
                raise QueryDeadlineExceeded("model execution exceeded total budget")
            try:
                return future.result(timeout=min(remaining, 0.05) if remaining is not None else 0.05)
            except FutureTimeout:
                if future.done():
                    return future.result()
    except FutureTimeout:
        io.cancel()
        future.cancel()
        raise QueryDeadlineExceeded("model execution exceeded total budget") from None
    except BaseException:
        io.cancel()
        future.cancel()
        raise


def stream_bounded(factory, timeout=None):
    """让后台线程生产模型事件，通过队列交给调用方，并在消费超时或取消时关闭连接。

    上游为 `ai.app.models.runtime.ModelRuntime.chat`，下游调用 `_submit`、`ai.app.models.cancellation.check_cancelled`。
    """
    io, queue = IOState(), Queue(maxsize=16)
    end = None if timeout is None else monotonic() + timeout

    def put(value):
        """在可取消的等待中向有界队列交付模型流事件，使读写双方共享背压。

        上游为 `run`。
        """
        while not io.cancelled.is_set():
            try:
                queue.put(value, timeout=0.05)
                return
            except Full:
                pass

    def run():
        """驱动模型迭代器并向受限队列转交事件、错误和结束信号，退出时关闭迭代器。

        下游调用 `put`、`close`。
        """
        iterator = factory()
        try:
            for item in iterator:
                if io.cancelled.is_set():
                    break
                put(("item", item))
        except BaseException as exc:
            put(("error", exc))
        finally:
            close = getattr(iterator, "close", None)
            if close:
                close()
            put(("end", None))

    # 有界队列让下游消费速度反向限制模型事件生产，结束消费时取消底层 IO。
    future = _submit(run, io)
    try:
        while True:
            check_cancelled()
            remaining = io.remaining_seconds(end)
            if remaining is not None and remaining <= 0:
                raise QueryDeadlineExceeded("model stream exceeded total budget")
            try:
                kind, value = queue.get(timeout=min(remaining, 0.05) if remaining is not None else 0.05)
            except Empty:
                continue
            if kind == "error":
                raise value
            if kind == "end":
                return
            yield value
    finally:
        io.cancel()
        future.cancel()
