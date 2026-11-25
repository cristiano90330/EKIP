# 容器部署文件

| 文件 | 负责什么 |
| --- | --- |
| [`docker-compose.yml`](../docker-compose.yml) | 装配业务服务、数据库、缓存、对象存储和建桶任务。 |
| [`docker-compose.observability.yml`](../docker-compose.observability.yml) | 在业务部署上添加 Collector、ClickHouse 和 Langfuse。 |
| [`otel-collector-config.yaml`](otel-collector-config.yaml) | 供外部观测栈挂载，定义 OTLP 接收、处理和转发。 |
| [`docker-compose.dev-test.yml`](../docker-compose.dev-test.yml) | 在开发联调时显式开启 RAG 连通性模式。 |
| [Java Dockerfile](../knowledge-platform/Dockerfile) · [AI Dockerfile](../ai/Dockerfile) | 分别生成 Spring Boot 与 FastAPI 服务镜像。 |

运行入口见[快速开始](../README.md#quickstart)，选装追踪栈见[观测说明](../docs/observability.md)。
