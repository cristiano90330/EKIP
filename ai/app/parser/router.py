"""接收 Java 的文档解析请求，下载对象并返回带来源位置的切片。"""

import logging
import os
import tempfile

from fastapi import APIRouter, HTTPException

from ai.app.config import CHUNK_OVERLAP, CHUNK_SIZE
from ai.app.llm.telemetry import get_request_id
from ai.app.parser.errors import DocumentInputError, PdfOcrUnavailable
from ai.app.parser.pipeline import parse_local_document
from ai.app.parser.schema import ParseObjectRequest
from ai.app.service.minio_service import MinioService

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/parser", tags=["parser"])
minio_service = MinioService()


def _object_flow(request: ParseObjectRequest):
    """下载原文件并返回包含文档 ID 与切片的字典。

    请求提供文档 ID、桶名、对象名和扩展名。
    上游由 parse_object 与 parse_pdf_object 调用，Java 主入口为 AiServiceClient.parseDocument。
    下游由 MinioService.download_file 获取文件，交给 parser/pipeline.py 的 parse_local_document。
    下载和 OCR 故障以 503 反馈，文档输入问题映射为 422。
    """
    temp_path = None
    try:
        # 先拿到临时路径并关闭句柄，让下载器和各格式解析器依次按路径读取。
        with tempfile.NamedTemporaryFile(suffix=f".{request.fileExtension}", delete=False) as temp:
            temp_path = temp.name

        try:
            minio_service.download_file(request.bucketName, request.objectName, temp_path)
        except Exception as exc:
            logger.exception(
                "文档下载失败 document_id=%s source=%s request_id=%s",
                request.documentId, request.objectName, get_request_id(),
            )
            raise HTTPException(status_code=503, detail="对象存储不可达，无法下载文档") from exc

        try:
            # 下载成功后再进入格式解析。两类失败使用不同 HTTP 状态，便于 Java 判断是否需要重试。
            chunks = parse_local_document(
                temp_path,
                request.fileExtension,
                chunk_size=CHUNK_SIZE,
                chunk_overlap=CHUNK_OVERLAP,
                source=request.objectName,
                document_id=request.documentId,
            )
        except DocumentInputError as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from exc
        except PdfOcrUnavailable as exc:
            raise HTTPException(status_code=503, detail=str(exc)) from exc

        return {"documentId": request.documentId, "chunks": chunks}
    finally:
        # 原文件只作为本次请求的中间文件，返回或异常退出都需要清理。
        if temp_path and os.path.exists(temp_path):
            os.remove(temp_path)


# 将 AiServiceClient.parseDocument 提交的对象请求交给 _object_flow，返回原文件解析切片。
@router.post("/object")
def parse_object(request: ParseObjectRequest):
    return _object_flow(request)


# 为 PDF 专用入口确定解析扩展名后调用 _object_flow，返回与通用入口一致的切片结构。
@router.post("/pdf/object")
def parse_pdf_object(request: ParseObjectRequest):
    request.fileExtension = "pdf"
    return _object_flow(request)
