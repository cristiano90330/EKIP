"""解析供应商和协议配置，计算实际端点与兼容参数并拒绝不合法组合。"""

from urllib.parse import urlsplit, urlunsplit

from ai.app.models.schema import ModelConfig, PROTOCOL_TYPES
from ai.app.models.catalog import MODEL_CATALOG

_CHAT_KEYS = {"enableThinking", "thinkingDisableOnNonStream", "maxTokensField",
              "omitTemperature", "supportsStreamUsage"}
_EMBED_KEYS = {"embeddingBatchSize", "inputType", "queryInstruction", "documentInstruction",
               "supportsDimensionOverride"}
_RERANK_KEYS = {"scoreScale", "maxBatchSize"}
COMPAT_KEYS = {
    "openai_chat": _CHAT_KEYS,
    "openai_embedding": _EMBED_KEYS,
    "cohere_rerank": _RERANK_KEYS,
    "dashscope_rerank": _RERANK_KEYS,
}
PROVIDERS = [
    {"id": "generic", "name": "Custom API", "protocols": list(PROTOCOL_TYPES), "defaultBaseUrl": ""},
    {"id": "openai", "name": "OpenAI compatible", "protocols": ["openai_chat", "openai_embedding"],
     "defaultBaseUrl": "https://api.openai.com/v1"},
    {"id": "aliyun", "name": "Alibaba Cloud Model Studio",
     "protocols": ["openai_chat", "openai_embedding", "dashscope_rerank"],
     "defaultBaseUrl": "https://dashscope.aliyuncs.com/compatible-mode/v1"},
    {"id": "cohere", "name": "Cohere", "protocols": ["cohere_rerank"],
     "defaultBaseUrl": "https://api.cohere.com/v2"},
    {"id": "ollama", "name": "Ollama (OpenAI compatible)", "protocols": ["openai_chat", "openai_embedding"],
     "defaultBaseUrl": "http://localhost:11434/v1"},
]


class ModelConfigurationError(ValueError):
    """Safe configuration errors never contain the submitted model or credentials."""


def provider_metadata():
    """将供应商目录转换为模型管理页面和 Java 可消费的能力描述。

    上游为 `ai.app.models.router.providers`，下游调用 `ai.app.models.schema.PROTOCOL_TYPES.items`。
    """
    return {"providers": PROVIDERS,
            "protocols": [{"id": key, "type": kind, "compatKeys": sorted(COMPAT_KEYS[key])}
                          for key, kind in PROTOCOL_TYPES.items()],
            "models": MODEL_CATALOG}


def effective_compat(model: ModelConfig) -> dict:
    """将供应商默认能力与模型级兼容配置合并，返回构造模型请求使用的选项。

    上游为 `ai.app.models.runtime.ModelRuntime.embed`、`ai.app.models.runtime.ModelRuntime.rerank`。
    """
    unsupported = set(model.compat) - COMPAT_KEYS[model.protocol]
    if unsupported:
        raise ModelConfigurationError("unknown compatibility option for selected protocol")
    compat = {}
    if model.protocol == "openai_chat":
        compat.update(maxTokensField="max_tokens", omitTemperature=False, supportsStreamUsage=True)
    if model.type == "embedding":
        compat.update(embeddingBatchSize=16, supportsDimensionOverride=False)
    if model.type == "rerank":
        compat.update(scoreScale="probability", maxBatchSize=100)
    if model.provider == "aliyun" and model.protocol == "openai_chat" and model.capabilities.get("thinking"):
        compat["enableThinking"] = False
    for candidate in MODEL_CATALOG:
        if (candidate["provider"], candidate["protocol"], candidate["model"]) == (
                model.provider, model.protocol, model.model):
            compat.update(candidate.get("compatDefaults", {}))
            break
    compat.update(model.compat)
    for key in {"enableThinking", "thinkingDisableOnNonStream", "omitTemperature",
                "supportsStreamUsage", "supportsDimensionOverride"} & compat.keys():
        if not isinstance(compat[key], bool):
            raise ModelConfigurationError("compatibility boolean has invalid type")
    for key in {"embeddingBatchSize", "maxBatchSize"} & compat.keys():
        value = compat[key]
        if isinstance(value, bool) or not isinstance(value, int) or not 1 <= value <= 1000000:
            raise ModelConfigurationError("compatibility integer must be positive")
    for key in {"inputType", "queryInstruction", "documentInstruction"} & compat.keys():
        if not isinstance(compat[key], str) or len(compat[key]) > 4000:
            raise ModelConfigurationError("invalid embedding compatibility text")
    if compat.get("scoreScale", "probability") not in {"probability", "logit"}:
        raise ModelConfigurationError("scoreScale must be probability or logit")
    if compat.get("maxTokensField", "max_tokens") not in {"max_tokens", "max_completion_tokens"}:
        raise ModelConfigurationError("unsupported maxTokensField")
    if any(key in compat for key in {"enableThinking", "thinkingDisableOnNonStream"}) and not model.capabilities.get("thinking"):
        raise ModelConfigurationError("thinking compatibility requires the thinking capability")
    if model.protocol == "openai_embedding" and "inputType" in compat and model.provider != "aliyun":
        raise ModelConfigurationError("inputType requires the aliyun embedding provider")
    return compat


def endpoint(model: ModelConfig) -> str:
    """根据模型协议和服务地址解析模型的实际 HTTP 端点。

    上游为 `ai.app.models.runtime.ModelRuntime._json`、`ai.app.models.runtime.ModelRuntime._openai_events`。
    """
    provider = next(item for item in PROVIDERS if item["id"] == model.provider)
    if model.protocol not in provider["protocols"]:
        raise ModelConfigurationError("provider does not support selected protocol")
    base = (model.baseUrl or provider["defaultBaseUrl"]).rstrip("/")
    if not base:
        raise ModelConfigurationError("baseUrl is required for custom models")
    parts = urlsplit(base)
    path = parts.path.rstrip("/")
    suffix = {"openai_chat": "/chat/completions", "openai_embedding": "/embeddings",
              "cohere_rerank": "/rerank", "dashscope_rerank": "/api/v1/services/rerank/text-rerank/text-rerank"}[model.protocol]
    if model.protocol == "dashscope_rerank":
        if path in {"", "/compatible-mode/v1"}:
            path = suffix
        elif not path.endswith(suffix):
            raise ModelConfigurationError("DashScope rerank requires its native endpoint or server root")
    elif not path.endswith(suffix):
        path += suffix
    return urlunsplit((parts.scheme, parts.netloc, path, "", ""))


def resolve(model: ModelConfig) -> dict:
    """解析模型的供应商、协议和兼容选项，返回预览或运行前校验所需的信息。

    上游为 `ai.app.models.router.preview`，下游调用 `effective_compat`、`endpoint`。
    """
    compat = effective_compat(model)
    resolved_endpoint = endpoint(model)
    warnings = []
    if model.type == "embedding" and model.dimension not in {1024, 2560}:
        warnings.append("Knowledge indices support 1024 and 2560 dimensions; other dimensions may only be connection-tested.")
    if model.type == "rerank" and (model.calibration is None or model.calibration.semanticFingerprint != model.semanticFingerprint):
        warnings.append("This model revision has no calibrated evidence threshold; score admission will fail closed.")
    return {"protocol": model.protocol, "endpoint": resolved_endpoint, "model": model.model,
            "effectiveCompat": compat, "warnings": warnings}
