"""提供模型供应商元数据、预览和连接检查入口。"""

import json
from time import monotonic
from fastapi import APIRouter

from ai.app.models.schema import ModelRequest, ModelExecution
from ai.app.models.resolution import provider_metadata, resolve, ModelConfigurationError
from ai.app.models.context import model_execution
from ai.app.models.runtime import runtime, ModelCallError
from ai.app.rag.deadline import QueryDeadlineExceeded

router = APIRouter(prefix="/models", tags=["models"])

# 返回支持的供应商及协议目录，供 Java 模型登记页面读取。
# 下游调用 `ai.app.models.resolution.provider_metadata`。
@router.get("/providers")
def providers():
    return provider_metadata()

# 解析请求中的模型配置并返回连接参数预览，不发起模型推理。
# 下游调用 `ai.app.models.resolution.resolve`。
@router.post("/resolve")
def preview(request: ModelRequest):
    return resolve(request.model)

# 按请求声明的模型类型发起最小连接检查，返回模型能力和调用结果。
# 下游调用 `ai.app.models.resolution.resolve`、`ai.app.models.schema.ModelExecution`。
@router.post("/test")
def test(request: ModelRequest):
    selected = request.model.model_copy(deep=True)
    selected.runtime.retries = 0
    resolve(selected)
    checks = request.checks or [{"chat":"text","embedding":"embedding","rerank":"rerank"}[selected.type]]
    allowed = {"chat":{"text","tools","stream","json"},"embedding":{"embedding"},"rerank":{"rerank"}}[selected.type]
    if not checks or len(set(checks)) != len(checks) or not set(checks) <= allowed:
        raise ModelConfigurationError("test_checks_do_not_match_model_type")
    purpose = {"chat":"agent","embedding":"embedding","rerank":"rerank"}[selected.type]
    execution = ModelExecution(budgetMs=30000, bindings={purpose:selected})
    started, results, vectors, scores = monotonic(), {}, None, None
    with model_execution(execution):
        for check in checks:
            try:
                messages = [{"role":"user","content":"Reply with the word OK."}]
                if check == "embedding":
                    vectors = runtime.embed(selected, ["model connection test"], input_type="document")
                elif check == "rerank":
                    scores = runtime.rerank(selected, "leave policy", ["Annual leave requires approval.", "A bicycle has two wheels."])
                elif check == "stream":
                    stream = runtime.chat(selected, purpose, messages, stream=True, max_tokens=32)
                    terminal = None
                    try:
                        for item in stream:
                            if not isinstance(item, str): terminal = item
                    finally:
                        stream.close()
                    if terminal is None: raise ModelCallError("model_stream_incomplete")
                elif check == "tools":
                    tools = [{"type":"function","function":{"name":"connection_test","description":"Use this tool to complete the test.","parameters":{"type":"object","properties":{},"additionalProperties":False}}}]
                    result = runtime.chat(selected, purpose, [{"role":"user","content":"Call connection_test with no arguments."}], tools=tools, max_tokens=128)
                    if not result.tool_calls or any((item.get("function") or {}).get("name") != "connection_test" for item in result.tool_calls):
                        raise ModelCallError("model_tools_not_observed")
                elif check == "json":
                    result = runtime.chat(selected, purpose, [{"role":"user","content":"Return a JSON object with key ok and boolean value true."}], response_format={"type":"json_object"}, max_tokens=64)
                    if not isinstance(json.loads(result.content or ""), dict): raise ModelCallError("model_json_not_observed")
                else:
                    result = runtime.chat(selected, purpose, messages, max_tokens=32)
                    if not (result.content or "").strip(): raise ModelCallError("model_empty_response")
                results[check] = {"success":True,"message":"verified"}
            except (ModelCallError, ModelConfigurationError, QueryDeadlineExceeded, ValueError) as exc:
                code = exc.code if isinstance(exc, ModelCallError) else "model_timeout" if isinstance(exc, QueryDeadlineExceeded) else "model_test_failed"
                results[check] = {"success":False,"message":code}
    return {"success":all(item["success"] for item in results.values()),
            "elapsedMs":int((monotonic()-started)*1000),"model":selected.model,"protocol":selected.protocol,
            "dimension":len(vectors[0]) if vectors else None,"scores":scores,
            "message":"completed","checks":results}
