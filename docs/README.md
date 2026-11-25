# EKIP 文档入口

## 使用与开发

| 想了解什么 | 从这里看 |
| --- | --- |
| 找文件、理解目录职责 | [根目录导航](../README.md#目录结构)、[脚本](../scripts/README.md)、[部署文件](../docker/README.md) |
| 找本机配置和密钥入口 | [本机配置与密钥](local-configuration.md) |
| 启动本机并走一遍演示 | [日常启动](../README.md#本机日常演示)、[长资料三问路线](../scripts/context-capacity-demo/README.md) |
| 安装、部署和功能操作 | [总 README](../README.md)、[后端](../knowledge-platform/README.md)、[AI 服务](../ai/README.md)、[前端](../frontend/README.md) |
| 配模型、重建索引、排查故障 | [模型管理](model-management.md)、[观测说明](observability.md) |
| 看长资料怎么问、怎么核引用 | [长资料问答与输入容量](context-capacity.md) |
| 看整体架构和文档处理流程 | [运行单元与调用关系](../README.md#架构设计)、[文档任务状态图](architecture/ekip-document-lifecycle.html) |
| 查权限、模型能力和验收标准 | [权限矩阵](architecture/contracts/access-matrix.md)、[模型与 Agent 能力](architecture/contracts/model-agent-capability-matrix.md)、[轻量模型验收标准](architecture/contracts/lightweight-acceptance.md) |

## 给编码助手

[AGENTS.md](../AGENTS.md) 提供项目框架与修改规则，[模块导航](architecture/modules/README.md) 指向各模块源码。[CLAUDE.md](../CLAUDE.md) 使用同一份指南。接口行为、配置值和错误处理从当前源码与测试核对。
