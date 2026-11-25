# 启动、演示与诊断脚本

| 入口 | 什么时候用 |
| --- | --- |
| [`start-demo.ps1`](start-demo.ps1) | 启动或复用本机 Docker、模型服务、业务服务和前端。见[日常启动说明](../README.md#本机日常演示)。 |
| [`start-local-reranker.ps1`](start-local-reranker.ps1) | 单独启动本机 llama.cpp 重排服务。 |
| [`context-capacity-demo/`](context-capacity-demo/README.md) | 准备长资料演示知识库，走普通问答、跨段综合和缺证三种场景。 |
| [`verify_deployment.py`](verify_deployment.py) | 检查目标部署入口及依赖的基本运行条件。 |
| [`check_ai_config_forwarding.py`](check_ai_config_forwarding.py) | 核对 Compose 是否将预期变量传给 AI 容器。 |
| [`verify_trace_isolation.py`](verify_trace_isolation.py) | 用不同账号核对观测链路记录的可见范围。 |
| [`rag-pipeline-validation/`](rag-pipeline-validation/README.md) | 用相同授权范围和请求对照 fixed 与 LangChain 两条管线。 |

业务回归入口见[测试索引](../tests/README.md)。
