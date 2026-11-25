"""声明支持的供应商、协议及兼容选项，供模型配置解析使用。"""

_CHAT_CANDIDATES = (
    "qwen3.8-27b", "qwen3.8-flash", "kimi-k3", "qwen3.8-max-0902",
    "deepseek-v4.1-flash-1017", "glm-5.3", "qwen3.8-2.4t-a95b", "qwen3.8-max",
)

MODEL_CATALOG = [
    {"model": name, "name": name, "type": "chat", "source": "remote",
     "provider": "aliyun", "protocol": "openai_chat", "verified": False,
     "compatDefaults": {}}
    for name in _CHAT_CANDIDATES
] + [
    {"model": "qwen3.7-text-embedding-flash", "name": "qwen3.7-text-embedding-flash",
     "type": "embedding", "source": "remote", "provider": "aliyun",
     "protocol": "openai_embedding", "verified": False, "compatDefaults": {}},
    {"model": "qwen3.7-text-rerank", "name": "qwen3.7-text-rerank", "type": "rerank",
     "source": "remote", "provider": "aliyun", "protocol": "dashscope_rerank",
     "verified": False, "compatDefaults": {}},
]
