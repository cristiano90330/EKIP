# -*- coding: utf-8 -*-
"""验证解析块及输出内容如何清除 PostgreSQL 无法保存的空字符。"""

import logging
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

from ai.app.parser.blocks import Block, BlockType, strip_nul_from_blocks
from ai.app.parser import router as parser_module
from ai.app.parser import pipeline as pipeline_module
from ai.app.main import app
from ai.tests.conftest import AUTH_HEADERS

client = TestClient(app, headers=AUTH_HEADERS)

NUL = "\x00"
BLOCKS_LOGGER = "ai.app.parser.blocks"


# ---------- (a) 清洗后不含 NUL ----------

def test_strip_nul_removes_nul_in_text_and_rows():
    """验证 `removed` 等于 `2`，且 `NUL` 不属于 `b.text`。"""
    b = Block(type=BlockType.PARAGRAPH, text=f"小写{NUL}123.00 元", page=2)
    t = Block(type=BlockType.TABLE, table_id="p2-t1", page=2,
              rows=[["列A", "列B"], [f"v{NUL}1", "v2"]])
    removed = strip_nul_from_blocks([b, t], source="procurement_contract.pdf")

    assert removed == 2
    assert NUL not in b.text
    assert b.text == "小写123.00 元"
    assert all(NUL not in c for c in t.rows[1])


# ---------- (b) 命中即 WARN ----------

def test_strip_nul_logs_warning_with_filename_and_count(caplog):
    """验证 `len(warnings)` 等于 `1`，且 `'procurement_contract.pdf'` 属于 `msg`。"""
    b = Block(type=BlockType.PARAGRAPH, text=f"a{NUL}{NUL}b")
    with caplog.at_level(logging.WARNING, logger=BLOCKS_LOGGER):
        strip_nul_from_blocks([b], source="procurement_contract.pdf")

    warnings = [r for r in caplog.records if r.levelno == logging.WARNING]
    assert len(warnings) == 1
    msg = warnings[0].getMessage()
    assert "procurement_contract.pdf" in msg   # 文件名
    assert "2" in msg                          # 剥离字符数


def test_route_logs_warning_when_nul_present(caplog, monkeypatch):
    """真实走 /parser/object：NUL 命中必须在日志里响亮报出，不静默吞掉。"""

    def fake_download(bucket, obj, path):
        """提供空操作实现，无返回值。输入为 `bucket`、`obj`、`path`。"""
        pass

    def fake_parse_by_ext(ext, temp_path):
        """返回 列表。输入为 `ext`、`temp_path`。下游调用 `ai.app.parser.blocks.Block`。"""
        return [Block(type=BlockType.PARAGRAPH, text=f"正文{NUL}含 NUL", page=2)]

    monkeypatch.setattr(parser_module, "minio_service", SimpleNamespace(download_file=fake_download))
    monkeypatch.setattr(pipeline_module, "_parse_by_ext", fake_parse_by_ext)

    with caplog.at_level(logging.WARNING, logger=BLOCKS_LOGGER):
        resp = client.post("/parser/object", json={
            "documentId": 497, "objectName": "procurement_contract.pdf",
            "bucketName": "b", "fileExtension": "pdf"})
    assert resp.status_code == 200
    assert any("procurement_contract.pdf" in r.getMessage()
               for r in caplog.records if r.levelno == logging.WARNING)


# ---------- (c) 正常输入不受影响 ----------

def test_clean_input_unchanged_and_no_warning(caplog):
    """验证 `removed` 等于 `0`，且 `b.text is before or b.text == before` 成立。"""
    text = "这是一段正常的中文正文，没有 NUL。"
    b = Block(type=BlockType.PARAGRAPH, text=text)
    before = b.text
    with caplog.at_level(logging.WARNING, logger=BLOCKS_LOGGER):
        removed = strip_nul_from_blocks([b], source="clean.pdf")

    assert removed == 0
    assert b.text is before or b.text == before      # 不误删
    assert len(b.text) == len(text)                  # 长度不变
    assert not [r for r in caplog.records if r.levelno == logging.WARNING]


# ---------- (d) 只清 NUL，不做别的规范化 ----------

def test_only_nul_removed_newline_tab_and_non_str_kept():
    """验证 `b.text` 等于 `'行一\n\t缩进后'`，且 `t.rows[1][1]` 等于 `num`。"""
    text = "行一\n\t缩进\x00后"
    b = Block(type=BlockType.PARAGRAPH, text=text)
    num, when = 1, 20260926          # Excel 里常见的非 str 单元格
    t = Block(type=BlockType.TABLE, table_id="Sheet1",
              rows=[["名称", "数量"], ["采购", num], ["日期", when]])
    strip_nul_from_blocks([b, t], source="x.xlsx")

    assert b.text == "行一\n\t缩进后"        # \n / \t 原样保留，仅 NUL 消失
    assert t.rows[1][1] == num               # 非 str 单元格不被 str() 强转
    assert t.rows[2][1] == when


# ---------- (a)(c) 端到端：真实 chunk_blocks 后 content/searchText 无 NUL ----------

def test_route_chunk_content_and_searchtext_have_no_nul(monkeypatch):
    """打桩只到解析层，真实跑 chunk_blocks——验证 content 与 searchText 都干净。"""

    def fake_download(bucket, obj, path):
        """提供空操作实现，无返回值。输入为 `bucket`、`obj`、`path`。"""
        pass

    def fake_parse_by_ext(ext, temp_path):
        """返回 列表。输入为 `ext`、`temp_path`。下游调用 `ai.app.parser.blocks.Block`。"""
        return [
            Block(type=BlockType.PARAGRAPH, text=f"合同小写 {NUL}123.00 元", page=2),
            Block(type=BlockType.TABLE, table_id="p2-t1", page=2,
                  rows=[["项", "值"], [f"金{NUL}额", "100"]]),
        ]

    monkeypatch.setattr(parser_module, "minio_service", SimpleNamespace(download_file=fake_download))
    monkeypatch.setattr(pipeline_module, "_parse_by_ext", fake_parse_by_ext)

    resp = client.post("/parser/object", json={
        "documentId": 497, "objectName": "procurement_contract.pdf",
        "bucketName": "b", "fileExtension": "pdf"})
    assert resp.status_code == 200
    chunks = resp.json()["chunks"]
    assert chunks, "应产出非空 chunk"
    for c in chunks:
        assert NUL not in c["content"]
        assert NUL not in c["searchText"]
