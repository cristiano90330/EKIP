"""从模型文本中提取 JSON 对象供生成、评测及证据流程使用。"""

import json
import re


def extract_json_obj(raw: str) -> dict | None:
    """从模型的原始文本中恢复顶层 JSON 对象，无法解析时返回 None 供调用方处理。

    上游为 `ai.app.query.router._parse_rewrite`。
    """
    if not raw:
        return None
    text = raw.strip()
    if text.startswith("```"):
        text = re.sub(r"^```(?:json)?\s*|\s*```$", "", text).strip()
    try:
        obj = json.loads(text)
        if isinstance(obj, dict):
            return obj
    except Exception:
        pass
    # 兜底：取第一个 { ... } 平衡块
    start = text.find("{")
    if start == -1:
        return None
    depth = 0
    for i in range(start, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                try:
                    obj = json.loads(text[start:i + 1])
                    if isinstance(obj, dict):
                        return obj
                except Exception:
                    return None
    return None
