"""在授权库和索引代次内执行向量及关键词召回，合并候选并按需读取原文窗口。"""

import logging
import threading
from concurrent.futures import ThreadPoolExecutor
from concurrent.futures import TimeoutError as FuturesTimeoutError
from dataclasses import dataclass
from contextvars import copy_context
from time import monotonic
from typing import Callable, Optional

from ai.app.config import RAG_RETRIEVAL_MAX_INFLIGHT
from ai.app.embedding.errors import EmbeddingUnavailableError
from ai.app.models.context import index_scopes
from ai.app.models.resolution import ModelConfigurationError
from ai.app.models.runtime import ModelCallError
from ai.app.rag.deadline import QueryDeadlineExceeded, inherit_deadline
from ai.app.retrieval import db

logger = logging.getLogger("ekip.retrieval")

RRF_K = 60

# 进程内共享召回线程池；信号量限制运行中及排队的总任务数。
_LEG_EXECUTOR = ThreadPoolExecutor(max_workers=2, thread_name_prefix="ekip-retrieval")
_LEG_SLOTS = threading.BoundedSemaphore(RAG_RETRIEVAL_MAX_INFLIGHT)

# 直接调用时的召回等待默认值，管线可传入独立上限和整链截止时间。
DEFAULT_LEG_TIMEOUT_MS = 2000


@dataclass
class LegFanout:
    """保存各路候选、耗时及超时、依赖故障和容量拒绝，供上层判断检索是否完整。"""
    results: dict[str, list]
    timed_out: list[str]
    durations_ms: dict[str, int]
    failures: dict[str, str]
    rejected: list[str]

    @property
    def statuses(self) -> dict[str, str]:
        """读取两路召回的状态结果，供融合阶段区分完成、降级和失败。"""
        result = {name: "ok" for name in self.results if name not in self.timed_out
                  and name not in self.failures and name not in self.rejected}
        result.update({name: "timeout" for name in self.timed_out})
        result.update({name: "failed" for name in self.failures})
        result.update({name: "capacity_rejected" for name in self.rejected})
        return result

    def __iter__(self):
        """按调用方既有解包顺序依次返回向量及关键词候选。"""
        yield self.results
        yield self.timed_out
        yield self.durations_ms


# 用同一授权范围并行发起向量与关键词召回，返回各路候选及完成或失败状态。
# 上游为 `ai.app.rag.pipeline._fuse_legs`，下游调用 `_timed`。
def fan_out_legs(
    legs: dict[str, Callable[[], list]],
    timeout_ms: int = DEFAULT_LEG_TIMEOUT_MS,
    *,
    deadline_at: float | None = None,
) -> LegFanout:
    """并发提交各路召回，再按同一个截止时间等待，避免依次等待时重复消耗预算。

    截止时间取召回等待上限与整链 deadline 的较早值；非正等待上限不额外限时，仍服从传入 deadline。
    准入失败立即记录 capacity_rejected，预期依赖故障与超时按路返回，其他异常继续抛出。
    运行中的同步 I/O 不能由 future 强制停止；底层继承同一 deadline，槽位由完成回调释放。
    """
    if not legs:
        return LegFanout({}, [], {}, {}, [])
    durations: dict[str, int] = {}
    failures: dict[str, str] = {}
    rejected: list[str] = []
    timed_out: list[str] = []
    results: dict[str, list] = {}
    futures = {}

    start_at = monotonic()
    wait_deadline = start_at + (timeout_ms / 1000.0) if timeout_ms and timeout_ms > 0 else None
    if deadline_at is not None:
        wait_deadline = deadline_at if wait_deadline is None else min(wait_deadline, deadline_at)

    def _timed(name: str, fn: Callable[[], list]) -> Callable[[], list]:
        """包装一条召回操作并记录耗时及状态，供并行执行器统一上报。

        上游为 `fan_out_legs`。
        """
        def run():
            """在继承的截止时间内执行单条召回，返回候选，并在成功或异常退出时记录耗时。

            下游调用 `ai.app.rag.deadline.inherit_deadline`。
            """
            t0 = monotonic()
            try:
                with inherit_deadline(wait_deadline):
                    return fn()
            finally:
                durations[name] = int((monotonic() - t0) * 1000)

        return run

    # 先完成有限准入和提交，再等待各路结果，避免提交顺序使后续召回失去并发机会。
    for name, fn in legs.items():
        if not _LEG_SLOTS.acquire(blocking=False):
            rejected.append(name)
            results[name] = []
            continue
        try:
            future = _LEG_EXECUTOR.submit(copy_context().run, _timed(name, fn))
        except Exception:
            _LEG_SLOTS.release()
            raise
        future.add_done_callback(lambda _f: _LEG_SLOTS.release())
        futures[name] = future

    for name, fut in futures.items():
        try:
            remaining = None if wait_deadline is None else max(0.0, wait_deadline - monotonic())
            results[name] = fut.result(timeout=remaining) or []
        except (FuturesTimeoutError, QueryDeadlineExceeded):
            logger.warning("并行召回一路超过共享等待预算: leg=%s timeoutMs=%s", name, timeout_ms)
            results[name] = []
            timed_out.append(name)
            fut.cancel()
        except PartialVectorRetrieval as exc:
            results[name] = exc.results
            failures[name] = "partial_vector_retrieval"
        except (db.ReadOnlyPgError, EmbeddingUnavailableError, ModelConfigurationError, ModelCallError) as exc:
            logger.warning("并行召回一路失败: leg=%s errorType=%s", name, type(exc).__name__)
            results[name] = []
            failures[name] = type(exc).__name__
        except Exception:
            for pending in futures.values():
                pending.cancel()
            raise
    # 复制当前耗时，避免超时返回后仍运行的 worker 改写已上报结果。
    observed = {
        name: (durations[name] if name in durations else max(0, timeout_ms or 0))
        for name in legs
    }
    return LegFanout(results, timed_out, observed, failures, rejected)


@dataclass
class Candidate:
    """管线内部检索候选，保留来源、距离、分数以及分数是否可用于门禁的状态。"""
    id: int
    document_id: int
    knowledge_base_id: Optional[int]
    chunk_index: int
    content: str
    source_type: Optional[str] = None
    page_no: Optional[int] = None
    heading_path: Optional[str] = None
    distance: Optional[float] = None
    score: Optional[float] = None
    score_source: str = "none"
    rerank_scorer: str = "none"
    score_valid: bool = False
    rerank_degraded: bool = False
    score_error: str = ""
    content_offset: Optional[int] = None
    source_name: Optional[str] = None


def _vector_literal(vec) -> str:
    """将嵌入向量编码为 PostgreSQL 可接收的参数文本。

    上游为 `_space_hits`。
    """
    return "[" + ",".join(repr(float(v)) for v in vec) + "]"


def _segment_query(query: str) -> str:
    """为给定问题生成 PostgreSQL 全文搜索条件，供关键词召回复用文档分词规则。

    上游为 `keyword_retrieve`，下游调用 `ai.app.keyword.segmenter.segment`。
    """
    from ai.app.keyword.segmenter import segment
    _, tsquery = segment(query)
    return tsquery


# 按命中 ID 回填正文时再次绑定知识库范围，避免无范围的按 ID 读取。
_HYDRATE_SQL = """
SELECT id, document_id, knowledge_base_id, chunk_index, content,
       source_type, page_no, heading_path, source_name
FROM document_chunk_retrieval
WHERE id = ANY(%s::bigint[])
  AND knowledge_base_id = ANY(%s::bigint[])
"""

_HYDRATE_DOCUMENT_SQL = """
SELECT id, document_id, knowledge_base_id, chunk_index, content,
       source_type, page_no, heading_path, source_name
FROM document_chunk_retrieval
WHERE id = ANY(%s::bigint[])
  AND knowledge_base_id = ANY(%s::bigint[])
  AND document_id = %s
"""

# 关键词查询同样绑定授权范围，使用入库的预分词全文检索字段。
_KEYWORD_SQL = """
SELECT id, document_id, knowledge_base_id, chunk_index, content,
       source_type, page_no, heading_path, source_name
FROM document_chunk_retrieval
WHERE knowledge_base_id = ANY(%s::bigint[])
  AND search_vector @@ to_tsquery('simple', %s)
ORDER BY ts_rank(search_vector, to_tsquery('simple', %s)) DESC
LIMIT %s
"""

_KEYWORD_DOCUMENT_SQL = """
SELECT id, document_id, knowledge_base_id, chunk_index, content,
       source_type, page_no, heading_path, source_name
FROM document_chunk_retrieval
WHERE knowledge_base_id = ANY(%s::bigint[])
  AND document_id = %s
  AND search_vector @@ to_tsquery('simple', %s)
ORDER BY ts_rank(search_vector, to_tsquery('simple', %s)) DESC
LIMIT %s
"""


def _to_candidate(row: dict, *, distance: Optional[float] = None) -> Candidate:
    """将数据库中的切片记录转换成带正文、定位和距离的检索候选。

    上游为 `read_document_window`、`vector_retrieve`。
    """
    return Candidate(
        id=row["id"],
        document_id=row["document_id"],
        knowledge_base_id=row.get("knowledge_base_id"),
        chunk_index=row["chunk_index"],
        content=row["content"],
        source_type=row.get("source_type"),
        page_no=row.get("page_no"),
        heading_path=row.get("heading_path"),
        source_name=row.get("source_name"),
        distance=distance,
        score_source="distance" if distance is not None else "none",
    )


_DOCUMENT_POSITION_SQL = """
WITH scoped AS (
    SELECT id, ROW_NUMBER() OVER (ORDER BY chunk_index ASC, id ASC) - 1 AS row_offset
    FROM document_chunk
    WHERE knowledge_base_id = ANY(%s::bigint[])
      AND document_id = %s
      AND source_type IS DISTINCT FROM 'parent'
)
SELECT row_offset FROM scoped WHERE id = %s
"""

_DOCUMENT_WINDOW_SQL = """
SELECT id, document_id, knowledge_base_id, chunk_index,
       SUBSTRING(content FROM %s FOR %s) AS content,
       CHAR_LENGTH(content) AS content_length, source_type, page_no, heading_path, source_name
FROM document_chunk_retrieval
WHERE knowledge_base_id = ANY(%s::bigint[])
  AND document_id = %s
  AND source_type IS DISTINCT FROM 'parent'
ORDER BY chunk_index ASC, id ASC
LIMIT 2 OFFSET %s
"""


# 在授权知识库和目标文档内按窗口请求读取正文，返回候选与页偏移信息。
# 上游为 `ai.app.rag.pipeline.retrieve_query`，下游调用 `ai.app.rag.schema.DocumentReadPage`、`ai.app.retrieval.db.query_rows`。
def read_document_window(kb_ids, document_id: int, request):
    """Read one bounded window before scoring. Never skip the unseen suffix of a long chunk.

    The second row is only a has-more probe. Both queries bind the document and authorized KBs;
    parent rows are excluded so offsets do not duplicate their child text.
    """
    from ai.app.rag.schema import DocumentReadPage
    offset, content_offset = request.offset, request.contentOffset
    end_offset = request.endOffset
    if not kb_ids:
        return [], DocumentReadPage(offset=offset, contentOffset=content_offset, endOffset=end_offset)
    if request.aroundChunkId is not None:
        positions = db.query_rows(_DOCUMENT_POSITION_SQL,
                                  (list(kb_ids), document_id, request.aroundChunkId))
        if not positions:
            raise ValueError("aroundChunkId is not readable in this document")
        anchor = int(positions[0]["row_offset"])
        offset = max(0, anchor - request.contextRadius)
        end_offset = anchor + request.contextRadius + 1
    page = DocumentReadPage(offset=offset, contentOffset=content_offset, endOffset=end_offset)
    if end_offset is not None and offset >= end_offset:
        return [], page
    rows = db.query_rows(_DOCUMENT_WINDOW_SQL,
                         (content_offset + 1, request.windowChars, list(kb_ids), document_id, offset))
    if not rows:
        return [], page
    row = rows[0]
    content_length = int(row["content_length"] or 0)
    if content_offset > 0 and content_offset >= content_length:
        raise ValueError("contentOffset is outside the current chunk")
    fragment = row["content"] or ""
    page.scannedChars = len(fragment)
    if content_offset + len(fragment) < content_length:
        page.nextOffset = offset
        page.nextContentOffset = content_offset + len(fragment)
        page.hasMore = True
    elif len(rows) > 1 and (end_offset is None or offset + 1 < end_offset):
        page.nextOffset = offset + 1
        page.nextContentOffset = 0
        page.hasMore = True
    candidate = _to_candidate(row)
    candidate.content_offset = content_offset
    return ([candidate] if fragment.strip() else []), page


class PartialVectorRetrieval(EmbeddingUnavailableError):
    """One vector space failed; preserve already retrieved authorized candidates."""

    def __init__(self, results):
        """创建实例并初始化 `results`，供后续方法共享。

        输入为 `results`。
        """
        super().__init__("partial_vector_retrieval")
        self.results = results


def _space_hits(scopes, vector, top_k, document_id):
    # Table names and SQL fragments are selected from fixed code, never supplied
    # by a request. Every branch binds both the authorized KB and generation.
    """在同一向量空间的知识库及索引代次内召回近邻，必要时按相同范围补足精确结果。

    上游为 `vector_retrieve`，下游调用 `_vector_literal`、`ai.app.retrieval.db.query_rows`。
    """
    dimension = scopes[0].dimension
    table = {1024:"document_chunk_vector_1024",2560:"document_chunk_vector_2560"}[dimension]

    # 库与索引代次按配对条件过滤，避免把不同库的代次分别放进独立集合而扩大查询范围。
    pairs = " OR ".join("(v.knowledge_base_id = %s AND v.generation_id = %s)" for _ in scopes)
    kb_ids = [scope.knowledgeBaseId for scope in scopes]
    scope_args = [value for scope in scopes for value in (scope.knowledgeBaseId,scope.generationId)]
    conditions = ("v.knowledge_base_id = ANY(%s::bigint[]) AND c.knowledge_base_id = v.knowledge_base_id "
                  "AND ("+pairs+") AND c.source_type IS DISTINCT FROM 'parent'")
    args = [kb_ids,*scope_args]
    if document_id is not None:
        conditions += " AND c.document_id = %s"
        args.append(document_id)
    vector_sql = _vector_literal(vector)
    sql = ("SELECT v.chunk_id AS id, v.embedding <=> %s::halfvec AS distance FROM "+table+
           " v JOIN document_chunk c ON c.id=v.chunk_id WHERE "+conditions+" ORDER BY distance LIMIT %s")
    hits = db.query_rows(sql,(vector_sql,*args,top_k))
    if len(hits) < top_k:
        # ANN filtering can underfill. Exact supplementation stays within the
        # same KB/generation and uses a bounded materialized candidate window.
        exact = ("WITH scoped AS MATERIALIZED (SELECT v.chunk_id,v.embedding FROM "+table+
                 " v JOIN document_chunk c ON c.id=v.chunk_id WHERE "+conditions+
                 " ORDER BY v.chunk_id LIMIT 20000) SELECT chunk_id AS id, embedding <=> %s::halfvec AS distance "
                 "FROM scoped ORDER BY distance LIMIT %s")
        extra = db.query_rows(exact,(*args,vector_sql,top_k))
        by_id = {row["id"]:row for row in [*hits,*extra]}
        hits = sorted(by_id.values(),key=lambda row:float(row["distance"]))[:top_k]
    return hits


def vector_retrieve(kb_ids, query: str, top_k: int,
                    document_id: Optional[int] = None) -> list[Candidate]:
    """在授权知识库的索引空间内召回向量候选。

    输入为授权库 ID、查询、候选上限以及可选的文档 ID。
    由固定及 LCEL 召回步骤调用，经 models.context.index_scopes 获取绑定，并使用 _space_hits 搜索。
    每个空间独立嵌入和排序，跨空间按名次融合。某个空间失败时以 PartialVectorRetrieval 携带部分召回结果。
    上游为 `ai.app.rag.pipeline._fuse_legs`，下游调用 `ai.app.embedding.router.embedding_service.embed_batch`、`ai.app.retrieval.db.query_rows`。
    """
    if not kb_ids or top_k <= 0:
        return []

    # 每个向量空间先独立嵌入和检索，空间间不比较原始距离。
    scopes = index_scopes(kb_ids)
    groups = {}
    for scope in scopes:
        group = groups.setdefault(scope.spaceId,[])
        if group and (group[0].dimension != scope.dimension or
                      group[0].embeddingModel.semanticFingerprint != scope.embeddingModel.semanticFingerprint or
                      group[0].embeddingModel.id != scope.embeddingModel.id):
            raise ModelConfigurationError("inconsistent_index_space_snapshot")
        group.append(scope)
    from ai.app.embedding.router import embedding_service

    # 记录每个空间的排序结果与失败次数，部分成功的候选仍可交给上游做受控降级。
    ranked, failures = [], 0
    for group in groups.values():
        try:
            vector = embedding_service.embed_batch([query],model=group[0].embeddingModel,input_type="query")[0]
            hits = _space_hits(group,vector,top_k,document_id)
            ids = [hit["id"] for hit in hits]
            if not ids:
                ranked.append([])
                continue
            group_kbs = [scope.knowledgeBaseId for scope in group]
            rows = db.query_rows(_HYDRATE_SQL,(ids,group_kbs)) if document_id is None else db.query_rows(
                _HYDRATE_DOCUMENT_SQL,(ids,group_kbs,document_id))
            by_id = {row["id"]:row for row in rows}
            ranked.append([_to_candidate(by_id[hit["id"]],distance=hit["distance"])
                           for hit in hits if hit["id"] in by_id])
        except QueryDeadlineExceeded:
            raise
        except (db.ReadOnlyPgError,EmbeddingUnavailableError,ModelCallError,ModelConfigurationError):
            failures += 1
    if len(groups) == 1:
        result = ranked[0] if ranked else []
    else:
        candidates, scores = {}, {}
        for rows in ranked:
            for rank,candidate in enumerate(rows,1):
                candidates[candidate.id] = candidate
                scores[candidate.id] = scores.get(candidate.id,0)+1/(RRF_K+rank)
        result = []
        for chunk_id in sorted(scores,key=lambda key:scores[key],reverse=True)[:top_k]:
            candidate = candidates[chunk_id]
            candidate.distance, candidate.score, candidate.score_source = None,scores[chunk_id],"rrf"
            result.append(candidate)
    if failures:
        raise PartialVectorRetrieval(result)
    return result


# 用问题分词在授权知识库和可选文档范围内搜索，返回符合全文条件的候选。
# 上游为 `ai.app.rag.pipeline._fuse_legs`，下游调用 `_segment_query`、`ai.app.retrieval.db.query_rows`。
def keyword_retrieve(kb_ids, query: str, top_k: int,
                     document_id: Optional[int] = None) -> list[Candidate]:
    """空范围或无有效分词直接返回，否则绑定范围和 tsquery，读取关键词 Top-K。

    指定 document_id 时同一 SQL 还与知识库范围一起绑定该文档，不做返回后过滤。
    """
    if not kb_ids:
        return []
    tsquery = _segment_query(query)
    if not tsquery:
        return []
    if document_id is None:
        rows = db.query_rows(_KEYWORD_SQL, (list(kb_ids), tsquery, tsquery, top_k))
    else:
        rows = db.query_rows(_KEYWORD_DOCUMENT_SQL,
                             (list(kb_ids), document_id, tsquery, tsquery, top_k))
    return [_to_candidate(r) for r in rows]


def _rank_by_distance(results: list[Candidate]) -> list[Candidate]:
    """按向量距离排列候选，供融合阶段取得可比较的名次。

    上游为 `fuse`。
    """
    return sorted(
        results,
        key=lambda c: (c.distance is None, c.distance if c.distance is not None else float("inf")),
    )


# 对同一切片合并向量与关键词名次贡献，返回按融合分排序的 Top-K。
# 上游为 `ai.app.rag.pipeline._fuse_fanout`，下游调用 `add_rrf`、`_rank_by_distance`。
def fuse(vector_results, keyword_results, top_k: int, rrf_k: int = RRF_K) -> list[Candidate]:
    """以 document_id 与 chunk_index 标识同一候选，累加各路 1/(rrf_k+rank) 并取 Top-K。

    向量路先按距离排列，关键词路沿用全文相关度顺序；rrf_k 由调用方按请求传入，默认值用于直调。
    融合分标记为 rrf，后续门禁仍需依据合法 scorer 和对应证据策略处理。
    """
    if top_k <= 0:
        return []
    by_key: dict[str, Candidate] = {}
    scores: dict[str, float] = {}

    def add_rrf(results):
        """累加每路候选的倒数名次分，并保留融合结果所需的候选正文及定位。

        上游为 `fuse`。
        """
        rank = 1
        for c in results or []:
            if c is None or c.document_id is None or c.chunk_index is None:
                continue
            key = f"{c.document_id}:{c.chunk_index}"
            by_key.setdefault(key, c)
            scores[key] = scores.get(key, 0.0) + 1.0 / (rrf_k + rank)
            rank += 1

    add_rrf(_rank_by_distance(vector_results))
    add_rrf(keyword_results)

    ordered = sorted(scores.items(), key=lambda kv: kv[1], reverse=True)[:top_k]
    result = []
    for key, score in ordered:
        c = by_key[key]
        c.score = score
        c.score_source = "rrf"
        result.append(c)
    return result


# 父子连接要求知识库和文档一致，子块查询继续绑定授权知识库范围。
_EXPAND_SQL = """
SELECT c.id AS child_id, c.document_id, c.knowledge_base_id,
       c.parent_chunk_id, COALESCE(p.content, c.content) AS content
FROM document_chunk c
LEFT JOIN document_chunk p
  ON c.parent_chunk_id = p.id
 AND p.knowledge_base_id = c.knowledge_base_id
 AND p.document_id = c.document_id
WHERE c.id = ANY(%s::bigint[])
  AND c.knowledge_base_id = ANY(%s::bigint[])
"""


# 在获准知识库范围内用父块补充切片语境，保留选择所得证据的定位。
# 上游为 `ai.app.rag.pipeline.postprocess_query`，下游调用 `ai.app.retrieval.db.query_rows`。
def expand_parent_chunks(candidates: list[Candidate], authorized_kb_ids,
                         *, deduplicate: bool = True) -> list[Candidate]:
    """在候选和授权范围均非空时，批量读取子块对应的父块内容用于扩展上下文。

    查询前过滤候选知识库，查询后再核对数据库中的子块、文档和知识库身份；父块缺失时保留子块正文。
    候选 ID 继续指向子块，默认按父块去重；完整支持审查可关闭去重，保留必需引用的子块身份。
    """
    if not candidates or not authorized_kb_ids:
        return candidates
    allowed_kb_ids = set(authorized_kb_ids)
    eligible = [c for c in candidates
                if c.id is not None and c.knowledge_base_id in allowed_kb_ids]
    ids = list(dict.fromkeys(c.id for c in eligible))
    if not ids:
        return []

    rows = db.query_rows(_EXPAND_SQL, (ids, list(allowed_kb_ids)))
    candidate_by_id = {c.id: c for c in eligible}
    rows = [r for r in rows
            if r["child_id"] in candidate_by_id
            and r.get("knowledge_base_id") == candidate_by_id[r["child_id"]].knowledge_base_id
            and r.get("document_id") == candidate_by_id[r["child_id"]].document_id]
    parent_id_by_child = {r["child_id"]: r["parent_chunk_id"] for r in rows}
    content_by_parent: dict[int, str] = {}
    for r in rows:
        if r["parent_chunk_id"] is not None:
            content_by_parent[r["parent_chunk_id"]] = r["content"]

    out: list[Candidate] = []
    seen: set[int] = set()
    for c in eligible:
        parent_id = parent_id_by_child.get(c.id)
        key = parent_id if parent_id is not None else c.id
        if deduplicate and key is not None and key in seen:
            continue
        if key is not None:
            seen.add(key)
        if parent_id is not None:
            c.content = content_by_parent.get(parent_id, c.content)
        out.append(c)
    return out
