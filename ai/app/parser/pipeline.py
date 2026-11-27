"""按扩展名选择解析器，将结构块清洗、切分并补充分词和来源字段。"""

import csv
import logging
import shutil
from time import perf_counter
from xml.etree.ElementTree import ParseError
from zipfile import BadZipFile

from docx.opc.exceptions import PackageNotFoundError
from docx.oxml.exceptions import InvalidXmlError
from lxml.etree import XMLSyntaxError
from openpyxl.utils.exceptions import InvalidFileException
from pdfminer.psexceptions import PSException
from pdfplumber.utils.exceptions import MalformedPDFException, PdfminerException
from xlrd import XLRDError
from xlrd.compdoc import CompDocError

from ai.app.chunker.structured_chunker import chunk_blocks
from ai.app.llm.telemetry import get_request_id
from ai.app.models.cancellation import check_cancelled
from ai.app.parser import (
    docx_parser, epub_parser, excel_parser, md_parser, pdf_parser, pptx_parser,
    text_parser, xmind_parser,
)
from ai.app.parser import legacy_office
from ai.app.parser.blocks import strip_nul_from_blocks
from ai.app.parser.errors import DocumentInputError

logger = logging.getLogger(__name__)

_FORMAT_ERRORS = (
    BadZipFile,
    PackageNotFoundError,
    InvalidXmlError,
    XMLSyntaxError,
    ParseError,
    InvalidFileException,
    PSException,
    MalformedPDFException,
    UnicodeDecodeError,
)


def _parse_by_ext(extension: str, path: str):
    """按扩展名将文件路径交给对应格式解析器，返回统一的结构块列表。

    上游为 `parse_local_document`，下游调用 `ai.app.parser.pdf_parser.extract_blocks`、`ai.app.parser.md_parser.extract_blocks`。
    """
    check_cancelled()
    if extension == "pdf":
        return pdf_parser.extract_blocks(path)
    if extension in ("md", "markdown"):
        with open(path, "r", encoding="utf-8") as source_file:
            return md_parser.extract_blocks(source_file.read())
    if extension == "docx":
        return docx_parser.extract_blocks(path)
    if extension == "pptx":
        return pptx_parser.extract_blocks(path)
    if extension in ("xlsx", "xls"):
        return excel_parser.extract_blocks(path, extension)
    if extension == "epub":
        return epub_parser.extract_blocks(path)
    if extension == "xmind":
        return xmind_parser.extract_blocks(path)
    if extension == "csv":
        return text_parser.extract_csv_blocks(path)
    if extension == "json":
        return text_parser.extract_json_blocks(path)
    if extension in ("txt", "text"):
        return text_parser.extract_txt_blocks(path)
    if extension in ("doc", "ppt"):
        # 旧版二进制：能转则转到 OOXML 再解析，不能转由 legacy_office 给明确原因。
        target = "docx" if extension == "doc" else "pptx"
        if not legacy_office.is_legacy_binary(path):
            # 扩展名是 .doc/.ppt 但文件其实是 OOXML（常见于只改后缀）→ 按真实格式解析。
            return _parse_by_ext(target, path)
        converted, workdir = legacy_office.convert_legacy(path, extension)
        try:
            return _parse_by_ext(target, converted)
        finally:
            shutil.rmtree(workdir, ignore_errors=True)
    raise DocumentInputError(f"不支持的文档类型: {extension}")


_CONTAINER_ERRORS = (BadZipFile, PackageNotFoundError, InvalidXmlError, XMLSyntaxError, ParseError)


# 判断格式与解析异常是否构成已知的内容错误，供 parse_local_document 决定是否转换为输入异常。
def _is_known_format_error(extension: str, exc: Exception) -> bool:
    """判断异常是否属于「文件本身格式不对」（应判输入错误），而不是程序缺陷。

    docx / xlsx 保留原有按文案精确匹配的做法：这两个库的 ValueError/KeyError 语义很杂，
    一律当成格式错误会把程序缺陷误报成「坏文件」。pptx / epub / xmind 都是容器格式，
    结构错误（缺条目、坏 XML、坏 zip）只可能来自文件本身，可以整体按输入错误处理。
    """
    if extension not in ("docx", "xlsx", "xls", "pptx", "epub", "xmind"):
        return False
    if extension in ("pptx", "epub", "xmind"):
        return isinstance(exc, _CONTAINER_ERRORS + (KeyError, ValueError))
    message = str(exc)
    if isinstance(exc, KeyError):
        return len(exc.args) == 1 and str(exc.args[0]).startswith("There is no item named ")
    if extension == "docx" and isinstance(exc, ValueError):
        return message.startswith("file '") and "is not a Word file, content type is" in message
    if extension in ("xlsx", "xls"):
        if isinstance(exc, ValueError):
            return message.startswith("Unable to read workbook: could not ")
        if isinstance(exc, OSError):
            return message == "File contains no valid workbook part"
    return False


def parse_local_document(
    path: str,
    extension: str,
    *,
    chunk_size: int,
    chunk_overlap: int,
    source: str = "",
    document_id: int | None = None,
) -> list[dict]:
    """接收本地文件路径、格式及切片预算，解析并清洗结构块，返回带正文、分词和定位字段的切片。

    上游为 `ai.app.parser.router._object_flow`，下游调用 `_parse_by_ext`、`_is_known_format_error`。
    """
    request_id = get_request_id()
    started = perf_counter()
    blocks = None
    try:
        blocks = _parse_by_ext(extension, path)
    except (PdfminerException, *_FORMAT_ERRORS) as exc:
        # pdfplumber 会包装所有底层异常；未知程序错误不能因此变成坏文件。
        if isinstance(exc, PdfminerException):
            original = exc.args[0] if exc.args else exc.__context__
            if not isinstance(original, _FORMAT_ERRORS):
                raise
        logger.exception(
            "文档格式解析失败 document_id=%s source=%s ext=%s request_id=%s",
            document_id, source, extension, request_id,
        )
        raise DocumentInputError("文档解析失败：文件损坏、编码错误或内容格式不受支持") from exc
    except (XLRDError, CompDocError, csv.Error) as exc:
        # xlrd 的 BIFF / OLE2 结构错误（CompDocError 不继承 XLRDError）与 csv 方言错误
        # 都不属于 ValueError 家族，单独归一成输入错误。
        logger.exception(
            "文档格式解析失败 document_id=%s source=%s ext=%s request_id=%s",
            document_id, source, extension, request_id,
        )
        raise DocumentInputError("文档解析失败：文件损坏或内容格式不符") from exc
    except (ValueError, KeyError, OSError) as exc:
        if not _is_known_format_error(extension, exc):
            raise
        logger.exception(
            "文档格式解析失败 document_id=%s source=%s ext=%s request_id=%s",
            document_id, source, extension, request_id,
        )
        raise DocumentInputError("文档解析失败：文件损坏或内容格式不符") from exc
    finally:
        logger.info(
            "document_stage=extract document_id=%s source=%s ext=%s elapsed_ms=%.2f blocks=%s outcome=%s request_id=%s",
            document_id, source, extension, (perf_counter() - started) * 1000,
            len(blocks) if blocks is not None else 0,
            "success" if blocks is not None else "error", request_id,
        )

    started = perf_counter()
    removed = None
    try:
        check_cancelled()
        removed = strip_nul_from_blocks(blocks, source=source)
    finally:
        logger.info(
            "document_stage=clean document_id=%s source=%s elapsed_ms=%.2f blocks=%d removed_nul=%s outcome=%s request_id=%s",
            document_id, source, (perf_counter() - started) * 1000, len(blocks), removed,
            "success" if removed is not None else "error", request_id,
        )

    started = perf_counter()
    chunks = None
    try:
        check_cancelled()
        chunks = chunk_blocks(blocks, chunk_size=chunk_size, chunk_overlap=chunk_overlap)
        check_cancelled()
        if not chunks:
            raise DocumentInputError("文档解析失败: 未提取到文本")
    finally:
        logger.info(
            "document_stage=chunk document_id=%s source=%s elapsed_ms=%.2f blocks=%d chunks=%d outcome=%s request_id=%s",
            document_id, source, (perf_counter() - started) * 1000, len(blocks),
            len(chunks) if chunks is not None else 0, "success" if chunks else "error", request_id,
        )
    return chunks
