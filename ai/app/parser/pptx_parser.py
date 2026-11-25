"""遍历 PPTX 幻灯片的标题、文本与表格，保留页序和内容顺序。"""

from typing import List

from pptx import Presentation

from ai.app.parser.blocks import Block, BlockType


def _text_of(shape) -> List[tuple[str, int]]:
    """读取幻灯片形状中的文本及层级，供主提取流程按内容位置组织段落。

    上游为 `extract_blocks`。
    """
    lines: List[tuple[str, int]] = []
    for paragraph in shape.text_frame.paragraphs:
        text = "".join(run.text for run in paragraph.runs).strip()
        if text:
            lines.append((text, paragraph.level))
    return lines


def extract_blocks(path: str) -> List[Block]:
    """读取 PPTX 幻灯片的标题、正文和表格，按页返回结构块。

    上游为 `ai.app.parser.pipeline._parse_by_ext`，下游调用 `ai.app.parser.blocks.Block`、`_text_of`。
    """
    presentation = Presentation(path)
    blocks: List[Block] = []
    for slide_no, slide in enumerate(presentation.slides, start=1):
        title_shape = slide.shapes.title
        if title_shape is not None:
            title = "".join(run.text for run in title_shape.text_frame.paragraphs[0].runs).strip() \
                if title_shape.text_frame.paragraphs else ""
            if title:
                blocks.append(Block(type=BlockType.HEADING, text=title, level=2))

        table_seq = 0
        for shape in slide.shapes:
            if shape.has_table:
                table_seq += 1
                rows = [[cell.text.strip() for cell in row.cells] for row in shape.table.rows]
                rows = [row for row in rows if any(row)]
                if rows:
                    blocks.append(Block(type=BlockType.TABLE, text="",
                                        table_id=f"s{slide_no}-t{table_seq}", rows=rows))
                continue
            if not shape.has_text_frame or shape is title_shape:
                continue
            for text, level in _text_of(shape):
                if level > 0:
                    if blocks and blocks[-1].type == BlockType.LIST:
                        blocks[-1].text += "\n" + text
                    else:
                        blocks.append(Block(type=BlockType.LIST, text=text))
                else:
                    blocks.append(Block(type=BlockType.PARAGRAPH, text=text))

        if slide.has_notes_slide:
            notes = (slide.notes_slide.notes_text_frame.text or "").strip()
            if notes:
                blocks.append(Block(type=BlockType.PARAGRAPH, text=notes))
    return blocks
