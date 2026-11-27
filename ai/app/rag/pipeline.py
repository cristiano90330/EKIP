"""编排问答预算、改写、召回、证据准入和生成，并构造同步结果及 SSE 事件。"""

import contextvars
import logging
import re
from contextlib import closing, contextmanager, nullcontext
from contextvars import ContextVar
from dataclasses import replace
from functools import partial
from math import isfinite
from time import monotonic

from ai.app.config import (
    RAG_DEV_TEST_MODE,
    RAG_EVIDENCE_MODE,
    RAG_EVIDENCE_POLICY_VERSION,
    RAG_QUERY_DEADLINE_MS,
    RAG_QUERY_MAX_HISTORY_MESSAGES,
    RAG_QUERY_MAX_HISTORY_CHARS,
    RAG_QUERY_MAX_RECALL_N,
    RAG_QUERY_MAX_TOP_K,
    RETRIEVAL_LEG_TIMEOUT_MS,
)
from ai.app.observability.stage_trace import collecting, log_summary, record, stage
from ai.app.query.router import rewrite
from ai.app.query.schema import RewriteRequest
from ai.app.rag.postprocess import CompressionStats, apply_postprocess, compress_extractive
from ai.app.rag.deadline import (
    QueryDeadlineExceeded,
    current_request_started_at,
    current_deadline_at,
    deadline_expired,
    query_deadline,
    remaining_seconds,
)
from ai.app.rag.rag_service import GenerationResult, rag_service
from ai.app.rag.schema import (
    ContextItem,
    RagQueryCompression,
    RagEvidenceDecision,
    RagQueryRequest,
    RagQueryResponse,
    RagQuerySource,
    RagQueryStage,
    VALID_COMPRESSION_MODES,
)
from ai.app.rerank.router import rerank
from ai.app.rerank.schema import RerankCandidate, RerankRequest
from ai.app.retrieval.retriever import (
    RRF_K,
    Candidate,
    PartialVectorRetrieval,
    expand_parent_chunks,
    fan_out_legs,
    fuse,
    keyword_retrieve,
    read_document_window,
    vector_retrieve,
)
from ai.app.retrieval.db import ReadOnlyPgError
from ai.app.embedding.errors import EmbeddingUnavailableError
from ai.app.llm import telemetry
from ai.app.llm.guard import gate
from ai.app.rag.state import QueryState
from ai.app.rag.evidence_support import (
    POLICY_VERSION as SUPPORT_POLICY_VERSION, review, preserves_support, support_fragments,
)
from ai.app.rag.prompt_builder import build_prompt
from ai.app.llm.tokens import count_tokens, input_tokens
from ai.app.llm.context_input import organizing, unique_paragraphs
from ai.app.rag.rag_service import generation_contract
from ai.app.llm.purposes import get_purpose
from ai.app.models.context import model_execution, evidence_threshold
from ai.app.models.resolution import ModelConfigurationError
from ai.app.models.runtime import ModelCallError

logger = logging.getLogger("ekip.rag.query")

VALID_VERSIONS = ("baseline", "hybrid", "hybrid-rerank", "hybrid-rerank-rewrite")
VALID_PIPELINE_ENGINES = ("fixed", "langchain")
_CURRENT_STAGE: ContextVar[str | None] = ContextVar("rag_failed_stage", default=None)
_LAST_STAGE: ContextVar[str | None] = ContextVar("rag_last_stage", default=None)
_FAILED_STAGE: ContextVar[str | None] = ContextVar("rag_exception_stage", default=None)
_RETRIEVAL_STATUS: ContextVar[tuple[str, str]] = ContextVar(
    "rag_retrieval_status", default=("complete", "none"))


class RetrievalUnavailableError(RuntimeError):
    """All retrieval legs failed or no healthy leg could return usable results."""


@contextmanager
def _at_stage(name: str):
    """记录当前执行阶段及失败位置，使预算或依赖异常可以带阶段名返回给 Java。

    上游为 `ai.app.rag.langchain_pipeline._retrieve`、`_rerank_top`。
    """
    token = _CURRENT_STAGE.set(name)
    _LAST_STAGE.set(name)
    try:
        yield
    except Exception as exc:
        if _FAILED_STAGE.get() is None:
            _FAILED_STAGE.set(name)
        # Framework nodes can run in copied contexts; failure location must
        # travel with the exception as well as through request-local ContextVars.
        if not hasattr(exc, "rag_failed_stage"):
            exc.rag_failed_stage = name
        raise
    finally:
        _CURRENT_STAGE.reset(token)


def current_failed_stage() -> str | None:
    """读出最近失败的 RAG 阶段名，供错误响应和追踪生成使用。

    上游为 `run_query`、`_stream_events`。
    """
    return _FAILED_STAGE.get() or _CURRENT_STAGE.get() or _LAST_STAGE.get()


def prepare_pipeline_engines() -> None:
    """在应用启动时提前装配辅助问答引擎，供后续查询复用。

    上游为 `ai.app.main.lifespan`，下游调用 `ai.app.rag.langchain_pipeline.prepare`。
    """
    from ai.app.rag.langchain_pipeline import prepare
    prepare()


def _rerank_top(candidates: list[Candidate], query: str, top_k: int) -> list[Candidate]:
    """用当前重排绑定为选定候选评分，返回调整顺序的 Top-K，并记录重排的降级结果。

    上游为 `ai.app.rag.langchain_pipeline._retrieve`，下游调用 `_at_stage`、`ai.app.observability.stage_trace.stage`。
    """
    if not candidates:
        return []
    with _at_stage("rerank"), stage("rerank") as pending:
        resp = rerank(RerankRequest(
            query=query,
            candidates=[RerankCandidate(id=c.id, content=c.content) for c in candidates],
        ))
        # 将评分回退记录为阶段降级；阶段成功字段仍表示调用本身是否完成。
        pending.degraded = bool(getattr(resp, "degraded", False))
    ids = list(resp.ids or [])
    scores = list(resp.scores or [])
    expected_ids = [c.id for c in candidates]
    mapping_valid = (
        len(ids) == len(scores) == len(expected_ids)
        and len(set(ids)) == len(ids)
        and set(ids) == set(expected_ids)
    )
    score_by_id = dict(zip(ids, scores)) if mapping_valid else {}
    invalid_ids = set(resp.unscoredIds or [])
    for c in candidates:
        c.score_source = "rerank"
        c.rerank_scorer = resp.scorer or "none"
        c.rerank_degraded = bool(resp.degraded)
        if not mapping_valid:
            c.score = None
            c.score_valid = False
            c.score_error = "invalid_score_mapping"
            pending.error = "invalid_score_mapping"
            continue
        value = score_by_id.get(c.id)
        try:
            numeric = float(value)
        except (TypeError, ValueError, OverflowError):
            numeric = float("nan")
        c.score = numeric if isfinite(numeric) else None
        c.score_valid = (
            c.id not in invalid_ids and isfinite(numeric) and 0.0 <= numeric <= 1.0
        )
        c.score_error = "" if c.score_valid else (
            "unscored" if c.id in invalid_ids else "invalid_score")
    # 降序、未映射（None）排末尾；Python sort 稳定 → 并列时保持 RRF 相对序（对齐 Java）
    ranked = sorted(
        candidates,
        key=lambda c: (c.score is None, -(c.score if c.score is not None else 0.0)),
    )
    return ranked[:top_k]


# 在指定知识库范围内并行执行向量及词项召回，交给 _fuse_fanout 合并候选。
# 上游为 `_retrieve`，下游调用 `ai.app.retrieval.retriever.vector_retrieve`、`ai.app.retrieval.retriever.keyword_retrieve`。
def _fuse_legs(kb_ids, query: str, recall_n: int, rrf_k: int = RRF_K,
               *, legs=None, document_id: int | None = None) -> list[Candidate]:
    """并行跑向量 + 关键词两路（**单路硬超时、超时按空结果参与 RRF**），再 RRF 融合。

    该逻辑与 Java `RetrievalService.fuseCandidates` 保持一致：两路 fan-out，单路超时（`RETRIEVAL_LEG_TIMEOUT_MS`，
    对齐 `rag.budget.retrieval-ms`）只是把该路降级成空结果，另一路照常出结果；
    两路都超时 → 空候选 → 上层空证据短路。**非超时异常照旧抛出**。

    指定 document_id 时两路都把该文档与知识库范围一起绑定到 SQL。
    """
    if legs is None:
        if document_id is None:
            legs = {
                "vector": lambda: vector_retrieve(kb_ids, query, recall_n),
                "keyword": lambda: keyword_retrieve(kb_ids, query, recall_n),
            }
        else:
            legs = {
                "vector": lambda: vector_retrieve(kb_ids, query, recall_n, document_id),
                "keyword": lambda: keyword_retrieve(kb_ids, query, recall_n, document_id),
            }
    fanout = fan_out_legs(
        legs,
        RETRIEVAL_LEG_TIMEOUT_MS,
        deadline_at=current_deadline_at(),
    )
    return _fuse_fanout(fanout, recall_n, rrf_k)


def _fuse_fanout(fanout, recall_n: int, rrf_k: int = RRF_K) -> list[Candidate]:
    """根据两路召回完成情况选择单路或融合结果，必要时报告部分可用或完全失败。

    上游为 `ai.app.rag.langchain_pipeline._fuse_documents`，下游调用 `ai.app.rag.deadline.deadline_expired`、`ai.app.rag.deadline.QueryDeadlineExceeded`。
    """
    results, timed_out, durations = fanout
    # 两路耗时在 worker 里测好后带回调用线程再 record——ThreadPoolExecutor 不复制 contextvars，
    # 在 worker 里 collect 是收集不到的（见 observability/stage_trace.py 的说明）。
    statuses = fanout.statuses
    for leg in ("vector", "keyword"):
        state = statuses.get(leg, "capacity_rejected")
        record(f"{leg}_retrieval", durations.get(leg, 0),
               success=state == "ok", degraded=state != "ok", error=fanout.failures.get(leg, state if state != "ok" else ""))
    unavailable = [name for name, state in statuses.items() if state != "ok"]
    any_results = bool(results.get("vector") or results.get("keyword"))
    if deadline_expired():
        raise QueryDeadlineExceeded("query deadline expired during retrieval fan-out")
    if unavailable and not any_results:
        # A successful empty leg cannot prove retrieval completed when the other required
        # leg timed out. Keep that distinct so a necessary retrieval timeout maps to 504.
        status = "unavailable" if all(state != "ok" for state in statuses.values()) else "partial"
        code = "query_deadline_exceeded" if any(
            state == "timeout" for state in statuses.values()
        ) else ("retrieval_unavailable" if status == "unavailable" else "partial_retrieval")
        _RETRIEVAL_STATUS.set((status, code))
        error = RetrievalUnavailableError("retrieval returned no results from an available leg")
        error.reason_code = code
        error.retrieval_status = status
        raise error
    if unavailable:
        _RETRIEVAL_STATUS.set(("partial", "partial_retrieval"))
        # 记录部分召回状态，便于区分完整召回与降级结果。
        logger.warning(
            "召回降级：legs=%s 不可用（预算/依赖/容量），按空结果参与 RRF；其余 leg 照常出结果",
            unavailable,
        )
    else:
        _RETRIEVAL_STATUS.set(("complete", "none"))
    with stage("fusion"):
        return fuse(results.get("vector", []), results.get("keyword", []), recall_n, rrf_k)


# 按请求策略选择召回与重排路径，返回供证据准入使用的候选列表。
# 下游调用 `_at_stage`、`ai.app.observability.stage_trace.stage`。
def _retrieve(kb_ids, query: str, top_k: int, recall_n: int, version: str,
              rrf_k: int = RRF_K, deadline_at: float | None = None,
              document_id: int | None = None) -> list[Candidate]:
    """按检索策略执行向量或混合召回，并在需要时完成融合与重排。

    注意档位差异：**`baseline` 是纯向量、不走两路 fan-out**，故没有单路超时/降级；
    `hybrid` 及以上才并行召回 + RRF。指定 document_id 时同样在每条检索 SQL 中绑定。
    """
    if not kb_ids:
        _RETRIEVAL_STATUS.set(("complete", "none"))
        return []
    if version == "baseline":
        with _at_stage("vector_retrieval"), stage("vector_retrieval"):
            result = (vector_retrieve(kb_ids, query, top_k) if document_id is None
                      else vector_retrieve(kb_ids, query, top_k, document_id))
        _RETRIEVAL_STATUS.set(("complete", "none"))
        return result
    if version == "hybrid":
        return _fuse_legs(kb_ids, query, recall_n, rrf_k,
                          document_id=document_id)[:top_k]
    if version == "hybrid-rerank":
        return _rerank_top(_fuse_legs(kb_ids, query, recall_n, rrf_k,
                                      document_id=document_id), query, top_k)
    if version == "hybrid-rerank-rewrite":
        return _rerank_top(_fuse_legs(kb_ids, query, recall_n, rrf_k,
                                      document_id=document_id), query, top_k)
    raise ValueError(f"非法 retrieval.version={version!r}（合法值 {list(VALID_VERSIONS)}）")


def _retrieve_with_status(kb_ids, query: str, top_k: int, recall_n: int, version: str,
                          rrf_k: int = RRF_K, deadline_at: float | None = None,
                          *, document_id: int | None = None, retrieve=None):
    """围绕召回调用保留候选、降级状态和错误原因，供查询状态机生成终态。

    上游为 `retrieve_query`。
    """
    token = _RETRIEVAL_STATUS.set(("complete", "none"))
    try:
        retrieve_fn = retrieve or _retrieve
        if deadline_at is None:
            candidates = retrieve_fn(kb_ids, query, top_k, recall_n, version, rrf_k,
                                     document_id=document_id)
        else:
            candidates = retrieve_fn(kb_ids, query, top_k, recall_n, version, rrf_k,
                                     deadline_at, document_id=document_id)
        retrieval_status, reason_code = _RETRIEVAL_STATUS.get()
        return candidates, retrieval_status, reason_code
    except RetrievalUnavailableError as exc:
        return [], getattr(exc, "retrieval_status", "unavailable"), getattr(
            exc, "reason_code", "retrieval_unavailable")
    except PartialVectorRetrieval as exc:
        return exc.results, "partial" if exc.results else "unavailable", "partial_retrieval" if exc.results else "retrieval_unavailable"
    except (ReadOnlyPgError, EmbeddingUnavailableError):
        return [], "unavailable", "retrieval_unavailable"
    finally:
        _RETRIEVAL_STATUS.reset(token)


def _to_source(c: Candidate) -> RagQuerySource:
    """将检索候选转换为 Java 可识别的来源对象，保留正文、分数和文档定位信息。

    上游为 `retrieve_query`，下游调用 `ai.app.rag.schema.RagQuerySource`。
    """
    return RagQuerySource(
        chunkId=c.id,
        documentId=c.document_id,
        contentOffset=c.content_offset,
        knowledgeBaseId=c.knowledge_base_id,
        chunkIndex=c.chunk_index,
        content=c.content,
        score=c.score,
        scoreSource=c.score_source,
        scorer=c.rerank_scorer if c.score_source == "rerank" else c.score_source,
        distance=c.distance,
        headingPath=c.heading_path,
        pageNo=c.page_no,
        sourceType=c.source_type,
    )


def _to_context(c: Candidate) -> ContextItem:
    """把准入候选转成生成提示使用的上下文项，保留引用定位字段。

    上游为 `_stream_events`，下游调用 `ai.app.rag.schema.ContextItem`。
    """
    return ContextItem(
        content=c.content,
        sourceName=c.source_name,
        headingPath=c.heading_path,
        pageNo=c.page_no,
        sourceType=c.source_type,
    )


_DEV_TEST_SYSTEM_PROMPT = (
    "你处于本地开发连通性测试模式。此模式只用于检查聊天模型调用和文本流，"
    "本次没有执行知识库检索或证据准入。请直接回应用户请求，不要声称回答有知识库依据。"
)


def _development_test_active(request: RagQueryRequest) -> bool:
    """依据环境和请求类型判断是否启用仅供本地联调的问答路径。

    上游为 `run_query`、`_stream_events`。
    """
    return RAG_DEV_TEST_MODE and request.generate


def _development_test_decision() -> RagEvidenceDecision:
    """为本地联调路径构造与证据准入协议兼容的结论对象。

    上游为 `_development_test_response`，下游调用 `ai.app.rag.schema.RagEvidenceDecision`。
    """
    return RagEvidenceDecision(
        state="bypassed", policyVersion="development-test-mode", scorer="none",
        reasonCode="development_test_mode")


def _development_test_messages(request: RagQueryRequest) -> list[dict[str, str]]:
    """用问答请求的当前问题组装本地联调的最小模型消息。

    上游为 `_run_development_test`、`_run_development_test_stream`。
    """
    messages = [{"role": "system", "content": _DEV_TEST_SYSTEM_PROMPT}]
    messages.extend({"role": item.role, "content": item.content} for item in request.history)
    messages.append({"role": "user", "content": request.question})
    return messages


def _development_test_response(request: RagQueryRequest, *, answer: str = "", outcome: str = "success",
                               reason_code: str = "development_test_mode", provider: str = "",
                               token_cost: int = 0) -> RagQueryResponse:
    """将联调路径的正文、终态和用量映射为标准 RAG 响应。

    上游为 `_run_development_test`，下游调用 `ai.app.rag.schema.RagQueryResponse`、`ai.app.llm.telemetry.get_request_id`。
    """
    failed = outcome in {"timeout", "dependency_unavailable"}
    return RagQueryResponse(
        pipelineEngine=request.pipelineEngine, retrievalVersion=request.version,
        answer=answer, originalQuery=request.question, rewrittenQuery=request.question,
        outcome=outcome, reasonCode=reason_code,
        failedStage="generate" if failed else None, requestId=telemetry.get_request_id(),
        retrievalStatus="skipped", evidenceDecision=_development_test_decision(),
        degraded=failed, degradeReason=reason_code if failed else "",
        providerUsed=provider, tokenCost=token_cost)


def _run_development_test(request: RagQueryRequest) -> RagQueryResponse:
    """使用生成模型验证联调请求的连通性，返回遵循标准结构的同步消息。

    上游为 `run_query`，下游调用 `_development_test_response`、`_at_stage`。
    """
    if not request.knowledgeBaseIds:
        return _development_test_response(
            request, outcome="dependency_unavailable",
            reason_code="development_test_requires_authorized_knowledge_base")

    with _at_stage("generate"), stage("generate") as pending:
        result = gate.call_messages(
            "generate", _development_test_messages(request), allow_cache=False)
        reason = getattr(result, "reason", "") or "model_not_configured"
        if getattr(result, "degraded", False):
            pending.success = False
            pending.degraded = True
            pending.error = reason
            return _development_test_response(
                request, outcome="dependency_unavailable", reason_code=reason,
                provider=getattr(result, "provider", "") or "")

        answer = (getattr(result, "content", None) or "").strip()
        pending.total_tokens = getattr(result, "total_tokens", 0) or None
        if not answer:
            pending.success = False
            pending.error = "generation_empty_response"
            return _development_test_response(
                request, outcome="dependency_unavailable", reason_code="generation_empty_response",
                provider=getattr(result, "provider", "") or "",
                token_cost=getattr(result, "total_tokens", 0) or 0)
        return _development_test_response(
            request, answer=answer, provider=getattr(result, "provider", "") or "",
            token_cost=getattr(result, "total_tokens", 0) or 0)


def _run_development_test_stream(request: RagQueryRequest, collector):
    """运行联调模式的模型流，产生正文、用量及标准终态事件。

    上游为 `_stream_events`，下游调用 `_development_test_response`、`_at_stage`。
    """
    if not request.knowledgeBaseIds:
        response = _development_test_response(
            request, outcome="dependency_unavailable",
            reason_code="development_test_requires_authorized_knowledge_base")
    else:
        chunks: list[str] = []
        terminal = None
        with _at_stage("generate"), stage("generate") as pending:
            iterator = gate.stream("generate", messages=_development_test_messages(request), allow_cache=False)
            try:
                for item in iterator:
                    if isinstance(item, str):
                        chunks.append(item)
                        yield {"event": "delta", "data": {"text": item}}
                    else:
                        terminal = item
            finally:
                close = getattr(iterator, "close", None)
                if close is not None:
                    close()

            token_cost = getattr(terminal, "total_tokens", 0) or 0
            provider = getattr(terminal, "provider", "") or ""
            if terminal is None:
                reason_code = "generation_invalid_stream"
            elif getattr(terminal, "degraded", False):
                reason_code = getattr(terminal, "reason", "") or "generation_degraded"
            elif not chunks:
                reason_code = "model_streaming_no_deltas"
            else:
                reason_code = "development_test_mode"

            success = reason_code == "development_test_mode"
            pending.success = success
            pending.degraded = not success
            pending.total_tokens = token_cost or None
            pending.error = "" if success else reason_code
            response = _development_test_response(
                request, answer="".join(chunks) if success else "",
                outcome="success" if success else "dependency_unavailable",
                reason_code=reason_code, provider=provider, token_cost=token_cost)

    response.stages = [
        RagQueryStage(stage=rec.stage, durationMs=rec.duration_ms, success=rec.success,
                      error=_public_stage_error(rec.error), degraded=rec.degraded,
                      totalTokens=rec.total_tokens)
        for rec in collector.records
    ]
    log_summary(collector, question=request.question,
                extra=f" engine={request.pipelineEngine} version={request.version} dev_test=1")
    for rec in collector.records:
        yield {"event": "stage", "data": _stage_dto(rec)}
    yield _sources_event(response)
    if response.outcome == "dependency_unavailable":
        yield {"event": "error", "data": _error_data(response)}
    else:
        yield {"event": "done", "data": response.model_dump()}


def run_query(request: RagQueryRequest) -> RagQueryResponse:
    """执行同步问答并返回正文、证据、终态及阶段数据。

    输入为 Java 请求中的问题、授权库、策略、历史和模型绑定。
    由 rag/router.py 的 rag_query 调用，固定流程走 _execute，LCEL 流程走 langchain_pipeline.execute。
    下游调用 `_validate_request`、`ai.app.observability.stage_trace.collecting`。
    """
    budget_ms = _validate_request(request)
    failed_token = _FAILED_STAGE.set(None)
    last_token = _LAST_STAGE.set(None)
    retrieval_token = _RETRIEVAL_STATUS.set(("complete", "none"))
    with collecting() as collector:
        try:
            with query_deadline(budget_ms, started_at=current_request_started_at()), (model_execution(request.modelExecution, default_budget_ms=budget_ms) if request.knowledgeBaseIds else nullcontext()):
                if _development_test_active(request):
                    if request.pipelineEngine != "fixed":
                        raise ValueError("development test mode supports pipelineEngine=fixed only")
                    response = _run_development_test(request)
                elif request.pipelineEngine == "fixed":
                    response = _execute(request)
                else:
                    from ai.app.rag.langchain_pipeline import execute
                    response = execute(request)
        except QueryDeadlineExceeded as exc:
            response = _failure_response(
                request,
                outcome="timeout",
                reason_code="query_deadline_exceeded",
                retrieval_status=_RETRIEVAL_STATUS.get()[0],
                failed_stage=getattr(exc, "rag_failed_stage", None),
            )
        except (ModelConfigurationError, ModelCallError) as exc:
            response = _failure_response(request, outcome="dependency_unavailable",
                reason_code="model_not_configured" if isinstance(exc, ModelConfigurationError) else exc.code,
                retrieval_status=_RETRIEVAL_STATUS.get()[0], failed_stage=current_failed_stage())
        except (ReadOnlyPgError, EmbeddingUnavailableError) as exc:
            response = _failure_response(
                request,
                outcome="dependency_unavailable",
                reason_code="retrieval_unavailable",
                retrieval_status="unavailable",
                failed_stage=getattr(exc, "rag_failed_stage", None),
            )
        finally:
            _RETRIEVAL_STATUS.reset(retrieval_token)
            _FAILED_STAGE.reset(failed_token)
            _LAST_STAGE.reset(last_token)
    response.pipelineEngine = request.pipelineEngine
    response.retrievalVersion = request.version
    if (request.generate or request.admitEvidence) and not _development_test_active(request):
        response.evidenceMode = _evidence_mode(request)
    log_summary(collector, question=request.question,
                extra=f" engine={response.pipelineEngine} version={response.retrievalVersion}")
    response.stages = [
        RagQueryStage(
            stage=r.stage,
            durationMs=r.duration_ms,
            success=r.success,
            error=_public_stage_error(r.error),
            degraded=r.degraded,
            totalTokens=r.total_tokens,
        )
        for r in collector.records
    ]
    return response


def _stage_dto(rec) -> dict:
    """将当前阶段追踪记录转换为对外传输字段，供流式和同步响应共用。

    上游为 `_run_development_test_stream`，下游调用 `_public_stage_error`。
    """
    return {"stage": rec.stage, "durationMs": rec.duration_ms, "success": rec.success,
            "error": _public_stage_error(rec.error), "degraded": rec.degraded,
            "totalTokens": rec.total_tokens}


# 校验流式问答请求并返回事件迭代器，允许调用方提供替代检索与生成函数。
# 上游为 `ai.app.rag.router._progress_stream.produce`，下游调用 `_validate_request`、`_stream_in_context`。
def run_query_stream(request: RagQueryRequest, *, retrieve=None, generate_stream=None):
    """先校验 Java 的问答请求，再为所选引擎返回 SSE 事件迭代器。

    上游为 `ai.app.rag.router.rag_query_stream`。开发联调的证据绕过模式仅接收 fixed。
    """
    budget_ms = _validate_request(request)
    if _development_test_active(request) and request.pipelineEngine != "fixed":
        raise ValueError("development test mode supports pipelineEngine=fixed only")
    return _stream_in_context(
        _stream_events(request, budget_ms, retrieve=retrieve, generate_stream=generate_stream))


def _stream_in_context(gen):
    """在一致的请求作用域内推进事件迭代器，使不同工作线程消费时仍读取同一预算和追踪。

    上游为 `run_query_stream`。
    """
    ctx = contextvars.copy_context()
    try:
        while True:
            try:
                chunk = ctx.run(next, gen)
            except StopIteration:
                return
            yield chunk
    finally:
        ctx.run(gen.close)


# 推进流式问答并产出阶段、来源、正文和终态事件。
# 接收已校验请求和时限预算，允许注入检索或生成函数。
# 由 run_query_stream 创建，由 rag/router.py 的 SSE 路由消费。
# 改写、检索、准入与同步路径共用状态步骤，生成失败在流中以错误事件交付。
# 下游调用 `ai.app.observability.stage_trace.collecting`、`ai.app.rag.deadline.query_deadline`。
def _stream_events(request: RagQueryRequest, budget_ms: int, *, retrieve=None, generate_stream=None):
    """在所选引擎的召回与证据准入后发送正文增量、来源和唯一终态。

    `retrieve` 是固定流程的测试注入点，`generate_stream` 可替代两个引擎的生成。
    """
    failed_token = _FAILED_STAGE.set(None)
    last_token = _LAST_STAGE.set(None)
    retrieval_token = _RETRIEVAL_STATUS.set(("complete", "none"))
    with collecting() as collector:
        try:
            with query_deadline(budget_ms, started_at=current_request_started_at()), (model_execution(request.modelExecution, default_budget_ms=budget_ms) if request.knowledgeBaseIds else nullcontext()):
                if _development_test_active(request):
                    yield from _run_development_test_stream(request, collector)
                    return
                is_langchain = request.pipelineEngine == "langchain"
                if is_langchain or request.evidenceMode is not None:
                    yield {"event": "execution", "data": {
                        "pipelineEngine": request.pipelineEngine,
                        "retrievalVersion": request.version,
                        "evidenceMode": _evidence_mode(request)}}
                if is_langchain:
                    from ai.app.rag.langchain_pipeline import prepare_generation

                    state = prepare_generation(request)
                    for rec in collector.records:
                        yield {"event": "stage", "data": _stage_dto(rec)}
                    emitted = len(collector.records)
                else:
                    state = prepare_query(request)
                    emitted = 0
                    for step in (rewrite_query, retrieve_query, admit_query, postprocess_query):
                        if state.response is not None:
                            break
                        state = step(state) if step is not retrieve_query else retrieve_query(
                            state, retrieve=retrieve)
                        for rec in collector.records[emitted:]:
                            yield {"event": "stage", "data": _stage_dto(rec)}
                        emitted = len(collector.records)
                if state.response is not None:
                    for rec in collector.records[emitted:]:
                        yield {"event": "stage", "data": _stage_dto(rec)}
                    if state.response.outcome in {"timeout", "dependency_unavailable"}:
                        yield {"event": "error", "data": _error_data(state.response)}
                    else:
                        yield {"event": "done", "data": _finalize(state, request, collector).model_dump()}
                    return
                if not request.generate:
                    # admitEvidence 的流式孪生：证据准入与后处理照跑，但不调用模型。
                    state = finalize_admission_query(state)
                    yield _sources_event(state.response)
                    yield {"event": "done", "data": _finalize(state, request, collector).model_dump()}
                    return
                gen = generate_stream or rag_service.generate_stream
                if is_langchain and generate_stream is None:
                    gen = partial(gen, provisional=True)
                contexts = [_to_context(candidate) for candidate in state.admitted]
                terminal = None
                try:
                    with _at_stage("generate"), stage("generate") as pending:
                        _ensure_deadline()
                        generation_question = _generation_question(state)
                        with closing(gen(generation_question, contexts, state.history)) as model_events:
                            for kind, payload in model_events:
                                if kind == "delta":
                                    _ensure_deadline()
                                    data = {"text": payload}
                                    if is_langchain:
                                        data["provisional"] = True
                                    yield {"event": "delta", "data": data}
                                else:
                                    terminal = payload
                        _ensure_deadline()
                        pending.total_tokens = terminal.token_cost or None
                        pending.degraded = terminal.outcome == "dependency_unavailable"
                        if pending.degraded:
                            pending.error = terminal.reason_code
                except QueryDeadlineExceeded:
                    for rec in collector.records[emitted:]:
                        yield {"event": "stage", "data": _stage_dto(rec)}
                    state = replace(state, response=_failure_response(
                        request, outcome="timeout", reason_code="query_deadline_exceeded",
                        retrieval_status=state.retrieval_status, sources=state.sources,
                        evidence_sources=state.evidence_sources, decision=state.decision,
                        failed_stage="generate"))
                    yield {"event": "error", "data": _error_data(state.response)}
                    return
                except Exception as exc:                      # noqa: BLE001
                    # 生成流异常只发送一次 error 终态；原始 JSON 分片由生成模块隔离。
                    logger.warning("streaming generate interrupted: %s", type(exc).__name__)
                    for rec in collector.records[emitted:]:
                        yield {"event": "stage", "data": _stage_dto(rec)}
                    state = replace(state, response=_failure_response(
                        request, outcome="dependency_unavailable",
                        reason_code="generation_interrupted",
                        retrieval_status=state.retrieval_status, sources=state.sources,
                        evidence_sources=state.evidence_sources, decision=state.decision,
                        failed_stage="generate"))
                    yield {"event": "error", "data": _error_data(state.response)}
                    return
                for rec in collector.records[emitted:]:
                    yield {"event": "stage", "data": _stage_dto(rec)}
                emitted = len(collector.records)
                state = replace(state, response=_generation_response(state, terminal))
                yield _sources_event(state.response)
                if state.response.outcome == "dependency_unavailable":
                    yield {"event": "error", "data": _error_data(state.response)}
                else:
                    yield {"event": "done", "data": _finalize(state, request, collector).model_dump()}
        except QueryDeadlineExceeded as exc:
            resp = _failure_response(request, outcome="timeout", reason_code="query_deadline_exceeded",
                                     retrieval_status=_RETRIEVAL_STATUS.get()[0],
                                     failed_stage=getattr(exc, "rag_failed_stage", None))
            yield {"event": "error", "data": _error_data(resp)}
        except (ModelConfigurationError, ModelCallError) as exc:
            resp = _failure_response(request, outcome="dependency_unavailable",
                reason_code="model_not_configured" if isinstance(exc, ModelConfigurationError) else exc.code,
                retrieval_status=_RETRIEVAL_STATUS.get()[0], failed_stage=current_failed_stage())
            yield {"event": "error", "data": _error_data(resp)}
        except (ReadOnlyPgError, EmbeddingUnavailableError) as exc:
            resp = _failure_response(request, outcome="dependency_unavailable",
                                     reason_code="retrieval_unavailable", retrieval_status="unavailable",
                                     failed_stage=getattr(exc, "rag_failed_stage", None))
            yield {"event": "error", "data": _error_data(resp)}
        except Exception as exc:                          # noqa: BLE001 —— 兜底，必须排在具体分支之后
            # 流式路径在此捕获未预期异常并发出终态错误事件；前面的已知故障分支保持优先级。
            logger.exception("rag query stream failed: %s", type(exc).__name__)
            resp = _failure_response(
                request, outcome="dependency_unavailable", reason_code="internal_error",
                retrieval_status=_RETRIEVAL_STATUS.get()[0],
                failed_stage=current_failed_stage())
            yield {"event": "error", "data": _error_data(resp)}
            return
        finally:
            _RETRIEVAL_STATUS.reset(retrieval_token)
            _FAILED_STAGE.reset(failed_token)
            _LAST_STAGE.reset(last_token)


def _finalize(state: QueryState, request: RagQueryRequest, collector) -> RagQueryResponse:
    """将当前查询状态与追踪收集器汇合为可对外返回的最终问答响应。

    上游为 `_stream_events`，下游调用 `finish_query`、`_evidence_mode`。
    """
    resp = finish_query(state)
    resp.pipelineEngine = request.pipelineEngine
    resp.retrievalVersion = request.version
    if request.generate or request.admitEvidence:
        resp.evidenceMode = _evidence_mode(request)
    resp.stages = [
        RagQueryStage(stage=r.stage, durationMs=r.duration_ms, success=r.success,
                      error=_public_stage_error(r.error), degraded=r.degraded,
                      totalTokens=r.total_tokens)
        for r in collector.records
    ]
    log_summary(collector, question=request.question,
                extra=f" engine={resp.pipelineEngine} version={resp.retrievalVersion} stream=1")
    return resp


def _error_data(resp: RagQueryResponse) -> dict:
    """从失败响应摘取原因、阶段和请求标识，返回 SSE error 帧所需载荷。

    上游为 `_run_development_test_stream`、`_stream_events`。
    """
    if resp.outcome == "timeout":
        message = "查询超时，请重试"
    elif resp.reasonCode == "generation_interrupted":
        message = "回答生成中断，请重试"
    else:
        message = "证据或模型依赖暂不可用，查询未完成"
    return {"outcome": resp.outcome, "reasonCode": resp.reasonCode,
            "failedStage": resp.failedStage, "requestId": resp.requestId, "message": message}


def _sources_event(resp: RagQueryResponse) -> dict:
    """从问答响应中拆出候选及获准证据，编码为给调用方的来源事件。

    上游为 `_run_development_test_stream`、`_stream_events`。
    """
    return {"event": "sources", "data": {
        "sources": [source.model_dump() for source in resp.sources],
        "evidenceSources": [source.model_dump() for source in resp.evidenceSources],
        "evidenceDecision": resp.evidenceDecision.model_dump() if resp.evidenceDecision else None}}


def _public_stage_error(error: str) -> str:
    """将内部阶段异常分类压缩成允许随诊断结果公开的原因信息。

    上游为 `_run_development_test_stream`、`run_query`。
    """
    if not error:
        return ""
    code = error.split(":", 1)[0]
    return code if re.fullmatch(r"[A-Za-z][A-Za-z0-9_]{0,79}", code) else "stage_failed"


def _public_generation_reason(reason: str) -> str:
    """把生成阶段原因码转换成对外契约允许的终态原因。

    上游为 `_generation_response`。
    """
    if not reason or reason == "prompt 超预算直接降级":
        return reason
    return reason if re.fullmatch(r"[A-Za-z][A-Za-z0-9_]{0,79}", reason) else "generation_unavailable"


def _validate_request(request: RagQueryRequest) -> int:
    """校验请求边界、引擎、策略和可组合选项，返回本次请求允许使用的时限预算。

    上游为 `run_query`，下游调用 `_development_test_active`。
    """
    if request.pipelineEngine not in VALID_PIPELINE_ENGINES:
        raise ValueError("非法 pipelineEngine（合法值 fixed, langchain）")
    if request.version not in VALID_VERSIONS:
        raise ValueError(f"非法 retrieval.version（合法值 {list(VALID_VERSIONS)}）")
    if request.evidenceMode is not None:
        if not (request.generate or request.admitEvidence) or _development_test_active(request):
            raise ValueError("evidenceMode requires evidence admission")
        if request.evidenceMode == "score" and request.version not in {"hybrid-rerank", "hybrid-rerank-rewrite"}:
            raise ValueError("score evidence mode requires reranking")
    if request.compressionMode not in VALID_COMPRESSION_MODES:
        raise ValueError(f"非法 compressionMode（合法值 {list(VALID_COMPRESSION_MODES)}）")
    question = (request.question or "").strip()
    if not question:
        raise ValueError("question must not be empty")
    if len(question) > 4000:
        raise ValueError("question exceeds 4000 characters")
    if request.retrievalQuery is not None:
        retrieval_query = request.retrievalQuery.strip()
        if not retrieval_query or len(retrieval_query) > 4000:
            raise ValueError("retrievalQuery must contain 1 to 4000 characters")
    if request.topK < 1 or request.topK > RAG_QUERY_MAX_TOP_K:
        raise ValueError(f"topK must be between 1 and {RAG_QUERY_MAX_TOP_K}")
    if request.recallN < 1 or request.recallN > RAG_QUERY_MAX_RECALL_N:
        raise ValueError(f"recallN must be between 1 and {RAG_QUERY_MAX_RECALL_N}")
    if request.rrfK < 1:
        raise ValueError("rrfK must be positive")
    if request.budgetMs is not None and request.budgetMs <= 0:
        raise ValueError("budgetMs must be positive when supplied")
    if request.admitEvidence and request.generate:
        raise ValueError("admitEvidence requires generate=false")
    if request.documentId is not None:
        if not request.admitEvidence:
            raise ValueError("documentId requires admitEvidence=true")
        if request.documentId <= 0:
            raise ValueError("documentId must be positive")
    if request.documentRead is not None:
        reading = request.documentRead
        if request.documentId is None or not request.admitEvidence or request.generate:
            raise ValueError("documentRead requires documentId and admitEvidence=true, generate=false")
        if not 0 <= reading.offset <= 1000000 or not 0 <= reading.contentOffset <= 100000000:
            raise ValueError("documentRead offsets exceed bounds")
        if reading.endOffset is not None and not reading.offset <= reading.endOffset <= 1000000:
            raise ValueError("documentRead endOffset exceeds bounds")
        if not 1 <= reading.windowChars <= 1200 or not 0 <= reading.contextRadius <= 2:
            raise ValueError("documentRead window exceeds bounds")
        if reading.aroundChunkId is not None and (
                reading.aroundChunkId <= 0 or reading.offset != 0 or reading.contentOffset != 0
                or reading.endOffset is not None):
            raise ValueError("aroundChunkId requires an initial page without continuation offsets")
        if request.smallToBigEnabled or request.mmrEnabled or request.compressionMode != "none":
            raise ValueError("documentRead preserves windows; parent expansion, MMR and compression must be disabled")
    if len(request.knowledgeBaseIds) > 1000:
        raise ValueError("knowledgeBaseIds exceeds 1000 entries")
    if any(kb_id <= 0 for kb_id in request.knowledgeBaseIds):
        raise ValueError("knowledgeBaseIds must contain positive ids")
    history = request.history or []
    if len(history) > RAG_QUERY_MAX_HISTORY_MESSAGES:
        raise ValueError(
            f"history exceeds {RAG_QUERY_MAX_HISTORY_MESSAGES} messages"
        )
    history_chars = sum(len(message.content or "") for message in history)
    if history_chars > RAG_QUERY_MAX_HISTORY_CHARS:
        raise ValueError(
            f"history exceeds {RAG_QUERY_MAX_HISTORY_CHARS} characters"
        )
    requested_budget = request.budgetMs or RAG_QUERY_DEADLINE_MS
    return min(RAG_QUERY_DEADLINE_MS, requested_budget)


def _ensure_deadline() -> None:
    """在开始新阶段之前检查剩余时限，耗尽时中止阶段并触发超时终态。

    上游为 `ai.app.rag.langchain_pipeline.ScopedPgRetriever._get_relevant_documents`，下游调用 `ai.app.rag.deadline.deadline_expired`、`ai.app.rag.deadline.QueryDeadlineExceeded`。
    """
    if deadline_expired():
        raise QueryDeadlineExceeded("query deadline expired between pipeline stages")


def _evidence_mode(request: RagQueryRequest) -> str:
    """从请求或默认配置读取证据准入模式，供状态机选择评分或正文审阅。

    上游为 `run_query`、`_stream_events`。
    """
    return request.evidenceMode or RAG_EVIDENCE_MODE


def _empty_decision(reason_code: str, *, mode: str = "score", state: str = "no_evidence") -> RagEvidenceDecision:
    """用原因码、模式和状态构造无证据或不可用时的准入对象。

    上游为 `_admit_evidence`，下游调用 `ai.app.rag.schema.RagEvidenceDecision`。
    """
    return RagEvidenceDecision(
        state=state,
        policyVersion=SUPPORT_POLICY_VERSION if mode == "support" else RAG_EVIDENCE_POLICY_VERSION,
        scorer="none",
        threshold=None,
        reasonCode=reason_code,
    )


def _admit_evidence(candidates: list[Candidate]) -> tuple[list[Candidate], RagEvidenceDecision]:
    """检查候选分数及其阈值校准并返回获准证据和评分模式下的准入决策。

    上游为 `admit_query`，下游调用 `_empty_decision`、`ai.app.models.context.evidence_threshold`。
    """
    if not candidates:
        return [], _empty_decision("no_match")

    accepted: list[Candidate] = []
    candidate_reasons: list[str] = []
    scorers: set[str] = set()
    thresholds: set[float] = set()

    for candidate in candidates:
        # 分数阈值对应校准过的重排信号。召回距离或融合分可以排序，直接套用重排阈值会改变证据门的含义。
        if candidate.score_source != "rerank":
            candidate_reasons.append("scorer_unavailable")
            continue
        scorer = candidate.rerank_scorer
        scorers.add(scorer)
        if not candidate.score_valid or candidate.score is None:
            candidate_reasons.append(
                "invalid_score_mapping"
                if candidate.score_error == "invalid_score_mapping"
                else "scorer_unavailable"
            )
            continue

        if scorer == "model":
            threshold = evidence_threshold("model")
        elif scorer == "embed_sim":
            threshold = evidence_threshold("embed_sim")
        else:
            threshold = None
        if threshold is None:
            candidate_reasons.append(
                "threshold_not_configured" if scorer in {"model", "embed_sim"}
                else "scorer_unavailable"
            )
            continue
        thresholds.add(threshold)
        if candidate.score >= threshold:
            accepted.append(candidate)
        else:
            candidate_reasons.append("below_threshold")

    if accepted:
        reason = "none"
        state = "accepted"
    else:
        priority = (
            "invalid_score_mapping",
            "threshold_not_configured",
            "scorer_unavailable",
            "below_threshold",
        )
        reason = next((code for code in priority if code in candidate_reasons), "scorer_unavailable")
        state = "rejected"

    return accepted, RagEvidenceDecision(
        state=state,
        policyVersion=RAG_EVIDENCE_POLICY_VERSION,
        scorer=next(iter(scorers)) if len(scorers) == 1 else "mixed" if scorers else "none",
        threshold=next(iter(thresholds)) if len(thresholds) == 1 else None,
        retrievedCount=len(candidates),
        acceptedCount=len(accepted),
        rejectedCount=len(candidates) - len(accepted),
        reasonCode=reason,
    )


def _failure_response(request: RagQueryRequest, *, outcome: str, reason_code: str,
                      retrieval_status: str, sources: list[RagQuerySource] | None = None,
                      evidence_sources: list[RagQuerySource] | None = None,
                      decision: RagEvidenceDecision | None = None,
                      failed_stage: str | None = None) -> RagQueryResponse:
    """用当前请求及已有候选构造失败、拒答或降级响应，保留诊断字段。

    上游为 `run_query`，下游调用 `_development_test_active`、`_development_test_decision`。
    """
    if _development_test_active(request):
        retrieval_status = "skipped"
        decision = decision or _development_test_decision()
    return RagQueryResponse(
        answer="",
        originalQuery=request.question,
        rewrittenQuery=request.retrievalQuery or request.question,
        sources=sources or [],
        evidenceSources=evidence_sources or [],
        outcome=outcome,
        reasonCode=reason_code,
        failedStage=(failed_stage or current_failed_stage())
        if outcome in {"timeout", "dependency_unavailable"} else None,
        requestId=telemetry.get_request_id(),
        retrievalStatus=retrieval_status,
        evidenceDecision=decision or _empty_decision(reason_code, mode=_evidence_mode(request), state="unavailable"),
    )


# 按请求选项配置候选去重、父块扩展和正文压缩，返回后处理结果与统计。
# 上游为 `retrieve_query`，下游调用 `ai.app.rag.postprocess.apply_postprocess`。
def _postprocess(request: RagQueryRequest, candidates: list[Candidate],
                 query: str) -> tuple[list[Candidate], CompressionStats | None]:
    """MMR + 抽取式压缩。这四个开关（compressionMode/tokenBudget/mmrEnabled/mmrLambda）由 Java
    **逐请求**透传——见 `RagQueryRequest` 里的说明，不读 Python env 是为了让评测矩阵的
    按实例切档仍然生效。"""
    return apply_postprocess(
        candidates,
        query=query,
        top_k=request.topK,
        token_budget=request.tokenBudget,
        version=request.version,
        mmr_enabled=request.mmrEnabled,
        mmr_lambda=request.mmrLambda,
        compression_mode=request.compressionMode,
    )


def _compression_dto(stats: CompressionStats | None) -> RagQueryCompression | None:
    """将内部压缩统计转换为响应模型，未执行压缩时返回 None。

    上游为 `retrieve_query`，下游调用 `ai.app.rag.schema.RagQueryCompression`。
    """
    if stats is None:
        return None
    return RagQueryCompression(
        mode=stats.mode,
        tokensBefore=stats.tokens_before,
        tokensAfter=stats.tokens_after,
        ratio=stats.ratio,
    )


def _generation_question(state: QueryState) -> str:
    """从查询状态选择最终生成提示使用的问题文本。

    上游为 `_stream_events`、`generate_query`。
    """
    if state.support_review is None:
        return state.query
    return ("【原始用户请求：日期、对象、条件、范围与回答形式以此为准】\n"
            f"{state.question}\n\n"
            "【经审查的独立知识问题：仅辅助消解指代，不得删改原请求限定】\n"
            f"{state.support_review.standalone_question}")


def prepare_query(request: RagQueryRequest) -> QueryState:
    """将已校验请求整理为共享查询状态，无权读取任何库时提前形成结果。

    上游为 `ai.app.rag.langchain_pipeline.build_chain`，下游调用 `ai.app.rag.state.QueryState`、`ai.app.rag.schema.RagQueryResponse`。
    """
    question = request.question.strip()
    query = request.retrievalQuery.strip() if request.retrievalQuery is not None else question
    state = QueryState(request, question, query, history=request.history or [])
    if not request.knowledgeBaseIds:
        return replace(state, response=RagQueryResponse(
            answer="", originalQuery=question, rewrittenQuery=question,
            outcome="no_evidence", reasonCode="no_match",
            requestId=telemetry.get_request_id(), retrievalStatus="complete",
            evidenceDecision=_empty_decision("no_match", mode=_evidence_mode(request))))
    return state


def rewrite_query(state: QueryState) -> QueryState:
    """按请求策略用会话历史改写当前问题，并把改写结果写入下一阶段状态。

    上游为 `ai.app.rag.langchain_pipeline.build_chain`，下游调用 `_at_stage`、`ai.app.observability.stage_trace.stage`。
    """
    if (state.response is not None or state.request.documentRead is not None
            or state.request.version != "hybrid-rerank-rewrite"):
        return state
    with _at_stage("rewrite"), stage("rewrite") as pending:
        _ensure_deadline()
        result = rewrite(RewriteRequest(question=state.query, history=state.history))
        pending.total_tokens = getattr(result, "tokenCost", None) or None
    return replace(state, query=result.rewrittenQuery or state.query)


def retrieve_query(state: QueryState, *, retrieve=None) -> QueryState:
    """按查询范围、文档窗口和检索策略执行召回，返回携带候选与状态的查询对象。

    上游为 `ai.app.rag.langchain_pipeline._retrieve_state`，下游调用 `_at_stage`、`_ensure_deadline`。
    """
    if state.response is not None:
        return state
    request = state.request
    with _at_stage("retrieval"):
        _ensure_deadline()
        needs_full_review = request.generate or request.admitEvidence
        review_window = max(request.topK, request.recallN) if (
            needs_full_review and _evidence_mode(request) == "support") else request.topK
        args = (request.knowledgeBaseIds, state.query, review_window, request.recallN,
                request.version, request.rrfK)
        if request.documentRead is not None:
            with _at_stage("document_read"), stage("document_read"):
                candidates, page = read_document_window(
                    request.knowledgeBaseIds, request.documentId, request.documentRead)
            state = replace(state, document_page=page)
            # Fixed and LCEL share this explicit reading operation and the existing ModelGate reranker.
            candidates = _rerank_top(candidates, state.query, len(candidates))
            status, reason = "complete", "none"
        else:
            candidates, status, reason = _retrieve_with_status(
                *args, document_id=request.documentId, retrieve=retrieve)
    state = replace(state, candidates=candidates, retrieval_status=status,
                    reason_code="partial_retrieval" if status == "partial" else "none",
                    sources=[_to_source(candidate) for candidate in candidates[:request.topK]])
    if status == "unavailable" or (status == "partial" and not candidates):
        timed_out = reason == "query_deadline_exceeded"
        return replace(state, response=_failure_response(
            request, outcome="timeout" if timed_out else "dependency_unavailable",
            reason_code=reason or "retrieval_unavailable", retrieval_status=status,
            failed_stage="retrieval"))
    if not candidates:
        return replace(state, response=RagQueryResponse(
            answer="", originalQuery=state.question, rewrittenQuery=state.query,
            sources=state.sources, outcome="no_evidence", reasonCode="no_match",
            requestId=telemetry.get_request_id(), retrievalStatus=status,
            evidenceDecision=_empty_decision("no_match", mode=_evidence_mode(request))))
    if not request.generate and not request.admitEvidence:
        compression = None
        if request.compressionMode != "none":
            with _at_stage("compression"), stage("compression"):
                _, compression = _postprocess(request, list(candidates), state.query)
        return replace(state, response=RagQueryResponse(
            answer="", originalQuery=state.question, rewrittenQuery=state.query,
            sources=state.sources, outcome="success", reasonCode=state.reason_code,
            requestId=telemetry.get_request_id(), retrievalStatus=status,
            compression=_compression_dto(compression),
            evidenceDecision=RagEvidenceDecision(
                state="bypassed", policyVersion=RAG_EVIDENCE_POLICY_VERSION, scorer="none",
                retrievedCount=len(candidates), acceptedCount=0, reasonCode="retrieve_only")))
    return state


def admit_query(state: QueryState) -> QueryState:
    """根据请求的证据模式判断哪些候选可以支撑答案，将准入结果交给后处理。

    上游为 `ai.app.rag.langchain_pipeline._admit_state`，下游调用 `_evidence_mode`、`_admit_support`。
    """
    if state.response is not None:
        return state
    if _evidence_mode(state.request) == "support":
        return _admit_support(state)
    with _at_stage("evidence_gate"):
        _ensure_deadline()
        admitted, decision = _admit_evidence(state.candidates)
    state = replace(state, admitted=admitted, decision=decision,
                    evidence_sources=[_to_source(candidate) for candidate in admitted])
    if admitted:
        return state
    gate_unavailable = decision.reasonCode in {
        "threshold_not_configured", "scorer_unavailable", "invalid_score_mapping"}
    if gate_unavailable:
        outcome, reason = "dependency_unavailable", decision.reasonCode
    elif state.retrieval_status == "partial":
        outcome, reason = "dependency_unavailable", "partial_retrieval"
    else:
        outcome, reason = "no_evidence", decision.reasonCode
    return replace(state, response=RagQueryResponse(
        answer="", originalQuery=state.question, rewrittenQuery=state.query,
        sources=state.sources, outcome=outcome, reasonCode=reason,
        failedStage="evidence_gate" if outcome == "dependency_unavailable" else None,
        requestId=telemetry.get_request_id(), retrievalStatus=state.retrieval_status,
        evidenceDecision=decision))


def _admit_support(state: QueryState) -> QueryState:
    """把问题和候选原文交给证据审阅模型及规则校验，返回正文模式下的获准证据或拒答状态。

    上游为 `admit_query`，下游调用 `_failure_response`、`ai.app.rag.schema.RagEvidenceDecision`。
    """
    if state.retrieval_status != "complete":
        return replace(state, response=_failure_response(
            state.request, outcome="dependency_unavailable", reason_code="partial_retrieval",
            retrieval_status=state.retrieval_status, sources=state.sources,
            decision=RagEvidenceDecision(
                state="unavailable", policyVersion=SUPPORT_POLICY_VERSION, scorer="evidence_support",
                retrievedCount=len(state.candidates), rejectedCount=len(state.candidates),
                reasonCode="partial_retrieval"),
            failed_stage="evidence_gate"))
    with _at_stage("evidence_gate"), stage("evidence_gate") as pending:
        _ensure_deadline()
        review_options = {"retrieval_query": state.query}
        if state.document_page is not None:
            page = state.document_page
            review_options["source_window"] = {
                "documentId": state.request.documentId,
                "offset": page.offset, "contentOffset": page.contentOffset,
                "endOffset": page.endOffset, "hasMore": page.hasMore,
                "documentEndReached": not page.hasMore and page.endOffset is None,
            }
        result = review(state.question, state.history, state.candidates, current_deadline_at(),
                        **review_options)
        _ensure_deadline()
        pending.total_tokens = result.total_tokens or None
        pending.success = result.state != "unavailable"
        pending.error = result.reason_code if result.state == "unavailable" else ""
    fragments = ({item["source_id"]: item["content"]
                  for item in support_fragments(result, state.candidates)}
                 if result.state == "accepted" else {})
    admitted = [replace(candidate, content=fragments[candidate.id])
                for candidate in state.candidates if candidate.id in fragments]
    decision = RagEvidenceDecision(
        state=result.state, policyVersion=result.policy_version, scorer="evidence_support",
        retrievedCount=len(state.candidates), acceptedCount=len(admitted),
        rejectedCount=len(state.candidates) - len(admitted), reasonCode=result.reason_code)
    state = replace(state, admitted=admitted, decision=decision, support_review=result,
                    evidence_sources=[_to_source(candidate) for candidate in admitted])
    if result.state == "accepted" and admitted:
        return state
    unavailable = result.state == "unavailable" or result.state == "accepted"
    return replace(state, response=_failure_response(
        state.request, outcome="dependency_unavailable" if unavailable else "no_evidence",
        reason_code=result.reason_code if result.state != "accepted" else "support_context_lost",
        retrieval_status=state.retrieval_status, sources=state.sources, decision=decision,
        failed_stage="evidence_gate" if unavailable else None))


def postprocess_query(state: QueryState, *, provisional: bool = False) -> QueryState:
    """对获准证据执行允许的组合处理，并确认处理后的正文仍保留已通过校验的支撑。

    上游为 `ai.app.rag.langchain_pipeline._postprocess_state`，下游调用 `_at_stage`、`_ensure_deadline`。
    """
    if state.response is not None:
        return state
    request = state.request
    admitted = [replace(candidate) for candidate in state.admitted]
    compression = None
    try:
        with _at_stage("postprocess"):
            _ensure_deadline()
            if request.compressionMode != "none":
                with stage("compression"):
                    admitted, compression = _postprocess(request, admitted, state.query)
            else:
                admitted, compression = _postprocess(request, admitted, state.query)
        if request.smallToBigEnabled:
            with _at_stage("small_to_big"):
                _ensure_deadline()
                admitted = (expand_parent_chunks(admitted, request.knowledgeBaseIds,
                                                deduplicate=False)
                            if state.support_review is not None else
                            expand_parent_chunks(admitted, request.knowledgeBaseIds))
        admitted = [candidate for candidate in admitted if (candidate.content or "").strip()]
        if request.smallToBigEnabled and request.compressionMode == "extractive":
            expanded = compress_extractive(state.query, admitted, request.tokenBudget)
            admitted = expanded.candidates
            if compression is None:
                compression = expanded
            else:
                compression.tokens_after = expanded.tokens_after
                compression.candidates = admitted
    except (QueryDeadlineExceeded, ReadOnlyPgError) as exc:
        timeout = isinstance(exc, QueryDeadlineExceeded)
        return replace(state, response=_failure_response(
            request, outcome="timeout" if timeout else "dependency_unavailable",
            reason_code="query_deadline_exceeded" if timeout else "retrieval_unavailable",
            retrieval_status=state.retrieval_status, sources=state.sources,
            evidence_sources=state.evidence_sources, decision=state.decision,
            failed_stage=current_failed_stage()))
    if state.support_review is not None:
        # MMR, compression and parent deduplication may remove a required quote.
        # Parent expansion can also add unreviewed text: retain only checked spans.
        if not preserves_support(state.support_review, admitted):
            return _support_context_failure(state, "support_context_lost")
        fragments = {item["source_id"]: item["content"]
                     for item in support_fragments(state.support_review, admitted)}
        admitted = [replace(candidate, content=fragments[candidate.id])
                    for candidate in admitted if candidate.id in fragments]
        context_cost = sum(count_tokens(candidate.content) for candidate in admitted)
        if context_cost > request.tokenBudget:
            return _support_context_failure(state, "support_context_capacity_exceeded")
    state = replace(state, admitted=admitted, compression=compression,
                    evidence_sources=[_to_source(candidate) for candidate in admitted])
    if request.generate and admitted:
        state = _fit_generation_input(state, provisional=provisional)
        if state.response is not None:
            return state
    if not admitted:
        return replace(state, response=RagQueryResponse(
            answer="", originalQuery=state.question, rewrittenQuery=state.query,
            sources=state.sources, outcome="no_evidence",
            reasonCode="empty_context_after_postprocess", requestId=telemetry.get_request_id(),
            retrievalStatus=state.retrieval_status, evidenceDecision=state.decision,
            compression=_compression_dto(compression)))
    return state


def _fit_generation_input(state: QueryState, *, provisional: bool = False) -> QueryState:
    """根据生成模型窗口估算提示开销，先缩减可移除历史，再确定保留证据能否进入当前预算。

    上游为 `postprocess_query`，下游调用 `ai.app.llm.purposes.get_purpose`、`cost`。
    """
    budget = get_purpose("generate").budget_tokens
    system, response_format, _ = generation_contract(provisional=provisional)

    def cost(value):
        """运行生成提示的相同组织方式，返回指定查询状态的估计输入 Token 成本。

        上游为 `_fit_generation_input`，下游调用 `ai.app.llm.tokens.input_tokens`、`ai.app.rag.prompt_builder.build_prompt`。
        """
        return input_tokens([
            {"role": "system", "content": system},
            {"role": "user", "content": build_prompt(_generation_question(value),
                [_to_context(candidate) for candidate in value.admitted], value.history)}],
            response_format=response_format)

    before = cost(state)
    if before <= budget:
        return state
    _ensure_deadline()
    organizing("generate", before, budget)
    # Support fragments are already bound to exact original spans. Do not alter them.
    if state.support_review is None:
        state = replace(state, admitted=[replace(c, content=unique_paragraphs(c.content))
                                        for c in state.admitted])
    history = list(state.history)

    # 优先移除较早的完整对话轮，保住当前问题与已准入的证据。
    while history and cost(replace(state, history=history)) > budget:
        history.pop(0)
        while history and history[0].role != "user":
            history.pop(0)
    state = replace(state, history=history,
                    evidence_sources=[_to_source(c) for c in state.admitted])
    after = cost(state)
    organizing("generate", before, budget, after=after)
    _ensure_deadline()
    if after > budget:
        return replace(state, response=_failure_response(
            state.request, outcome="dependency_unavailable",
            reason_code="generation_context_capacity_exceeded", retrieval_status=state.retrieval_status,
            sources=state.sources, evidence_sources=state.evidence_sources,
            decision=state.decision, failed_stage="generate"))
    return state


def _support_context_failure(state: QueryState, reason: str) -> QueryState:
    """在证据输入无法纳入模型预算时创建失败状态，向调用端暴露对应原因。

    上游为 `postprocess_query`，下游调用 `_failure_response`。
    """
    decision = state.decision.model_copy(update={"state": "unavailable", "reasonCode": reason,
                                                "acceptedCount": 0,
                                                "rejectedCount": state.decision.retrievedCount})
    return replace(state, response=_failure_response(
        state.request, outcome="dependency_unavailable", reason_code=reason,
        retrieval_status=state.retrieval_status, sources=state.sources, decision=decision,
        failed_stage="postprocess"))


# 为仅请求检索与准入的调用方构造响应，交付获准证据而不执行生成模型。
# 上游为 `ai.app.rag.langchain_pipeline._finalize_admission_state`，下游调用 `ai.app.rag.schema.RagQueryResponse`、`ai.app.llm.telemetry.get_request_id`。
def finalize_admission_query(state: QueryState) -> QueryState:
    """Complete the explicit admit-without-generation path after evidence postprocessing.

    This is a distinct completion branch: it never calls the model and never clears an
    answer produced by generate. Only the postprocessed admitted fragments are exposed
    as evidenceSources.
    """
    if state.response is not None:
        return state
    return replace(state, response=RagQueryResponse(
        answer="", originalQuery=state.question, rewrittenQuery=state.query,
        sources=state.sources, evidenceSources=state.evidence_sources,
        outcome="success", reasonCode=state.reason_code,
        requestId=telemetry.get_request_id(), retrievalStatus=state.retrieval_status,
        evidenceDecision=state.decision,
        compression=_compression_dto(state.compression)))


def generate_query(state: QueryState, *, generate=None) -> QueryState:
    """把当前问题、历史和已准入证据送给生成函数，返回含生成结果或失败原因的查询状态。

    上游为 `ai.app.rag.langchain_pipeline._generate_state`，下游调用 `_to_context`、`_at_stage`。
    """
    if state.response is not None:
        return state
    contexts = [_to_context(candidate) for candidate in state.admitted]
    generate_fn = generate or rag_service.generate_with_usage
    try:
        with _at_stage("generate"), stage("generate") as pending:
            _ensure_deadline()
            result = generate_fn(
                _generation_question(state),
                contexts, state.history)
            _ensure_deadline()
            pending.total_tokens = result.token_cost or None
            pending.degraded = result.outcome == "dependency_unavailable"
            if pending.degraded:
                pending.error = result.reason_code
    except QueryDeadlineExceeded:
        return replace(state, response=_failure_response(
            state.request, outcome="timeout", reason_code="query_deadline_exceeded",
            retrieval_status=state.retrieval_status, sources=state.sources,
            evidence_sources=state.evidence_sources, decision=state.decision,
            failed_stage="generate"))
    return replace(state, response=_generation_response(state, result))


def _generation_response(state: QueryState, result: GenerationResult) -> RagQueryResponse:
    """组合生成结果与查询状态，形成正文、证据、终态原因和模型用量的 RAG 响应。

    上游为 `_stream_events`，下游调用 `ai.app.rag.schema.RagQueryResponse`、`ai.app.llm.telemetry.get_request_id`。
    """
    unavailable = result.outcome == "dependency_unavailable"
    return RagQueryResponse(
        answer=result.answer, originalQuery=state.question,
        rewrittenQuery=state.query, sources=state.sources,
        evidenceSources=state.evidence_sources if result.outcome == "success" else [],
        outcome=result.outcome,
        reasonCode=state.reason_code if result.outcome == "success" else result.reason_code,
        failedStage="generate" if unavailable else None,
        requestId=telemetry.get_request_id(), retrievalStatus=state.retrieval_status,
        evidenceDecision=state.decision, degraded=unavailable,
        degradeReason=_public_generation_reason(result.degrade_reason),
        providerUsed=result.provider, tokenCost=result.token_cost,
        compression=_compression_dto(state.compression))


def finish_query(state: QueryState) -> RagQueryResponse:
    """取得已生成的结果并补齐阶段收集信息，供同步流程或 LCEL 链路返回。

    上游为 `ai.app.rag.langchain_pipeline.build_chain`、`_finalize`。
    """
    if state.response is None:
        raise RuntimeError("RAG pipeline did not produce a result")
    if state.response.outcome in {"success", "no_evidence"}:
        state.response.documentPage = state.document_page
    state.response.originalQuery = state.question
    state.response.rewrittenQuery = state.query
    return state.response


def _execute(request: RagQueryRequest) -> RagQueryResponse:
    """依次推进固定流程的准备、改写、检索、准入、后处理和生成，已有终态时跳过后续工作。

    上游为 `run_query`，下游调用 `prepare_query`、`finish_query`。
    """
    state = prepare_query(request)
    final_step = generate_query if request.generate else finalize_admission_query
    for step in (rewrite_query, retrieve_query, admit_query, postprocess_query, final_step):
        if state.response is not None:
            break
        state = step(state)
    return finish_query(state)
