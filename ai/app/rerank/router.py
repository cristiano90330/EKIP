"""接收托管重排请求，按当次模型绑定返回候选相关性分数。"""

from ai.app.models.context import binding
from ai.app.models.runtime import runtime
from ai.app.rerank.schema import RerankRequest, RerankResponse


def rerank(request: RerankRequest):
    """接收请求的查询和候选文本，经托管重排模型返回按候选定位的相关性评分。

    上游为 `ai.app.rag.pipeline._rerank_top`，下游调用 `ai.app.rerank.schema.RerankResponse`、`ai.app.models.context.binding`。
    """
    if not request.candidates:
        return RerankResponse(scores=[], ids=[], scorer="none")
    model = binding("rerank")
    scores = runtime.rerank(model, request.query, [item.content for item in request.candidates])
    return RerankResponse(scores=scores, ids=[item.id for item in request.candidates], scorer="model")
