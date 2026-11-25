"""验证基础文件解析、格式错误和结构信息保留。"""

import os
from ai.app.parser import md_parser, docx_parser, excel_parser, pdf_parser

def test_md_parser_headings_and_table():
    """验证 `blocks[0].type == 'heading' and blocks[0].level == 1 and (blocks[0].text == '一级')` 成立，且 `blocks[1].type` 等于 `'paragraph'`。"""
    blocks = md_parser.extract_blocks("# 一级\n\n正文段落。\n\n## 二级\n\n| a | b |\n| 1 | 2 |")
    assert blocks[0].type == "heading" and blocks[0].level == 1 and blocks[0].text == "一级"
    assert blocks[1].type == "paragraph"
    assert blocks[2].type == "heading" and blocks[2].level == 2
    assert blocks[3].type == "table" and blocks[3].rows == [["a", "b"], ["1", "2"]]


def test_md_parser_skips_separator_row():
    # GFM 分隔行（| --- | --- |）不应作为表格数据行入库，只应留下表头 + 数据行
    """验证 `blocks[-1].type` 等于 `'table'`，且 `blocks[-1].rows` 等于 `[['a', 'b'], ['1', '2']]`。"""
    blocks = md_parser.extract_blocks("| a | b |\n| --- | --- |\n| 1 | 2 |")
    assert blocks[-1].type == "table"
    assert blocks[-1].rows == [["a", "b"], ["1", "2"]]


def test_docx_parser_heading_paragraph_table(tmp_path):
    """验证 `blocks[0].type == 'heading' and blocks[0].level == 1 and (blocks[0].text == '标题一')` 成立，且 `blocks[1].type == 'paragraph' and blocks[1].text == '正文内容'` 成立。"""
    import docx
    d = docx.Document()
    d.add_heading("标题一", level=1)
    d.add_paragraph("正文内容")
    table = d.add_table(rows=2, cols=2)
    table.cell(0, 0).text = "h1"
    table.cell(0, 1).text = "h2"
    table.cell(1, 0).text = "a"
    table.cell(1, 1).text = "b"
    path = str(tmp_path / "sample.docx")
    d.save(path)
    blocks = docx_parser.extract_blocks(path)
    assert blocks[0].type == "heading" and blocks[0].level == 1 and blocks[0].text == "标题一"
    assert blocks[1].type == "paragraph" and blocks[1].text == "正文内容"
    table_blocks = [b for b in blocks if b.type == "table"]
    assert len(table_blocks) == 1
    assert table_blocks[0].rows == [["h1", "h2"], ["a", "b"]]


def test_excel_parser_sheet_as_table(tmp_path):
    """验证 `len(blocks)` 等于 `1`，且 `blocks[0].type` 等于 `'table'`。"""
    import openpyxl
    wb = openpyxl.Workbook()
    ws = wb.active
    ws.title = "Sheet1"
    ws.append(["a", "b"])
    ws.append(["1", "2"])
    path = str(tmp_path / "sample.xlsx")
    wb.save(path)
    blocks = excel_parser.extract_blocks(path)
    assert len(blocks) == 1
    assert blocks[0].type == "table"
    assert blocks[0].table_id == "Sheet1"
    assert blocks[0].rows == [["a", "b"], ["1", "2"]]


def test_pdf_parser_table_id_is_numbered_per_page(tmp_path):
    # 本章 3.1 P1-9：table_id 应是「按页编号」的 p{页}-t{该页第几张表}，
    # 而不是跨页累加的全局计数（那样第 2 页的第 1 张表会被叫成 p2-t3，与 p{pno} 前缀自相矛盾）。
    """验证 `ids` 等于 `['p1-t1', 'p1-t2', 'p2-t1']`。"""
    from reportlab.lib import colors
    from reportlab.lib.pagesizes import A4
    from reportlab.platypus import PageBreak, SimpleDocTemplate, Spacer, Table, TableStyle

    def grid(data):
        """向 `test_pdf_parser_table_id_is_numbered_per_page` 提供预设数据。返回 `t`。输入为 `data`。"""
        t = Table(data)
        t.setStyle(TableStyle([("GRID", (0, 0), (-1, -1), 0.5, colors.black)]))
        return t

    path = str(tmp_path / "tables.pdf")
    # 两张表之间必须留空：紧挨着时共享的边框线会让 pdfplumber 把它们识别成一张表
    SimpleDocTemplate(path, pagesize=A4).build([
        grid([["a", "b"], ["1", "2"]]),
        Spacer(1, 30),
        grid([["c", "d"], ["3", "4"]]),
        PageBreak(),
        grid([["e", "f"], ["5", "6"]]),
    ])

    blocks = pdf_parser.extract_blocks(path)
    ids = [b.table_id for b in blocks if b.type == "table"]
    assert ids == ["p1-t1", "p1-t2", "p2-t1"]


def test_pdf_parser_has_extract_blocks():
    # 契约桩：真实 PDF 提取依赖实际文件（端到端兜底），单测仅确认接口存在可调用。
    """验证 `callable(pdf_parser.extract_blocks)` 成立。"""
    assert callable(pdf_parser.extract_blocks)


def test_pdf_parser_ocr_preserves_page_for_mixed_document(tmp_path, monkeypatch):
    """通过 `pdf_parser.extract_blocks` 核对本用例构造的状态。"""
    from PIL import Image
    from reportlab.lib.utils import ImageReader
    from reportlab.pdfgen import canvas
    from types import SimpleNamespace
    from ai.app.parser import pdf_ocr

    path = str(tmp_path / "mixed.pdf")
    document = canvas.Canvas(path, pagesize=(300, 300))
    document.drawString(20, 200, "Existing text layer")
    document.showPage()
    with Image.new("RGB", (100, 100), "white") as image:
        document.drawImage(ImageReader(image), 20, 20, width=260, height=260)
    document.save()

    def recognize(command, **options):
        """返回 `SimpleNamespace` 的结果。输入为 `command`、`options`。"""
        assert command == ["tesseract", "stdin", "stdout", "-l", "chi_sim+eng"]
        assert options["input"].startswith(b"\x89PNG")
        return SimpleNamespace(stdout="扫描件原文".encode("utf-8"))

    monkeypatch.setattr(pdf_ocr.subprocess, "run", recognize)
    assert [(block.page, block.text) for block in pdf_parser.extract_blocks(path)] == [
        (1, "Existing text layer"), (2, "扫描件原文"),
    ]


def test_pdf_parser_detects_headings_by_font_size(tmp_path):
    # 用 reportlab 生成「标题 16pt / 正文 10pt / 子标题 13pt」的 PDF，
    # 验证字号众数（10.0）作为正文基准、明显更大的行判为 heading 并按档位推 level（本章 3.1 P1-6）。
    """验证 `headings` 等于 `[(1, 'Chapter 1 Overview'), (2, '1.1 Background')]`，且 `any(('normal body paragraph' in b.text for b in paragraphs))` 成立。"""
    from reportlab.pdfgen import canvas
    from reportlab.lib.pagesizes import A4

    path = str(tmp_path / "heading_sample.pdf")
    c = canvas.Canvas(path, pagesize=A4)
    c.setFont("Helvetica", 16)
    c.drawString(72, 720, "Chapter 1 Overview")
    c.setFont("Helvetica", 10)
    for i in range(8):
        c.drawString(72, 690 - i * 15, "This is a normal body paragraph line.")
    c.setFont("Helvetica", 13)
    c.drawString(72, 550, "1.1 Background")
    c.setFont("Helvetica", 10)
    c.drawString(72, 520, "Body text continues here.")
    c.save()

    blocks = pdf_parser.extract_blocks(path)
    headings = [(b.level, b.text) for b in blocks if b.type == "heading"]
    assert headings == [(1, "Chapter 1 Overview"), (2, "1.1 Background")]
    # 正文行仍应为 paragraph，不被误判
    paragraphs = [b for b in blocks if b.type == "paragraph"]
    assert any("normal body paragraph" in b.text for b in paragraphs)
