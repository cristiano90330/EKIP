"""将 DOCX 正文、标题和表格按文档顺序转为中间结构块。"""

import re
from typing import List

from docx import Document
from docx.table import Table

from ai.app.models.cancellation import check_cancelled
from ai.app.parser.blocks import Block, BlockType


def extract_blocks(path: str) -> List[Block]:
    """接收 DOCX 文件路径，按文档节点顺序提取正文、标题和表格，返回供统一切分的结构块。

    上游为 `ai.app.parser.pipeline._parse_by_ext`，下游调用 `ai.app.parser.blocks.Block`。
    """
    doc = Document(path)
    blocks: List[Block] = []
    table_seq = 0
    # 顶层段落和表格共用正文顺序，表格随其前面的标题进入同一章节。
    for item in doc.iter_inner_content():
        check_cancelled()
        if isinstance(item, Table):
            table_seq += 1
            rows = [[c.text.strip() for c in row.cells] for row in item.rows]
            blocks.append(Block(type=BlockType.TABLE, text="", table_id=str(table_seq), rows=rows))
            continue
        para = item
        t = para.text.strip()
        if not t:
            continue
        name = para.style.name if para.style else ""
        m = None
        for key in ("Heading", "标题"):
            m = re.search(rf"{key}\s*(\d+)", name)
            if m:
                break
        if m:
            blocks.append(Block(type=BlockType.HEADING, text=t, level=int(m.group(1))))
        else:
            blocks.append(Block(type=BlockType.PARAGRAPH, text=t))
    return blocks
