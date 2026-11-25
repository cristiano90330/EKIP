"""验证降级召回、索引空间约束和托管模型的检索表现。"""

import pytest

from ai.app.models.context import model_execution
from ai.app.models.resolution import ModelConfigurationError
from ai.app.models.schema import IndexScope, ModelConfig, ModelExecution
from ai.app.retrieval import retriever as ret


def scope(kb=7, dimension=1024, space="space-a", model_id="embed-a", generation="generation-a"):
    """向 `test_missing_verified_scope_fails_before_database_or_embedding` 提供预设数据。返回 `IndexScope` 的结果。

    输入为 `kb`、`dimension`、`space`、`model_id`、`generation`。
    上游为 `test_generation_and_document_predicates_are_bound_and_exact_supplement_is_scoped`，下游调用 `ai.app.models.schema.ModelConfig`。
    """
    return IndexScope(knowledgeBaseId=kb,generationId=generation,spaceId=space,dimension=dimension,
                      embeddingModel=ModelConfig(id=model_id,type="embedding",provider="generic",
                          protocol="openai_embedding",model=model_id,baseUrl="https://models.invalid/v1",
                          dimension=dimension,semanticFingerprint=space))


def test_missing_verified_scope_fails_before_database_or_embedding(monkeypatch):
    """验证用例触发 `ModelConfigurationError` 异常。"""
    monkeypatch.setattr(ret.db,"query_rows",lambda *args:pytest.fail("must fail before SQL"))
    with model_execution(ModelExecution(indexScopes=[scope()])):
        with pytest.raises(ModelConfigurationError,match="requires_rebuild"):
            ret.vector_retrieve([7,8],"q",5)


@pytest.mark.parametrize("dimension",[1024,2560])
def test_generation_and_document_predicates_are_bound_and_exact_supplement_is_scoped(monkeypatch,dimension):
    """验证 `len(hits) == 1 and len(captured) == 2` 成立，且 `'document_chunk_vector_' + str(dimension)` 属于 `sql`。"""
    captured = []
    dangerous_generation = "generation' OR TRUE --"
    scopes = [scope(dimension=dimension,generation=dangerous_generation),
              scope(kb=8,dimension=dimension,generation="generation-b")]

    def query(sql,params):
        """返回 列表。输入为 `sql`、`params`。"""
        captured.append((sql,params))
        return [{"id":11,"distance":0.1}]

    monkeypatch.setattr(ret.db,"query_rows",query)
    hits = ret._space_hits(scopes,[1.,0.],5,42)
    assert len(hits) == 1 and len(captured) == 2
    for sql,params in captured:
        assert "document_chunk_vector_"+str(dimension) in sql
        assert "v.knowledge_base_id = ANY(%s::bigint[])" in sql
        assert "c.knowledge_base_id = v.knowledge_base_id" in sql
        assert sql.count("v.generation_id = %s") == 2
        assert "c.document_id = %s" in sql
        assert dangerous_generation not in sql and dangerous_generation in params
        assert 42 in params and "42" not in sql
    assert "MATERIALIZED" in captured[1][0] and "LIMIT 20000" in captured[1][0]


def test_multiple_spaces_embed_once_each_and_fuse_ranks_without_comparing_distance(monkeypatch):
    """验证 `embedded` 等于 `[('embed-a', 'query'), ('embed-b', 'query')]`，且 `groups` 等于 `[[7, 8], [9]]`。"""
    from ai.app.embedding.router import embedding_service
    embedded, groups = [], []

    def embed(texts,*,model,input_type):
        """返回 列表。输入为 `texts`、`model`、`input_type`。"""
        embedded.append((model.id,input_type))
        return [[1.,0.]]

    def hits(scopes,vector,top_k,document_id):
        """将当前记录追加到 `groups`，供调用方后续读取。输入为 `scopes`、`vector`、`top_k`、`document_id`。"""
        groups.append([item.knowledgeBaseId for item in scopes])
        return [{"id":11,"distance":0.99},{"id":12,"distance":1.2}] if scopes[0].spaceId == "space-a" else [
            {"id":13,"distance":0.001},{"id":14,"distance":0.002}]

    def hydrate(sql,params):
        """返回 列表。输入为 `sql`、`params`。"""
        ids,kbs=params
        return [{"id":id_,"document_id":id_,"knowledge_base_id":kbs[0],"chunk_index":0,"content":"content"}
                for id_ in ids]

    monkeypatch.setattr(embedding_service,"embed_batch",embed)
    monkeypatch.setattr(ret,"_space_hits",hits)
    monkeypatch.setattr(ret.db,"query_rows",hydrate)
    execution = ModelExecution(indexScopes=[scope(),scope(kb=8),
        scope(kb=9,dimension=2560,space="space-b",model_id="embed-b"),
        scope(kb=99,space="unrequested",model_id="unrequested")])
    with model_execution(execution):
        candidates = ret.vector_retrieve([7,8,9],"question",4)
    assert embedded == [("embed-a","query"),("embed-b","query")]
    assert groups == [[7,8],[9]]
    assert [item.id for item in candidates] == [11,13,12,14]
    assert all(item.distance is None and item.score_source == "rrf" for item in candidates)
    assert candidates[0].score == candidates[1].score


def test_same_dimension_different_models_still_form_distinct_spaces(monkeypatch):
    """验证 `ret.vector_retrieve([7, 8], 'q', 5)` 等于 `[]`，且 `model_ids` 等于 `['embed-a', 'embed-b']`。"""
    from ai.app.embedding.router import embedding_service
    model_ids = []
    monkeypatch.setattr(embedding_service,"embed_batch",lambda texts,**kwargs:model_ids.append(kwargs["model"].id) or [[1.,0.]])
    monkeypatch.setattr(ret,"_space_hits",lambda *args:[])
    with model_execution(ModelExecution(indexScopes=[scope(),scope(kb=8,space="space-b",model_id="embed-b")])):
        assert ret.vector_retrieve([7,8],"q",5) == []
    assert model_ids == ["embed-a","embed-b"]


def test_one_space_failure_preserves_other_authorized_results_as_partial(monkeypatch):
    """验证用例触发 `ret.PartialVectorRetrieval` 异常，并检查 `[item.id for item in failure.value.results]` 等于 `[11]`。"""
    from ai.app.embedding.router import embedding_service
    from ai.app.embedding.errors import EmbeddingUnavailableError

    def embed(texts,**kwargs):
        """返回 列表。输入为 `texts`、`kwargs`。"""
        if kwargs["model"].id == "embed-b":
            raise EmbeddingUnavailableError("unavailable")
        return [[1.,0.]]

    monkeypatch.setattr(embedding_service,"embed_batch",embed)
    monkeypatch.setattr(ret,"_space_hits",lambda *args:[{"id":11,"distance":0.2}])
    monkeypatch.setattr(ret.db,"query_rows",lambda *args:[{"id":11,"document_id":1,"knowledge_base_id":7,"chunk_index":0,"content":"allowed"}])
    with model_execution(ModelExecution(indexScopes=[scope(),scope(kb=8,space="space-b",model_id="embed-b")])), pytest.raises(ret.PartialVectorRetrieval) as failure:
        ret.vector_retrieve([7,8],"q",5)
    assert [item.id for item in failure.value.results] == [11]


@pytest.mark.parametrize("engine",["fixed","langchain"])
def test_retrieve_only_does_not_require_chat_or_rerank_bindings(monkeypatch,engine):
    """验证 `response.outcome == 'success' and response.sources[0].chunkId == 1` 成立，且 `response.evidenceDecision.state` 等于 `'bypassed'`。"""
    from ai.app.rag import pipeline
    from ai.app.rag.schema import RagQueryRequest
    from ai.app.models.context import index_scopes

    def retrieve(kbs,*args,**kwargs):
        """返回 列表。

        输入为 `kbs`、`args`、`kwargs`。
        下游调用 `ai.app.models.context.index_scopes`、`ai.app.retrieval.retriever.Candidate`。
        """
        assert index_scopes(kbs)[0].embeddingModel.id == "embed-a"
        return [ret.Candidate(1,1,7,0,"authorized content",distance=0.2)]

    monkeypatch.setattr(pipeline,"vector_retrieve",retrieve)
    request = RagQueryRequest(knowledgeBaseIds=[7],question="question",version="baseline",
        generate=False,pipelineEngine=engine,modelExecution=ModelExecution(indexScopes=[scope()]))
    response = pipeline.run_query(request)
    assert response.outcome == "success" and response.sources[0].chunkId == 1
    assert response.evidenceDecision.state == "bypassed"


def test_empty_scope_short_circuits_even_without_model_execution(monkeypatch):
    """通过 `RagQueryRequest` 核对本用例构造的状态。"""
    from ai.app.rag import pipeline
    from ai.app.rag.schema import RagQueryRequest
    monkeypatch.setattr(pipeline,"vector_retrieve",lambda *args:pytest.fail("empty scope must stop"))
    assert pipeline.run_query(RagQueryRequest(knowledgeBaseIds=[],question="q")).outcome == "no_evidence"


def test_keyword_source_name_comes_from_scoped_retrieval_view(monkeypatch):
    """验证 `candidates[0].source_name` 等于 `'星河项目报销规则-演示.txt'`，且 `'FROM document_chunk_retrieval'` 属于 `sql`。"""
    captured = []
    monkeypatch.setattr(ret, "_segment_query", lambda query: "报销")

    def query(sql, params):
        """返回 列表。输入为 `sql`、`params`。"""
        captured.append((sql, params))
        return [{"id": 11, "document_id": 42, "knowledge_base_id": 7,
                 "chunk_index": 0, "content": "材料缺失时应在 3 个工作日内补齐。",
                 "source_name": "星河项目报销规则-演示.txt"}]

    monkeypatch.setattr(ret.db, "query_rows", query)
    candidates = ret.keyword_retrieve([7], "材料缺失", 5, 42)

    assert candidates[0].source_name == "星河项目报销规则-演示.txt"
    sql, params = captured[0]
    assert "FROM document_chunk_retrieval" in sql
    assert "knowledge_base_id = ANY(%s::bigint[])" in sql and "document_id = %s" in sql
    assert params[:2] == ([7], 42)
