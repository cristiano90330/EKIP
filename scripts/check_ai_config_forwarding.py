"""检查 Compose 配置是否向 AI 容器转交所需环境变量。"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CONFIG = ROOT / "ai" / "app" / "config.py"
MODEL_RUNTIME = ROOT / "ai" / "app" / "models" / "runtime.py"
COMPOSE = ROOT / "docker-compose.yml"
TEMPLATE = ROOT / "ai" / ".env.example"

# 不能/不应转发进 ai 容器的变量：必须在这里给出理由，防止「漏配」与「有意不配」混淆。
HOST_SIDE_ONLY: dict[str, str] = {}

_GETENV_RE = re.compile(r"os\.getenv\(\s*[\"']([A-Z0-9_]+)[\"']")
_COMPOSE_AI_RE = re.compile(r"^  ai:\s*$", re.MULTILINE)
_NEXT_SERVICE_RE = re.compile(r"^  [a-z][a-z0-9-]*:\s*$", re.MULTILINE)
_ENV_KEY_RE = re.compile(r"^      ([A-Z][A-Z0-9_]*):", re.MULTILINE)
# 模板里既有生效行（NAME=value）也有注释行（# NAME=value），两种都算「已记录」。
_TEMPLATE_KEY_RE = re.compile(r"^\s*#?\s*([A-Z][A-Z0-9_]*)=", re.MULTILINE)


def declared_config_keys() -> list[str]:
    """从 AI 配置源中提取需要核对的环境变量名。

    上游为 `main`。
    """
    text = CONFIG.read_text(encoding="utf-8") + MODEL_RUNTIME.read_text(encoding="utf-8")
    keys = set(_GETENV_RE.findall(text))
    return sorted(keys)


def compose_ai_keys() -> set[str]:
    """读取 Compose 中 AI 服务的环境变量声明并返回键集合。

    上游为 `main`。
    """
    text = COMPOSE.read_text(encoding="utf-8")
    match = _COMPOSE_AI_RE.search(text)
    if not match:
        raise SystemExit("未找到 docker-compose.yml 的 ai 服务块")
    rest = text[match.end():]
    nxt = _NEXT_SERVICE_RE.search(rest)
    block = rest[: nxt.start()] if nxt else rest
    return set(_ENV_KEY_RE.findall(block))


def template_keys() -> set[str]:
    """提取模板声明的环境变量键，供配置转发覆盖比对。

    上游为 `main`。
    """
    return set(_TEMPLATE_KEY_RE.findall(TEMPLATE.read_text(encoding="utf-8")))


def main() -> int:
    """比较 Python 声明、Compose 转发以及配置模板，报告各处缺少的变量。

    下游调用 `declared_config_keys`、`compose_ai_keys`。
    """
    if not all(path.exists() for path in (CONFIG, MODEL_RUNTIME, COMPOSE, TEMPLATE)):
        print("找不到 ai/app/config.py、docker-compose.yml 或 ai/.env.example", file=sys.stderr)
        return 2

    declared = declared_config_keys()
    forwarded = compose_ai_keys()
    documented = template_keys()
    missing_forward = [key for key in declared if key not in forwarded and key not in HOST_SIDE_ONLY]
    missing_doc = [key for key in declared + sorted(HOST_SIDE_ONLY) if key not in documented]
    extra = sorted(forwarded - set(declared))

    print(f"AI 基础与资源配置声明 {len(declared)} 项；compose 转发 {len(forwarded)} 项；模板记录 {len(documented)} 项")
    print("宿主机侧（有意不转发）: " + (", ".join(sorted(HOST_SIDE_ONLY)) or "无"))

    if extra:
        # 转发但代码不读的变量通常是改名或删除后的残留，提示但不判失败。
        print("提示: compose 转发了 config.py 未读取的变量: " + ", ".join(extra))
    if missing_forward:
        print("FAIL 未在 compose 转发的配置项: " + ", ".join(missing_forward))
    if missing_doc:
        print("FAIL 未在 .env.example 记录的配置项: " + ", ".join(missing_doc))
    if missing_forward or missing_doc:
        return 1
    print("OK 配置项在 compose 与模板中均已覆盖，宿主机侧变量已明确标注")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
