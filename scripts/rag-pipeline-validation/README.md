# 双 RAG 管线检索对照

`compare_retrieval_engines.py` 从 Java 合法授权入口对照 fixed / langchain。需要现有 Java/AI/PG/模型服务和 `EKIP_LAB_ACCESS_TOKEN`（已有登录 JWT）。设定该环境变量后，在仓库根目录运行 `python scripts/rag-pipeline-validation/compare_retrieval_engines.py --query "带已有资料的问题"`。

脚本调用知识库列表、只检索接口和 Trace，核对授权范围、实际版本、来源身份、正文 SHA256、排序、距离及结果状态。仅允许明确端口的 HTTP 回环地址，拒绝代理和重定向；不打印 token 或来源正文。

不注册用户、不上传文档、不改会话、不生成答案。报告保存到本机 `logs/`，不要把日志或凭据提交。只有两版成功返回非空来源且内容/顺序/策略/状态一致才算该样本通过；通过也不代表完整答案质量验收。
