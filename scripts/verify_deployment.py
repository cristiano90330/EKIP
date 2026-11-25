"""检查目标部署入口及关联服务是否满足项目的基本运行条件。"""

from __future__ import annotations

import argparse
import json
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ENV_FILE = ROOT / ".env"
# 容器内访问用服务名，宿主机直连用 127.0.0.1；脚本默认按宿主机直连取证。
DEFAULT_AI = "http://127.0.0.1:8000"
DEFAULT_JAVA = "http://127.0.0.1:8080"


def load_env(path: Path) -> dict[str, str]:
    """读取选定环境配置文件中的变量映射，供部署端点和认证配置解析。

    上游为 `main`。
    """
    values: dict[str, str] = {}
    if not path.exists():
        return values
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        values[key.strip()] = value.strip().strip('"').strip("'")
    return values


def http(method: str, url: str, headers: dict[str, str] | None = None,
         body: dict | None = None, timeout: float = 120.0) -> tuple[int, dict | str, float]:
    """向目标部署发送指定方法和请求信息，返回探针可检查的状态及响应体。

    上游为 `register_temp_user`、`main`。
    """
    data = json.dumps(body).encode("utf-8") if body is not None else None
    request = urllib.request.Request(url, data=data, method=method)
    request.add_header("Accept", "application/json")
    if data is not None:
        request.add_header("Content-Type", "application/json")
    for key, value in (headers or {}).items():
        request.add_header(key, value)
    started = time.time()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read().decode("utf-8", errors="replace")
            elapsed = time.time() - started
            try:
                return response.status, json.loads(raw), elapsed
            except json.JSONDecodeError:
                return response.status, raw[:400], elapsed
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8", errors="replace")
        elapsed = time.time() - started
        try:
            return exc.code, json.loads(raw), elapsed
        except json.JSONDecodeError:
            return exc.code, raw[:400], elapsed
    except Exception as exc:                      # noqa: BLE001 — 网络层失败要如实记录
        return 0, f"{type(exc).__name__}: {exc}", time.time() - started


def capability_rows(ready: dict) -> list[str]:
    """把服务就绪结果中的能力项格式化为部署报告行。

    上游为 `main`。
    """
    rows = []
    for name, cap in (ready.get("capabilities") or {}).items():
        rows.append(
            f"  {name:<13} {cap.get('status', '?'):<10} reason={cap.get('reasonCode') or '-'}"
            f" scorer={cap.get('scorer') or '-'} policy={cap.get('policyVersion') or '-'}"
        )
    return rows


def register_temp_user(java_base: str) -> str | None:
    """注册并登录一次性部署验证账号，返回后续探针可用的认证凭据。

    上游为 `main`，下游调用 `http`。
    """
    username = "verify" + time.strftime("%H%M%S")
    password = f"Vf{int(time.time()) % 100000}{username[-3:]}"
    http("POST", f"{java_base}/user/register", body={"username": username, "password": password}, timeout=30)
    status, body, _ = http("POST", f"{java_base}/user/login", body={"username": username, "password": password}, timeout=30)
    if status != 200 or not isinstance(body, dict):
        print(f"  临时用户登录失败: {status}")
        return None
    token = (body.get("data") or {}).get("token")
    if token:
        print(f"  临时验证用户: {username}")
    return token


def main() -> int:
    """对目标部署执行存活、就绪及业务入口检查，并输出各项观察结果。

    下游调用 `load_env`、`http`。
    """
    parser = argparse.ArgumentParser()
    parser.add_argument("--ai-base", default=DEFAULT_AI)
    parser.add_argument("--java-base", default=DEFAULT_JAVA)
    parser.add_argument("--kb", type=int, help="用于检索验证的知识库 ID（Java 已授权的范围）")
    parser.add_argument("--question", default="什么是向量检索")
    parser.add_argument("--token", help="Java JWT；提供时读取 /observability/capabilities")
    parser.add_argument("--register-user", action="store_true",
                        help="临时注册一个验证用户并登录，用其令牌读取 Java 侧能力快照")
    parser.add_argument("--skip-generate", action="store_true")
    args = parser.parse_args()

    env = load_env(ENV_FILE)
    internal_token = env.get("INTERNAL_API_TOKEN", "")
    if not internal_token:
        print("FAIL 仓库根 .env 缺少 INTERNAL_API_TOKEN，无法调用 AI 服务")
        return 2

    failures = 0
    ai_headers = {"X-Internal-Token": internal_token}

    print("== AI 服务 ==")
    status, body, elapsed = http("GET", f"{args.ai_base}/health", timeout=10)
    print(f"  /health           {status} {round(elapsed, 2)}s")
    failures += status != 200

    status, ready, elapsed = http("GET", f"{args.ai_base}/ready", ai_headers, timeout=30)
    print(f"  /ready            {status} {round(elapsed, 2)}s  status={ready.get('status') if isinstance(ready, dict) else ready}")
    if isinstance(ready, dict):
        for row in capability_rows(ready):
            print(row)
    else:
        failures += 1

    if args.kb:
        query = {
            "knowledgeBaseIds": [args.kb],
            "question": args.question,
            "topK": 5,
            "version": "hybrid-rerank-rewrite",
            "generate": False,
            "admitEvidence": True,
        }
        status, result, elapsed = http("POST", f"{args.ai_base}/rag/query", ai_headers, query)
        print(f"  /rag/query(检索)  {status} {round(elapsed, 2)}s")
        if isinstance(result, dict):
            sources = result.get("sources") or []
            evidence = result.get("evidenceSources") or []
            scorers = sorted({(s.get("scorer") or "none") for s in sources})
            print(f"  retrievalStatus={result.get('retrievalStatus')} outcome={result.get('status')}")
            print(f"  sources={len(sources)} evidence={len(evidence)} scorers={scorers}")
            print(f"  evidenceDecision={result.get('evidenceDecision')} reason={result.get('reasonCode')}")
            if result.get("stages"):
                stages = ", ".join(f"{s.get('name') or s.get('stage')}={s.get('durationMs')}ms"
                                   for s in result["stages"])
                print(f"  stages: {stages}")
        else:
            failures += 1

        if not args.skip_generate:
            query["generate"] = True
            query["admitEvidence"] = False
            status, result, elapsed = http("POST", f"{args.ai_base}/rag/query", ai_headers, query)
            print(f"  /rag/query(生成)  {status} {round(elapsed, 2)}s")
            if isinstance(result, dict):
                answer = result.get("answer") or ""
                usage = result.get("usage") or {}
                print(f"  status={result.get('status')} provider={result.get('providerUsed')}"
                      f" scorer={result.get('evidenceScorer')} answerChars={len(answer)}")
                print(f"  tokens={usage.get('totalTokens')} reason={result.get('reasonCode')}")
            else:
                failures += 1

    print("== Java 服务 ==")
    status, body, elapsed = http("GET", f"{args.java_base}/actuator/health", timeout=10)
    state = body.get("status") if isinstance(body, dict) else body
    print(f"  /actuator/health  {status} {round(elapsed, 2)}s status={state}")
    failures += status != 200

    if not args.token and args.register_user:
        args.token = register_temp_user(args.java_base.rstrip("/"))

    if args.token:
        headers = {"Authorization": f"Bearer {args.token}"}
        status, caps, elapsed = http("GET", f"{args.java_base}/observability/capabilities", headers, timeout=15)
        print(f"  /observability/capabilities {status} {round(elapsed, 2)}s")
        if isinstance(caps, dict) and isinstance(caps.get("data"), dict):
            data = caps["data"]
            print(f"  overall={data.get('status')} checkedAt={data.get('checkedAt')}")
            for row in capability_rows(data):
                print(row)
        else:
            failures += 1

    print(f"\n结果：{'通过' if failures == 0 else f'{failures} 项失败'}")
    return 0 if failures == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
