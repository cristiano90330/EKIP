"""运行单次文档解析所属的子进程，在超时或断连时结束它。"""

import os
import signal
import subprocess
import tempfile
from time import monotonic

from ai.app.models.cancellation import check_cancelled


def run_parser_command(command, *, timeout: float, check: bool, input=None, env=None):
    """执行不经 shell 的 OCR 或转换命令，返回退出状态和捕获的输出。

    上游为 PDF OCR 和旧 Office 转换。临时文件承接输入输出，轮询只等待本次所属进程退出。
    """
    check_cancelled()
    isolated_group = os.name == "posix"
    deadline = monotonic() + timeout
    with tempfile.TemporaryFile() as source, tempfile.TemporaryFile() as stdout, tempfile.TemporaryFile() as stderr:
        if input is not None:
            source.write(input)
            source.seek(0)
        check_cancelled()
        with subprocess.Popen(command, stdin=source, stdout=stdout, stderr=stderr,
                              env=env, start_new_session=isolated_group) as process:
            try:
                while True:
                    check_cancelled()
                    remaining = deadline - monotonic()
                    if remaining <= 0:
                        raise subprocess.TimeoutExpired(command, timeout)
                    try:
                        process.wait(timeout=min(0.1, remaining))
                        break
                    except subprocess.TimeoutExpired:
                        continue
            finally:
                if process.poll() is None:
                    if isolated_group:
                        # LibreOffice 的启动器可能另起进程，范围限定为本次新建的进程组。
                        try:
                            os.killpg(process.pid, signal.SIGKILL)
                        except ProcessLookupError:
                            pass
                    else:
                        process.kill()
        check_cancelled()
        stdout.seek(0)
        stderr.seek(0)
        result = subprocess.CompletedProcess(command, process.returncode, stdout.read(), stderr.read())
        if check:
            result.check_returncode()
        return result
