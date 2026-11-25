# 本机长资料问答演示

从仓库根目录运行 `& .\scripts\start-demo.ps1`。看到 `Ready` 后进入页面。[日常启动说明](../../README.md#本机日常演示)

## 页面演示路线

1. 打开 [5173 控制台](http://127.0.0.1:5173/login)，用已有演示账号登录。在知识库找到《澄岚项目样品复核手册》，确认文档处理已完成。
2. 使用可读取该知识库的演示账号，在问答页选择固定流程，依次提问下方三题。问答页查询账号当前有权读取的知识库。普通题展示原文来源。复合题展示日期、例外和引用，可打开 Trace 看重排、输入整理和审查。资料外题展示无证据拒答。
3. 如尚未准备资料，只执行下方准备命令，再回页面演示。

## 非重复长资料与三问

`daily_demo.py` 通过 Java 注册演示账号、创建知识库并上传合成手册。资料约 1.3 万字符、101 个不同段落，包含凭据要求、生效前后不同期限和紧急申请不豁免。以下命令只准备页面演示需要的账号和资料。

```powershell
python scripts/context-capacity-demo/daily_demo.py --case prepare
```

每次不带 `--state` 都会新增演示数据。原文、账号密码和结果保存在已忽略的 `logs/daily-demo/<本次时间>/`。如需从命令行核对简短版三问，可复用已有账号文件：

```powershell
python scripts/context-capacity-demo/daily_demo.py --state logs/daily-demo/<run-id>/account.json --question-profile concise --case all
```

三个问题：

1. 普通问题：归档时负责人应保存哪两种凭据？预期电子回执、书面审批单及可核对引用。
2. 复合问题：截至 2031-03-31，澄岚项目样品复核材料缺失时应在几个工作日内补齐？紧急申请是否豁免？请说明日期依据并附原文引用。预期生效前为 4 个工作日、紧急申请不豁免，附上生效日、旧期限和紧急条款原文。
3. 资料外问题：由哪家供应商承接？预期 `no_evidence`，不生成供应商或公开证据，且拒答原因应为实际支持不足。

脚本核对真实 `done/error` 终态、回答、引用原文与来源范围；复杂问题另外要求出现完成的 `context` 事件，且 `method=batch_extract`、`batchCount>=2`。整理事件本身不表示最终答案正确，仍须检查终态。所有运行保留独立请求 ID，失败结果不会被后续成功覆盖。当前脚本和浏览器均默认 topK=5；`--complex-top-k 12` 可复现早期参数。

`--question-profile original` 保留原来的“说明日期依据并附引用”复合题和采购预算题。脚本核对无答案原因，审查覆盖失败会使检查失败。日常 r7 曾出现原题误拒答及 `support_question_coverage_incomplete`，新版的 D13 修复与三题回归见[定向结果](../../docs/context-capacity.md#d13-定向回归)。

## 历史容量场景

`prepare_demo.py` 保留普通 Agent、重复段落去重、必需系统输入过长三个场景，默认 Java 已统一为 `8080`。
