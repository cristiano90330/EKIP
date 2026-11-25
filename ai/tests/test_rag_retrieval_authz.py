"""验证问答检索使用调用方传入的知识库授权范围。"""

import pytest

from ai.app.rag import pipeline
from ai.app.retrieval import retriever as ret
from ai.app.retrieval.retriever import keyword_retrieve, vector_retrieve

ALL_VERSIONS = ("baseline", "hybrid", "hybrid-rerank", "hybrid-rerank-rewrite")


def _verified_execution(kb_ids):
    """向 `test_vector_sql_pushes_kb_predicate_as_bound_parameter_never_interpolated` 提供预设数据。返回 `ModelExecution` 的结果。

    输入为 `kb_ids`。
    上游为 `test_vector_document_scope_is_bound_in_both_sql_never_interpolated`，下游调用 `ai.app.models.schema.ModelConfig`、`ai.app.models.schema.IndexScope`。
    """
    from ai.app.models.schema import ModelConfig, ModelExecution, IndexScope
    model = ModelConfig(id="embed-authz",type="embedding",protocol="openai_embedding",
                        model="embed",baseUrl="https://invalid.local/v1",dimension=1024,
                        semanticFingerprint="authz-space")
    return ModelExecution(indexScopes=[IndexScope(knowledgeBaseId=kb,generationId="generation-"+str(kb),
        spaceId="authz-space",dimension=1024,embeddingModel=model) for kb in kb_ids])



# 输入为 `monkeypatch`。
# 上游为 `test_vector_retrieve_empty_kb_ids_returns_empty_without_db`、`test_keyword_retrieve_empty_kb_ids_returns_empty_without_db`。
def _db_must_not_be_touched(monkeypatch):
    """把 DB 换成「一碰就炸」，用来证明短路发生在 DB 之前，而不是「查了再过滤」。"""

    def boom(*a, **k):
        """触发 `AssertionError`，交由上游处理。输入为 `a`、`k`。"""
        raise AssertionError("空 kb_ids 时不得触碰 DB")

    monkeypatch.setattr(ret.db, "query_rows", boom)


# ---------- 1. 空集合 = 不读（且不查） ----------

@pytest.mark.parametrize("empty", [[], None])
def test_vector_retrieve_empty_kb_ids_returns_empty_without_db(monkeypatch, empty):
    """验证 `vector_retrieve(empty, '住宿标准', 5)` 等于 `[]`。"""
    _db_must_not_be_touched(monkeypatch)
    assert vector_retrieve(empty, "住宿标准", 5) == []


@pytest.mark.parametrize("empty", [[], None])
def test_keyword_retrieve_empty_kb_ids_returns_empty_without_db(monkeypatch, empty):
    """验证 `keyword_retrieve(empty, '住宿标准', 5)` 等于 `[]`。"""
    _db_must_not_be_touched(monkeypatch)
    assert keyword_retrieve(empty, "住宿标准", 5) == []


@pytest.mark.parametrize("version", ALL_VERSIONS)
def test_retrieve_route_short_circuits_for_every_version(monkeypatch, version):
    """路由层同样必须在 fan-out / rerank / 融合之前就短路 —— 每个档位都要，不能只测一条。"""
    _db_must_not_be_touched(monkeypatch)
    # 若短路被写漏，leg 会被真正调用；这里让它们炸出来，暴露「短路失效」而不是静默返回空。
    monkeypatch.setattr(pipeline, "vector_retrieve",
                        lambda *a, **k: (_ for _ in ()).throw(AssertionError("leg 不该被调用")))
    monkeypatch.setattr(pipeline, "keyword_retrieve",
                        lambda *a, **k: (_ for _ in ()).throw(AssertionError("leg 不该被调用")))
    assert pipeline._retrieve([], "住宿标准", 5, 20, version) == []


# ---------- 2. kb 过滤以绑定参数进 SQL（防回归，从 Java PgVectorStoreTest 移植） ----------

def test_vector_sql_pushes_kb_predicate_as_bound_parameter_never_interpolated(monkeypatch):
    """验证 `len(vector_retrieve([7, 8], '住宿标准', 1))` 等于 `1`，且 `'WHERE v.knowledge_base_id = ANY(%s::bigint[])'` 属于 `sql`。"""
    captured = []

    def fake_query_rows(sql, params):
        """返回 列表。输入为 `sql`、`params`。"""
        captured.append((sql, params))
        if len(captured) == 1:
            return [{"id": 11, "distance": 0.1}]
        return [{"id": 11, "document_id": 99, "knowledge_base_id": 7,
                 "chunk_index": 0, "content": "允许内容"}]

    monkeypatch.setattr(ret.db, "query_rows", fake_query_rows)
    from ai.app.embedding.router import embedding_service
    from ai.app.models.context import model_execution
    monkeypatch.setattr(embedding_service, "embed_batch", lambda *args, **kwargs: [[1.0, 2.0]])

    with model_execution(_verified_execution([7,8])):
        assert len(vector_retrieve([7, 8], "住宿标准", 1)) == 1

    sql, params = captured[0]
    assert "WHERE v.knowledge_base_id = ANY(%s::bigint[])" in sql
    assert "v.generation_id = %s" in sql
    assert "document_chunk_vector_1024" in sql
    # 知识库 id 走绑定参数（位置 1），没有拼进 SQL 文本
    assert params[1] == [7, 8]
    assert "{7,8}" not in sql
    assert "7,8" not in sql

    hydrate_sql, hydrate_params = captured[1]
    assert "AND knowledge_base_id = ANY(%s::bigint[])" in hydrate_sql
    assert hydrate_params == ([11], [7, 8])


def test_keyword_sql_pushes_kb_predicate_as_bound_parameter_never_interpolated(monkeypatch):
    """验证 `keyword_retrieve([7, 8], '住宿标准', 5)` 等于 `[]`，且 `'WHERE knowledge_base_id = ANY(%s::bigint[])'` 属于 `sql`。"""
    captured = []

    def fake_query_rows(sql, params):
        """返回 列表。输入为 `sql`、`params`。"""
        captured.append((sql, params))
        return []

    monkeypatch.setattr(ret.db, "query_rows", fake_query_rows)
    monkeypatch.setattr(ret, "_segment_query", lambda q: "住宿 | 标准")

    assert keyword_retrieve([7, 8], "住宿标准", 5) == []

    sql, params = captured[0]
    assert "WHERE knowledge_base_id = ANY(%s::bigint[])" in sql
    assert "search_vector @@ to_tsquery('simple', %s)" in sql
    assert params[0] == [7, 8]      # 关键词 SQL 的 kb 集合是第 0 个参数
    assert "{7,8}" not in sql
    assert "7,8" not in sql


# ---------- 3. 文档限定检索：document_id 与 KB 范围一起走绑定参数 ----------

def test_vector_document_scope_is_bound_in_both_sql_never_interpolated(monkeypatch):
    """验证 `len(vector_retrieve([7, 8], '住宿标准', 1, 42))` 等于 `1`，且 `'WHERE v.knowledge_base_id = ANY(%s::bigint[])'` 属于 `sql`。"""
    captured = []

    def fake_query_rows(sql, params):
        """返回 列表。输入为 `sql`、`params`。"""
        captured.append((sql, params))
        if len(captured) == 1:
            return [{"id": 11, "distance": 0.1}]
        return [{"id": 11, "document_id": 42, "knowledge_base_id": 7,
                 "chunk_index": 0, "content": "允许内容"}]

    monkeypatch.setattr(ret.db, "query_rows", fake_query_rows)
    from ai.app.embedding.router import embedding_service
    from ai.app.models.context import model_execution
    monkeypatch.setattr(embedding_service, "embed_batch", lambda *args, **kwargs: [[1.0, 2.0]])

    with model_execution(_verified_execution([7,8])):
        assert len(vector_retrieve([7, 8], "住宿标准", 1, 42)) == 1

    sql, params = captured[0]
    assert "WHERE v.knowledge_base_id = ANY(%s::bigint[])" in sql
    assert "AND c.document_id = %s" in sql
    assert "v.generation_id = %s" in sql
    assert params[1] == [7, 8] and params[-2] == 42
    assert "42" not in sql

    hydrate_sql, hydrate_params = captured[1]
    assert "AND knowledge_base_id = ANY(%s::bigint[])" in hydrate_sql
    assert "AND document_id = %s" in hydrate_sql
    assert hydrate_params == ([11], [7, 8], 42)
    assert "42" not in hydrate_sql


def test_keyword_document_scope_is_bound_never_interpolated(monkeypatch):
    """验证 `keyword_retrieve([7, 8], '住宿标准', 5, 42)` 等于 `[]`，且 `'WHERE knowledge_base_id = ANY(%s::bigint[])'` 属于 `sql`。"""
    captured = []

    def fake_query_rows(sql, params):
        """返回 列表。输入为 `sql`、`params`。"""
        captured.append((sql, params))
        return []

    monkeypatch.setattr(ret.db, "query_rows", fake_query_rows)
    monkeypatch.setattr(ret, "_segment_query", lambda q: "住宿 | 标准")

    assert keyword_retrieve([7, 8], "住宿标准", 5, 42) == []

    sql, params = captured[0]
    assert "WHERE knowledge_base_id = ANY(%s::bigint[])" in sql
    assert "AND document_id = %s" in sql
    assert params[0] == [7, 8] and params[1] == 42
    assert "42" not in sql


def test_document_window_default_denies_empty_scope_and_binds_scope_document_and_cursor(monkeypatch):
    """验证 `ret.read_document_window([], 42, DocumentReadRequest())[0] == []` 成立，且 `candidates[0].content_offset` 等于 `2`。"""
    from ai.app.rag.schema import DocumentReadRequest
    _db_must_not_be_touched(monkeypatch)
    assert ret.read_document_window([], 42, DocumentReadRequest())[0] == []
    captured = []

    def query(sql, params):
        """返回 列表。输入为 `sql`、`params`。"""
        captured.append((sql, params))
        return [{"id": 11, "document_id": 42, "knowledge_base_id": 7, "chunk_index": 0,
                 "content": "😀原文续", "content_length": 12}]

    monkeypatch.setattr(ret.db, "query_rows", query)
    candidates, page = ret.read_document_window([7, 8], 42,
                                               DocumentReadRequest(offset=3, contentOffset=2, windowChars=4))
    assert candidates[0].content_offset == 2
    assert page.nextOffset == 3 and page.nextContentOffset == 6
    assert page.scannedChars == 4  # Unicode code points, not UTF-16 units.
    sql, params = captured[0]
    assert "knowledge_base_id = ANY(%s::bigint[])" in sql and "document_id = %s" in sql
    assert "source_type IS DISTINCT FROM 'parent'" in sql
    assert "ORDER BY chunk_index ASC, id ASC" in sql and "LIMIT 2 OFFSET %s" in sql
    assert params == (3, 4, [7, 8], 42, 3)
    assert "42" not in sql


def test_document_context_anchor_is_scoped_and_continuation_stops_at_context_boundary(monkeypatch):
    """验证 `captured[0][1]` 等于 `([7], 42, 99)`，且 `'knowledge_base_id = ANY(%s::bigint[])'` 属于 `captured[0][0]`。"""
    from ai.app.rag.schema import DocumentReadRequest
    captured = []

    def query(sql, params):
        """返回 列表。输入为 `sql`、`params`。"""
        captured.append((sql, params))
        if len(captured) == 1:
            return [{"row_offset": 5}]
        return [{"id": 11, "document_id": 42, "knowledge_base_id": 7, "chunk_index": 4,
                 "content": "原文", "content_length": 2}, {"id": 12}]

    monkeypatch.setattr(ret.db, "query_rows", query)
    _, page = ret.read_document_window([7], 42, DocumentReadRequest(aroundChunkId=99, contextRadius=1))
    assert captured[0][1] == ([7], 42, 99)
    assert "knowledge_base_id = ANY(%s::bigint[])" in captured[0][0]
    assert "document_id = %s" in captured[0][0]
    assert page.offset == 4 and page.endOffset == 7 and page.nextOffset == 5
    _, final = ret.read_document_window([7], 42, DocumentReadRequest(offset=6, endOffset=7))
    assert final.hasMore is False and final.nextOffset is None
