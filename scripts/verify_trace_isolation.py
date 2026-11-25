"""使用不同身份查询链路记录，检查观测记录的账号隔离。"""

from __future__ import annotations

import argparse
import json
import random
import string
import time
import urllib.error
import urllib.request

DEFAULT_JAVA = "http://127.0.0.1:8080"


def http(method: str, url: str, headers: dict[str, str] | None = None,
         body: dict | None = None, timeout: float = 60.0) -> tuple[int, object]:
    """向目标 Java 服务发送带必要认证的请求，并保留响应状态和原始正文。

    上游为 `register_and_login`，下游调用 `_json_or_text`。
    """
    data = json.dumps(body).encode("utf-8") if body is not None else None
    request = urllib.request.Request(url, data=data, method=method)
    request.add_header("Accept", "application/json")
    if data is not None:
        request.add_header("Content-Type", "application/json")
    for key, value in (headers or {}).items():
        request.add_header(key, value)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read().decode("utf-8", errors="replace")
            return response.status, _json_or_text(raw)
    except urllib.error.HTTPError as exc:
        return exc.code, _json_or_text(exc.read().decode("utf-8", errors="replace"))
    except Exception as exc:                      # noqa: BLE001
        return 0, f"{type(exc).__name__}: {exc}"


def _json_or_text(raw: str):
    """尝试解码响应 JSON，解析失败时保留原始字符串供错误诊断。

    上游为 `http`。
    """
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return raw[:300]


def register_and_login(base: str, username: str) -> tuple[str, str] | None:
    """为指定名字建立临时账号及会话，返回隔离对比所需的认证信息。

    上游为 `main`，下游调用 `http`。
    """
    password = "".join(random.choice(string.ascii_letters + string.digits) for _ in range(16))
    status, body = http("POST", f"{base}/user/register",
                        body={"username": username, "password": password})
    if status not in (200, 400, 409):
        print(f"  注册 {username} 失败: {status} {body}")
        return None
    status, body = http("POST", f"{base}/user/login",
                        body={"username": username, "password": password})
    if status != 200 or not isinstance(body, dict):
        print(f"  登录 {username} 失败: {status} {body}")
        return None
    token = (body.get("data") or {}).get("token")
    if not token:
        print(f"  登录 {username} 未返回 token")
        return None
    return username, token


def trigger_trace(base: str, token: str) -> None:
    """以当前账号发起会记录追踪的请求，为账号隔离检查准备数据。

    上游为 `main`，下游调用 `http`。
    """
    status, _ = http("GET", f"{base}/knowledge/search?query=%E5%90%91%E9%87%8F%E6%A3%80%E7%B4%A2&topK=3",
                     {"Authorization": f"Bearer {token}"})


def list_traces(base: str, token: str) -> list[dict]:
    """获取当前令牌允许读取的链路记录列表，用于对照其他账号的可见范围。

    上游为 `main`，下游调用 `http`。
    """
    status, body = http("GET", f"{base}/observability/trace?limit=10",
                        {"Authorization": f"Bearer {token}"})
    if status != 200 or not isinstance(body, dict):
        print(f"  列表失败: {status} {body}")
        return []
    return body.get("data") or []


def main() -> int:
    """对不同账号分别生成和读取跟踪记录，输出跨账号隔离的检查结果。

    下游调用 `register_and_login`、`trigger_trace`。
    """
    parser = argparse.ArgumentParser()
    parser.add_argument("--java-base", default=DEFAULT_JAVA)
    args = parser.parse_args()
    base = args.java_base.rstrip("/")
    suffix = time.strftime("%H%M%S")
    users = [f"bd03a{suffix}", f"bd03b{suffix}"]

    print("== 建立两个隔离用户 ==")
    sessions = []
    for name in users:
        session = register_and_login(base, name)
        if session is None:
            return 1
        sessions.append(session)
        print(f"  {name} 就绪")

    print("== 各自触发一次检索（落 Trace） ==")
    for name, token in sessions:
        trigger_trace(base, token)
        print(f"  {name} 已请求 /knowledge/search")

    traces = {}
    for name, token in sessions:
        rows = list_traces(base, token)
        traces[name] = rows
        ids = [row.get("traceId") for row in rows]
        print(f"  {name} 可见 {len(rows)} 条: {ids}")

    failures = 0
    name_a, token_a = sessions[0]
    name_b, token_b = sessions[1]
    a_ids = {row.get("traceId") for row in traces[name_a]}
    b_ids = {row.get("traceId") for row in traces[name_b]}

    if not a_ids or not b_ids:
        print("  FAIL 至少一方没有落 Trace，无法判断隔离")
        return 1

    shared = a_ids & b_ids
    print(f"== 列表隔离 ==\n  A∩B = {shared or '空'}")
    if shared - {"unknown"}:
        # traceId 在无 Micrometer span 时退化为 "unknown"，同名不代表同一条记录。
        print("  FAIL 双方看到同一 traceId，隔离未生效")
        failures += 1
    else:
        print("  OK 列表只含本人记录")

    foreign = next(iter(b_ids))
    status, body = http("GET", f"{base}/observability/trace/{foreign}",
                        {"Authorization": f"Bearer {token_a}"})
    code = body.get("code") if isinstance(body, dict) else None
    print(f"== 跨用户按 ID 读取 ==\n  A 读取 B 的 {foreign}: HTTP {status} code={code}")
    if status == 404 and code == 404:
        print("  OK 404 状态与业务码一致")
    else:
        print("  FAIL 期望 HTTP 404 + code 404")
        failures += 1

    own = next(iter(a_ids))
    status, body = http("GET", f"{base}/observability/trace/{own}",
                        {"Authorization": f"Bearer {token_a}"})
    print(f"== 读取本人记录 ==\n  A 读取 {own}: HTTP {status}")
    if status != 200:
        print("  FAIL 本人记录应可读")
        failures += 1
    else:
        print("  OK 本人记录可读")

    print(f"\n结果：{'通过' if failures == 0 else f'{failures} 项失败'}（验证用户：{', '.join(users)}）")
    return 0 if failures == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
