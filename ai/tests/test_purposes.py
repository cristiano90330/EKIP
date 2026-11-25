"""验证模型用途的配置表、必备用途和未知用途行为。"""

import pytest

from ai.app.llm.purposes import PURPOSE_NAMES, get_purpose


def test_supported_purposes():
    """在当前作用域内完成状态变更，无直接返回值。"""
    assert PURPOSE_NAMES == {"generate", "rewrite", "rerank", "judge", "agent", "summarize", "evidence_support"}


def test_unknown_purpose_is_rejected():
    """验证用例触发 `ValueError` 异常。"""
    with pytest.raises(ValueError, match="unknown model purpose"):
        get_purpose("unknown")
