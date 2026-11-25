"""验证附加文档格式、Office 转换和解析结构的契约。"""

import json
import zipfile

import pytest

from ai.app.parser import epub_parser, excel_parser, pptx_parser, text_parser, xmind_parser
from ai.app.parser.errors import DocumentInputError
from ai.app.parser import legacy_office


# ---------- 文本类 ----------

def test_txt_parser_splits_paragraphs_on_blank_lines(tmp_path):
    """验证 `[b.type for b in blocks]` 等于 `['paragraph', 'paragraph']`，且 `blocks[0].text` 等于 `'第一段第一行\n第一段第二行'`。"""
    path = tmp_path / "a.txt"
    path.write_text("第一段第一行\n第一段第二行\n\n第二段\n", encoding="utf-8")

    blocks = text_parser.extract_txt_blocks(str(path))

    assert [b.type for b in blocks] == ["paragraph", "paragraph"]
    assert blocks[0].text == "第一段第一行\n第一段第二行"
    assert blocks[1].text == "第二段"


def test_txt_parser_falls_back_to_gb18030(tmp_path):
    """验证 `blocks[0].text` 等于 `'企业知识管理'`。"""
    path = tmp_path / "gbk.txt"
    path.write_bytes("企业知识管理".encode("gb18030"))

    blocks = text_parser.extract_txt_blocks(str(path))

    assert blocks[0].text == "企业知识管理"


def test_txt_parser_marks_bullet_lines_as_list(tmp_path):
    """验证 `[b.type for b in blocks]` 等于 `['paragraph', 'list']`，且 `blocks[1].text` 等于 `'- 第一项\n- 第二项'`。"""
    path = tmp_path / "list.txt"
    path.write_text("要点：\n- 第一项\n- 第二项\n", encoding="utf-8")

    blocks = text_parser.extract_txt_blocks(str(path))

    assert [b.type for b in blocks] == ["paragraph", "list"]
    assert blocks[1].text == "- 第一项\n- 第二项"


def test_csv_parser_builds_single_table_with_semicolon_dialect(tmp_path):
    """验证 `len(blocks)` 等于 `1`，且 `blocks[0].type` 等于 `'table'`。"""
    path = tmp_path / "a.csv"
    path.write_text("名称;数量\n苹果;3\n梨;5\n", encoding="utf-8")

    blocks = text_parser.extract_csv_blocks(str(path))

    assert len(blocks) == 1
    assert blocks[0].type == "table"
    assert blocks[0].rows == [["名称", "数量"], ["苹果", "3"], ["梨", "5"]]


def test_json_parser_maps_uniform_object_array_to_table(tmp_path):
    """验证 `blocks[0].type` 等于 `'table'`，且 `blocks[0].rows` 等于 `[['id', 'name'], ['1', '甲'], ['2', '乙']]`。"""
    path = tmp_path / "a.json"
    path.write_text(json.dumps([{"id": 1, "name": "甲"}, {"id": 2, "name": "乙"}]), encoding="utf-8")

    blocks = text_parser.extract_json_blocks(str(path))

    assert blocks[0].type == "table"
    assert blocks[0].rows == [["id", "name"], ["1", "甲"], ["2", "乙"]]


def test_json_parser_keeps_nested_payload_as_text(tmp_path):
    """验证 `blocks[0].type` 等于 `'paragraph'`，且 `'"b": 1'` 属于 `blocks[0].text`。"""
    path = tmp_path / "b.json"
    path.write_text(json.dumps({"a": {"b": 1}}), encoding="utf-8")

    blocks = text_parser.extract_json_blocks(str(path))

    assert blocks[0].type == "paragraph"
    assert '"b": 1' in blocks[0].text


def test_json_parser_rejects_malformed_input(tmp_path):
    """验证用例触发 `DocumentInputError` 异常，并检查 `'JSON 解析失败'` 属于 `str(exc.value)`。"""
    path = tmp_path / "bad.json"
    path.write_text("{ not json", encoding="utf-8")

    with pytest.raises(DocumentInputError) as exc:
        text_parser.extract_json_blocks(str(path))
    assert "JSON 解析失败" in str(exc.value)


# ---------- PPTX ----------

def test_pptx_parser_extracts_title_bullets_table_and_notes(tmp_path):
    """验证 `headings and headings[0].text == '季度回顾' and (headings[0].level == 2)` 成立，且 `any((b.type == 'list' and '收入增长' in b.text for b in blocks))` 成立。"""
    from pptx import Presentation
    from pptx.util import Inches

    presentation = Presentation()
    slide = presentation.slides.add_slide(presentation.slide_layouts[1])
    slide.shapes.title.text = "季度回顾"
    body = slide.placeholders[1].text_frame
    body.text = "总体进展"
    bullet = body.add_paragraph()
    bullet.text = "收入增长"
    bullet.level = 1
    table = slide.shapes.add_table(2, 2, Inches(1), Inches(4), Inches(4), Inches(1)).table
    table.cell(0, 0).text = "指标"
    table.cell(0, 1).text = "数值"
    table.cell(1, 0).text = "营收"
    table.cell(1, 1).text = "100"
    slide.notes_slide.notes_text_frame.text = "讲解备注"
    path = str(tmp_path / "deck.pptx")
    presentation.save(path)

    blocks = pptx_parser.extract_blocks(path)

    headings = [b for b in blocks if b.type == "heading"]
    assert headings and headings[0].text == "季度回顾" and headings[0].level == 2
    assert any(b.type == "list" and "收入增长" in b.text for b in blocks)
    tables = [b for b in blocks if b.type == "table"]
    assert tables[0].rows == [["指标", "数值"], ["营收", "100"]]
    assert tables[0].table_id == "s1-t1"
    assert any(b.type == "paragraph" and b.text == "讲解备注" for b in blocks)


# ---------- Excel ----------

def test_xls_extension_with_xlsx_content_is_parsed(tmp_path):
    """只改后缀的常见情况：内容其实是 OOXML，不能报「文件损坏」。"""
    import openpyxl

    workbook = openpyxl.Workbook()
    workbook.active.append(["a", "b"])
    workbook.active.append([1, 2])
    path = str(tmp_path / "renamed.xls")
    workbook.save(path)

    blocks = excel_parser.extract_blocks(path, "xls")

    assert blocks[0].rows == [["a", "b"], [1, 2]]


def test_corrupt_xls_becomes_document_input_error(tmp_path):
    """坏 BIFF 必须归一成输入错误（422 / 不重试），不能在解析层抛裸异常变成 500。"""
    from ai.app.parser.pipeline import parse_local_document

    path = tmp_path / "broken.xls"
    path.write_bytes(b"\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1" + b"\x00" * 256)

    with pytest.raises(DocumentInputError):
        parse_local_document(str(path), "xls", chunk_size=800, chunk_overlap=100)


# ---------- EPUB ----------

def _write_epub(path, chapters):
    """委托 `archive.writestr` 完成当前操作，无直接返回值。

    输入为 `path`、`chapters`。
    上游为 `test_epub_parser_follows_spine_order_and_maps_blocks`。
    """
    container = (
        '<?xml version="1.0"?><container version="1.0" '
        'xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles>'
        '<rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>'
        "</rootfiles></container>"
    )
    manifest = "".join(
        f'<item id="c{i}" href="c{i}.xhtml" media-type="application/xhtml+xml"/>'
        for i, _ in enumerate(chapters, start=1)
    )
    spine = "".join(f'<itemref idref="c{i}"/>' for i, _ in enumerate(chapters, start=1))
    opf = (
        '<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0">'
        f"<manifest>{manifest}</manifest><spine>{spine}</spine></package>"
    )
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("mimetype", "application/epub+zip")
        archive.writestr("META-INF/container.xml", container)
        archive.writestr("OEBPS/content.opf", opf)
        for i, html in enumerate(chapters, start=1):
            archive.writestr(f"OEBPS/c{i}.xhtml", html)


def test_epub_parser_follows_spine_order_and_maps_blocks(tmp_path):
    """验证 `blocks[0].text == '第一章' and blocks[0].level == 1` 成立，且 `blocks[2].text` 等于 `'甲\n乙'`。"""
    path = str(tmp_path / "book.epub")
    _write_epub(path, [
        "<html><body><h1>第一章</h1><p>正文一</p><ul><li>甲</li><li>乙</li></ul>"
        "<table><tr><th>列</th></tr><tr><td>值</td></tr></table></body></html>",
        "<html><body><h2>第二节</h2><p>正文二</p></body></html>",
    ])

    blocks = epub_parser.extract_blocks(path)

    assert [b.type for b in blocks] == [
        "heading", "paragraph", "list", "table", "heading", "paragraph"]
    assert blocks[0].text == "第一章" and blocks[0].level == 1
    assert blocks[2].text == "甲\n乙"
    assert blocks[3].rows == [["列"], ["值"]]
    assert blocks[4].text == "第二节" and blocks[4].level == 2


def test_epub_parser_rejects_non_zip(tmp_path):
    """验证用例触发 `zipfile.BadZipFile` 异常。"""
    path = tmp_path / "bad.epub"
    path.write_bytes(b"not a zip")

    with pytest.raises(zipfile.BadZipFile):
        epub_parser.extract_blocks(str(path))


# ---------- XMind ----------

def test_xmind_parser_maps_topic_depth_to_heading_levels(tmp_path):
    """验证 `headings` 等于 `[('中心主题', 1), ('分支一', 2), ('叶子', 3), ('分支二', 2)]`，且 `any((b.type == 'paragraph' and b.text == '中心备注' for b in blocks))` 成立。"""
    payload = [{
        "title": "Sheet 1",
        "rootTopic": {
            "title": "中心主题",
            "notes": {"plain": {"content": "中心备注"}},
            "children": {"attached": [
                {"title": "分支一", "children": {"attached": [{"title": "叶子"}]}},
                {"title": "分支二"},
            ]},
        },
    }]
    path = str(tmp_path / "map.xmind")
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("content.json", json.dumps(payload, ensure_ascii=False))

    blocks = xmind_parser.extract_blocks(path)

    headings = [(b.text, b.level) for b in blocks if b.type == "heading"]
    # 默认 Sheet 名不作为标题；主题深度即层级。
    assert headings == [("中心主题", 1), ("分支一", 2), ("叶子", 3), ("分支二", 2)]
    assert any(b.type == "paragraph" and b.text == "中心备注" for b in blocks)


def test_xmind_parser_rejects_archive_without_content(tmp_path):
    """验证用例触发 `ValueError` 异常。"""
    path = str(tmp_path / "empty.xmind")
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("other.txt", "x")

    with pytest.raises(ValueError):
        xmind_parser.extract_blocks(path)


# ---------- 旧版二进制 Office ----------

def test_legacy_doc_without_converter_reports_actionable_error(tmp_path, monkeypatch):
    """验证用例触发 `DocumentInputError` 异常，并检查 `'docx' in message and '另存为' in message` 成立。"""
    monkeypatch.setattr(legacy_office, "soffice_binary", lambda: None)
    path = tmp_path / "old.doc"
    path.write_bytes(b"\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1" + b"\x00" * 128)

    with pytest.raises(DocumentInputError) as exc:
        legacy_office.convert_legacy(str(path), "doc")

    message = str(exc.value)
    assert "docx" in message and "另存为" in message


def test_legacy_doc_detected_by_magic_header(tmp_path):
    """验证 `legacy_office.is_legacy_binary(str(path))` 为 `True`，且 `legacy_office.is_legacy_binary(str(modern))` 为 `False`。"""
    path = tmp_path / "old.doc"
    path.write_bytes(b"\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1" + b"\x00" * 128)
    modern = tmp_path / "new.doc"
    modern.write_bytes(b"PK\x03\x04rest")

    assert legacy_office.is_legacy_binary(str(path)) is True
    assert legacy_office.is_legacy_binary(str(modern)) is False
