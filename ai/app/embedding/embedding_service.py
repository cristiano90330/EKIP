"""根据执行绑定选择向量模型，为索引写入和向量召回生成嵌入。"""

from collections import OrderedDict
from threading import RLock
from ai.app.models.context import binding
from ai.app.models.runtime import runtime, identity, ModelCallError
from ai.app.embedding.errors import EmbeddingDimensionError, EmbeddingUnavailableError
from ai.app.models.cancellation import check_cancelled
from ai.app.rag.deadline import QueryDeadlineExceeded, deadline_expired

class EmbeddingService:
    def __init__(self):
        """初始化嵌入服务实例，使路由和检索能够共享同一托管运行时。"""
        self._cache = OrderedDict()
        self._lock = RLock()

    def embed_batch(self, texts, *, model=None, input_type="document"):
        """对一批正文或查询按当前用途的模型执行嵌入，返回用于索引写入或召回的向量列表。

        下游调用 `ai.app.models.cancellation.check_cancelled`、`ai.app.rag.deadline.deadline_expired`。
        """
        check_cancelled()
        if deadline_expired():
            raise QueryDeadlineExceeded("embedding exceeded total budget")
        if not texts:
            return []
        selected = model or binding("embedding")
        prefix = identity(selected) + ":" + input_type
        with self._lock:
            found = {text: self._cache[(prefix, text)] for text in texts if (prefix, text) in self._cache}
            missing = list(dict.fromkeys(text for text in texts if (prefix, text) not in self._cache))
        if missing:
            try:
                vectors = runtime.embed(selected, missing, input_type=input_type)
            except ModelCallError as exc:
                if exc.code == "embedding_dimension_mismatch":
                    raise EmbeddingDimensionError(exc.code) from None
                raise EmbeddingUnavailableError(exc.code) from None
            found.update(zip(missing, vectors))
            with self._lock:
                for text, vector in zip(missing, vectors):
                    self._cache[(prefix, text)] = vector
                    self._cache.move_to_end((prefix, text))
                while len(self._cache) > 4096:
                    self._cache.popitem(last=False)
        # Cache eviction during a large batch or another request must not lose
        # this call's results; callers also cannot mutate retained vectors.
        return [list(found[text]) for text in texts]
