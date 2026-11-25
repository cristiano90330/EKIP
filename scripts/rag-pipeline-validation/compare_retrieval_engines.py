# 用相同请求对照两种检索问答编排的阶段、输出和结果差异。

"""Compare production retrieval engines through Java's authorized search endpoint.

No DB/model configuration or .env is read. There are no ingest, conversation, model
generation or data-write calls. The existing backend/AI services must be running.
The result is retrieval-parity evidence, not answer-quality acceptance.
"""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
from time import perf_counter
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener


class ProbeError(RuntimeError):
    """Short safe failure messages; remote response bodies are never printed."""


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        """触发 `ProbeError`，交由上游处理。

        输入为 `req`、`fp`、`code`、`msg`、`headers`、`newurl`。
        """
        raise ProbeError("HTTP redirect refused; credentials stay on the configured backend")


def _loopback_base(value):
    """验证待比较的服务地址指向允许的本机 HTTP 入口。

    上游为 `__init__`。
    """
    try:
        parsed = urlsplit(value)
        port = parsed.port
    except ValueError as exc:
        raise ProbeError("backend URL is invalid") from exc
    if (parsed.scheme != "http" or parsed.hostname not in {"127.0.0.1", "localhost", "::1"}
            or parsed.username is not None or parsed.password is not None
            or parsed.query or parsed.fragment or parsed.path not in {"", "/"}
            or port is None or not 1 <= port <= 65535):
        raise ProbeError("backend URL must be a direct HTTP loopback origin with an explicit port")
    return value.rstrip("/")


class Client:
    def __init__(self, base, token, timeout):
        """创建实例并初始化 `base`、`token`、`timeout`、`opener`，供后续方法共享。

        输入为 `base`、`token`、`timeout`。
        下游调用 `_loopback_base`。
        """
        self.base = _loopback_base(base)
        self.token = token
        self.timeout = timeout
        # Do not forward the user's bearer token through configured proxies or redirects.
        self.opener = build_opener(ProxyHandler({}), NoRedirect())

    def get(self, path, parameters=None):
        """返回 `json.loads` 的结果。

        输入为 `path`、`parameters`。
        """
        query = "?" + urlencode(parameters) if parameters else ""
        request = Request(self.base + path + query, headers={
            "Authorization": "Bearer " + self.token,
            "Accept": "application/json",
        })
        try:
            with self.opener.open(request, timeout=self.timeout) as response:
                raw = response.read(8 * 1024 * 1024 + 1)
        except HTTPError as exc:
            # Response bodies and exception URLs may carry private values, so omit them.
            raise ProbeError(f"backend returned HTTP {exc.code} for {path}") from None
        except (URLError, TimeoutError, OSError):
            raise ProbeError(f"backend connection failed for {path}") from None
        if len(raw) > 8 * 1024 * 1024:
            raise ProbeError(f"response exceeded the probe size limit for {path}")
        try:
            return json.loads(raw)
        except (ValueError, UnicodeError):
            raise ProbeError(f"backend did not return valid JSON for {path}") from None


def _data(value, expected):
    """读取响应包装并核验负载满足期望类型，供对照步骤消费。

    上游为 `_trace_list`、`_run_engine`。
    """
    if not isinstance(value, dict) or value.get("code") != 200:
        raise ProbeError("backend business response was not successful")
    data = value.get("data")
    if not isinstance(data, expected):
        raise ProbeError("backend response shape does not match the current contract")
    return data


def _trace_list(client):
    """查询本次对照账号能看到的链路记录，供结果补充阶段信息。

    上游为 `_run_engine`，下游调用 `_data`。
    """
    return _data(client.get("/observability/trace", {"limit": 200, "entrypoint": "search"}), list)


def _source_summary(rows, authorized_ids):
    """将检索返回的来源整理为可比较摘要，并核对知识库归属。

    上游为 `_run_engine`。
    """
    if not isinstance(rows, list):
        raise ProbeError("search response must contain the ranked source list")
    summaries = []
    for row in rows:
        if not isinstance(row, dict):
            raise ProbeError("search source has an invalid shape")
        kb = row.get("knowledgeBaseId")
        if isinstance(kb, bool) or not isinstance(kb, int) or kb not in authorized_ids:
            raise ProbeError("search returned a source outside the observed authorized scope")
        for key in ("id", "documentId", "chunkIndex"):
            if isinstance(row.get(key), bool) or not isinstance(row.get(key), int):
                raise ProbeError("search source identity is missing or malformed")
        distance = row.get("distance")
        if distance is not None and (isinstance(distance, bool) or not isinstance(distance, (float, int))
                                     or not math.isfinite(distance)):
            raise ProbeError("search source distance is invalid")
        content = row.get("content")
        if not isinstance(content, str):
            raise ProbeError("search source content is malformed")
        summaries.append({
            "chunkId": row["id"], "documentId": row["documentId"],
            "knowledgeBaseId": kb, "chunkIndex": row["chunkIndex"], "distance": distance,
            "contentSha256": hashlib.sha256(content.encode("utf-8")).hexdigest(),
        })
    return summaries


def _run_engine(client, engine, query, top_k, authorized_ids):
    """以指定引擎对同一问题执行检索，返回耗时、结果及阶段记录。

    上游为 `compare`，下游调用 `_trace_list`、`_source_summary`。
    """
    existing = {row.get("traceId") for row in _trace_list(client) if isinstance(row, dict)}
    started = perf_counter()
    sources = client.get("/knowledge/search", {
        "query": query, "topK": top_k, "pipelineEngine": engine,
    })
    elapsed_ms = round((perf_counter() - started) * 1000)
    summaries = _source_summary(sources, authorized_ids)
    fresh = [row for row in _trace_list(client)
             if isinstance(row, dict) and row.get("traceId") not in existing
             and row.get("originalQuery") == query.strip() and row.get("entrypoint") == "search"]
    if len(fresh) != 1:
        raise ProbeError("cannot uniquely identify the new search Trace; retry without concurrent identical searches")
    summary = fresh[0]
    trace_id = summary.get("traceId")
    if not isinstance(trace_id, str) or not trace_id or any(
            char not in "0123456789abcdefABCDEF-" for char in trace_id):
        raise ProbeError("search Trace id is malformed")
    trace = _data(client.get("/observability/trace/" + trace_id), dict)
    if trace.get("pipelineEngine") != engine:
        raise ProbeError("actual pipeline in Trace differs from the requested engine")
    if not isinstance(trace.get("retrievalVersion"), str) or not trace["retrievalVersion"]:
        raise ProbeError("Trace is missing the actual retrieval strategy")
    stages = []
    for span in trace.get("spans") or []:
        if not isinstance(span, dict):
            raise ProbeError("search Trace stage is malformed")
        stages.append({key: span.get(key) for key in ("stage", "durationMs", "totalTokens", "success")})
    return {
        "pipelineEngine": trace["pipelineEngine"], "retrievalVersion": trace["retrievalVersion"],
        "traceId": trace_id, "outcome": trace.get("outcome"), "reasonCode": trace.get("reasonCode"),
        "requestElapsedMs": elapsed_ms, "stages": stages, "sources": summaries,
    }


def compare(client, query, top_k):
    """在同一授权范围和输入下运行两个编排引擎，输出可核对的结果差异。

    上游为 `main`，下游调用 `_data`、`_run_engine`。
    """
    bases = _data(client.get("/knowledge-base/list"), list)
    authorized_ids = {row["id"] for row in bases if isinstance(row, dict)
                      and isinstance(row.get("id"), int) and not isinstance(row["id"], bool)}
    runs = [_run_engine(client, engine, query, top_k, authorized_ids)
            for engine in ("fixed", "langchain")]
    same_sources = runs[0]["sources"] == runs[1]["sources"]
    same_strategy = runs[0]["retrievalVersion"] == runs[1]["retrievalVersion"]
    same_outcome = (runs[0]["outcome"], runs[0]["reasonCode"]) == (
        runs[1]["outcome"], runs[1]["reasonCode"])
    report = {
        "scope": "Java-authenticated global knowledge search; generation is disabled",
        "querySha256": hashlib.sha256(query.encode("utf-8")).hexdigest(), "topK": top_k,
        "authorizedKnowledgeBaseCount": len(authorized_ids),
        "sameSourcesAndContent": same_sources, "sameRetrievalStrategy": same_strategy,
        "sameOutcome": same_outcome, "runs": runs,
        "limitation": "One retrieval sample; does not establish answer correctness or calibrated evidence support",
    }
    # Empty scope/results or dependency failure must not be described as a live retrieval pass.
    usable = all(run["outcome"] == "success" and run["sources"] for run in runs)
    report["passed"] = bool(usable and same_sources and same_strategy and same_outcome)
    return report


def main():
    """解析本机服务、问题及账号参数，执行引擎对照并输出报告。

    下游调用 `compare`。
    """
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--backend-url", default="http://127.0.0.1:8080")
    parser.add_argument("--query", required=True, help="Use an existing KB question; no new data is created")
    parser.add_argument("--top-k", type=int, default=5)
    parser.add_argument("--timeout", type=float, default=45.0)
    parser.add_argument("--output", type=Path, help="Optional local JSON report containing hashes and source identities")
    args = parser.parse_args()
    try:
        token = os.environ.get("EKIP_LAB_ACCESS_TOKEN", "").strip()
        if not token or "\r" in token or "\n" in token:
            raise ProbeError("set EKIP_LAB_ACCESS_TOKEN to your existing Java login JWT")
        if not args.query.strip() or len(args.query) > 4000:
            raise ProbeError("query must contain between 1 and 4000 characters")
        if not 1 <= args.top_k <= 50 or not 0 < args.timeout <= 120:
            raise ProbeError("top-k must be 1..50 and timeout must be positive and at most 120 seconds")
        report = compare(Client(args.backend_url, token, args.timeout), args.query, args.top_k)
        result = json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False)
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(result + "\n", encoding="utf-8")
        print(result)
        return 0 if report["passed"] else 1
    except ProbeError as exc:
        print(json.dumps({"passed": False, "error": str(exc)}, ensure_ascii=False))
        return 2
    except OSError:
        print(json.dumps({"passed": False, "error": "cannot save the local report"}))
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
