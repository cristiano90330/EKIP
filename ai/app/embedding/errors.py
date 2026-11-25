"""定义向量生成调用可识别的错误类型，供检索链路判断召回失败。"""


class EmbeddingUnavailableError(RuntimeError):
    """嵌入服务不可用或响应无效；路由返回 503，允许任务重试。"""


class EmbeddingDimensionError(RuntimeError):
    """嵌入维度与 `VECTOR_DIM` 不匹配；路由返回 422，作为永久配置错误处理。"""
