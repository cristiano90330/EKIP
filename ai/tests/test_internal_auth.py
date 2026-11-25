"""验证 AI 内部令牌配置、受保护与公开路由的鉴权边界。"""

from fastapi.testclient import TestClient

from ai.app.main import app
from ai.tests.conftest import AUTH_HEADERS

client = TestClient(app)

# 空 kbIds + baseline 档 → 管线在检索处短路（不碰 DB、不调 LLM），
# 因此这个 payload 在**带令牌**时应当一次干净的 200，适合做鉴权的正反对照。
_CHEAP_PAYLOAD = {"knowledgeBaseIds": [], "question": "x", "version": "baseline"}


def test_health_needs_no_token():
    """健康检查必须保持开放。

    compose 里 `ai` 的 healthcheck 是 `urllib.request.urlopen('http://localhost:8000/health')`
    —— 不带头。若把 `/health` 也纳入鉴权，健康检查恒失败 → `backend` 判定 ai 未就绪
    → 连锁起不来。这是**有意的例外**，不是漏配。
    """
    r = client.get("/health")
    assert r.status_code == 200


def test_ready_requires_internal_token():
    """验证 `r.status_code` 等于 `401`。"""
    r = client.get("/ready")
    assert r.status_code == 401


def test_ready_returns_capability_snapshot():
    """验证 `r.status_code` 等于 `200`，且 `payload['status']` 属于 `{'unknown', 'not_ready', 'degraded', 'ready'}`。"""
    r = client.get("/ready", headers=AUTH_HEADERS)
    assert r.status_code == 200
    payload = r.json()
    assert payload["status"] in {"unknown", "not_ready", "degraded", "ready"}
    assert set(payload["capabilities"]) == {"ingest", "search", "answer", "evidenceGate", "rerank"}
    for capability in payload["capabilities"].values():
        assert capability["status"] in {"unknown", "not_ready", "degraded", "ready"}
        assert "reasonCode" in capability


def test_missing_token_is_rejected():
    """不带令牌 → 401。修复前这里是 200（洞本身）。"""
    r = client.post("/rag/query", json=_CHEAP_PAYLOAD)
    assert r.status_code == 401


def test_wrong_token_is_rejected():
    """令牌不对 → 401（不是「有头就放行」）。"""
    r = client.post("/rag/query", json=_CHEAP_PAYLOAD, headers={"X-Internal-Token": "wrong-token"})
    assert r.status_code == 401


def test_correct_token_is_accepted():
    """令牌正确 → 放行（这里应是 200：空 kbIds 走空证据短路，不碰 DB）。"""
    r = client.post("/rag/query", json=_CHEAP_PAYLOAD, headers=AUTH_HEADERS)
    assert r.status_code == 200


def test_other_business_routes_are_also_guarded():
    """守卫挂在 router 上（不是只挂 /rag/query 一个路径），抽查另一个路由组。"""
    r = client.post("/embedding", json={"texts": ["x"]})
    assert r.status_code == 401
