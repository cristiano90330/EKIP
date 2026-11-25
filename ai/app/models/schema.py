"""校验 Java 下发的模型绑定、运行配置和索引范围快照。"""

from typing import Literal
from urllib.parse import urlsplit

from pydantic import BaseModel, ConfigDict, Field, SecretStr, model_validator

MODEL_TYPES = {"chat", "embedding", "rerank"}
PURPOSE_TYPES = {"generate": "chat", "rewrite": "chat", "agent": "chat",
                 "summarize": "chat", "judge": "chat", "evidence_support": "chat",
                 "embedding": "embedding", "rerank": "rerank"}
PROTOCOL_TYPES = {"openai_chat": "chat", "openai_embedding": "embedding",
                  "cohere_rerank": "rerank", "dashscope_rerank": "rerank"}


class RuntimeConfig(BaseModel):
    model_config = ConfigDict(extra="forbid", hide_input_in_errors=True)
    timeoutMs: int = Field(default=20000, ge=100, le=600000)
    retries: int = Field(default=0, ge=0, le=3)
    backoffMs: int = Field(default=500, ge=0, le=10000)
    maxConcurrency: int = Field(default=1, ge=1, le=64)
    resourceGroup: str = Field(default="", max_length=128)


class Calibration(BaseModel):
    model_config = ConfigDict(extra="forbid", hide_input_in_errors=True)
    semanticFingerprint: str = Field(min_length=1, max_length=128)
    threshold: float = Field(ge=0, le=1, allow_inf_nan=False)
    reference: str = Field(min_length=1, max_length=1000)


class ModelConfig(BaseModel):
    model_config = ConfigDict(extra="forbid", hide_input_in_errors=True)
    id: str = Field(default="preview", min_length=1, max_length=128)
    revision: int = Field(default=1, ge=1)
    credentialVersion: int = Field(default=0, ge=0)
    credentialConfigured: bool = False
    semanticFingerprint: str = Field(default="", max_length=128)
    type: Literal["chat", "embedding", "rerank"]
    name: str = Field(default="", max_length=200)
    source: Literal["local", "remote"] = "remote"
    provider: Literal["generic", "openai", "aliyun", "cohere", "ollama"] = "generic"
    protocol: Literal["openai_chat", "openai_embedding",
                      "cohere_rerank", "dashscope_rerank"]
    model: str = Field(min_length=1, max_length=256)
    baseUrl: str = Field(default="", max_length=2048)
    apiKey: SecretStr | None = Field(default=None, repr=False, exclude=True)
    dimension: int | None = Field(default=None, ge=1, le=65536)
    contextWindow: int | None = Field(default=None, ge=128, le=10000000)
    maxOutputTokens: int | None = Field(default=None, ge=1, le=1000000)
    capabilities: dict[str, bool] = Field(default_factory=dict)
    compat: dict = Field(default_factory=dict)
    runtime: RuntimeConfig = Field(default_factory=RuntimeConfig)
    calibration: "Calibration | None" = None

    @model_validator(mode="after")
    def validate_shape(self):
        """检查模型能力、窗口及打分标度等组合是否合法，成功时返回当前模型对象。"""
        if PROTOCOL_TYPES[self.protocol] != self.type:
            raise ValueError("model type does not match protocol")
        if not self.model.strip():
            raise ValueError("model name must not be blank")
        if set(self.capabilities) - {"tools", "streaming", "json", "thinking"}:
            raise ValueError("unknown model capability")
        if self.type != "chat" and any(self.capabilities.values()):
            raise ValueError("chat capabilities cannot be enabled for this model type")
        if self.baseUrl:
            parts = urlsplit(self.baseUrl)
            if (parts.scheme not in {"http", "https"} or not parts.hostname or parts.username
                    or parts.password or parts.query or parts.fragment):
                raise ValueError("baseUrl must be an HTTP(S) URL without credentials, query or fragment")
        if self.type == "embedding" and self.dimension is None:
            raise ValueError("embedding dimension must be explicitly configured")
        if (self.contextWindow is not None and self.maxOutputTokens is not None
                and self.maxOutputTokens >= self.contextWindow):
            raise ValueError("maxOutputTokens must be smaller than contextWindow")
        if self.source == "local" and not self.runtime.resourceGroup:
            self.runtime.resourceGroup = "local-gpu"
        if self.type == "embedding" and "timeoutMs" not in self.runtime.model_fields_set:
            self.runtime.timeoutMs = 120000
        return self


class IndexScope(BaseModel):
    model_config = ConfigDict(extra="forbid", hide_input_in_errors=True)
    knowledgeBaseId: int = Field(gt=0)
    generationId: str = Field(min_length=1, max_length=128)
    spaceId: str = Field(min_length=1, max_length=128)
    dimension: Literal[1024, 2560]
    embeddingModel: ModelConfig

    @model_validator(mode="after")
    def validate_space(self):
        """校验索引范围绑定的向量模型和维度，供召回阶段过滤不兼容的快照。"""
        if self.embeddingModel.type != "embedding" or self.embeddingModel.dimension != self.dimension:
            raise ValueError("index scope embedding model or dimension is invalid")
        return self


class ModelExecution(BaseModel):
    model_config = ConfigDict(extra="forbid", hide_input_in_errors=True)
    schemaVersion: Literal[1] = 1
    budgetMs: int = Field(default=30000, ge=100, le=600000)
    bindings: dict[str, ModelConfig] = Field(default_factory=dict)
    indexScopes: list[IndexScope] = Field(default_factory=list)

    @model_validator(mode="after")
    def validate_bindings(self):
        """检查请求的模型用途绑定和索引范围，返回可进入执行上下文的快照。"""
        for purpose, model in self.bindings.items():
            if purpose not in PURPOSE_TYPES:
                raise ValueError("unknown model purpose")
            if model.type != PURPOSE_TYPES[purpose]:
                raise ValueError("purpose binding has an incompatible model type")
        ids = [scope.knowledgeBaseId for scope in self.indexScopes]
        if len(ids) != len(set(ids)):
            raise ValueError("a knowledge base has multiple active index scopes")
        return self


class ModelRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", hide_input_in_errors=True)
    model: ModelConfig
    checks: list[Literal["text", "embedding", "rerank", "tools", "stream", "json"]] | None = None
