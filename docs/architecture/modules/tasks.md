# 文档任务

Java 负责解析任务的投递、执行、恢复、终态写入与状态通知。

## 源码与契约入口

- 任务入口与文档处理 [DocumentTaskService.java](../../../knowledge-platform/src/main/java/com/pony/knowledge/service/DocumentTaskService.java)、[DocumentProcessServiceImpl.java](../../../knowledge-platform/src/main/java/com/pony/knowledge/service/impl/DocumentProcessServiceImpl.java)。
- 投递、完成与恢复 [task/](../../../knowledge-platform/src/main/java/com/pony/knowledge/service/task/)、[TaskReliabilityProperties.java](../../../knowledge-platform/src/main/java/com/pony/knowledge/config/TaskReliabilityProperties.java)。
- 任务测试 [task/](../../../knowledge-platform/src/test/java/com/pony/knowledge/service/task/)。

共享 PostgreSQL 和 Redis 的后端可配置 `TASK_STATUS_BROADCAST_ENABLED=true`。同一部署的实例使用相同的 `TASK_STATUS_BROADCAST_CHANNEL`，共用 Redis 的独立部署使用各自的频道。已提交的任务变化只在 Redis 发布文档 ID，收到通知的实例回查快照并推给现有 SSE 订阅。消息是即时通知，掉线后通过任务查询或重连读取最新状态。[跨实例通知](../../../knowledge-platform/src/main/java/com/pony/knowledge/service/task/TaskStatusBroadcast.java)

任务工作节点共享 PostgreSQL 租约。每次认领先锁定文档，然后通过条件更新获取租约并递增累计尝试号。启动扫描投递可认领的 `WAITING` 和 `PROCESSING`，两类任务都遵守重试到期时间。运行期扫描同样识别到期的 `PROCESSING`，并补偿等待过久的 `WAITING`。多个节点可以扫描到同一候选，只有获得有效租约的尝试可以提交切片和终态。[认领与恢复 SQL](../../../knowledge-platform/src/main/java/com/pony/knowledge/mapper/DocumentTaskMapper.java)

默认每 20 秒续租，租期为 120 秒，单次执行硬上限为 10 分钟。租约失效后，下一次扫描或已有投递可接手。自动重试先保存退避到期时间，再安排本地投递；节点退出后，其他节点可在到期后恢复。新的累计尝试号能阻止旧工作线程的结果和旧重试回调覆盖当前状态。

续租确认当前尝试已失效后，持有该尝试的 Java 节点关闭本次解析连接。Python 收到断连后，在下载完成、格式解析、PDF 分页和文本切块等检查点退出，并结束本次所启动的 OCR 或 Office 转换子进程。检测延迟取决于续租调度，第三方阻塞调用可能继续到下一个检查点。数据库的尝试身份校验保护最后的入库边界。 [取消传输](../../../knowledge-platform/src/main/java/com/pony/knowledge/service/ai/DocumentParseCancellation.java) · [解析入口](../../../ai/app/parser/router.py)

首次升级须先停止所有旧版后端工作节点，再应用 `V25` 并启动新版。升级时无租约的旧 `PROCESSING` 任务进入恢复范围。各新版节点使用同一个主库；Redis 广播按页面通知需求另行启用。[租约迁移](../../../knowledge-platform/src/main/resources/db/migration/V25__document_task_leases.sql)
