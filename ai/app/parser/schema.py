"""定义对象存储解析请求、切片元数据和批量解析响应。"""

from typing import List, Optional

from pydantic import BaseModel

class ParseObjectRequest(BaseModel):
    documentId: int
    objectName: str
    bucketName: str
    fileExtension: str = ""       # 小写无点，如 "pdf"/"md"/"docx"/"xlsx"

class ChunkItem(BaseModel):
    """文档 chunk 的响应契约，包含检索文本及来源元数据。"""
    chunkIndex: int
    content: str
    # jieba 分词后的文本（用空格连接），Java 落库时转 search_vector
    searchText: str = ""
    # 结构化元数据（可空）：来源类型 / 页码 / 标题路径 / 表格 id / 行号
    sourceType: str = "text"
    pageNo: Optional[int] = None
    headingPath: str = ""
    tableId: Optional[str] = None
    rowIndex: Optional[int] = None

class ChunkResult(BaseModel):
    documentId: int
    chunks: List[ChunkItem]
