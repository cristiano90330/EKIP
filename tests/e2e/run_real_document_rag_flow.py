# 在隔离依赖中走通真实 PDF 入库与检索，或记录解析失败后的回执、删除和清理结果。

"""Verify document ingestion and failure boundaries with isolated Java, AI, PG, Redis and MinIO.

The PDF success scenario calls a selected local Ollama model. The format suite
uses a deterministic embedding HTTP fixture. Uploaded objects and database rows
live in one-off containers backed by tmpfs.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import ipaddress
import json
import os
import secrets
import socket
import subprocess
import sys
import time
import uuid
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from io import BytesIO
from pathlib import Path
from threading import Event, Thread
from typing import Any
from urllib.parse import urlsplit

import httpx
from minio import Minio
from minio.error import S3Error


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_PDF = ROOT / "ai" / "enterprise_docs" / "travel_reimbursement.pdf"
POSTGRES_IMAGE = "pgvector/pgvector:pg17"
REDIS_IMAGE = "redis:7"
MINIO_IMAGE = "minio/minio:RELEASE.2025-09-07T16-13-09Z"
OLLAMA_MODEL = "ekip-qwen3-embedding:0.6b-4k"
OLLAMA_BASE_URL = "http://127.0.0.1:11434/v1"
READONLY_PASSWORD = "rag_readonly_dev"


def free_port() -> int:
    """让系统为回环监听分配可用端口，将端口号返回给隔离服务启动方。

    上游为 `isolated_ports`。
    """
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


def isolated_ports() -> dict[str, int]:
    """为数据库、缓存、对象存储及 Java 和 AI 服务分配彼此不同的回环端口。

    上游为 `main`，下游调用 `free_port`。
    """
    ports: dict[str, int] = {}
    used: set[int] = set()
    for name in ("pg", "redis", "minio", "ai", "java"):
        port = free_port()
        while port in used:
            port = free_port()
        ports[name] = port
        used.add(port)
    return ports


def run(command: list[str], *, timeout: int = 60, check: bool = True) -> subprocess.CompletedProcess[str]:
    """运行隔离流程的外部命令并收集输出，按 check 选项决定是否报告非零退出。

    上游为 `require_image`、`start_container`。
    """
    result = subprocess.run(command, cwd=ROOT, text=True, capture_output=True, timeout=timeout)
    if check and result.returncode:
        details = (result.stderr or result.stdout)[-3000:]
        raise RuntimeError(f"Command failed ({result.returncode}): {command[0]}\n{details}")
    return result


def require_image(image: str) -> None:
    """通过本地 Docker 确认所需镜像已经存在，不隐式拉取镜像。

    上游为 `main`，下游调用 `run`。
    """
    run(["docker", "image", "inspect", image], timeout=20)


def start_container(name: str, image: str, args: list[str], command: list[str] | None = None) -> None:
    """以给定镜像、运行参数及命令启动指定的临时容器，供上游登记归属。

    上游为 `main`，下游调用 `run`。
    """
    run(["docker", "run", "--pull=never", "--rm", "--detach", "--name", name, *args, image, *(command or [])], timeout=60)


def wait_until(label: str, action: Any, timeout: int, interval: float = 1.0) -> Any:
    """直至检查返回有效结果或预算用尽，保留最后错误作为超时原因。

    上游为 `verify_parser_failure`、`main`。
    """
    deadline = time.monotonic() + timeout
    last_error: Exception | None = None
    while time.monotonic() < deadline:
        try:
            result = action()
            if result:
                return result
        except Exception as exc:  # startup races are expected until each process is ready
            last_error = exc
        time.sleep(interval)
    suffix = f": {last_error}" if last_error else ""
    raise TimeoutError(f"Timed out waiting for {label}{suffix}")


def http_ready(url: str) -> bool:
    """探测指定 HTTP 端点是否返回 200，将传输异常视为未就绪。

    上游为 `main`。
    """
    try:
        response = httpx.get(url, timeout=2, trust_env=False)
        return response.status_code == 200
    except httpx.HTTPError:
        return False


def start_process(command: list[str], env: dict[str, str], log_path: Path) -> subprocess.Popen[bytes]:
    """用指定命令及独立环境启动子进程，记录日志并返回可供清理使用的进程句柄。

    上游为 `main`。
    """
    flags = subprocess.CREATE_NEW_PROCESS_GROUP if os.name == "nt" else 0
    with log_path.open("wb") as log:
        return subprocess.Popen(
            command,
            cwd=ROOT,
            env=env,
            stdin=subprocess.DEVNULL,
            stdout=log,
            stderr=subprocess.STDOUT,
            creationflags=flags,
        )


def stop_process(process: subprocess.Popen[bytes] | None) -> None:
    """在给定进程仍运行时停止其进程组，并等待其退出。

    上游为 `cleanup`，下游调用 `run`。
    """
    if process is None or process.poll() is not None:
        return
    if os.name == "nt":
        try:
            run(["taskkill", "/PID", str(process.pid), "/T", "/F"], timeout=20, check=False)
        except (OSError, subprocess.SubprocessError):
            pass
    else:
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()


def data_of(response: Any) -> Any:
    """从包装业务响应中取 data 字段，已有直接结果时原样返回。

    上游为 `verify_parser_failure`、`main`。
    """
    if isinstance(response, dict) and "data" in response:
        return response["data"]
    return response


def verify_parser_failure(api: Any, pg_container: str, minio: Minio,
                          bucket: str, kb_id: int, report: dict[str, Any], *,
                          filename: str = "parser-failure.pdf",
                          content: bytes = b"%PDF-1.7\nV03 deliberately malformed PDF without a document catalog.\n",
                          mime: str = "application/pdf") -> None:
    """上传损坏文件并等待解析失败，核对回执和零入库结果，再验证删除后的数据库与对象状态。

    上游为 `main`，下游调用 `data_of`、`api`。
    """
    request_key = f"parser-failure-{uuid.uuid4()}"
    report["file"] = filename
    report["fileSha256"] = hashlib.sha256(content).hexdigest()
    receipt = data_of(api(
        "POST", "/document/upload", params={"knowledgeBaseId": kb_id},
        headers={"Idempotency-Key": request_key},
        files={"file": (filename, content, mime)},
    ))
    document_id = int(receipt["documentId"])
    report["documentId"] = document_id
    report["knowledgeBaseId"] = kb_id

    def terminal_task() -> dict[str, Any] | None:
        """查询样本文档解析状态，仅在识别到终态时令轮询完成。

        下游调用 `data_of`、`api`。
        """
        snapshot = data_of(api("GET", f"/document/{document_id}/task"))
        return snapshot if str(snapshot.get("status", "")).lower() in {
            "success", "failed", "cancelled",
        } else None

    task = wait_until("parser failure terminal state", terminal_task, 90)
    report["task"] = {
        "status": task.get("status"), "attempt": task.get("attempt"),
        "errorCode": task.get("errorCode"),
    }
    if str(task.get("status", "")).upper() != "FAILED" or task.get("errorCode") != "DOCUMENT_INVALID":
        raise RuntimeError(f"{filename} did not produce DOCUMENT_INVALID failure: {report['task']}")
    report["checks"]["parserRejectedInvalidPdf" if mime == "application/pdf" else "parserRejectedInvalidFormat"] = True

    def database_snapshot() -> dict[str, Any]:
        """查询当前样本文档相关的数据库行，供报告核对失败后的入库与清理状态。

        上游为 `verify_parser_failure`，下游调用 `run`。
        """
        sql = (
            "SELECT json_build_object("
            f"'documentCount',(SELECT count(*) FROM document WHERE id={document_id}),"
            f"'documentStatus',(SELECT status FROM document WHERE id={document_id}),"
            f"'objectName',(SELECT file_path FROM document WHERE id={document_id}),"
            f"'taskCount',(SELECT count(*) FROM document_task WHERE document_id={document_id}),"
            f"'taskStatus',(SELECT status FROM document_task WHERE document_id={document_id} ORDER BY id DESC LIMIT 1),"
            f"'chunkCount',(SELECT count(*) FROM document_chunk WHERE document_id={document_id}),"
            "'vectorCount',"
            f"(SELECT count(*) FROM document_chunk_vector_1024 WHERE chunk_id IN "
            f"(SELECT id FROM document_chunk WHERE document_id={document_id}))+"
            f"(SELECT count(*) FROM document_chunk_vector_2560 WHERE chunk_id IN "
            f"(SELECT id FROM document_chunk WHERE document_id={document_id})),"
            "'receiptCount',"
            f"(SELECT count(*) FROM document_upload_receipt WHERE document_id={document_id}));"
        )
        result = run([
            "docker", "exec", pg_container, "psql", "-U", "postgres", "-d", "knowledge_test", "-Atc", sql,
        ], timeout=20)
        return json.loads(result.stdout.strip())

    failed_state = database_snapshot()
    report["failureDatabaseState"] = failed_state
    # DocumentStatus.FAILED and DocumentTaskStatus.FAILED both persist as integer 3.
    expected = {"documentCount": 1, "documentStatus": 3, "taskCount": 1, "taskStatus": 3,
                "chunkCount": 0, "vectorCount": 0, "receiptCount": 1}
    if any(failed_state.get(key) != value for key, value in expected.items()):
        raise RuntimeError(f"Parser failure left an unexpected database state: {failed_state}")
    report["checks"]["failedDocumentAndTaskRetainedWithoutChunksOrVectors"] = True

    object_name = failed_state["objectName"]
    stored_object = minio.stat_object(bucket, object_name)
    if stored_object.size != len(content):
        raise RuntimeError("Failed document's original object was not preserved intact")
    report["checks"]["originalObjectRetainedAfterParserFailure"] = True

    lookup = data_of(api("GET", "/document/upload/receipt", params={
        "knowledgeBaseId": kb_id, "requestKey": request_key,
    }))
    if not lookup.get("found") or int(lookup.get("receipt", {}).get("documentId", -1)) != document_id:
        raise RuntimeError("Failed document's original upload receipt could not be recovered")
    report["checks"]["failedUploadReceiptRecoverable"] = True

    api("DELETE", f"/document/{document_id}")
    deleted_state = database_snapshot()
    report["deletedDatabaseState"] = deleted_state
    count_fields = ("documentCount", "taskCount", "chunkCount", "vectorCount", "receiptCount")
    if any(deleted_state.get(key) != 0 for key in count_fields):
        raise RuntimeError(f"Deleting the failed document did not cascade: {deleted_state}")
    report["checks"]["failedDocumentDeletionCascaded"] = True

    try:
        minio.stat_object(bucket, object_name)
    except S3Error as exc:
        if exc.code not in {"NoSuchKey", "NoSuchObject"}:
            raise
    else:
        raise RuntimeError("Original object still exists after deleting the failed document")
    report["checks"]["failedDocumentOriginalObjectDeleted"] = True


def start_embedding_fixture() -> tuple[ThreadingHTTPServer, Thread, Event, Event]:
    """在隔离端口提供固定向量，并暂停 DOCX 向量响应以观察真实任务续租。"""
    entered, release = Event(), Event()

    class Embeddings(BaseHTTPRequestHandler):
        def do_POST(self) -> None:
            if self.path != "/v1/embeddings":
                self.send_error(404)
                return
            request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            texts = request["input"]
            if any("V03-LEASE-DOCX" in text for text in texts):
                entered.set()
                if not release.wait(45):
                    self.send_error(504)
                    return
            vector = [1.0] + [0.0] * 1023
            response = json.dumps({"data": [{"index": i, "embedding": vector} for i in range(len(texts))]}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(response)))
            self.end_headers()
            self.wfile.write(response)

        def log_message(self, format: str, *args: Any) -> None:
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Embeddings)
    runner = Thread(target=server.serve_forever, name="isolated-embedding-fixture", daemon=True)
    runner.start()
    return server, runner, entered, release


def office_fixtures() -> list[tuple[str, str, str, bytes]]:
    """生成带有不同标记的合成 DOCX、XLSX 和 PPTX，供上传 API 验证。"""
    from docx import Document
    from openpyxl import Workbook
    from pptx import Presentation

    word = Document()
    word.add_heading("Lease fixture", level=1)
    word.add_paragraph("V03-LEASE-DOCX")
    excel = Workbook()
    excel.active.append(["Name", "Value"])
    excel.active.append(["fixture", "V03-TABLE-XLSX"])
    slides = Presentation()
    slide = slides.slides.add_slide(slides.slide_layouts[1])
    slide.shapes.title.text = "Slide fixture"
    slide.placeholders[1].text = "V03-TEXT-PPTX"
    fixtures = []
    for extension, subtype, marker, content in (
        ("docx", "wordprocessingml.document", "V03-LEASE-DOCX", word),
        ("xlsx", "spreadsheetml.sheet", "V03-TABLE-XLSX", excel),
        ("pptx", "presentationml.presentation", "V03-TEXT-PPTX", slides),
    ):
        stream = BytesIO()
        content.save(stream)
        fixtures.append((f"isolated.{extension}", f"application/vnd.openxmlformats-officedocument.{subtype}",
                         marker, stream.getvalue()))
    return fixtures


def verify_office_formats(api: Any, pg_container: str, minio: Minio, bucket: str, kb_id: int,
                          report: dict[str, Any], embed_started: Event, embed_release: Event) -> None:
    """验证 Office 入库、活动索引、处理中续租以及损坏文件的失败和清理。"""
    def pg_json(sql: str) -> Any:
        return json.loads(run(["docker", "exec", pg_container, "psql", "-U", "postgres",
                               "-d", "knowledge_test", "-Atc", sql], timeout=10).stdout)

    migrations = pg_json("""
        SELECT json_agg(version ORDER BY installed_rank) FROM flyway_schema_history
        WHERE version IS NOT NULL AND success
    """)
    if "25" not in migrations:
        raise RuntimeError("Document task lease migration V25 was not applied")
    report["flywayLatestSuccessfulVersion"] = migrations[-1]
    report["checks"]["leaseMigrationApplied"] = True
    report["formats"] = {}
    fixtures = office_fixtures()
    for name, mime, marker, content in fixtures:
        receipt = data_of(api("POST", "/document/upload", params={"knowledgeBaseId": kb_id},
                              headers={"Idempotency-Key": f"format-{uuid.uuid4()}"},
                              files={"file": (name, content, mime)}))
        document_id = int(receipt["documentId"])
        task_id = int(receipt["taskId"])
        if name.endswith(".docx"):
            try:
                if not embed_started.wait(20):
                    raise RuntimeError("DOCX did not reach the embedding transport fixture")
                lease_sql = f"""
                    SELECT json_build_object('status', status, 'attempt', total_attempt_count,
                        'expires', extract(epoch FROM lease_expires_at),
                        'hardDeadline', extract(epoch FROM lease_deadline_at),
                        'leaseRemaining', extract(epoch FROM (lease_expires_at - clock_timestamp())),
                        'hardDeadlineRemaining', extract(epoch FROM (lease_deadline_at - clock_timestamp())))
                    FROM document_task WHERE id={task_id}
                """
                initial = pg_json(lease_sql)
                if (initial["status"] != 1 or not 100 < initial["leaseRemaining"] <= 120
                        or not 580 < initial["hardDeadlineRemaining"] <= 600):
                    raise RuntimeError("DOCX did not hold a processing lease with the default budget")

                def renewed_lease() -> dict[str, Any] | None:
                    current = pg_json(lease_sql)
                    if current["attempt"] != initial["attempt"] or current["hardDeadline"] != initial["hardDeadline"]:
                        raise RuntimeError("Renewal changed the attempt or hard deadline")
                    return current if current["expires"] > initial["expires"] else None

                renewed = wait_until("document lease renewal", renewed_lease, 30, .5)
                elapsed = round(renewed["expires"] - initial["expires"], 2)
                if not 18 <= elapsed <= 30:
                    raise RuntimeError(f"Unexpected lease renewal interval: {elapsed}s")
                report["lease"] = {"observedRenewalSeconds": elapsed,
                                   "initialLeaseRemainingSeconds": round(initial["leaseRemaining"], 2),
                                   "initialHardDeadlineRemainingSeconds": round(initial["hardDeadlineRemaining"], 2)}
                report["checks"]["processingLeaseRenewed"] = True
            finally:
                embed_release.set()

        def terminal_task() -> dict[str, Any] | None:
            task = data_of(api("GET", f"/document/{document_id}/task"))
            return task if str(task.get("status", "")).lower() in {"success", "failed", "cancelled"} else None

        task = wait_until(f"{name} terminal state", terminal_task, 90)
        if str(task.get("status", "")).lower() != "success":
            raise RuntimeError(f"{name} did not ingest successfully: {task.get('errorCode')}")
        records = data_of(api("GET", f"/document/{document_id}/chunks", params={"page": 1, "size": 100}))["records"]
        if not any(marker in item["content"] for item in records):
            raise RuntimeError(f"{name} did not preserve the expected source text")
        persisted = pg_json(f"""
            SELECT json_build_object('mime', file_type, 'object', file_path,
                'activeVectors', (SELECT count(*) FROM document_chunk_vector_1024 v
                    JOIN knowledge_index_binding b ON b.knowledge_base_id=v.knowledge_base_id
                        AND b.active_generation_id=v.generation_id
                    JOIN document_chunk c ON c.id=v.chunk_id WHERE c.document_id=d.id),
                'leaseCleared', (SELECT lease_expires_at IS NULL AND lease_deadline_at IS NULL
                    FROM document_task WHERE id={task_id}))
            FROM document d WHERE id={document_id}
        """)
        if (persisted["mime"] != mime or not persisted["activeVectors"] or not persisted["leaseCleared"]
                or minio.stat_object(bucket, persisted["object"]).size != len(content)):
            raise RuntimeError(f"{name} did not persist its MIME, original object, vectors and lease terminal state")
        report["formats"][name] = {"chunkCount": len(records), "activeVectors": persisted["activeVectors"],
                                   "leaseCleared": persisted["leaseCleared"], "documentId": document_id}

    failure = dict(checks={})
    report["corruptFormat"] = failure
    verify_parser_failure(api, pg_container, minio, bucket, kb_id, failure, filename="broken.docx",
                          content=b"deliberately corrupt Office archive", mime=fixtures[0][1])
    report["checks"]["officeFormatsAndFailureBoundaryVerified"] = True


def main() -> int:
    """构造真实 PDF 或损坏文件场景的隔离环境，记录解析、索引、查询与删除结果。

    下游调用 `isolated_ports`、`require_image`。
    """
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scenario", choices=("success", "parser-failure", "office-formats"), default="success")
    parser.add_argument("--file", type=Path, default=DEFAULT_PDF, help="PDF to upload; defaults to the travel reimbursement fixture")
    parser.add_argument(
        "--query",
        default="紧急情况无法事先审批的，须在出差开始后几个工作日内补办审批手续？",
        help="A retrieval question answered by the uploaded PDF",
    )
    parser.add_argument("--ollama-base-url", default=OLLAMA_BASE_URL)
    parser.add_argument("--embedding-model", default=OLLAMA_MODEL)
    parser.add_argument("--startup-timeout", type=int, default=240)
    args = parser.parse_args()

    parsed_model_url = urlsplit(args.ollama_base_url)
    hostname = (parsed_model_url.hostname or "").lower()
    try:
        is_loopback = hostname == "localhost" or ipaddress.ip_address(hostname).is_loopback
    except ValueError:
        is_loopback = False
    if parsed_model_url.scheme not in {"http", "https"} or not is_loopback:
        raise SystemExit("Ollama endpoint must use localhost or a loopback IP address")

    pdf = args.file.resolve()
    if args.scenario == "success" and (not pdf.is_file() or pdf.suffix.lower() != ".pdf"):
        raise SystemExit(f"Input must be an existing PDF: {pdf}")

    log_root = ROOT / "logs" / "real-document-rag-flow"
    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
    run_dir = log_root / run_id
    run_dir.mkdir(parents=True, exist_ok=False)
    tag = f"ekip-rag-{run_id.replace('-', '')}"[:55]
    names = {kind: f"{tag}-{kind}" for kind in ("pg", "redis", "minio")}
    ports = isolated_ports()
    bucket = f"realflow-{uuid.uuid4().hex[:12]}"
    pg_password = secrets.token_urlsafe(24)
    minio_user = f"rf{secrets.token_hex(6)}"
    minio_password = secrets.token_urlsafe(24)
    internal_token = secrets.token_urlsafe(36)
    jwt_secret = secrets.token_urlsafe(48)
    encryption_key = base64.b64encode(secrets.token_bytes(32)).decode("ascii")
    username = f"rf_{uuid.uuid4().hex[:16]}"
    password = f"T{secrets.token_urlsafe(12)}!"
    java_process: subprocess.Popen[bytes] | None = None
    ai_process: subprocess.Popen[bytes] | None = None
    embedding_fixture: tuple[ThreadingHTTPServer, Thread, Event, Event] | None = None
    started_containers: list[str] = []
    report: dict[str, Any] = {
        "runId": run_id,
        "scenario": args.scenario,
        "isolation": {"containers": names, "ports": ports, "storage": "tmpfs"},
        "checks": {},
    }
    if args.scenario == "success":
        report.update(file=str(pdf.relative_to(ROOT)) if pdf.is_relative_to(ROOT) else pdf.name,
                      fileSha256=hashlib.sha256(pdf.read_bytes()).hexdigest(),
                      embeddingModel=args.embedding_model, query=args.query)

    def cleanup() -> None:
        """关闭本次真实文档流程使用的临时客户端、应用进程及依赖容器。

        上游为 `main`，下游调用 `stop_process`、`run`。
        """
        errors: list[str] = []
        for process in (ai_process, java_process):
            try:
                stop_process(process)
            except Exception as exc:
                errors.append(f"process cleanup: {type(exc).__name__}: {exc}")
        for name in reversed(started_containers):
            stopped = run(["docker", "stop", "--time", "5", name], timeout=20, check=False)
            if stopped.returncode:
                still_exists = run(["docker", "container", "inspect", name], timeout=10, check=False)
                if still_exists.returncode == 0:
                    errors.append(f"temporary container was not stopped: {name}")
        if errors:
            raise RuntimeError("; ".join(errors))

    try:
        for image in (POSTGRES_IMAGE, REDIS_IMAGE, MINIO_IMAGE):
            require_image(image)
        if args.scenario == "success":
            ollama_tags = httpx.get(
                args.ollama_base_url.removesuffix("/v1") + "/api/tags", timeout=5, trust_env=False
            ).json()
            available_models = {model.get("name") for model in ollama_tags.get("models", [])}
            if args.embedding_model not in available_models:
                raise RuntimeError(f"Ollama model is not available locally: {args.embedding_model}")
            report["checks"]["ollamaModelAvailable"] = True
        if args.scenario == "office-formats":
            embedding_fixture = start_embedding_fixture()
            report["embeddingBoundary"] = "deterministic HTTP fixture"

        start_container(
            names["pg"], POSTGRES_IMAGE,
            ["--tmpfs", "/var/lib/postgresql/data:rw,size=1073741824,mode=1777",
             "--env", "POSTGRES_DB=knowledge_test", "--env", "POSTGRES_USER=postgres",
             "--env", f"POSTGRES_PASSWORD={pg_password}", "--publish", f"127.0.0.1:{ports['pg']}:5432"],
        )
        started_containers.append(names["pg"])
        start_container(
            names["redis"], REDIS_IMAGE,
            ["--tmpfs", "/data:rw,size=128m", "--publish", f"127.0.0.1:{ports['redis']}:6379",
            ],
            ["redis-server", "--save", "", "--appendonly", "no"],
        )
        started_containers.append(names["redis"])
        start_container(
            names["minio"], MINIO_IMAGE,
            ["--tmpfs", "/data:rw,size=512m", "--env", f"MINIO_ROOT_USER={minio_user}",
             "--env", f"MINIO_ROOT_PASSWORD={minio_password}", "--publish", f"127.0.0.1:{ports['minio']}:9000",
            ],
            ["server", "/data", "--console-address", ":9001"],
        )
        started_containers.append(names["minio"])

        wait_until(
            "temporary PostgreSQL",
            lambda: run(["docker", "exec", names["pg"], "pg_isready", "-U", "postgres", "-d", "knowledge_test"], timeout=5, check=False).returncode == 0,
            90,
        )
        wait_until(
            "temporary Redis",
            lambda: run(["docker", "exec", names["redis"], "redis-cli", "ping"], timeout=5, check=False).stdout.strip() == "PONG",
            60,
        )
        wait_until("temporary MinIO", lambda: http_ready(f"http://127.0.0.1:{ports['minio']}/minio/health/live"), 90)
        minio = Minio(
            f"127.0.0.1:{ports['minio']}", access_key=minio_user,
            secret_key=minio_password, secure=False,
        )
        minio.make_bucket(bucket)
        report["checks"]["isolatedDependenciesReady"] = True

        inherited_blocklist = (
            "SPRING_", "POSTGRES_", "PG_", "AI_", "MINIO_", "REDIS_", "JWT_",
            "INTERNAL_API_TOKEN", "MODEL_CONFIG_", "RETRIEVAL_", "TASK_", "SERVER_", "RAG_", "OLLAMA_",
        )
        common_env = {
            key: value
            for key, value in os.environ.items()
            if not key.upper().startswith(inherited_blocklist)
            and key.upper() not in {"JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"}
            and not key.upper().endswith("PROXY")
        }
        common_env.update({
            "JWT_SECRET": jwt_secret,
            "INTERNAL_API_TOKEN": internal_token,
            "POSTGRES_HOST": "127.0.0.1",
            "POSTGRES_PORT": str(ports["pg"]),
            "POSTGRES_DB": "knowledge_test",
            "POSTGRES_USER": "postgres",
            "POSTGRES_PASSWORD": pg_password,
            "REDIS_HOST": "127.0.0.1",
            "REDIS_PORT": str(ports["redis"]),
            "MINIO_ENDPOINT": f"http://127.0.0.1:{ports['minio']}",
            "MINIO_ACCESS_KEY": minio_user,
            "MINIO_SECRET_KEY": minio_password,
            "MINIO_BUCKET": bucket,
            "MODEL_CONFIG_ENCRYPTION_KEY": encryption_key,
            "SERVER_PORT": str(ports["java"]),
            "SERVER_ADDRESS": "127.0.0.1",
            "AI_BASE_URL": f"http://127.0.0.1:{ports['ai']}",
            "RETRIEVAL_VERSION": "baseline",
            "TASK_RELIABILITY_RECOVERY_ENABLED": "false",
            "MODEL_RESOURCE_REDIS_URL": "",
            "OTEL_ENABLED": "false",
        })
        if embedding_fixture is not None:
            common_env.update({
                "TASK_RELIABILITY_LEASE_RENEW_INTERVAL_MS": "20000",
                "TASK_RELIABILITY_LEASE_DURATION_MS": "120000",
                "TASK_RELIABILITY_STUCK_TIMEOUT_MS": "600000",
            })

        mvnw = ROOT / "knowledge-platform" / "mvnw.cmd"
        java_process = start_process(
            ["cmd.exe", "/d", "/c", str(mvnw), "-f", str(ROOT / "knowledge-platform" / "pom.xml"), "spring-boot:run"],
            common_env,
            run_dir / "java.log",
        )
        wait_until(
            "isolated Java API",
            lambda: http_ready(f"http://127.0.0.1:{ports['java']}/actuator/health"),
            args.startup_timeout,
        )
        report["checks"]["javaStartedFromCurrentSource"] = True

        ai_env = common_env.copy()
        ai_env.update({
            "PYTHONUTF8": "1",
            "PG_READONLY_HOST": "127.0.0.1",
            "PG_READONLY_PORT": str(ports["pg"]),
            "PG_READONLY_DB": "knowledge_test",
            "PG_READONLY_USER": "rag_readonly",
            "PG_READONLY_PASSWORD": READONLY_PASSWORD,
            "MINIO_ENDPOINT": f"127.0.0.1:{ports['minio']}",
            "MINIO_BUCKET": bucket,
            "INTERNAL_API_TOKEN": internal_token,
            "RETRIEVAL_LEG_TIMEOUT_MS": "10000",
            "RAG_DEV_TEST_MODE": "false",
        })
        ai_process = start_process(
            [sys.executable, "-m", "uvicorn", "ai.app.main:app", "--host", "127.0.0.1", "--port", str(ports["ai"])],
            ai_env,
            run_dir / "ai.log",
        )
        wait_until("isolated AI API", lambda: http_ready(f"http://127.0.0.1:{ports['ai']}/health"), args.startup_timeout)
        report["checks"]["aiStartedFromCurrentSource"] = True

        base = f"http://127.0.0.1:{ports['java']}"
        with httpx.Client(base_url=base, timeout=30, trust_env=False) as client:
            def api(method: str, path: str, **kwargs: Any) -> Any:
                """向隔离 Java 端点发起请求，携带样本账号配置并解析业务响应。

                上游为 `verify_parser_failure`、`main`。
                """
                response = client.request(method, path, **kwargs)
                if response.is_error:
                    raise RuntimeError(f"{method} {path} returned {response.status_code}: {response.text[:1200]}")
                return response.json() if response.content else {}

            api("POST", "/user/register", json={"username": username, "password": password})
            login = data_of(api("POST", "/user/login", json={"username": username, "password": password}))
            token = login.get("token") if isinstance(login, dict) else None
            if not token:
                raise RuntimeError("Isolated account login response did not contain a token")
            client.headers["Authorization"] = f"Bearer {token}"

            embedding_url = (args.ollama_base_url if embedding_fixture is None
                             else f"http://127.0.0.1:{embedding_fixture[0].server_address[1]}/v1")
            model = data_of(api("POST", "/models", json={
                "name": f"realflow-{run_id}",
                "type": "embedding",
                "source": "local",
                "provider": "ollama",
                "protocol": "openai_embedding",
                "model": args.embedding_model if embedding_fixture is None else "deterministic-office-fixture",
                "baseUrl": embedding_url,
                "dimension": 1024,
                "contextWindow": 4096,
                "capabilities": {},
                "compat": {},
                "runtime": {"timeoutMs": 120000, "retries": 0, "backoffMs": 0,
                            "maxConcurrency": 1, "resourceGroup": "local-gpu"},
            }))
            model_id = model.get("id") if isinstance(model, dict) else None
            if not model_id:
                raise RuntimeError("Embedding model was not created")
            api("PUT", "/models/bindings", json={"bindings": {"embedding": model_id}})
            report["checks"]["embeddingModelRegisteredAndBound"] = True

            kb_name = f"realflow-{run_id}"
            api("POST", "/knowledge-base", json={"name": kb_name, "description": "temporary real-file RAG closure"})
            knowledge_bases = data_of(api("GET", "/knowledge-base/list"))
            kb = next((item for item in knowledge_bases if item.get("name") == kb_name), None)
            if not kb or not kb.get("id"):
                raise RuntimeError("New isolated knowledge base could not be found")
            kb_id = int(kb["id"])

            if args.scenario == "parser-failure":
                verify_parser_failure(api, names["pg"], minio, bucket, kb_id, report)
            elif embedding_fixture is not None:
                verify_office_formats(api, names["pg"], minio, bucket, kb_id, report,
                                      embedding_fixture[2], embedding_fixture[3])
            else:
                idempotency_key = f"realflow-{uuid.uuid4()}"
                with pdf.open("rb") as source:
                    receipt = data_of(api(
                        "POST",
                        "/document/upload",
                        params={"knowledgeBaseId": kb_id},
                        headers={"Idempotency-Key": idempotency_key},
                        files={"file": (pdf.name, source, "application/pdf")},
                        timeout=60,
                    ))
                document_id = int(receipt["documentId"])
                report["documentId"] = document_id
                report["knowledgeBaseId"] = kb_id

                def task_snapshot() -> dict[str, Any] | None:
                    """读取样本文档的任务状态，返回供等待与结果检查使用的任务快照。

                    下游调用 `data_of`、`api`。
                    """
                    snapshot = data_of(api("GET", f"/document/{document_id}/task"))
                    state = str(snapshot.get("status", "")).lower()
                    if state in {"success", "failed", "cancelled"}:
                        return snapshot
                    return None

                task = wait_until("document task terminal state", task_snapshot, 300, 1.0)
                report["task"] = {"status": task.get("status"), "attempt": task.get("attempt"), "errorCode": task.get("errorCode")}
                if str(task.get("status", "")).lower() != "success":
                    raise RuntimeError(f"Document task ended without success: {report['task']}")
                report["checks"]["documentTaskSucceeded"] = True

                page = data_of(api("GET", f"/document/{document_id}/chunks", params={"page": 1, "size": 100}))
                records = page.get("records", []) if isinstance(page, dict) else []
                chunk_texts = [str(item.get("content", "")) for item in records]
                normalized_chunks = ["".join(text.split()) for text in chunk_texts]
                if not chunk_texts or not any("补办审批手续" in text and "三个工作日" in text for text in normalized_chunks):
                    raise RuntimeError("Parsed PDF chunks did not contain the expected approval deadline text")
                report["chunkCount"] = len(chunk_texts)
                report["checks"]["pdfParsedIntoExpectedText"] = True

                sql = (
                    "SELECT count(*) FROM document_chunk_vector_1024 v "
                    "JOIN knowledge_index_binding b ON b.knowledge_base_id=v.knowledge_base_id "
                    "AND b.active_generation_id=v.generation_id "
                    f"WHERE v.knowledge_base_id={kb_id} AND v.chunk_id IN "
                    f"(SELECT id FROM document_chunk WHERE document_id={document_id});"
                )
                vector_result = run([
                    "docker", "exec", names["pg"], "psql", "-U", "postgres", "-d", "knowledge_test", "-Atc", sql,
                ], timeout=20)
                vector_count = int(vector_result.stdout.strip())
                report["vectorCount"] = vector_count
                if vector_count <= 0:
                    raise RuntimeError("No active 1024-dimensional vector rows were persisted for the uploaded PDF")
                report["checks"]["activeVectorRowsPersisted"] = True

                results = api("GET", "/knowledge/search", params={"query": args.query, "topK": 5}, timeout=90)
                if isinstance(results, dict):
                    results = data_of(results)
                document_results = [item for item in results if int(item.get("documentId", -1)) == document_id]
                report["queryCandidates"] = [
                    {"chunkId": item.get("id"), "contentExcerpt": str(item.get("content", ""))[:240]}
                    for item in document_results
                ]
                if not document_results:
                    raise RuntimeError("Query returned no result from the uploaded document")
                found = next((
                    item for item in document_results
                    if "补办审批手续" in "".join(str(item.get("content", "")).split())
                    and "三个工作日" in "".join(str(item.get("content", "")).split())
                ), None)
                if not found:
                    raise RuntimeError("Query result did not contain the expected evidence passage")
                report["queryResult"] = {
                    "documentId": found.get("documentId"),
                    "chunkId": found.get("id"),
                    "contentExcerpt": str(found.get("content", ""))[:240],
                }
                report["checks"]["queryReturnedExpectedEvidence"] = True

        report["status"] = "passed"
        return_code = 0
    except Exception as exc:
        report["status"] = "failed"
        report["error"] = f"{type(exc).__name__}: {exc}"
        return_code = 1
    finally:
        try:
            if embedding_fixture is not None:
                server, runner, _, release = embedding_fixture
                release.set()
                server.shutdown()
                server.server_close()
                runner.join(timeout=5)
            cleanup()
            report["checks"]["isolatedCleanupSucceeded"] = True
        except Exception as cleanup_error:
            report.setdefault("cleanupError", f"{type(cleanup_error).__name__}: {cleanup_error}")
            report["status"] = "failed"
            return_code = 1
        report_path = run_dir / "report.json"
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps(report, ensure_ascii=False, indent=2))
        print(f"Report: {report_path}")
        print(f"Logs: {run_dir}")

    return return_code


if __name__ == "__main__":
    raise SystemExit(main())
