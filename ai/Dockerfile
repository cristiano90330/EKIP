# 装配 Python AI 服务镜像，提供文档解析工具、运行依赖及单工作进程入口。

FROM python:3.10-slim
WORKDIR /code
RUN apt-get update \
    && apt-get install -y --no-install-recommends tesseract-ocr tesseract-ocr-eng tesseract-ocr-chi-sim \
    && rm -rf /var/lib/apt/lists/*
# 模型经受治理的 HTTP 适配器调用云端或本地服务；此进程不加载模型权重。
COPY requirements.txt /code/requirements.txt
RUN --mount=type=cache,target=/root/.cache/pip \
    pip install -r requirements.txt
COPY . /code/ai
ENV PYTHONPATH=/code PYTHONUTF8=1
EXPOSE 8000
# 本版资源组容量由单进程内协调，固定单 worker。
CMD ["uvicorn", "ai.app.main:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1"]
