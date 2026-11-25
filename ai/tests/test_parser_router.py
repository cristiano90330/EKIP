# -*- coding: utf-8 -*-
"""验证对象解析接口的文件生命周期、格式入口及错误状态。"""

import json
import os
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient
from pdfminer.pdfparser import PDFSyntaxError
from pydantic import ValidationError

from ai.app.parser.schema import ParseObjectRequest, ChunkItem, ChunkResult
from ai.app.parser import router as parser_module
from ai.app.parser import pipeline as pipeline_module
from ai.app.parser.errors import DocumentInputError
from ai.app.parser.blocks import Block, BlockType
from ai.app.main import app
from ai.tests.conftest import AUTH_HEADERS

client = TestClient(app, headers=AUTH_HEADERS)


# ---------- schema ----------

def test_parse_request_has_extension_and_rich_chunk():
    """验证 `req.fileExtension` 等于 `'pdf'`，且 `ci.headingPath` 等于 `'A > B'`。"""
    req = ParseObjectRequest(documentId=1, objectName="x.pdf", bucketName="b", fileExtension="pdf")
    assert req.fileExtension == "pdf"
    ci = ChunkItem(chunkIndex=0, content="c", sourceType="table", headingPath="A > B", rowIndex=2)
    assert ci.headingPath == "A > B"
    assert ci.sourceType == "table"
    assert ci.rowIndex == 2
    # 未提供的可空字段取默认值
    assert ci.pageNo is None
    assert ci.tableId is None
    assert ci.searchText == ""


def test_parse_request_default_extension_is_empty():
    """验证 `req.fileExtension` 等于 `''`。"""
    req = ParseObjectRequest(documentId=1, objectName="x.pdf", bucketName="b")
    assert req.fileExtension == ""


# ---------- 扩展名派发 ----------

def test_parse_by_ext_dispatches_to_right_parser(monkeypatch, tmp_path):
    """验证 `calls['pdf'][0]` 等于 `pdf_path`，且 `calls['md'][0]` 等于 `md_content`。"""
    calls = {}

    def make(name):
        """向 `test_parse_by_ext_dispatches_to_right_parser` 提供预设数据。返回 `fake`。输入为 `name`。"""
        def fake(path, *args):
            """返回 列表。输入为 `path`、`args`。"""
            calls[name] = (path, args)
            return []

        return fake

    for name, mod in [("pdf", pipeline_module.pdf_parser),
                      ("md", pipeline_module.md_parser),
                      ("docx", pipeline_module.docx_parser),
                      ("xlsx", pipeline_module.excel_parser)]:
        monkeypatch.setattr(mod, "extract_blocks", make(name))

    md_content = "# 标题\n\n正文"
    pdf_path = str(tmp_path / "a.pdf")
    md1 = str(tmp_path / "b.md")
    md2 = str(tmp_path / "c.md")
    docx_path = str(tmp_path / "d.docx")
    xlsx1 = str(tmp_path / "e.xlsx")
    xls2 = str(tmp_path / "f.xls")
    for p in (pdf_path, md1, md2, docx_path, xlsx1, xls2):
        with open(p, "w", encoding="utf-8") as f:
            f.write(md_content)

    pipeline_module._parse_by_ext("pdf", pdf_path)
    pipeline_module._parse_by_ext("md", md1)
    pipeline_module._parse_by_ext("markdown", md2)   # md 与 markdown 走同一解析器
    pipeline_module._parse_by_ext("docx", docx_path)
    pipeline_module._parse_by_ext("xlsx", xlsx1)
    pipeline_module._parse_by_ext("xls", xls2)       # xls 由同一模块按扩展名分流到 xlrd

    assert calls["pdf"][0] == pdf_path
    assert calls["md"][0] == md_content             # md 解析器收到文件内容，而非路径（修复点）
    assert calls["docx"][0] == docx_path
    # 2026-10-24：.xls 不再复用 openpyxl 路径，扩展名必须透传给解析器做分流。
    assert calls["xlsx"] == (xls2, ("xls",))


def test_parse_by_ext_unsupported_raises_input_error():
    """验证用例触发 `DocumentInputError` 异常，并检查 `'不支持的文档类型'` 属于 `str(exc_info.value)`。"""
    with pytest.raises(DocumentInputError) as exc_info:
        pipeline_module._parse_by_ext("exe", "g.exe")
    assert "不支持的文档类型" in str(exc_info.value)


# ---------- /parser/object 路由管线 ----------

# 输入为 `monkeypatch`、`content`。
# 上游为 `test_legacy_pdf_object_still_works_and_delegates`。
def _stub_pipeline(monkeypatch, content="hello"):
    """把 minio_service 下载、_parse_by_ext、chunk_blocks 打桩，规避真实 MinIO 下载/解析。"""
    seen = {}

    def fake_download(bucket, obj, path):
        # 不真正写文件：_parse_by_ext 已被打桩，temp 文件不会被读取
        """在当前作用域内完成状态变更，无直接返回值。输入为 `bucket`、`obj`、`path`。"""
        seen["bucket"] = bucket

    def fake_parse_by_ext(ext, temp_path):
        """返回 列表。输入为 `ext`、`temp_path`。下游调用 `ai.app.parser.blocks.Block`。"""
        seen["ext"] = ext
        return [Block(type="paragraph", text=content)]

    def fake_chunk_blocks(blocks, **kwargs):
        """返回 列表。输入为 `blocks`、`kwargs`。"""
        assert len(blocks) == 1
        return [{"chunkIndex": 0, "content": content, "searchText": content,
                 "sourceType": "text", "pageNo": None, "headingPath": "",
                 "tableId": None, "rowIndex": None}]

    monkeypatch.setattr(parser_module, "minio_service", SimpleNamespace(download_file=fake_download))
    monkeypatch.setattr(pipeline_module, "_parse_by_ext", fake_parse_by_ext)
    monkeypatch.setattr(pipeline_module, "chunk_blocks", fake_chunk_blocks)
    return seen


def test_object_route_returns_rich_chunks(monkeypatch):
    """验证 `resp.status_code` 等于 `200`，且 `body['documentId']` 等于 `7`。"""
    seen = _stub_pipeline(monkeypatch)
    resp = client.post("/parser/object", json={
        "documentId": 7, "objectName": "x.pdf", "bucketName": "b", "fileExtension": "pdf"})
    assert resp.status_code == 200
    body = resp.json()
    assert body["documentId"] == 7
    assert body["chunks"][0]["content"] == "hello"
    assert body["chunks"][0]["sourceType"] == "text"
    assert seen["ext"] == "pdf"


def test_legacy_pdf_object_still_works_and_delegates(monkeypatch):
    """验证 `resp.status_code` 等于 `200`，且 `seen['ext']` 等于 `'pdf'`。"""
    seen = _stub_pipeline(monkeypatch, content="legacy")
    # 旧客户端未带 fileExtension，路由应强制按 pdf 解析
    resp = client.post("/parser/pdf/object", json={
        "documentId": 3, "objectName": "x.pdf", "bucketName": "b"})
    assert resp.status_code == 200
    assert seen["ext"] == "pdf"
    assert resp.json()["documentId"] == 3
    assert resp.json()["chunks"][0]["content"] == "legacy"


def test_object_route_no_text_returns_422(monkeypatch):
    """解析/切块后未提取到任何文本块 → 422（原为 500）。

    语义理由：同一份文件重试仍会是空，属**永久失败**。原来抛 500 会让 Java 的
    `TaskFailureClassifier` 判 TRANSIENT（`status >= 500`）→ 白白重试 3 次，
    用户多等约 15 秒并跑三遍完整解析。改 422 后 Java 侧零改动自动判 PERMANENT。
    """

    def fake_download(bucket, obj, path):
        """提供空操作实现，无返回值。输入为 `bucket`、`obj`、`path`。"""
        pass

    def fake_parse_by_ext(ext, temp_path):
        """返回 列表。输入为 `ext`、`temp_path`。"""
        return []

    def fake_chunk_blocks(blocks, **kwargs):
        """返回 列表。输入为 `blocks`、`kwargs`。"""
        return []

    monkeypatch.setattr(parser_module, "minio_service", SimpleNamespace(download_file=fake_download))
    monkeypatch.setattr(pipeline_module, "_parse_by_ext", fake_parse_by_ext)
    monkeypatch.setattr(pipeline_module, "chunk_blocks", fake_chunk_blocks)

    resp = client.post("/parser/object", json={
        "documentId": 1, "objectName": "x.pdf", "bucketName": "b", "fileExtension": "pdf"})
    assert resp.status_code == 422
    assert "未提取到文本" in resp.json()["detail"]


def test_object_route_minio_failure_returns_503(monkeypatch):
    """MinIO 下载失败（对象存储不可达）→ 503 → Java 判 TRANSIENT，该重试。

    这一档必须与「文件损坏」分开：原来两者都落在同一个 `except Exception` 里被包成 500，
    瞬态与永久被压成同一种错误。
    """

    def fake_download(bucket, obj, path):
        """触发 `ConnectionError`，交由上游处理。输入为 `bucket`、`obj`、`path`。"""
        raise ConnectionError("minio endpoint refused connection")

    monkeypatch.setattr(parser_module, "minio_service", SimpleNamespace(download_file=fake_download))

    resp = client.post("/parser/object", json={
        "documentId": 2, "objectName": "x.pdf", "bucketName": "b", "fileExtension": "pdf"})
    assert resp.status_code == 503
    assert "对象存储不可达" in resp.json()["detail"]


def test_object_route_corrupt_file_returns_422(monkeypatch):
    """解析库抛异常（文件损坏 / 内容与扩展名不符 / 编码错）→ 422 → Java 判 PERMANENT。

    四个 parser 自身不 raise，异常来自 pypdf / pdfplumber / openpyxl / python-docx。
    原来这条是 500（白重试 3 次）。
    """

    def fake_download(bucket, obj, path):
        """提供空操作实现，无返回值。输入为 `bucket`、`obj`、`path`。"""
        pass

    def fake_parse_by_ext(ext, temp_path):
        """触发 `PDFSyntaxError`，交由上游处理。输入为 `ext`、`temp_path`。"""
        raise PDFSyntaxError("No /Root object!")

    monkeypatch.setattr(parser_module, "minio_service", SimpleNamespace(download_file=fake_download))
    monkeypatch.setattr(pipeline_module, "_parse_by_ext", fake_parse_by_ext)

    resp = client.post("/parser/object", json={
        "documentId": 4, "objectName": "x.pdf", "bucketName": "b", "fileExtension": "pdf"})
    assert resp.status_code == 422
    assert "文档解析失败" in resp.json()["detail"]


def test_object_route_unsupported_ext_still_422_through_route(monkeypatch):
    """扩展名不支持走 HTTP 全链路仍是 422（B3 重构后 _parse_by_ext 的 HTTPException 不被 503/422 包装吞掉）。"""

    def fake_download(bucket, obj, path):
        """提供空操作实现，无返回值。输入为 `bucket`、`obj`、`path`。"""
        pass

    monkeypatch.setattr(parser_module, "minio_service", SimpleNamespace(download_file=fake_download))

    resp = client.post("/parser/object", json={
        "documentId": 5, "objectName": "x.exe", "bucketName": "b", "fileExtension": "exe"})
    assert resp.status_code == 422
    assert "不支持的文档类型" in resp.json()["detail"]


def test_object_route_cleans_temp_file_on_failure(monkeypatch):
    """`finally` 的临时文件清理在失败路径上必须保留（B3 删 catch-all 时最容易连带删掉）。"""
    created = {}

    def fake_download(bucket, obj, path):
        """将当前内容写入 `f`，供调用方保留本次结果。输入为 `bucket`、`obj`、`path`。"""
        created["path"] = path
        with open(path, "w", encoding="utf-8") as f:      # 让文件真实存在，才能验证被清理
            f.write("x")
        raise ConnectionError("minio down")

    monkeypatch.setattr(parser_module, "minio_service", SimpleNamespace(download_file=fake_download))

    resp = client.post("/parser/object", json={
        "documentId": 6, "objectName": "x.pdf", "bucketName": "b", "fileExtension": "pdf"})
    assert resp.status_code == 503
    assert "path" in created
    assert not os.path.exists(created["path"]), "临时文件应在 finally 里被删除"


# ---------- md 分支读文件内容（真实解析，非打桩 _parse_by_ext）----------

def test_md_branch_reads_real_content_not_path(monkeypatch):
    """md_parser.extract_blocks 接收 markdown 内容而非文件路径。

    若误把 temp_path 传给 md_parser，chunk content 会是文件路径字符串（垃圾）；
    本测试仅打桩 MinIO 下载（把真实 markdown 写入临时文件），真实跑 md 分支 + chunk_blocks，
    断言 chunk 内容来自 markdown 而非路径。
    """
    md_content = "# 一级标题\n\n这是正文段落。"

    def fake_download(bucket, obj, temp_path):
        """将当前内容写入 `f`，供调用方保留本次结果。输入为 `bucket`、`obj`、`temp_path`。"""
        with open(temp_path, "w", encoding="utf-8") as f:
            f.write(md_content)

    monkeypatch.setattr(parser_module, "minio_service", SimpleNamespace(download_file=fake_download))

    resp = client.post("/parser/object", json={
        "documentId": 9, "objectName": "笔记.md", "bucketName": "b", "fileExtension": "md"})
    assert resp.status_code == 200
    chunks = resp.json()["chunks"]
    assert len(chunks) >= 1
    body = "\n".join(c["content"] for c in chunks)
    assert "这是正文段落。" in body          # 真实 markdown 正文进入 chunk
    assert "笔记.md" not in body             # 内容不应是临时文件路径（垃圾）
    # 标题被解析成 headingPath，证明走的是真实 markdown 解析而非路径 split
    assert any("一级标题" in c["headingPath"] for c in chunks)


# ---------- P1-18：响应结构漂移兜底 ----------

def test_response_shape_validates_against_chunk_result(monkeypatch):
    """真实响应必须能通过 `ChunkResult` 校验（本章 3.1 P1-18）。

    路由刻意**不挂** `response_model=ChunkResult`（省掉对几千个 chunk 的重复校验+序列化），
    代价是运行时没有 Pydantic 兜底 ⇒ **这条用例就是唯一的结构守卫**：
    `chunk_blocks` 哪天改了字段名，Java 侧会静默拿到 `null`，只有这里会响。

    两个刻意的选择：
    - 只打桩 `_parse_by_ext`（拿到可控的 paragraph + table 两种 Block），
      **不打桩 `chunk_blocks`**——要校验的正是它的真实产出，打桩等于自己写字典自己验，永远绿。
    - 末尾反向验证一次「少一个字段就校验失败」，确认这个守卫不是空转的。
    """

    def fake_download(bucket, obj, path):
        """提供空操作实现，无返回值。输入为 `bucket`、`obj`、`path`。"""
        pass

    def fake_parse_by_ext(ext, temp_path):
        """返回 列表。输入为 `ext`、`temp_path`。下游调用 `ai.app.parser.blocks.Block`。"""
        return [
            Block(type=BlockType.PARAGRAPH, text="正文段落", page=1),
            Block(type=BlockType.TABLE, text="", page=2, table_id="p2-t1",
                  rows=[["列A", "列B"], ["1", "2"]]),
        ]

    monkeypatch.setattr(parser_module, "minio_service", SimpleNamespace(download_file=fake_download))
    monkeypatch.setattr(pipeline_module, "_parse_by_ext", fake_parse_by_ext)

    resp = client.post("/parser/object", json={
        "documentId": 10, "objectName": "x.pdf", "bucketName": "b", "fileExtension": "pdf"})
    assert resp.status_code == 200
    body = resp.json()

    # 结构漂移在这里炸（字段名/类型对不上 ChunkResult 即刻失败）
    parsed = ChunkResult.model_validate(body)
    assert parsed.documentId == 10
    assert {c.sourceType for c in parsed.chunks} == {"text", "table"}

    table_chunk = next(c for c in parsed.chunks if c.sourceType == "table")
    assert table_chunk.tableId == "p2-t1"
    assert table_chunk.rowIndex == 2        # 表头 row1，数据行从 2 起
    assert table_chunk.pageNo == 2
    assert all(isinstance(c.chunkIndex, int) for c in parsed.chunks)

    # 反向验证：这个守卫不是空转的——少一个必填字段必须失败
    broken = json.loads(json.dumps(body))
    broken["chunks"][0].pop("content")
    with pytest.raises(ValidationError):
        ChunkResult.model_validate(broken)
