"""使用 LCEL 装配查询阶段，同时复用固定流程的状态、证据和错误契约。"""

from dataclasses import asdict, dataclass, replace
from functools import lru_cache, partial
from typing import Any

from langchain_core.callbacks import CallbackManagerForRetrieverRun
from langchain_core.documents import Document
from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.messages import AIMessage, BaseMessage, HumanMessage, SystemMessage
from langchain_core.outputs import ChatGeneration, ChatResult
from langchain_core.prompts import ChatPromptTemplate
from langchain_core.retrievers import BaseRetriever
from langchain_core.runnables import Runnable, RunnableBranch, RunnableConfig, RunnableLambda, RunnablePassthrough
from langsmith import tracing_context

from ai.app.llm.guard import gate
from ai.app.rag import pipeline as steps
from ai.app.rag.deadline import current_deadline_at
from ai.app.rag.prompt_builder import SYSTEM_PROMPT, build_prompt
from ai.app.rag.rag_service import GenerationResult, decode_generation, generation_response_format
from ai.app.rag.schema import RagQueryRequest, RagQueryResponse
from ai.app.rag.state import QueryState
from ai.app.retrieval.retriever import Candidate, LegFanout


def _document(candidate: Candidate) -> Document:
    """将检索候选转换为 LCEL 使用的 Document，正文独立存放且索引定位保留在元数据中。

    上游为 `_fuse_documents`、`_retrieve`。
    """
    metadata = asdict(candidate)
    content = metadata.pop("content")
    return Document(id=str(candidate.id), page_content=content, metadata=metadata)


def _candidate(document: Document) -> Candidate:
    """把 LCEL Document 的正文与元数据还原为固定流程能识别的 Candidate。

    上游为 `_fuse_documents`。
    """
    return Candidate(content=document.page_content, **document.metadata)


class ScopedPgRetriever(BaseRetriever):
    """A per-call retriever; scope comes exclusively from Java's authorized request."""
    knowledge_base_ids: tuple[int, ...]
    top_k: int
    leg: str
    document_id: int | None = None

    def _get_relevant_documents(self, query: str, *, run_manager: CallbackManagerForRetrieverRun) -> list[Document]:
        """按当前授权库、可选目标文档及召回类型取得候选，校验返回范围后转换为 Document 列表。

        下游调用 `ai.app.rag.pipeline._ensure_deadline`、`_document`。
        """
        if not self.knowledge_base_ids:
            return []
        steps._ensure_deadline()
        retrieve = steps.vector_retrieve if self.leg == "vector" else steps.keyword_retrieve
        candidates = (retrieve(list(self.knowledge_base_ids), query, self.top_k)
                      if self.document_id is None else
                      retrieve(list(self.knowledge_base_ids), query, self.top_k, self.document_id))
        if any(candidate.knowledge_base_id not in self.knowledge_base_ids for candidate in candidates):
            raise ValueError("retrieval scope mismatch")
        if self.document_id is not None and any(
                candidate.document_id != self.document_id for candidate in candidates):
            raise ValueError("retrieval document scope mismatch")
        return [_document(candidate) for candidate in candidates]


class BoundedParallelRecall(Runnable[dict, LegFanout]):
    """Parallel Runnable whose workers, capacity and remaining IO budget are shared with fixed."""

    def invoke(self, input: dict, config: RunnableConfig | None = None, **kwargs: Any) -> LegFanout:
        """用同一查询和 LCEL 配置启动向量、关键词两路召回并返回各路执行情况。

        上游为 `_generate`，下游调用 `ai.app.rag.pipeline.fan_out_legs`、`recall`。
        """
        retrievers = {leg: ScopedPgRetriever(
            knowledge_base_ids=tuple(input["scope"]), top_k=input["recall_n"], leg=leg,
            document_id=input.get("document_id"))
            for leg in ("vector", "keyword")}

        def recall(retriever):
            # The shared executor propagates a distinct copied context per task,
            # including the selected models, deadline and cancellation event.
            # Disable remote tracing in each worker too, before callbacks start.
            """关闭局部框架追踪后执行指定检索器，向并行召回阶段返回候选文档。

            上游为 `invoke`。
            """
            with tracing_context(enabled=False):
                return retriever.invoke(input["query"], config=config)

        return steps.fan_out_legs(
            {leg: lambda retriever=retriever: recall(retriever)
             for leg, retriever in retrievers.items()},
            steps.RETRIEVAL_LEG_TIMEOUT_MS, deadline_at=current_deadline_at())


def _fuse_documents(fanout: LegFanout, recall_n: int, rrf_k: int):
    # Worker-local ContextVars cannot update the caller. Status/durations explicitly
    # cross the join, then common fusion records them on the collector's thread.
    """复用固定流程的融合策略合并检索分路结果，返回 Document 列表和召回状态。

    上游为 `_retrieve`，下游调用 `ai.app.retrieval.retriever.LegFanout`、`_candidate`。
    """
    candidates = LegFanout(
        {leg: [_candidate(document) for document in documents]
         for leg, documents in fanout.results.items()},
        fanout.timed_out, fanout.durations_ms, fanout.failures, fanout.rejected)
    documents = [_document(candidate) for candidate in steps._fuse_fanout(candidates, recall_n, rrf_k)]
    # RunnableLambda also copies ContextVars. Return status rather than relying
    # on a mutation inside the fusion node to leak into its caller.
    return documents, steps._RETRIEVAL_STATUS.get()


def _retrieve(scope, query, top_k, recall_n, version, rrf_k, document_id=None):
    """按请求策略选择向量或并行召回，并按需重排，回转为共享候选结构供后续准入使用。

    下游调用 `ai.app.rag.pipeline._at_stage`、`ai.app.rag.pipeline.stage`。
    """
    if version == "baseline":
        with steps._at_stage("vector_retrieval"), steps.stage("vector_retrieval"):
            retriever = ScopedPgRetriever(knowledge_base_ids=tuple(scope), top_k=top_k,
                                          leg="vector", document_id=document_id)
            documents = retriever.invoke(query)
        steps._RETRIEVAL_STATUS.set(("complete", "none"))
    else:
        recall = BoundedParallelRecall() | RunnableLambda(
            lambda fanout: _fuse_documents(fanout, recall_n, rrf_k), name="rrf_fusion")
        documents, status = recall.invoke({
            "scope": scope, "query": query, "recall_n": recall_n, "document_id": document_id})
        steps._RETRIEVAL_STATUS.set(status)
    if version in {"hybrid-rerank", "hybrid-rerank-rewrite"}:
        rank = RunnableLambda(
            lambda docs: [_document(candidate) for candidate in steps._rerank_top(
                [_candidate(document) for document in docs], query, top_k)], name="rerank")
        documents = rank.invoke(documents)
    else:
        documents = documents[:top_k]
    return [_candidate(document) for document in documents]


class GuardedChatModel(BaseChatModel):
    """LangChain model interface backed by the existing ModelGate, with no second retry/cache layer."""
    cache: bool = False

    @property
    def _llm_type(self) -> str:
        """返回 LangChain 识别本项目门控模型适配器的类型标识。"""
        return "ekip-model-gate"

    def _generate(self, messages: list[BaseMessage], stop=None, run_manager=None, **kwargs) -> ChatResult:
        """验证 LCEL 的系统和用户消息后调用项目模型门控，将输出包装为 LangChain ChatResult。

        下游调用 `ai.app.llm.guard.gate.call`、`ai.app.rag.rag_service.generation_response_format`。
        """
        if (len(messages) != 2 or not isinstance(messages[0], SystemMessage)
                or not isinstance(messages[1], HumanMessage) or stop):
            raise ValueError("EKIP generation requires the existing system and user prompt")
        result = gate.call("generate", messages[0].content, messages[1].content,
                           response_format=generation_response_format())
        generated = decode_generation(result)
        usage = None
        input_tokens = getattr(result, "prompt_tokens", None)
        output_tokens = getattr(result, "completion_tokens", None)
        if input_tokens is not None and output_tokens is not None:
            usage = {"input_tokens": input_tokens, "output_tokens": output_tokens,
                     "total_tokens": result.total_tokens}
        message = AIMessage(content=generated.answer,
                            response_metadata=asdict(generated), usage_metadata=usage)
        return ChatResult(generations=[ChatGeneration(message=message)])


@lru_cache(maxsize=1)
def _answer_chain():
    # Payload strings are values, never interpreted as templates (documents can contain JSON/braces).
    """组合项目的系统及用户提示模板与门控模型，返回可调用的答案生成链。

    上游为 `_generate`、`prepare`。
    """
    prompt = ChatPromptTemplate.from_messages([("system", "{system_prompt}"), ("human", "{user_prompt}")])
    return prompt | GuardedChatModel()


def _generate(query, contexts, history):
    """将问题、证据和历史交给 LCEL 答案链，返回固定流程能识别的 GenerationResult。

    下游调用 `invoke`、`_answer_chain`。
    """
    message = _answer_chain().invoke({"system_prompt": SYSTEM_PROMPT,
                                      "user_prompt": build_prompt(query, contexts, history)})
    return GenerationResult(**message.response_metadata)


@dataclass(frozen=True)
class DocumentState:
    """LCEL's inter-stage payload retains native Documents and an immutable query-state value."""
    query: QueryState
    documents: tuple[Document, ...] = ()


def _retrieve_state(state: DocumentState) -> DocumentState:
    """按查询状态执行统一召回，将候选和对应的 Document 列表整理至 LCEL 阶段状态。

    下游调用 `ai.app.rag.pipeline.retrieve_query`、`_document`。
    """
    result = steps.retrieve_query(state.query, retrieve=_retrieve)
    return DocumentState(result, tuple(_document(candidate) for candidate in result.candidates))


def _admit_state(state: DocumentState) -> DocumentState:
    """对当前召回结果执行共享准入，把候选文档更新为获准证据。

    下游调用 `_candidate`、`ai.app.rag.pipeline.admit_query`。
    """
    query = replace(state.query, candidates=[_candidate(document) for document in state.documents])
    result = steps.admit_query(query)
    return DocumentState(result, tuple(_document(candidate) for candidate in result.admitted))


def _postprocess_state(state: DocumentState, *, provisional: bool) -> DocumentState:
    """对获准证据运行共享后处理，并把结果转换为下一阶段可用的 Document。

    下游调用 `_candidate`、`ai.app.rag.pipeline.postprocess_query`。
    """
    query = replace(state.query, admitted=[_candidate(document) for document in state.documents])
    result = steps.postprocess_query(query, provisional=provisional)
    return DocumentState(result, tuple(_document(candidate) for candidate in result.admitted))


def _finalize_admission_state(state: DocumentState) -> DocumentState:
    """将只请求证据的场景交付为已完成查询状态，供链路跳过生成。

    下游调用 `ai.app.rag.pipeline.finalize_admission_query`。
    """
    return replace(state, query=steps.finalize_admission_query(state.query))


def _generate_state(state: DocumentState) -> DocumentState:
    """将允许生成的查询交给共享生成逻辑与 LCEL 模型适配器，返回包含终态的状态。

    下游调用 `_candidate`、`ai.app.rag.pipeline.generate_query`。
    """
    query = replace(state.query, admitted=[_candidate(document) for document in state.documents])
    return replace(state, query=steps.generate_query(query, generate=_generate))


def _active(node, name):
    """将一个 LCEL 节点包装成仅在当前查询尚无结果时执行的分支。

    上游为 `_pre_generation_chain`。
    """
    return RunnableBranch((lambda state: state.query.response is None, RunnableLambda(node, name=name)),
                          RunnablePassthrough())


@lru_cache(maxsize=2)
def _pre_generation_chain(*, provisional: bool = False):
    """装配同步与 SSE 共用的 LCEL 准备、改写、召回、证据准入和上下文整理。

    上游为 `build_chain`、`prepare_generation`，返回准备完毕或提前终止的 DocumentState。
    """
    return (
        RunnableLambda(lambda request: DocumentState(steps.prepare_query(request)), name="prepare")
        | _active(lambda state: replace(state, query=steps.rewrite_query(state.query)), "rewrite")
        | _active(_retrieve_state, "retrieval")
        | _active(_admit_state, "evidence_gate")
        | _active(partial(_postprocess_state, provisional=provisional), "context")
    )


@lru_cache(maxsize=1)
def build_chain():
    """在共享 LCEL 流程后接入同步生成或纯证据交付，并返回最终查询响应。"""
    return (
        _pre_generation_chain()
        | RunnableBranch(
            (lambda state: state.query.response is not None, RunnablePassthrough()),
            (lambda state: not state.query.request.generate,
             RunnableLambda(_finalize_admission_state, name="evidence_only")),
            RunnableLambda(_generate_state, name="generate"))
        | RunnableLambda(lambda state: steps.finish_query(state.query), name="response")
    )


def prepare_generation(request: RagQueryRequest) -> QueryState:
    """在当前请求作用域执行共享 LCEL 流程，向 SSE 入口返回获准证据与生成输入。

    上游为 `ai.app.rag.pipeline._stream_events`。关闭 LangSmith 外部追踪，保留本地阶段收集。
    """
    with tracing_context(enabled=False):
        return _pre_generation_chain(provisional=True).invoke(request).query


def execute(request: RagQueryRequest) -> RagQueryResponse:
    # Never send enterprise questions, documents or tokens to environment-enabled LangSmith tracing.
    """在关闭框架外部追踪的上下文中执行 LCEL 链，返回与固定流程一致的 RAG 响应。

    上游为 `ai.app.rag.pipeline.run_query`，下游调用 `invoke`、`build_chain`。
    """
    with tracing_context(enabled=False):
        return build_chain().invoke(request)


def prepare() -> None:
    """在应用启动阶段提前构造 LCEL 链，供首个实际请求复用。

    上游为 `ai.app.rag.pipeline.prepare_pipeline_engines`，下游调用 `build_chain`、`_answer_chain`。
    """
    with tracing_context(enabled=False):
        build_chain()
        _pre_generation_chain(provisional=True)
        _answer_chain()
