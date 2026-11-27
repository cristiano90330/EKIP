"""将 XLSX 或 XLS 的工作表提取为表格块，并处理公式缓存与单元格类型。"""

import logging
import os
from typing import List

import xlrd
from openpyxl import load_workbook

from ai.app.models.cancellation import check_cancelled
from ai.app.parser.blocks import Block, BlockType
from ai.app.rag.deadline import QueryDeadlineExceeded

logger = logging.getLogger(__name__)

# 公式缓存探测的开销上限：超过就不再扫第二遍（大表代价高、收益低）。
_PROBE_MAX_BYTES = 5 * 1024 * 1024
_PROBE_MAX_CELLS = 20000


# 检查 XLSX 归档的工作表数据，统计缺少缓存结果的公式单元格。
# 上游为 `extract_blocks`。
def _formula_cells_without_cache(path: str) -> int:
    """统计「有公式」的单元格数量（data_only=False 读到的值以 = 开头）。

    这是诊断信息，不是解析结果：任何异常都返回 0，不能因此让整份文档解析失败。
    """
    try:
        if os.path.getsize(path) > _PROBE_MAX_BYTES:
            return 0
        workbook = load_workbook(path, data_only=False, read_only=True)
    except Exception:                              # noqa: BLE001 — 探测失败不影响主流程
        return 0
    try:
        total = 0
        scanned = 0
        for sheet in workbook.worksheets:
            for row in sheet.iter_rows():
                check_cancelled()
                for cell in row:
                    scanned += 1
                    if scanned > _PROBE_MAX_CELLS:
                        return total
                    value = cell.value
                    if isinstance(value, str) and value.startswith("="):
                        total += 1
        return total
    except QueryDeadlineExceeded:
        raise
    except Exception:                              # noqa: BLE001
        return 0
    finally:
        workbook.close()


def _extract_xlsx(path: str) -> List[Block]:
    # 用文件句柄而不是路径打开：openpyxl 会按**文件名后缀**做格式校验，
    # 而 .xls 后缀 + OOXML 内容（只改后缀）是常见情况，传路径会被直接判成不支持。
    """打开 XLSX 文件并将各工作表转换为表格块，返回供统一切分的结果。

    上游为 `_extract_xls`，下游调用 `ai.app.parser.blocks.Block`。
    """
    with open(path, "rb") as handle:
        workbook = load_workbook(handle, data_only=True, read_only=True)
        try:
            blocks: List[Block] = []
            for sheet in workbook.worksheets:
                rows = []
                for row in sheet.iter_rows():
                    check_cancelled()
                    values = [cell.value if cell.value is not None else "" for cell in row]
                    if any(str(cell).strip() for cell in values):
                        rows.append(values)
                if rows:
                    blocks.append(Block(type=BlockType.TABLE, text="", table_id=sheet.title, rows=rows))
            return blocks
        finally:
            # read_only 模式以 zipfile 持有句柄，必须显式关闭，否则 Windows 上
            # 后续删除临时文件会报 PermissionError (WinError 32)
            workbook.close()


def _xls_cell(book, sheet, row: int, column: int):
    """将指定 XLS 行列的单元格转换为表格可读值，兼顾日期和特殊类型。

    上游为 `_extract_xls`。
    """
    cell = sheet.cell(row, column)
    if cell.ctype == xlrd.XL_CELL_DATE:
        try:
            return str(xlrd.xldate_as_datetime(cell.value, book.datemode))
        except Exception:                          # noqa: BLE001 — 非日期型数值按原值处理
            return cell.value
    if cell.ctype == xlrd.XL_CELL_NUMBER and float(cell.value).is_integer():
        return int(cell.value)
    return cell.value


def _extract_xls(path: str) -> List[Block]:
    # 扩展名是 .xls 但文件其实是 OOXML（只改后缀的情况很常见）→ 按 xlsx 解析，
    # 否则用户会收到「文件损坏」，而文件本身完全正常。
    """读取旧版 XLS 的工作表和单元格并返回表格块。

    上游为 `extract_blocks`，下游调用 `_extract_xlsx`、`_xls_cell`。
    """
    with open(path, "rb") as handle:
        if handle.read(2) == b"PK":
            return _extract_xlsx(path)
    book = xlrd.open_workbook(path)
    blocks: List[Block] = []
    for sheet in book.sheets():
        rows = []
        for row in range(sheet.nrows):
            check_cancelled()
            values = [_xls_cell(book, sheet, row, column) for column in range(sheet.ncols)]
            if any(str(cell).strip() for cell in values):
                rows.append(values)
        if rows:
            blocks.append(Block(type=BlockType.TABLE, text="", table_id=sheet.name, rows=rows))
    return blocks


def extract_blocks(path: str, extension: str = "xlsx") -> List[Block]:
    """根据扩展名选择 XLS 或 XLSX 读取路径，返回按工作表组织的结构块。

    上游为 `ai.app.parser.pipeline._parse_by_ext`，下游调用 `_extract_xls`、`_formula_cells_without_cache`。
    """
    if extension == "xls":
        return _extract_xls(path)

    formulas = _formula_cells_without_cache(path)
    if formulas:
        logger.warning(
            "Excel 含 %d 个公式单元格，按文件保存时的缓存值读取：source=%s。"
            "无缓存值的公式单元格会是空的，需要最新结果请用 Office/WPS 重新计算并保存。",
            formulas, path,
        )
    return _extract_xlsx(path)
