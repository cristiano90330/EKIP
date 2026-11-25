"""接收 Java 的批量嵌入请求并在当次模型执行上下文中生成向量。"""

from ai.app.models.context import managed_endpoint
"""为 Java 文档入库提供批量文本嵌入接口，并映射嵌入服务故障状态。"""
from fastapi import APIRouter, HTTPException

from ai.app.embedding.schema import EmbeddingRequest, EmbeddingResponse
from ai.app.embedding.embedding_service import EmbeddingService
from ai.app.embedding.errors import EmbeddingDimensionError, EmbeddingUnavailableError

router = APIRouter(prefix="/embedding", tags=["embedding"])

embedding_service = EmbeddingService()


# 接收 Java 的批量嵌入请求，将 texts 与 inputType 交给 EmbeddingService 并返回向量。
# 下游调用 `ai.app.embedding.schema.EmbeddingResponse`、`ai.app.models.context.managed_endpoint`。
@router.post("", response_model=EmbeddingResponse)
@managed_endpoint(budget_ms=120000)
def create_embedding(request: EmbeddingRequest):     # 同步调用由 FastAPI 在线程池执行。
    """批量生成文本向量；服务故障与维度配置错误映射为不同状态码。"""
    # Java 按状态码区分可重试的服务故障（503）与永久配置错误（422）。
    try:
        vectors = embedding_service.embed_batch(request.texts)
    except EmbeddingUnavailableError as e:
        raise HTTPException(status_code=503, detail=f"嵌入服务不可用: {e}") from e
    except EmbeddingDimensionError as e:
        raise HTTPException(status_code=422, detail=f"嵌入维度配置错误: {e}") from e

    return EmbeddingResponse(embeddings=vectors)
