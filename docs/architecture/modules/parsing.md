# 文档解析与切分

Java 调用 AI 服务读取原始文件，Python 解析文档并切分文本。

## 源码与契约入口

- Python 解析入口、类型与分派 [router.py](../../../ai/app/parser/router.py)、[schema.py](../../../ai/app/parser/schema.py)、[pipeline.py](../../../ai/app/parser/pipeline.py)。
- 切分与 Java 调用入口 [chunker/](../../../ai/app/chunker/)、[AiServiceClient.java](../../../knowledge-platform/src/main/java/com/pony/knowledge/service/ai/AiServiceClient.java)。
