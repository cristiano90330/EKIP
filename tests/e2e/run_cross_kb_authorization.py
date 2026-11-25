# 在隔离服务中写入两种向量维度的索引，并检查授权前后跨库检索的结果。

"""Verify real index writes and KB-scoped retrieval across two embedding spaces.

The model profiles below were read from the active managed_model records on
2026-11-10. They are recreated for temporary users against loopback Ollama
endpoints. The 2560-dimensional model can use a separate local model store.
No rows or objects are written to the shared services.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import httpx
import ipaddress
import json
import os
import secrets
import subprocess
import sys
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit

from minio import Minio

import run_real_document_rag_flow as support


ROOT = support.ROOT
MODEL_PROFILES = {
    "small": {
        "sourceRegistryId": "a0a770c7-34f8-4bdb-9a77-87772d78b9d9",
        "sourceOwnerId": 10,
        "name": "本地轻量 Qwen3 Embedding",
        "model": "ekip-qwen3-embedding:0.6b-4k",
        "dimension": 1024,
    },
    "large": {
        "sourceRegistryId": "640cc07f-1b2d-47f2-9328-cb820d2d15b2",
        "sourceOwnerId": 3,
        "name": "本地 Qwen3 Embedding",
        "model": "Qwen3-Embedding-4B:latest",
        "dimension": 2560,
    },
}
PDF_CASES = {
    "small": {
        "file": ROOT / "ai" / "enterprise_docs" / "travel_reimbursement.pdf",
        "query": "紧急情况无法事先审批的，须在出差开始后几个工作日内补办审批手续？",
        "evidence": ("补办审批手续", "三个工作日"),
    },
    "large": {
        "file": ROOT / "ai" / "enterprise_docs" / "password_policy.pdf",
        "query": "VPN 口令最少需要多少位？",
        "evidence": ("VPN", "不少于十二位"),
    },
}


def isolated_environment() -> dict[str, str]:
    """过滤与真实部署有关的环境变量和代理设置，返回供两个隔离应用继承的环境。

    上游为 `main`。
    """
    blocked = (
        "SPRING_", "POSTGRES_", "PG_", "AI_", "MINIO_", "REDIS_", "JWT_",
        "INTERNAL_API_TOKEN", "MODEL_CONFIG_", "MODEL_DEFAULT_", "RETRIEVAL_", "TASK_", "SERVER_", "RAG_", "OLLAMA_",
    )
    return {
        key: value for key, value in os.environ.items()
        if not key.upper().startswith(blocked)
        and key.upper() not in {"JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"}
        and not key.upper().endswith("PROXY")
    }


def call(client: httpx.Client, method: str, path: str, **kwargs: Any) -> Any:
    """向隔离服务请求 API，统一检查 HTTP 状态和业务码，并返回解包后的业务数据。

    上游为 `logged_in_client`、`create_model`。
    """
    response = client.request(method, path, **kwargs)
    if response.is_error:
        raise RuntimeError(f"{method} {path} returned {response.status_code}: {response.text[:1200]}")
    body = response.json() if response.content else {}
    if isinstance(body, dict) and "code" in body and body["code"] != 200:
        raise RuntimeError(f"API rejected {method} {path}: code={body['code']}")
    return support.data_of(body)


def logged_in_client(base_url: str, username: str, password: str) -> tuple[httpx.Client, dict[str, Any]]:
    """注册并登录隔离账号，返回带授权头的客户端和身份数据，失败时关闭客户端。

    上游为 `main`，下游调用 `call`。
    """
    client = httpx.Client(base_url=base_url, timeout=40, trust_env=False)
    try:
        call(client, "POST", "/user/register", json={"username": username, "password": password})
        login = call(client, "POST", "/user/login", json={"username": username, "password": password})
        if not isinstance(login, dict) or not login.get("token") or not login.get("id"):
            raise RuntimeError("Temporary account did not return its user ID and access token")
        client.headers["Authorization"] = f"Bearer {login['token']}"
        return client, login
    except BaseException:
        client.close()
        raise


def create_model(client: httpx.Client, profile: dict[str, Any], run_id: str, ollama_base_url: str) -> str:
    """将样本模型登记并绑定到隔离账号的向量用途，返回模型 ID。

    上游为 `main`，下游调用 `call`。
    """
    model = call(client, "POST", "/models", json={
        "name": f"{profile['name']} isolated {run_id}",
        "type": "embedding",
        "source": "local",
        "provider": "ollama",
        "protocol": "openai_embedding",
        "model": profile["model"],
        "baseUrl": ollama_base_url,
        "dimension": profile["dimension"],
        "contextWindow": None,
        "maxOutputTokens": None,
        "capabilities": {"tools": False, "streaming": False, "json": False, "thinking": False},
        "compat": {},
        "runtime": {"timeoutMs": 120000, "retries": 0, "backoffMs": 500,
                    "maxConcurrency": 1, "resourceGroup": "local-gpu"},
    })
    model_id = model.get("id") if isinstance(model, dict) else None
    if not model_id:
        raise RuntimeError("Could not register a temporary copy of the current embedding model")
    call(client, "PUT", "/models/bindings", json={"bindings": {"embedding": model_id}})
    return str(model_id)


def create_kb(client: httpx.Client, label: str, run_id: str) -> int:
    """为当前用户创建样本知识库，并从列表读取其 ID 供后续上传使用。

    上游为 `main`，下游调用 `call`。
    """
    name = f"cross-kb-{label}-{run_id}"
    call(client, "POST", "/knowledge-base", json={"name": name, "description": "temporary isolated cross-KB authorization check"})
    rows = call(client, "GET", "/knowledge-base/list")
    item = next((row for row in rows if row.get("name") == name), None)
    if not item or not item.get("id"):
        raise RuntimeError(f"Could not find temporary knowledge base {label}")
    return int(item["id"])


def contains_evidence(row: dict[str, Any], terms: tuple[str, ...]) -> bool:
    """忽略内容的排版空白，检查一条切片是否包含所有预设证据词。

    上游为 `upload_and_index`、`main`。
    """
    text = "".join(str(row.get("content", "")).split())
    return all(term in text for term in terms)


def upload_and_index(
    client: httpx.Client,
    pg_name: str,
    file: Path,
    kb_id: int,
    evidence_terms: tuple[str, ...],
    dimension: int,
    pg_database: str,
) -> dict[str, Any]:
    """上传样本 PDF 并等待任务结束，核对切片正文和活动索引向量后返回入库证据。

    上游为 `main`，下游调用 `call`、`contains_evidence`。
    """
    with file.open("rb") as source:
        receipt = call(
            client,
            "POST",
            "/document/upload",
            params={"knowledgeBaseId": kb_id},
            headers={"Idempotency-Key": f"cross-kb-{uuid.uuid4()}"},
            files={"file": (file.name, source, "application/pdf")},
            timeout=60,
        )
    document_id = int(receipt["documentId"])

    def terminal_task() -> dict[str, Any] | None:
        """查询样本文档处理状态，成功、失败或取消时交付终态给等待循环。

        下游调用 `call`。
        """
        task = call(client, "GET", f"/document/{document_id}/task")
        if str(task.get("status", "")).lower() in {"success", "failed", "cancelled"}:
            return task
        return None

    task = support.wait_until(f"document {document_id} terminal state", terminal_task, 360, 1.0)
    if str(task.get("status", "")).lower() != "success":
        raise RuntimeError(f"Document task {document_id} did not succeed: {task}")

    page = call(client, "GET", f"/document/{document_id}/chunks", params={"page": 1, "size": 100})
    chunks = page.get("records", []) if isinstance(page, dict) else []
    if not any(contains_evidence(row, evidence_terms) for row in chunks):
        raise RuntimeError(f"Parsed chunks for {file.name} did not contain expected text")

    # 以模型登记的维度确定物理表，再用知识库的活动代次限制写入证据。
    vector_table = f"document_chunk_vector_{dimension}"
    sql = (
        f"SELECT count(*) FROM {vector_table} v "
        "JOIN knowledge_index_binding b ON b.knowledge_base_id=v.knowledge_base_id "
        "AND b.active_generation_id=v.generation_id "
        f"WHERE v.knowledge_base_id={kb_id} AND v.chunk_id IN "
        f"(SELECT id FROM document_chunk WHERE document_id={document_id});"
    )
    rows = support.run(["docker", "exec", pg_name, "psql", "-U", "postgres", "-d", pg_database, "-Atc", sql], timeout=20)
    vector_count = int(rows.stdout.strip())
    if vector_count <= 0:
        raise RuntimeError(f"No active vector rows were written for document {document_id}")
    return {"documentId": document_id, "taskStatus": task.get("status"), "attempt": task.get("attempt"),
            "chunkCount": len(chunks), "vectorCount": vector_count, "vectorTable": vector_table}


def search(client: httpx.Client, query: str) -> list[dict[str, Any]]:
    """向隔离 Java 服务提交查询，返回当前账号有权读取的检索结果列表。

    上游为 `main`，下游调用 `call`。
    """
    result = call(client, "GET", "/knowledge/search", params={"query": query, "topK": 10}, timeout=100)
    if not isinstance(result, list):
        raise RuntimeError("Knowledge search did not return a result list")
    return result


def local_ollama_url(value: str) -> str:
    """校验模型地址为无凭据、无附加参数且使用预期路径的本机 Ollama 端点。"""
    parsed = urlsplit(value)
    hostname = (parsed.hostname or "").lower()
    try:
        loopback = hostname == "localhost" or ipaddress.ip_address(hostname).is_loopback
    except ValueError:
        loopback = False
    if (parsed.scheme not in {"http", "https"} or not loopback or parsed.path.rstrip("/") != "/v1"
            or parsed.username or parsed.password or parsed.query or parsed.fragment):
        raise argparse.ArgumentTypeError("expected a loopback Ollama URL ending in /v1")
    return value.rstrip("/")


def verify_model(profile: dict[str, Any], url: str) -> dict[str, Any]:
    """读取模型清单并向本机端点申请样本向量，核对返回维度并记录模型摘要。

    上游为 `main`。
    """
    with httpx.Client(trust_env=False, timeout=120) as client:
        tags = client.get(url.removesuffix("/v1") + "/api/tags", timeout=5).raise_for_status().json()
        installed = next((item for item in tags.get("models", []) if item.get("name") == profile["model"]), None)
        if installed is None:
            raise RuntimeError(f"Ollama model {profile['model']} is missing at {url}")
        payload = {"model": profile["model"], "input": ["EKIP isolated native dimension check"]}
        vectors = client.post(url + "/embeddings", json=payload).raise_for_status().json()["data"]
    if len(vectors) != 1 or len(vectors[0]["embedding"]) != profile["dimension"]:
        raise RuntimeError(f"{profile['model']} did not return the expected {profile['dimension']} native dimensions")
    return {"model": profile["model"], "baseUrl": url, "nativeDimension": profile["dimension"],
            "digest": installed.get("digest"), "details": installed.get("details")}


def main() -> int:
    """启动隔离进程和依赖，为两种维度分别入库并比较赋权前后与反向的检索范围。

    下游调用 `verify_model`、`isolated_environment`。
    """
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--startup-timeout", type=int, default=240)
    parser.add_argument("--ollama-base-url", type=local_ollama_url, default=support.OLLAMA_BASE_URL)
    parser.add_argument("--large-ollama-base-url", type=local_ollama_url,
                        help="Optional separate loopback Ollama endpoint for the 2560-dimensional model")
    parser.add_argument("--large-model", default=MODEL_PROFILES["large"]["model"],
                        help="The installed Ollama tag for the 2560-dimensional model")
    args = parser.parse_args()
    profiles = {label: dict(profile) for label, profile in MODEL_PROFILES.items()}
    if args.large_model != MODEL_PROFILES["large"]["model"]:
        profiles["large"].update(model=args.large_model, sourceRegistryId=None, sourceOwnerId=None)
    endpoints = {"small": args.ollama_base_url, "large": args.large_ollama_base_url or args.ollama_base_url}
    if not all(item["file"].is_file() for item in PDF_CASES.values()):
        raise SystemExit("The two PDF fixtures are required")

    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
    run_dir = ROOT / "logs" / "cross-kb-authorization" / run_id
    run_dir.mkdir(parents=True, exist_ok=False)
    tag = f"ekip-xkb-{run_id.replace('-', '')}"[:55]
    names = {kind: f"{tag}-{kind}" for kind in ("pg", "redis", "minio")}
    ports = support.isolated_ports()
    database = "knowledge_test"
    pg_password = secrets.token_urlsafe(24)
    minio_user = f"xkb{secrets.token_hex(6)}"
    minio_password = secrets.token_urlsafe(24)
    bucket = f"crosskb-{uuid.uuid4().hex[:12]}"
    internal_token = secrets.token_urlsafe(36)
    jwt_secret = secrets.token_urlsafe(48)
    encryption_key = base64.b64encode(secrets.token_bytes(32)).decode("ascii")
    java_process: subprocess.Popen[bytes] | None = None
    ai_process: subprocess.Popen[bytes] | None = None
    started: list[str] = []
    clients: list[httpx.Client] = []
    report: dict[str, Any] = {
        "runId": run_id,
        "modelSource": "historical managed-model profiles with optional 2560-dimensional model override",
        "modelProfiles": profiles,
        "servedModels": {},
        "retrievalStrategy": "baseline (vector only)",
        "indexCases": {},
        "authorization": {},
        "checks": {},
        "isolation": {"containers": names, "ports": ports, "storage": "tmpfs"},
    }
    return_code = 1

    def cleanup() -> None:
        """依次关闭隔离客户端、进程和归属于本次运行的容器，使失败场景也释放临时资源。

        上游为 `main`。
        """
        errors: list[str] = []
        for client in clients:
            try:
                client.close()
            except Exception as exc:
                errors.append(f"client cleanup: {exc}")
        for process in (ai_process, java_process):
            try:
                support.stop_process(process)
            except Exception as exc:
                errors.append(f"process cleanup: {exc}")
        for name in reversed(started):
            stopped = support.run(["docker", "stop", "--time", "5", name], timeout=20, check=False)
            if stopped.returncode and support.run(["docker", "container", "inspect", name], timeout=10, check=False).returncode == 0:
                errors.append(f"temporary container was not stopped: {name}")
        if errors:
            raise RuntimeError("; ".join(errors))

    try:
        for image in (support.POSTGRES_IMAGE, support.REDIS_IMAGE, support.MINIO_IMAGE):
            support.require_image(image)
        for label, profile in profiles.items():
            report["servedModels"][label] = verify_model(profile, endpoints[label])
        report["checks"]["bothModelTagsReturnedNativeDimensions"] = True

        support.start_container(names["pg"], support.POSTGRES_IMAGE, [
            "--tmpfs", "/var/lib/postgresql/data:rw,size=1073741824,mode=1777",
            "--env", f"POSTGRES_DB={database}", "--env", "POSTGRES_USER=postgres",
            "--env", f"POSTGRES_PASSWORD={pg_password}", "--publish", f"127.0.0.1:{ports['pg']}:5432",
        ])
        started.append(names["pg"])
        support.start_container(names["redis"], support.REDIS_IMAGE, [
            "--tmpfs", "/data:rw,size=128m", "--publish", f"127.0.0.1:{ports['redis']}:6379",
        ], ["redis-server", "--save", "", "--appendonly", "no"])
        started.append(names["redis"])
        support.start_container(names["minio"], support.MINIO_IMAGE, [
            "--tmpfs", "/data:rw,size=512m", "--env", f"MINIO_ROOT_USER={minio_user}",
            "--env", f"MINIO_ROOT_PASSWORD={minio_password}", "--publish", f"127.0.0.1:{ports['minio']}:9000",
        ], ["server", "/data", "--console-address", ":9001"])
        started.append(names["minio"])

        support.wait_until(
            "isolated PostgreSQL",
            lambda: support.run(["docker", "exec", names["pg"], "pg_isready", "-U", "postgres", "-d", database], timeout=5, check=False).returncode == 0,
            90,
        )
        support.wait_until(
            "isolated Redis",
            lambda: support.run(["docker", "exec", names["redis"], "redis-cli", "ping"], timeout=5, check=False).stdout.strip() == "PONG",
            60,
        )
        support.wait_until("isolated MinIO", lambda: support.http_ready(f"http://127.0.0.1:{ports['minio']}/minio/health/live"), 90)
        Minio(f"127.0.0.1:{ports['minio']}", access_key=minio_user, secret_key=minio_password, secure=False).make_bucket(bucket)
        report["checks"]["isolatedDependenciesReady"] = True

        env = isolated_environment()
        env.update({
            "JWT_SECRET": jwt_secret, "INTERNAL_API_TOKEN": internal_token,
            "POSTGRES_HOST": "127.0.0.1", "POSTGRES_PORT": str(ports["pg"]),
            "POSTGRES_DB": database, "POSTGRES_USER": "postgres", "POSTGRES_PASSWORD": pg_password,
            "REDIS_HOST": "127.0.0.1", "REDIS_PORT": str(ports["redis"]),
            "MINIO_ENDPOINT": f"http://127.0.0.1:{ports['minio']}", "MINIO_ACCESS_KEY": minio_user,
            "MINIO_SECRET_KEY": minio_password, "MINIO_BUCKET": bucket,
            "MODEL_CONFIG_ENCRYPTION_KEY": encryption_key,
            "SERVER_PORT": str(ports["java"]), "SERVER_ADDRESS": "127.0.0.1",
            "AI_BASE_URL": f"http://127.0.0.1:{ports['ai']}",
            "RETRIEVAL_VERSION": "baseline", "TASK_RELIABILITY_RECOVERY_ENABLED": "false",
        })
        java_process = support.start_process(
            ["cmd.exe", "/d", "/c", str(ROOT / "knowledge-platform" / "mvnw.cmd"),
             "-f", str(ROOT / "knowledge-platform" / "pom.xml"), "spring-boot:run"],
            env, run_dir / "java.log",
        )
        support.wait_until("isolated Java API", lambda: support.http_ready(f"http://127.0.0.1:{ports['java']}/actuator/health"), args.startup_timeout)
        report["checks"]["javaStartedFromCurrentSource"] = True

        ai_env = env.copy()
        ai_env.update({
            "PYTHONUTF8": "1", "PG_READONLY_HOST": "127.0.0.1", "PG_READONLY_PORT": str(ports["pg"]),
            "PG_READONLY_DB": database, "PG_READONLY_USER": "rag_readonly", "PG_READONLY_PASSWORD": support.READONLY_PASSWORD,
            "MINIO_ENDPOINT": f"127.0.0.1:{ports['minio']}", "MINIO_BUCKET": bucket,
            "INTERNAL_API_TOKEN": internal_token, "RETRIEVAL_LEG_TIMEOUT_MS": "20000",
        })
        ai_process = support.start_process(
            [sys.executable, "-m", "uvicorn", "ai.app.main:app", "--host", "127.0.0.1", "--port", str(ports["ai"])],
            ai_env, run_dir / "ai.log",
        )
        support.wait_until("isolated AI API", lambda: support.http_ready(f"http://127.0.0.1:{ports['ai']}/health"), args.startup_timeout)
        report["checks"]["aiStartedFromCurrentSource"] = True

        base_url = f"http://127.0.0.1:{ports['java']}"
        accounts: dict[str, dict[str, Any]] = {}
        for label in ("small", "large"):
            username = f"xkb_{label}_{uuid.uuid4().hex[:10]}"
            password = f"R{secrets.token_urlsafe(12)}!"
            client, login = logged_in_client(base_url, username, password)
            clients.append(client)
            model_id = create_model(client, profiles[label], run_id, endpoints[label])
            kb_id = create_kb(client, label, run_id)
            accounts[label] = {"client": client, "userId": int(login["id"]), "modelId": model_id, "kbId": kb_id}
        report["checks"]["bothCurrentModelConfigsRegisteredAndBound"] = True

        for label in ("small", "large"):
            account = accounts[label]
            case = PDF_CASES[label]
            report["indexCases"][label] = upload_and_index(
                account["client"], names["pg"], case["file"], account["kbId"],
                case["evidence"], profiles[label]["dimension"], database,
            )
            report["indexCases"][label]["sourcePdf"] = str(case["file"].relative_to(ROOT))
            report["indexCases"][label]["fileSha256"] = hashlib.sha256(case["file"].read_bytes()).hexdigest()
        report["checks"]["realIndexesWrittenFor1024And2560Dimensions"] = True

        small = accounts["small"]
        large = accounts["large"]

        # 用同一道问题对照授权前后，避免输入变化掩盖第二个向量空间是否进入可读范围。
        protected_case = PDF_CASES["large"]
        before = search(small["client"], protected_case["query"])
        before_kbs = {int(row.get("knowledgeBaseId", -1)) for row in before}
        report["authorization"]["beforeMembershipKnowledgeBaseIds"] = sorted(before_kbs)
        if before_kbs != {small["kbId"]}:
            raise RuntimeError(f"Expected only the owner's 1024-dimensional KB before grant: {before_kbs}")
        report["checks"]["foreignKbExcludedBeforeMembership"] = True

        call(large["client"], "POST", f"/knowledge-base/{large['kbId']}/members", json={"userId": small["userId"]})
        after = search(small["client"], protected_case["query"])
        permitted = next((
            row for row in after
            if int(row.get("knowledgeBaseId", -1)) == large["kbId"]
            and contains_evidence(row, protected_case["evidence"])
        ), None)
        after_kbs = {int(row.get("knowledgeBaseId", -1)) for row in after}
        report["authorization"]["afterMembershipKnowledgeBaseIds"] = sorted(after_kbs)
        if after_kbs != {small["kbId"], large["kbId"]}:
            raise RuntimeError(f"A single search did not retrieve both authorized vector spaces: {after_kbs}")
        if not permitted:
            raise RuntimeError("Authorized member could not retrieve the foreign KB's expected evidence")
        report["authorization"]["permittedResult"] = {
            "knowledgeBaseId": permitted.get("knowledgeBaseId"),
            "documentId": permitted.get("documentId"),
            "chunkId": permitted.get("id"),
            "contentExcerpt": str(permitted.get("content", ""))[:220],
        }
        report["checks"]["memberCanRetrieveForeignKbAfterGrant"] = True

        reverse = search(large["client"], PDF_CASES["small"]["query"])
        reverse_kbs = {int(row.get("knowledgeBaseId", -1)) for row in reverse}
        report["authorization"]["reverseKnowledgeBaseIds"] = sorted(reverse_kbs)
        if reverse_kbs != {large["kbId"]}:
            raise RuntimeError(f"Expected only the owner's 2560-dimensional KB on reverse search: {reverse_kbs}")
        report["checks"]["authorizationDoesNotGrantReverseAccess"] = True
        report["status"] = "passed"
        return_code = 0
    except Exception as exc:
        report["status"] = "failed"
        report["error"] = f"{type(exc).__name__}: {exc}"
    finally:
        try:
            cleanup()
        except Exception as exc:
            report["cleanupError"] = f"{type(exc).__name__}: {exc}"
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
