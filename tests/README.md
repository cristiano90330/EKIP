# 测试与验收目录

跨项目验收脚本和共享夹具集中在这里。Java 与 Python 的单元、集成测试按各自工具的标准目录归属，便于直接使用 Maven 和 pytest 运行。

| 范围 | 位置 | 运行方式或说明 |
| --- | --- | --- |
| Java 后端测试 | `knowledge-platform/src/test/java/` | `mvn -f knowledge-platform/pom.xml test` |
| 文档任务隔离契约 | `knowledge-platform/src/test/java/com/pony/knowledge/service/task/` 及关联处理/权限/SSE 用例 | 按涉及的任务契约选择测试类 |
| 四条核心业务贯通 | `tests/e2e/run_core_business_test.py` | 专用 PG / Redis；真实 Java 业务，AI 与文件存储出口使用替身；见下文 |
| 真实 PDF 与解析失败/删除 | `tests/e2e/run_real_document_rag_flow.py` | 专用 PG / Redis / MinIO 与独立 Java / AI；成功场景调用本地 Embedding，失败场景检查原对象和数据边界 |
| Redis Store 契约 | `tests/e2e/run_redis_store_test.py` | 缓存 `redis:7`、随机 loopback 端口、DB15 归属标记；覆盖真实命令/Lua、自然过期、目录 TTL 和跨 JVM 存储协作后按标签销毁容器；不连接开发 Redis |
| AI 服务测试 | `ai/tests/` | 从仓库根目录运行 `python -m pytest ai/tests` |
| 观测部署配置契约 | `tests/test_observability_compose.py` | 使用虚拟 env 夹具执行 `docker compose config`，不启动或检查容器 |
| MIME 字段真实 schema | `DocumentMimeSchemaIntegrationTest` | 仅对显式启用的 loopback 专用临时 PG 建表、写入三种 Office MIME |
| 共享 PDF 夹具 | `tests/fixtures/` | 小型上传样本等跨项目测试资料 |
| Vue 前端 | `frontend/` | `npm run typecheck`、`npm run build`；`npm run test:sse / test:models / test:uploads` 验证帧、协议与实际上传格式回调，不连接业务服务 |


按功能选取需要的测试类或运行器。


## 账户、知识库、文档任务与问答

从仓库根目录运行：

```powershell
$env:PYTHONUTF8 = '1'
python tests/e2e/run_core_business_test.py --java-home "$env:JAVA_HOME"
```

准备 Docker、`JAVA_HOME` 指向的 JDK 17+，以及本机已有的 `pgvector/pgvector:pg17`、`redis:7` 镜像。运行器优先使用仓库 Maven 包装器，只选择 `CoreBusinessFlowIntegrationTest`；不会拉取镜像、连接既有业务库或清空既有 Redis。

测试使用专用容器及随机宿主端口，运行当前源码和 Flyway 迁移；结束时按本次标签核对归属后销毁两个容器及测试数据。JDK 通过子进程环境选择，不修改系统 Java 设置。

| 主线 | 断言内容 |
| --- | --- |
| 账户 | 注册保存密码哈希、登录成功 / 无效凭证、访问认证、刷新轮换、登出后刷新拒绝 |
| 知识库 | 当前用户绑定创建者、成员可见性、重复添加幂等、创建者管理权限、移除成员后拒绝读取 |
| 文档任务 | 上传权限、回执找回、同键复用与内容冲突、真实后台处理与 chunk 写入、关联成功终态、失败任务原 ID 重试、删除外键级联 |
| 问答 | Java 授权范围、单轮 / 会话 / 搜索入口、原始候选与获准来源区别、真实 Redis 历史隔离、正常无证据追加历史、503 / 504 不写正常历史 |

这里的 MockMvc 不启动真实 HTTP 服务器，但运行完整 MVC 与 Security 过滤链。只有 `AiServiceClient`、`MinioService` 被替换；测试不证明外部解析、模型、网络传输或对象实际删除正确。恢复扫描与 SSE 断流不是本测试的验收范围。历史检索策略版本与实际执行元数据仍按生产契约传递。

每次执行的结果与 Maven 输出写到忽略目录 `logs/core-business-test/<run-id>/`。只有 JUnit 无失败、无跳过、六个业务检查点齐全且清理成功时，运行器才返回 0；没有报告或未执行不能算通过。

## 真实 PDF 与解析失败边界

从仓库根目录选择一个场景运行。

```powershell
$env:PYTHONUTF8 = '1'
python tests/e2e/run_real_document_rag_flow.py --scenario success
python tests/e2e/run_real_document_rag_flow.py --scenario parser-failure
python tests/e2e/run_real_document_rag_flow.py --scenario office-formats
```

`office-formats` 用生成的 DOCX、XLSX、PPTX 和损坏的 DOCX 检查入库、任务租约及迟到数据边界。Java、FastAPI、PostgreSQL、Redis 和 MinIO 实际运行，Embedding 通过本机 HTTP 替身返回可复核的向量。`python tests/e2e/run_model_management_test.py --suite all` 在隔离 PostgreSQL 上运行模型登记与索引生命周期检查，模型 HTTP 使用替身。

运行器使用已有的 PG17/pgvector、Redis、MinIO 镜像，在随机 loopback 端口启动一次性 tmpfs 容器和独立 Java/AI 进程，结束后清理。成功场景使用本地已存在的 `ekip-qwen3-embedding:0.6b-4k`，检查 PDF 解析、活动向量与 baseline 检索返回目标切片。`parser-failure` 场景上传程序内生成的损坏 PDF，检查失败状态、零切片/向量、原文件与回执保留，再经正常删除接口核对数据库级联和真实对象删除。

结果写入 `logs/real-document-rag-flow/<run-id>/report.json`。
