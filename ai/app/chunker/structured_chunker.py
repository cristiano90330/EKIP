"""按标题、正文和表格结构生成切片，保留页码、标题路径及表格定位。"""

from typing import Iterator, List, Optional, Tuple

from ai.app.parser.blocks import Block, BlockType
from ai.app.keyword.segmenter import segment
from ai.app.models.cancellation import check_cancelled


def _serialize_table(rows: List[List[str]]) -> str:
    """将表格单元格拼为可进入切片正文的文本，供 chunk_blocks 处理表格行。"""
    return "\n".join(" | ".join(str(c) for c in r) for r in rows)


def _seg_text(text: str) -> str:
    """对切片正文做中文分词，返回供 PostgreSQL 索引的搜索词文本。

    上游为 `emit`，下游调用 `ai.app.keyword.segmenter.segment`。
    """
    return " ".join(segment(text)[0])


def _split_text(text: str, size: int) -> Iterator[str]:
    """以指定字符长度依次产出超长正文片段，供结构切片器处理单块超预算情形。

    上游为 `chunk_blocks`。
    """
    for start in range(0, len(text), size):
        yield text[start:start + size]


# 按结构边界和长度预算切分解析块，返回正文及定位字段供 parser.pipeline 入库。
# 上游为 `ai.app.parser.pipeline.parse_local_document`，下游调用 `flush`、`emit`。
def chunk_blocks(blocks: List[Block], chunk_size: int = 800, chunk_overlap: int = 100) -> List[dict]:
    """标题层级感知的块切分。

    遍历 Block 列表：
    - heading：先冲刷当前节的累积文本（保证标题切换时干净落盘、不跨节混合），
      再按 level 维护标题栈（弹出层级 >= 当前标题层级，再压入新标题）。
    - paragraph/list：仅在同页、同类型内累积；长段落使用有界滑动窗口。
    - table：先冲刷文本段，再逐行切块；长行分片，短表头在各片中重复。
    """
    if chunk_size <= 0 or not 0 <= chunk_overlap < chunk_size:
        raise ValueError("chunk_size 必须大于 0，且 0 <= chunk_overlap < chunk_size")

    stack: List[Tuple[int, str]] = []      # 标题栈，存 (level, text)；用栈顶实际层级而非 len(stack)
    out: List[dict] = []
    pending = ""                           # 当前节累积的文本
    pending_page: Optional[int] = None
    pending_type = "text"                  # 当前节文本类型：text | list

    def heading_path() -> str:
        """读取当前标题栈并拼接其路径，供 emit 写入切片的来源定位字段。"""
        return " > ".join(text for _, text in stack)

    def emit(content: str, source_type: str, page: Optional[int],
             table_id: Optional[str] = None, row_index: Optional[int] = None) -> None:
        """依据正文、来源、页码和表格位置构造入库记录，并把结果追加到切片列表。

        上游为 `chunk_blocks`，下游调用 `_seg_text`、`heading_path`。
        """
        if not content:
            return
        check_cancelled()
        out.append({
            "chunkIndex": len(out),
            "content": content,
            "searchText": _seg_text(content),
            "sourceType": source_type,
            "pageNo": page,
            "headingPath": heading_path(),
            "tableId": table_id,
            "rowIndex": row_index,
        })

    def flush(keep_tail: bool = False) -> None:
        """提交当前缓存的切片内容，可按配置保留适合下一切片的正文尾部。

        上游为 `chunk_blocks`，下游调用 `emit`。
        """
        nonlocal pending
        emit(pending, pending_type, pending_page)
        pending = pending[-chunk_overlap:] if keep_tail and chunk_overlap else ""

    for b in blocks:
        check_cancelled()
        if b.type == BlockType.HEADING:
            # 标题切换：先冲刷当前节文本（不保留尾部，干净地切到下一节）
            flush(keep_tail=False)
            # 使用实际标题层级退栈，跳级后的同级标题仍是兄弟关系。
            while stack and stack[-1][0] >= b.level:
                stack.pop()
            stack.append((b.level, b.text))
        elif b.type in (BlockType.PARAGRAPH, BlockType.LIST):
            chunk_text = b.text.strip()
            if not chunk_text:
                continue
            source_type = "text" if b.type == BlockType.PARAGRAPH else "list"
            if pending and (pending_page != b.page or pending_type != source_type):
                flush()
            pending_type = source_type
            pending_page = b.page

            candidate = pending + "\n\n" + chunk_text if pending else chunk_text
            if len(candidate) <= chunk_size:
                pending = candidate
                continue

            # 同来源溢出时保留可容纳的跨段 overlap；长段落自身按窗口切分。
            flush(keep_tail=True)
            candidate = pending + "\n\n" + chunk_text if pending else chunk_text
            if len(candidate) <= chunk_size:
                pending = candidate
            elif len(chunk_text) <= chunk_size:
                pending = chunk_text
            else:
                start = 0
                step = chunk_size - chunk_overlap
                while len(chunk_text) - start > chunk_size:
                    emit(chunk_text[start:start + chunk_size], pending_type, pending_page)
                    start += step
                pending = chunk_text[start:]
        elif b.type == BlockType.TABLE:
            # 表格：先冲刷前面的文本段，干净地切到表格
            flush(keep_tail=False)
            rows = b.rows or []
            header = rows[0] if rows else []
            body = rows[1:] if rows else []
            header_text = _serialize_table([header])
            prefix = header_text + "\n" if header_text else ""
            row_budget = chunk_size - len(prefix)
            for row_index, row in enumerate(body, start=2):
                row_text = _serialize_table([row])
                content = _serialize_table([header, row])
                if len(content) <= chunk_size:
                    emit(content, "table", b.page, b.table_id, row_index)
                elif row_budget >= (chunk_size + 1) // 2:
                    # 至少给数据保留一半预算，避免长表头挤占每个片段的正文。
                    for part in _split_text(row_text, row_budget):
                        emit(prefix + part, "table", b.page, b.table_id, row_index)
                else:
                    # 超宽表头与当前数据行整体有序分片，不混入下一行、不丢内容。
                    for part in _split_text(content, chunk_size):
                        emit(part, "table", b.page, b.table_id, row_index)

    # 末尾冲刷残留文本
    flush()
    return out
