"""为 AI 用例加载共享环境与测试入口配置。"""

import os

TEST_INTERNAL_TOKEN = "test-internal-token"

os.environ["INTERNAL_API_TOKEN"] = TEST_INTERNAL_TOKEN

# Dedicated dummy credentials: tests must never inherit backend postgres credentials.
os.environ["PG_READONLY_HOST"] = "127.0.0.1"
os.environ["PG_READONLY_PORT"] = "5432"
os.environ["PG_READONLY_DB"] = "ekip_test"
os.environ["PG_READONLY_USER"] = "ekip_test_readonly"
os.environ["PG_READONLY_PASSWORD"] = "test-only-not-a-secret"

# 供 `ai/tests/test_*.py` 的模块级 `TestClient` 复用：
#     client = TestClient(app, headers=AUTH_HEADERS)
# 集中在 conftest 定义，避免同一个字面量在 5 个测试文件里各写一遍、改一处漏四处。
AUTH_HEADERS = {"X-Internal-Token": TEST_INTERNAL_TOKEN}
