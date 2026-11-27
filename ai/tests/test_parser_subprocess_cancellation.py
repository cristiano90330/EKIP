"""验证解析子进程仍在运行时的取消与清理。"""

import subprocess
from threading import Event
from unittest.mock import MagicMock

import pytest

from ai.app.models.cancellation import cancellation_scope
from ai.app.parser import subprocess_runner
from ai.app.rag.deadline import QueryDeadlineExceeded


def test_cancelling_parser_kills_only_spawned_process(monkeypatch):
    """在第一次限时等待时断开请求，检查本次子进程被结束并回收。"""
    disconnected = Event()
    process = MagicMock()
    process.__enter__.return_value = process
    process.poll.return_value = None

    def wait(timeout):
        assert 0 < timeout <= 0.1
        disconnected.set()
        raise subprocess.TimeoutExpired(["fixture-command"], timeout)

    process.wait.side_effect = wait
    start = MagicMock(return_value=process)
    monkeypatch.setattr(subprocess_runner.subprocess, "Popen", start)
    kill_group = MagicMock()
    monkeypatch.setattr(subprocess_runner.os, "killpg", kill_group, raising=False)

    with cancellation_scope(disconnected), pytest.raises(QueryDeadlineExceeded):
        subprocess_runner.run_parser_command(["fixture-command"], timeout=10, check=True)

    start.assert_called_once()
    if subprocess_runner.os.name == "posix":
        kill_group.assert_called_once_with(process.pid, subprocess_runner.signal.SIGKILL)
        process.kill.assert_not_called()
    else:
        process.kill.assert_called_once()
        kill_group.assert_not_called()
    process.__exit__.assert_called_once()
