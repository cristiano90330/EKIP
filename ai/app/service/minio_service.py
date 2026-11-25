"""使用应用配置连接 MinIO，将指定桶和对象下载到本地路径。"""

from urllib3 import PoolManager, Timeout

from minio import Minio

from ai.app.config import (MINIO_ENDPOINT, MINIO_ACCESS_KEY, MINIO_SECRET_KEY)

class MinioService:
    """通过 MinIO 下载待解析文档对象。"""

    def __init__(self):
        # 限制建连和读取时间，避免对象下载长期占用解析请求。
        """根据应用配置初始化 MinIO 客户端，供解析路由读取私有对象。"""
        http_client = PoolManager(timeout=Timeout(connect=5, read=60))

        self.client = Minio(
            MINIO_ENDPOINT,
            access_key=MINIO_ACCESS_KEY,
            secret_key=MINIO_SECRET_KEY,
            secure=False,
            http_client=http_client)

    def download_file(self, bucket_name: str, object_name: str, file_path: str):

        """用桶名及对象名从 MinIO 下载原始数据到指定本地路径，供格式解析器读取。"""
        self.client.fget_object(
            bucket_name,
            object_name,
            file_path)
