"""定义标题、段落和表格等中间结构，并清理入库前不可写入的空字符。"""

import logging
from dataclasses import dataclass
from enum import Enum
from typing import List, Optional

logger = logging.getLogger(__name__)

# PostgreSQL text 列不接受 NUL；解析流程在切块前统一移除该字符。
_NUL = "\x00"


class BlockType(str, Enum):
    """解析器识别的结构化块类型。"""
    HEADING = "heading"        # 标题
    PARAGRAPH = "paragraph"    # 正文段落
    TABLE = "table"            # 表格
    LIST = "list"              # 列表


@dataclass
class Block:
    type: BlockType                            # heading | paragraph | table | list
    text: str = ""
    level: int = 0                              # heading 层级 1..6；非标题=0
    page: Optional[int] = None                  # PDF 页码；非 PDF=None
    table_id: Optional[str] = None              # Excel=sheet 名 / PDF=p{page}-t{n} / DOCX=表序号
    rows: Optional[List[List[str]]] = None      # 仅 table：各行（首行=表头）


def _strip_nul(value: str):
    """删除文本中的空字符并记录移除数量，供结构块清洗统计。

    上游为 `strip_nul_from_blocks`。
    """
    if _NUL in value:
        return value.replace(_NUL, ""), value.count(_NUL)
    return value, 0


# 在块正文和表格单元格中移除不可存储的字符，原地更新块并返回移除总数。
# 上游为 `ai.app.parser.pipeline.parse_local_document`，下游调用 `_strip_nul`。
def strip_nul_from_blocks(blocks: List[Block], source: str = "") -> int:
    """在切块前清洗 Block 文本中的 NUL 并返回移除数量。

    仅处理字符串中的 NUL，不规范化空白或转换非字符串单元格；发现后记录来源与数量。
    """
    removed = 0
    for b in blocks:
        if b.text:
            b.text, n = _strip_nul(b.text)
            removed += n
        if b.rows:
            for row in b.rows:
                for i, cell in enumerate(row):
                    if isinstance(cell, str):
                        row[i], n = _strip_nul(cell)
                        removed += n
    if removed:
        logger.warning(
            "检测到 NUL(\\x00) 字符并已在解析出口剥离：source=%s，剥离字符数=%d"
            "（PG text 不接受 NUL，剥离前整篇 chunk 都会写库失败）",
            source, removed,
        )
    return removed
