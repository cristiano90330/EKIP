"""从 PDF 提取带页码的标题、段落和表格，为扫描页调用 OCR。"""

import statistics
from collections import Counter
from time import monotonic
from typing import List, Optional, Tuple

import pdfplumber

from ai.app.models.cancellation import check_cancelled
from ai.app.parser.blocks import Block, BlockType
from ai.app.parser.errors import DocumentInputError
from ai.app.parser.pdf_ocr import OCR_DOCUMENT_BUDGET_S, OCR_MAX_SCAN_PAGES, extract_scan_text


def _in_bbox(p: Tuple[float, float], bbox: Tuple[float, float, float, float]) -> bool:
    """判断提取坐标是否落在表格边界内，供正文提取排除表格内容。

    上游为 `extract_blocks`。
    """
    x0, top, x1, bottom = bbox
    return x0 <= p[0] <= x1 and top <= p[1] <= bottom


# 统计文件各页正文候选的字体大小，返回标题识别使用的基准字号。
# 上游为 `extract_blocks`。
def _body_size(words_by_page: List[List[dict]]) -> float:
    """以全文档字号众数估算正文字号。

    标题/页眉/图表标签等小众字号不会成为众数；用 round(size, 1) 聚合浮点噪声。
    Counter 平票取第一个（与 statistics.mode 不同，不抛 StatisticsError）。
    """
    counter = Counter(
        round(w["size"], 1)
        for page_words in words_by_page
        for w in page_words
        if w.get("size")
    )
    return counter.most_common(1)[0][0] if counter else 0.0


def _heading_level(line_size: float, body_size: float) -> Optional[int]:
    """根据行字号和正文基准判定标题层级，普通正文返回 None。

    上游为 `extract_blocks`。
    """
    if body_size <= 0:
        return None
    ratio = line_size / body_size
    if ratio >= 1.5:
        return 1
    if ratio >= 1.3:
        return 2
    if ratio >= 1.15:
        return 3
    return None


def _is_scan(page, words: List[dict]) -> bool:
    """依据页面文字和图像特征识别需要 OCR 的页面，供 extract_blocks 选择提取路径。"""
    if not words:
        return bool(page.images)
    # A printed page number alone is common on scans with a digital footer.
    if len(words) != 1 or not words[0]["text"].isdecimal():
        return False
    return any((img["x1"] - img["x0"]) * (img["bottom"] - img["top"])
               >= 0.5 * page.width * page.height for img in page.images)


def extract_blocks(path: str) -> List[Block]:
    """读取 PDF 的文字层、表格或扫描 OCR 结果，返回按页排序且带定位信息的结构块。

    上游为 `ai.app.parser.pipeline._parse_by_ext`，下游调用 `_is_scan`、`_in_bbox`。
    """
    blocks: List[Block] = []
    ocr_deadline = monotonic() + OCR_DOCUMENT_BUDGET_S
    with pdfplumber.open(path) as pdf:
        # 第一遍：收集每页的表格与段落词（跳过表格 bbox 内的词），用于全文档字号统计
        pages: List[Tuple[int, list, List[dict]]] = []
        scan_pages: set[int] = set()
        for pno, page in enumerate(pdf.pages, 1):
            check_cancelled()
            page_words = page.extract_words(extra_attrs=["size"])
            if _is_scan(page, page_words):
                scan_pages.add(pno)
                pages.append((pno, [], []))
                continue
            tables = page.find_tables()
            table_bboxes = [t.bbox for t in tables]
            # 请求字号属性供标题层级推断使用。
            words = [w for w in page_words
                     if not any(_in_bbox(((w["x0"] + w["x1"]) / 2, (w["top"] + w["bottom"]) / 2), bb) for bb in table_bboxes)]
            words.sort(key=lambda w: (w["top"], w["x0"]))
            pages.append((pno, tables, words))
        if len(scan_pages) > OCR_MAX_SCAN_PAGES:
            raise DocumentInputError(f"PDF 含超过 {OCR_MAX_SCAN_PAGES} 个扫描页，请分拆后上传")
        body_size = _body_size([ws for _, _, ws in pages])
        # 第二遍：按行识别标题/段落，再将文字与表格按页内位置合并。
        for pno, tables, words in pages:
            check_cancelled()
            if pno in scan_pages:
                text = extract_scan_text(pdf.pages[pno - 1], ocr_deadline)
                if text:
                    blocks.append(Block(type=BlockType.PARAGRAPH, text=text, page=pno))
                continue
            positioned_blocks: List[Tuple[float, float, Block]] = []
            # 单栏页从上到下排列，表格编号每页从 1 开始。
            for seq, t in enumerate(sorted(tables, key=lambda t: (t.bbox[1], t.bbox[0])), 1):
                check_cancelled()
                rows = [[(c or "").strip() for c in r] for r in t.extract()]
                positioned_blocks.append((t.bbox[1], t.bbox[0],
                                          Block(type=BlockType.TABLE, text="", page=pno,
                                                table_id=f"p{pno}-t{seq}", rows=rows)))
            lines: List[List[dict]] = []
            line_top = None
            for w in words:
                if line_top is None or abs(w["top"] - line_top) > 3.0:
                    lines.append([w])
                    line_top = w["top"]
                else:
                    lines[-1].append(w)
            for line in lines:
                line.sort(key=lambda w: w["x0"])
                s = " ".join(w["text"] for w in line).strip()
                if not s:
                    continue
                line_sizes = [w["size"] for w in line if w.get("size")]
                level = _heading_level(statistics.mean(line_sizes), body_size) if line_sizes else None
                if level:
                    block = Block(type=BlockType.HEADING, text=s, level=level, page=pno)
                else:
                    block = Block(type=BlockType.PARAGRAPH, text=s, page=pno)
                positioned_blocks.append((min(w["top"] for w in line), line[0]["x0"], block))
            blocks.extend(block for _, _, block in sorted(positioned_blocks, key=lambda item: item[:2]))
    return blocks
