"""识别 Markdown 标题、段落与表格并转换为统一结构块。"""

import re
from ai.app.parser.blocks import Block, BlockType
from typing import List

def _is_separator_row(cells: List[str]) -> bool:
    """根据表格分隔符识别 Markdown 对齐行，供解析逻辑区别表头和正文。

    上游为 `extract_blocks`。
    """
    for c in cells:
        cs = c.strip()
        if cs and not re.fullmatch(r":?-{3,}:?", cs):
            return False
    return True

def extract_blocks(text: str) -> List[Block]:
    """将 Markdown 文本的标题、段落和表格按出现顺序转换为结构块。

    上游为 `ai.app.parser.pipeline._parse_by_ext`，下游调用 `ai.app.parser.blocks.Block`、`_is_separator_row`。
    """
    blocks: List[Block] = []
    for line in text.splitlines():
        s = line.strip()
        if not s:
            continue
        m = re.match(r"^(#{1,6})\s+(.*)$", s)
        if m:
            blocks.append(Block(type=BlockType.HEADING, text=m.group(2), level=len(m.group(1))))
        elif s.startswith("|") and s.endswith("|"):
            cells = [c.strip() for c in s.strip("|").split("|")]
            (blocks[-1].rows if blocks and blocks[-1].type == BlockType.TABLE else blocks.append(Block(type=BlockType.TABLE, rows=[])))
            if blocks[-1].type == BlockType.TABLE:
                if _is_separator_row(cells):
                    continue
                blocks[-1].rows.append(cells)
        elif s.startswith(("-", "*", "+")) or re.match(r"^\d+[.)]", s):
            if blocks and blocks[-1].type == BlockType.LIST:
                blocks[-1].text += "\n" + s
            else:
                blocks.append(Block(type=BlockType.LIST, text=s))
        else:
            blocks.append(Block(type=BlockType.PARAGRAPH, text=s))
    return blocks
