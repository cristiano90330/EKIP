"""绑定单次请求的模型执行配置、索引范围与预算，并向同步和流式处理传递上下文。"""

from contextlib import contextmanager
from contextvars import ContextVar

from ai.app.models.resolution import ModelConfigurationError
from ai.app.models.schema import ModelExecution, PURPOSE_TYPES
from ai.app.rag.deadline import query_deadline

_execution: ContextVar[ModelExecution | None] = ContextVar("model_execution", default=None)


def current_execution():
    """读取当前请求的模型执行配置，供内层处理复用已绑定的快照。

    上游为 `ai.app.observability.readiness.ReadinessService._evidence_gate`、`binding`。
    """
    return _execution.get()


def binding(purpose: str):
    """按用途从当前快照取模型配置，向模型门控层报告缺少绑定的错误。

    上游为 `ai.app.agent.router.decide_stream`，下游调用 `current_execution`、`ai.app.models.schema.PURPOSE_TYPES.get`。
    """
    execution = current_execution()
    if execution is None or purpose not in execution.bindings:
        raise ModelConfigurationError("model_not_configured: missing model execution binding")
    model = execution.bindings[purpose]
    if model.type != PURPOSE_TYPES.get(purpose):
        raise ModelConfigurationError("model_not_configured: incompatible model purpose")
    return model


def evidence_threshold(scorer: str):
    """为指定评分器查询当前执行配置中的已校准阈值，供证据准入判断。

    上游为 `ai.app.observability.readiness.ReadinessService._evidence_gate`，下游调用 `binding`。
    """
    purpose = "rerank" if scorer == "model" else "embedding" if scorer == "embed_sim" else None
    if purpose is None:
        return None
    try:
        model = binding(purpose)
    except ModelConfigurationError:
        return None
    calibration = model.calibration
    if not model.semanticFingerprint or calibration is None or calibration.semanticFingerprint != model.semanticFingerprint:
        return None
    return calibration.threshold


@contextmanager
def model_execution(execution: ModelExecution | None, *, default_budget_ms: int = 30000):
    """绑定一次请求的模型执行快照，并让下游运行在请求与配置共同约束的时限内。

    上游为 `ai.app.agent.router.decide_stream`，下游调用 `ai.app.rag.deadline.query_deadline`。
    """
    if execution is None:
        raise ModelConfigurationError("model_not_configured: modelExecution is required")
    token = _execution.set(execution)
    try:
        with query_deadline(min(default_budget_ms, execution.budgetMs)):
            yield execution
    finally:
        _execution.reset(token)


def index_scopes(kb_ids):
    """按调用方授权的知识库筛选索引代次，缺少可用范围时向检索链路报告需重建索引。

    上游为 `ai.app.retrieval.retriever.vector_retrieve`，下游调用 `current_execution`。
    """
    execution = current_execution()
    if execution is None:
        raise ModelConfigurationError("model_not_configured: index scopes are required")

    # 在执行快照上再按当前操作收窄范围，避免文档窗口或内部工具使用整个请求可见的全部索引。
    allowed = set(kb_ids)
    scopes = [scope for scope in execution.indexScopes if scope.knowledgeBaseId in allowed]
    if {scope.knowledgeBaseId for scope in scopes} != allowed:
        raise ModelConfigurationError("requires_rebuild: a knowledge base has no verified index")
    return scopes


def managed_endpoint(*, budget_ms=30000):
    """构造内部路由包装器，使用请求携带的快照或当前快照执行同步处理。

    上游为 `ai.app.agent.router.decide`、`ai.app.agent.router.summarize`。
    """
    from functools import wraps

    def decorate(fn):
        """用受管理的模型上下文包装声明的路由函数，并返回保留原函数元数据的调用器。"""
        @wraps(fn)
        def call(request, *args, **kwargs):
            """使用路由请求携带的执行快照或当前快照运行原路由，并原样返回路由结果。

            下游调用 `current_execution`、`model_execution`。
            """
            execution = getattr(request, "modelExecution", None) or current_execution()
            with model_execution(execution, default_budget_ms=budget_ms):
                return fn(request, *args, **kwargs)

        return call

    return decorate


def iterate_in_context(iterator):
    """在同一上下文推进流式迭代器并执行关闭，使跨工作线程的事件保留请求绑定。

    上游为 `ai.app.agent.router.decide_stream`。
    """
    from contextvars import copy_context
    context = copy_context()
    try:
        while True:
            try:
                value = context.run(next, iterator)
            except StopIteration:
                return
            yield value
    finally:
        close = getattr(iterator, "close", None)
        if close:
            context.run(close)
