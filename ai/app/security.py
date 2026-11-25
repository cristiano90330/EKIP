"""校验 Java 内部调用令牌并为受保护的 AI 路由提供统一鉴权依赖。"""

import hmac
import logging
from typing import Optional

from fastapi import Header, HTTPException

from ai.app.config import INTERNAL_API_TOKEN

logger = logging.getLogger("ekip.security")


def is_internal_token_valid(token: Optional[str]) -> bool:
    """比较调用方提供的令牌与已经配置的内部令牌，返回鉴权判定。

    上游为 `ai.app.rag.admission.RagQueryAdmissionMiddleware.__call__`、`ai.app.rag.router.reject_duplicate_json_fields`。
    """
    return bool(INTERNAL_API_TOKEN and token and hmac.compare_digest(token, INTERNAL_API_TOKEN))


# 验证当前请求携带的内部令牌，拒绝不满足 Java 到 AI 调用契约的请求。
# 下游调用 `is_internal_token_valid`。
async def require_internal_token(
    x_internal_token: Optional[str] = Header(default=None),
) -> None:
    """从请求头读取内部令牌，未配置或校验失败时返回 401，不回显期望值。

    应用生命周期会在启动时校验配置，此处仍拒绝空配置，确保直接调用依赖时也不会绕过鉴权。
    """
    if not INTERNAL_API_TOKEN:
        logger.error("INTERNAL_API_TOKEN 为空：拒绝全部业务请求")
        raise HTTPException(status_code=401, detail="内部鉴权未配置")

    if not is_internal_token_valid(x_internal_token):
        raise HTTPException(status_code=401, detail="内部鉴权失败")
