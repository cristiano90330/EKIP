"""定义向量 HTTP 请求和批量响应字段。"""

from ai.app.models.schema import ModelExecution
from pydantic import BaseModel

class EmbeddingRequest(BaseModel):
    modelExecution: ModelExecution | None = None


    texts: list[str]

class EmbeddingResponse(BaseModel):

    embeddings: list[list[float]]
