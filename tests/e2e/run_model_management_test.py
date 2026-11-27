# 建立隔离 PostgreSQL 环境，选择模型登记或索引生命周期回归入口并汇总报告。

"""Run focused model-management tests against a disposable PG17+pgvector container.

Uses a cached image, a random loopback port and no persistent volume. It never
reads dotenv, changes existing containers or calls an actual model provider.
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

from run_core_business_test import command, java_home, mapped_port, wait_ready

ROOT = Path(__file__).resolve().parents[2]
SUITES = {
    "registry": "com.pony.knowledge.modelmanagement.ModelRegistryIntegrationTest",
    "index": "com.pony.knowledge.modelmanagement.index.KnowledgeIndexLifecycleIntegrationTest",
}


def run_maven(env: dict[str, str], log: Path, timeout: int, suites: list[str]) -> int:
    """运行选定模型管理场景并把 Maven 输出写入本次运行日志，返回退出状态。

    上游为 `main`。
    """
    wrapper = ROOT / "knowledge-platform" / ("mvnw.cmd" if os.name == "nt" else "mvnw")
    arguments = [str(wrapper), "--batch-mode", "--no-transfer-progress", "-f",
                 str(ROOT / "knowledge-platform/pom.xml"), "-Dtest=" + ",".join(suites), "test"]
    if os.name == "nt":
        arguments = ["cmd.exe", "/d", "/c", *arguments]
    with log.open("w", encoding="utf-8") as output, log.open(encoding="utf-8", errors="replace") as reader:
        process = subprocess.Popen(arguments, cwd=ROOT, env=env, stdout=output, stderr=subprocess.STDOUT,
                                   creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        deadline = time.monotonic() + timeout
        try:
            while True:
                for line in reader.readlines():
                    if re.search(r"Tests run:|BUILD SUCCESS|BUILD FAILURE|^\[ERROR\]", line):
                        print(line.rstrip(), flush=True)
                if process.poll() is not None:
                    for line in reader.readlines():
                        if re.search(r"Tests run:|BUILD SUCCESS|BUILD FAILURE|^\[ERROR\]", line):
                            print(line.rstrip(), flush=True)
                    return process.returncode
                if time.monotonic() >= deadline:
                    raise TimeoutError("Focused Maven tests exceeded total time budget")
                time.sleep(.3)
        finally:
            if process.poll() is None:
                if os.name == "nt":
                    command(["taskkill", "/PID", str(process.pid), "/T", "/F"], check=False)
                else:
                    process.terminate()
                process.wait(timeout=15)


def main() -> int:
    """启动隔离 PostgreSQL，运行模型登记或索引生命周期场景并保存报告及清理状态。

    下游调用 `run_maven`。
    """
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home")
    parser.add_argument("--suite", choices=("all", *SUITES), default="all",
                        help="run one integration suite in the disposable database")
    parser.add_argument("--timeout", type=int, default=480)
    args = parser.parse_args()
    suites = list(SUITES.values()) if args.suite == "all" else [SUITES[args.suite]]
    run_id = uuid.uuid4().hex[:12]
    name = "ekip-model-test-pg-" + run_id
    label = "ekip.model-test.run"
    folder = ROOT / "logs" / "model-management-test" / run_id
    folder.mkdir(parents=True, exist_ok=False)
    report = {"run_id": run_id, "suite": args.suite, "status": "failed", "containers_removed": False,
              "boundary": "real PostgreSQL/Flyway/locking; model HTTP is mocked", "suites": {}}
    created = False
    started = time.time()
    try:
        selected_java = java_home(args.java_home)
        command(["docker", "info", "--format", "{{.ServerVersion}}"])
        command(["docker", "image", "inspect", "pgvector/pgvector:pg17"])
        password = secrets.token_urlsafe(24)
        db = "ekip_model_test_" + run_id
        docker_env = dict(os.environ, POSTGRES_PASSWORD=password, POSTGRES_DB=db)
        created = True
        command(["docker", "run", "--detach", "--rm", "--pull=never", "--name", name,
                 "--label", label + "=" + run_id, "--publish", "127.0.0.1::5432",
                 "--env", "POSTGRES_PASSWORD", "--env", "POSTGRES_DB", "pgvector/pgvector:pg17"], env=docker_env)
        print("STARTED disposable " + name, flush=True)
        wait_ready(name, ["pg_isready", "-U", "postgres", "-d", db])
        env = dict(os.environ, JAVA_HOME=str(selected_java), EKIP_INDEX_TEST_ISOLATED="true",
                   EKIP_INDEX_TEST_JDBC_URL=f"jdbc:postgresql://127.0.0.1:{mapped_port(name, 5432)}/{db}",
                   EKIP_INDEX_TEST_DB_USER="postgres", EKIP_INDEX_TEST_DB_PASSWORD=password)
        path_key = next((key for key in env if key.upper() == "PATH"), "PATH")
        env[path_key] = str(selected_java / "bin") + os.pathsep + env.get(path_key, "")
        test_started = time.time()
        code = run_maven(env, folder / "maven.log", args.timeout, suites)
        report["maven_exit_code"] = code
        for suite_name in suites:
            xml = ROOT / "knowledge-platform/target/surefire-reports" / ("TEST-" + suite_name + ".xml")
            if not xml.is_file() or xml.stat().st_mtime < test_started - 2:
                raise RuntimeError("Missing fresh test evidence for " + suite_name)
            suite = ET.parse(xml).getroot()
            counts = {key: int(suite.get(key, "0")) for key in ("tests", "failures", "errors", "skipped")}
            report["suites"][suite_name] = counts
            if counts["tests"] < 1 or any(counts[key] for key in ("failures", "errors", "skipped")):
                raise RuntimeError("Incomplete passing evidence for " + suite_name)
        if code == 0:
            schema = command(["docker", "exec", name, "psql", "-U", "postgres", "-d", db, "-Atc",
                              "SELECT version FROM flyway_schema_history WHERE success "
                              "AND version IS NOT NULL ORDER BY installed_rank"]).stdout.split()
            if "25" not in schema:
                raise RuntimeError("The disposable database did not apply document task lease migration V25")
            report["flyway_latest_successful_version"] = schema[-1]
            report["document_task_lease_migration_applied"] = True
            report["status"] = "passed"
    except Exception as error:
        report["error"] = type(error).__name__ + ": " + str(error)
        print(report["error"], flush=True)
    finally:
        try:
            if created:
                inspected = command(["docker", "inspect", "--format", '{{ index .Config.Labels "' + label + '" }}', name], check=False)
                if inspected.returncode == 0:
                    if inspected.stdout.strip() != run_id or not name.endswith(run_id):
                        raise RuntimeError("Refused cleanup of a container not owned by this run")
                    command(["docker", "rm", "--force", "--volumes", name])
                elif "No such" not in inspected.stderr:
                    raise RuntimeError("Unable to verify test container cleanup")
            report["containers_removed"] = True
        except Exception as error:
            report["cleanup_error"] = type(error).__name__ + ": " + str(error)
        report["duration_seconds"] = round(time.time() - started, 2)
        (folder / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print("REPORT " + str(folder / "report.json"), flush=True)
    return 0 if report["status"] == "passed" and report["containers_removed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
