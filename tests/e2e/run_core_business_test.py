# 启动隔离 PostgreSQL 和 Redis 并采集 Java 核心业务链路报告。

"""Run the current Java business flow with disposable PostgreSQL/Redis dependencies.

Requires Docker, locally cached pgvector/pgvector:pg17 and redis:7 images, and JDK 17+.
Only AI and object-storage boundary beans are mocked; business services are real.
No existing service, database, Redis key, or persistent volume is changed.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
TEST = "com.pony.knowledge.business.CoreBusinessFlowIntegrationTest"
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)


def command(args: list[str], *, env=None, check=True, timeout=30):
    """在指定环境和超时内执行外部命令，向调用方返回捕获的状态及输出。

    上游为 `java_home`、`wait_ready`。
    """
    result = subprocess.run(args, cwd=ROOT, env=env, capture_output=True, text=True,
                            encoding="utf-8", errors="replace", timeout=timeout,
                            creationflags=NO_WINDOW)
    if check and result.returncode:
        # Commands containing generated credentials are never included in error text.
        raise RuntimeError(f"{Path(args[0]).name} {args[1]} failed (exit {result.returncode})")
    return result


def java_home(explicit: str | None) -> Path:
    """按显式参数及本机可用路径确定 Java 安装目录，供 Maven 与隔离服务启动使用。

    上游为 `main`，下游调用 `command`。
    """
    candidates = [explicit] if explicit else [os.environ.get("JAVA_HOME"),
                  str(Path.home()/".jdks/ms-17.0.17"), str(Path.home()/".jdks/openjdk-21")]
    for candidate in candidates:
        if not candidate:
            continue
        folder = Path(candidate).resolve()
        java = folder/"bin"/("java.exe" if os.name == "nt" else "java")
        javac = folder/"bin"/("javac.exe" if os.name == "nt" else "javac")
        if not java.is_file() or not javac.is_file():
            continue
        result = command([str(java), "-version"], check=False)
        version = re.search(r'version "(\d+)', result.stderr + result.stdout)
        if version and int(version.group(1)) >= 17:
            return folder
    raise RuntimeError("JDK 17+ is required; supply --java-home without changing global Java settings")


def wait_ready(name: str, probe: list[str]):
    """反复运行指定的依赖探针，目标成功后退出或在超时后报告未就绪。

    上游为 `main`，下游调用 `command`。
    """
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        if command(["docker", "exec", name, *probe], check=False, timeout=10).returncode == 0:
            return
        time.sleep(.5)
    raise RuntimeError(f"Disposable dependency did not become ready: {name}")


def mapped_port(name: str, internal: int) -> str:
    """从容器端口映射获取在宿主机可连接的隔离端口。

    上游为 `main`，下游调用 `command`。
    """
    address = command(["docker", "port", name, f"{internal}/tcp"]).stdout.strip()
    if not re.fullmatch(r"127\.0\.0\.1:\d+", address):
        raise RuntimeError("Expected a loopback-only disposable container port")
    return address.rsplit(":", 1)[1]


def run_maven(env: dict[str, str], log: Path, timeout: int) -> tuple[int, list[str]]:
    """用本次隔离环境运行指定业务用例，保存进程日志并返回执行状态和报告路径列表。

    上游为 `main`，下游调用 `command`。
    """
    wrapper = ROOT/"knowledge-platform"/("mvnw.cmd" if os.name == "nt" else "mvnw")
    args = [str(wrapper), "--batch-mode", "--no-transfer-progress", "-f",
            str(ROOT/"knowledge-platform/pom.xml"), "-Dtest=CoreBusinessFlowIntegrationTest", "test"]
    if os.name == "nt":
        args = ["cmd.exe", "/d", "/c", *args]
    checkpoints = []
    with log.open("w", encoding="utf-8") as output, log.open(encoding="utf-8", errors="replace") as reader:
        process = subprocess.Popen(args, cwd=ROOT, env=env, stdout=output, stderr=subprocess.STDOUT,
                                   creationflags=NO_WINDOW)
        deadline = time.monotonic() + timeout
        try:
            while True:
                for line in reader.readlines():
                    if "CORE_FLOW_PASS " in line:
                        checkpoint = line.split("CORE_FLOW_PASS ", 1)[1].strip()
                        checkpoints.append(checkpoint)
                        print("PASS " + checkpoint, flush=True)
                    elif re.search(r"Tests run:|BUILD SUCCESS|BUILD FAILURE|^\[ERROR\]", line):
                        print(line.rstrip(), flush=True)
                if process.poll() is not None:
                    # One extra drain after process exit avoids missing its last summary lines.
                    for line in reader.readlines():
                        if "CORE_FLOW_PASS " in line:
                            checkpoint = line.split("CORE_FLOW_PASS ", 1)[1].strip()
                            checkpoints.append(checkpoint)
                            print("PASS " + checkpoint, flush=True)
                        elif re.search(r"Tests run:|BUILD SUCCESS|BUILD FAILURE|^\[ERROR\]", line):
                            print(line.rstrip(), flush=True)
                    return process.returncode, checkpoints
                if time.monotonic() >= deadline:
                    raise TimeoutError("Targeted Maven test exceeded its time budget")
                time.sleep(.3)
        finally:
            if process.poll() is None:
                if os.name == "nt":
                    command(["taskkill", "/PID", str(process.pid), "/T", "/F"], check=False)
                else:
                    process.terminate()
                process.wait(timeout=15)


def main() -> int:
    """分配隔离 PostgreSQL 和 Redis，等待依赖启动后执行 Java 核心场景并记录结果。

    下游调用 `java_home`、`command`。
    """
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home")
    parser.add_argument("--timeout", type=int, default=480)
    args = parser.parse_args()
    run_id = uuid.uuid4().hex[:12]
    names = ["ekip-core-flow-pg-" + run_id, "ekip-core-flow-redis-" + run_id]
    folder = ROOT/"logs/core-business-test"/run_id
    folder.mkdir(parents=True, exist_ok=False)
    report = {"run_id": run_id, "status": "failed", "test_class": TEST,
              "boundary": "real Spring MVC/Security/services/PG/Redis; AI and storage mocks",
              "checkpoints": [], "containers_removed": False}
    started = time.time()
    created = []
    try:
        selected_java = java_home(args.java_home)
        command(["docker", "info", "--format", "{{.ServerVersion}}"])
        for image in ["pgvector/pgvector:pg17", "redis:7"]:
            command(["docker", "image", "inspect", image])
        password = secrets.token_urlsafe(24)
        db = "core_flow_" + run_id
        pg_env = dict(os.environ, POSTGRES_PASSWORD=password, POSTGRES_DB=db)
        for name, image, port, options, environment in [
            (names[0], "pgvector/pgvector:pg17", 5432,
             ["--env", "POSTGRES_PASSWORD", "--env", "POSTGRES_DB"], pg_env),
            (names[1], "redis:7", 6379, [], None),
        ]:
            # Names are registered before launch so ambiguous Docker failures can still be cleaned safely.
            created.append(name)
            command(["docker", "run", "--detach", "--rm", "--pull=never", "--name", name,
                     "--label", "ekip.core-flow.run=" + run_id,
                     "--publish", f"127.0.0.1::{port}", *options, image], env=environment)
            print("STARTED disposable " + name, flush=True)
        wait_ready(names[0], ["pg_isready", "-U", "postgres", "-d", db])
        wait_ready(names[1], ["redis-cli", "ping"])
        env = dict(os.environ, JAVA_HOME=str(selected_java), CORE_FLOW_DATABASE=db,
                   CORE_FLOW_POSTGRES_PORT=mapped_port(names[0], 5432),
                   CORE_FLOW_POSTGRES_PASSWORD=password, CORE_FLOW_REDIS_PORT=mapped_port(names[1], 6379))
        path_key = next((key for key in env if key.upper() == "PATH"), "PATH")
        env[path_key] = str(selected_java/"bin") + os.pathsep + env.get(path_key, "")
        test_started = time.time()
        code, checkpoints = run_maven(env, folder/"maven.log", args.timeout)
        report.update(maven_exit_code=code, checkpoints=checkpoints)
        xml = ROOT/"knowledge-platform/target/surefire-reports"/("TEST-" + TEST + ".xml")
        if code == 0 and xml.is_file() and xml.stat().st_mtime >= test_started - 2:
            suite = ET.parse(xml).getroot()
            counts = {key: int(suite.get(key, "0")) for key in ["tests", "failures", "errors", "skipped"]}
            report["junit"] = counts
            if counts == {"tests": 1, "failures": 0, "errors": 0, "skipped": 0} and len(checkpoints) == 6:
                report["status"] = "passed"
    except Exception as error:
        report["error"] = type(error).__name__ + ": " + str(error)
        print(report["error"], flush=True)
    finally:
        cleanup_errors = []
        for name in reversed(created):
            try:
                inspect = command(["docker", "inspect", "--format",
                                   '{{ index .Config.Labels "ekip.core-flow.run" }}', name], check=False)
                if inspect.returncode != 0:
                    if re.search(r"No such (?:object|container)", inspect.stderr, re.I):
                        continue
                    raise RuntimeError("Cannot verify disposable container cleanup")
                if inspect.stdout.strip() != run_id or not name.endswith(run_id):
                    raise RuntimeError("Refused cleanup of a container not owned by this run")
                command(["docker", "rm", "--force", "--volumes", name])
            except Exception as error:
                cleanup_errors.append(type(error).__name__ + ": " + str(error))
        report["containers_removed"] = not cleanup_errors
        if cleanup_errors:
            report.update(status="failed", cleanup_errors=cleanup_errors)
        report["duration_seconds"] = round(time.time() - started, 2)
        (folder/"report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print("RESULT " + report["status"] + "; report=" + str(folder/"report.json"), flush=True)
    return 0 if report["status"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
