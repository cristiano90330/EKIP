"""读取 TXT、CSV 和 JSON，分别转换为可切分的段落或表格。"""

import csv
import io
import json
import re
from typing import List

from ai.app.parser.blocks import Block, BlockType
from ai.app.parser.errors import DocumentInputError

# 依次尝试；GB18030 覆盖 GBK/GB2312，是 Windows 中文环境最常见的落盘编码。
_TEXT_ENCODINGS = ("utf-8-sig", "utf-8", "gb18030")

# JSON 转表格的上限：超过后只保留缩进文本，避免一张大表把单个块撑爆。
_MAX_TABLE_ROWS = 2000

_LIST_PREFIX = re.compile(r"^\s*(?:[-*+]|\d+[.)])\s+")


def _read_text(path: str) -> str:
    """按可用编码读取文件正文，为 TXT 和 JSON 的解析提供 Unicode 文本。

    上游为 `extract_txt_blocks`、`extract_csv_blocks`。
    """
    with open(path, "rb") as handle:
        raw = handle.read()
    for encoding in _TEXT_ENCODINGS:
        try:
            return raw.decode(encoding)
        except UnicodeDecodeError:
            continue
    raise DocumentInputError("文本编码无法识别（已尝试 UTF-8 / GB18030）")


def extract_txt_blocks(path: str) -> List[Block]:
    """将纯文本按段落划分，返回可进入统一切分的结构块。

    上游为 `ai.app.parser.pipeline._parse_by_ext`，下游调用 `_read_text`、`flush_paragraph`。
    """
    blocks: List[Block] = []
    paragraph: List[str] = []

    def flush_paragraph() -> None:
        """将缓存的当前段落追加到结果并清空缓存，供 extract_txt_blocks 处理段落边界。

        下游调用 `ai.app.parser.blocks.Block`。
        """
        if paragraph:
            blocks.append(Block(type=BlockType.PARAGRAPH, text="\n".join(paragraph)))
            paragraph.clear()

    for line in _read_text(path).splitlines():
        stripped = line.strip()
        if not stripped:
            flush_paragraph()
            continue
        if _LIST_PREFIX.match(stripped):
            flush_paragraph()
            if blocks and blocks[-1].type == BlockType.LIST:
                blocks[-1].text += "\n" + stripped
            else:
                blocks.append(Block(type=BlockType.LIST, text=stripped))
            continue
        # 缩进的续行属于上一项；顶格的新行结束列表（与 Markdown 的阅读直觉一致）。
        if blocks and blocks[-1].type == BlockType.LIST and line[:1].isspace():
            blocks[-1].text += "\n" + stripped
            continue
        paragraph.append(stripped)
    flush_paragraph()
    return blocks


def extract_csv_blocks(path: str) -> List[Block]:
    """将 CSV 的有效记录转换为表格块，供后续生成带表格来源的切片。

    上游为 `ai.app.parser.pipeline._parse_by_ext`，下游调用 `_read_text`、`ai.app.parser.blocks.Block`。
    """
    text = _read_text(path)
    if not text.strip():
        return []
    try:
        dialect = csv.Sniffer().sniff(text[:4096], delimiters=",;\t|")
    except csv.Error:
        dialect = csv.excel
    rows = [
        [(cell if cell is not None else "").strip() for cell in row]
        for row in csv.reader(io.StringIO(text), dialect)
    ]
    rows = [row for row in rows if any(cell for cell in row)]
    if not rows:
        return []
    return [Block(type=BlockType.TABLE, text="", table_id="csv", rows=rows)]


def _json_table(payload) -> List[List[str]] | None:
    """将可解释为记录集合的 JSON 数据转换为表格行，其他形态返回 None。

    上游为 `extract_json_blocks`。
    """
    if not isinstance(payload, list) or not payload or len(payload) > _MAX_TABLE_ROWS:
        return None
    if not all(isinstance(item, dict) for item in payload):
        return None
    header = list(payload[0].keys())
    if not header or any(list(item.keys()) != header for item in payload):
        return None
    rows: List[List[str]] = [header]
    for item in payload:
        rows.append(["" if item[key] is None else str(item[key]) for key in header])
    return rows


def extract_json_blocks(path: str) -> List[Block]:
    """解析 JSON 文件，将结构化集合转换为表格，否则按正文提取内容块。

    上游为 `ai.app.parser.pipeline._parse_by_ext`，下游调用 `_read_text`、`_json_table`。
    """
    text = _read_text(path)
    try:
        payload = json.loads(text)
    except json.JSONDecodeError as exc:
        raise DocumentInputError(f"JSON 解析失败：{exc.msg}（第 {exc.lineno} 行）") from exc
    table = _json_table(payload)
    if table:
        return [Block(type=BlockType.TABLE, text="", table_id="json", rows=table)]
    return [Block(type=BlockType.PARAGRAPH, text=json.dumps(payload, ensure_ascii=False, indent=2))]
