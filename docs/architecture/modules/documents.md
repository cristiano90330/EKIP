# 文档与文件生命周期

Java 协调上传回执、对象存储、入库和删除。后台任务交给文档任务模块。

## 源码与契约入口

- 文档接口与业务服务 [DocumentController.java](../../../knowledge-platform/src/main/java/com/pony/knowledge/controller/DocumentController.java)、[DocumentServiceImpl.java](../../../knowledge-platform/src/main/java/com/pony/knowledge/service/impl/DocumentServiceImpl.java)。
- 切片写入与数据库结构 [DocumentChunkServiceImpl.java](../../../knowledge-platform/src/main/java/com/pony/knowledge/service/impl/DocumentChunkServiceImpl.java)、[migration/](../../../knowledge-platform/src/main/resources/db/migration/)。
- 相关模块 [文档任务](tasks.md)、[文档解析与切分](parsing.md)、[知识索引与重建](indexes.md)。
