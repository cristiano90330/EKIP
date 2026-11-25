"""为 Redis 会话及记忆存储契约建立隔离实例并记录验证结果。"""

from __future__ import annotations

import json
import os
from pathlib import Path
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET

from run_core_business_test import ROOT, NO_WINDOW, command, java_home, mapped_port, wait_ready


def main() -> int:
    """为 Redis 存储契约分配隔离容器，执行已选场景并检查报告和归属清理。"""
    run_id = uuid.uuid4().hex[:12]
    name = "ekip-store-test-" + run_id
    folder = ROOT / "tmp" / name
    folder.mkdir(parents=True)
    report = {"status": "failed", "run_id": run_id}
    try:
        selected_java = java_home(None)
        command(["docker", "run", "--detach", "--rm", "--pull=never", "--name", name,
                 "--label", "ekip.store-test.run=" + run_id,
                 "--publish", "127.0.0.1::6379", "redis:7"])
        wait_ready(name, ["redis-cli", "ping"])
        command(["docker", "exec", name, "redis-cli", "-n", "15", "SET", "ekip:test:owner", run_id])
        env = dict(os.environ, JAVA_HOME=str(selected_java), EKIP_REDIS_TEST_ISOLATED="true",
                   EKIP_REDIS_TEST_PORT=mapped_port(name, 6379), EKIP_REDIS_TEST_RUN_ID=run_id)
        path_key = next((key for key in env if key.upper() == "PATH"), "PATH")
        env[path_key] = str(selected_java / "bin") + os.pathsep + env.get(path_key, "")
        tests = ["RedisStoreIntegrationTest"]
        args = [str(ROOT / "knowledge-platform" / ("mvnw.cmd" if os.name == "nt" else "mvnw")),
                "-o", "-B", "--no-transfer-progress", "-f", str(ROOT / "knowledge-platform/pom.xml"),
                "-Dtest=" + ",".join(tests), "test"]
        if os.name == "nt": args = ["cmd.exe", "/d", "/c", *args]
        started = time.time()
        with (folder / "maven.log").open("w", encoding="utf-8") as output:
            result = subprocess.run(args, cwd=ROOT, env=env, stdout=output, stderr=subprocess.STDOUT,
                                    timeout=300, creationflags=NO_WINDOW)
        report["maven_exit_code"] = result.returncode
        counts = dict.fromkeys(["tests", "failures", "errors", "skipped"], 0)
        for test in tests:
            xml = next((ROOT / "knowledge-platform/target/surefire-reports").glob("TEST-*." + test + ".xml"))
            if xml.stat().st_mtime < started - 2: raise RuntimeError("Stale JUnit report")
            suite = ET.parse(xml).getroot()
            for key in counts: counts[key] += int(suite.get(key, "0"))
        report["junit"] = counts
        if result.returncode == 0 and counts == dict(tests=7, failures=0, errors=0, skipped=0):
            report["status"] = "passed"
    except Exception as error:
        report["error"] = type(error).__name__ + ": " + str(error)
    finally:
        inspect = command(["docker", "inspect", "--format", '{{ index .Config.Labels "ekip.store-test.run" }}', name], check=False)
        if inspect.returncode == 0 and inspect.stdout.strip() == run_id:
            report["container_removed"] = command(["docker", "rm", "--force", "--volumes", name], check=False).returncode == 0
        else:
            report["container_removed"] = False
        (folder / "report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
        print(json.dumps(report), flush=True)
        print("Evidence: " + str(folder), flush=True)
    return 0 if report["status"] == "passed" and report["container_removed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
