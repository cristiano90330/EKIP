"""报告上下文整理进度，并去重正文段落以控制模型输入容量。"""

from contextlib import contextmanager
from contextvars import ContextVar
import re

from ai.app.models.cancellation import check_cancelled
from ai.app.observability.stage_trace import record

_progress = ContextVar("context_input_progress", default=None)


class ContextCapacityExceeded(ValueError):
    pass


@contextmanager
def context_progress(callback):
    """为当前调用临时安装整理进度回调，退出作用域时恢复原有回调。

    上游为 `ai.app.rag.router._progress_stream.produce`。
    """
    token = _progress.set(callback)
    try:
        yield
    finally:
        _progress.reset(token)


def organizing(purpose, before, budget, *, after=None, method=None, batch_count=None):
    """将阶段用途和整理前后的预算信息上报给当前请求注册的回调。

    上游为 `ai.app.rag.evidence_support._organize_support`，下游调用 `ai.app.models.cancellation.check_cancelled`。
    """
    check_cancelled()
    data = {"stage": "context_organize", "purpose": purpose,
            "inputTokensBefore": before, "inputBudget": budget,
            "status": "started" if after is None else "completed"}
    if after is not None:
        data["inputTokensAfter"] = after
        record("context_organize", 0)
    if method is not None:
        data["method"] = method
    if batch_count is not None:
        data["batchCount"] = batch_count
    callback = _progress.get()
    if callback is not None:
        callback(data)


def unique_paragraphs(text):
    """按原有顺序删去重复的非空正文段落，返回适合证据输入的文本。

    上游为 `ai.app.rag.evidence_support._organize_support`、`ai.app.rag.pipeline._fit_generation_input`。
    """
    parts = re.split(r"(\r?\n\s*\r?\n)", text)
    previous, kept = None, []
    for index in range(0, len(parts), 2):
        paragraph = parts[index]
        if paragraph.strip() and paragraph == previous:
            continue
        previous = paragraph
        if kept:
            kept.append("\n\n")
        kept.append(paragraph)
    return "".join(kept)
