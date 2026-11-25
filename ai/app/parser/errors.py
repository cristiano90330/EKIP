"""定义文档输入与 PDF OCR 失败的类型，供解析路由映射 HTTP 状态。"""


class DocumentInputError(Exception):
    """无需重试的文档输入错误，消息可安全用于 HTTP 422 响应。"""


class PdfOcrUnavailable(Exception):
    """扫描页 OCR 暂不可用或超时。"""
