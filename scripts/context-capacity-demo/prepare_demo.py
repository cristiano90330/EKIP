"""为上下文容量演示准备知识库、样本文档和运行参数。"""

import argparse
import json
import secrets
import time
from pathlib import Path

import httpx

ROOT = Path(__file__).resolve().parents[2]
QUESTION = "截至2030年5月31日，柏川项目材料缺失时应在几个工作日内补齐？紧急申请是否豁免？请说明生效时间依据并附原文引用。"
FACT = "柏川项目材料补齐细则：自2030年6月1日起，材料缺失时应在5个工作日内补齐；生效前沿用3个工作日的补齐期限。紧急申请也须遵守同一期限，不设豁免。"
NORMAL = "你好，请用一句话介绍你能做什么。"
CAPACITY = "请简短回答：你能否完整保留本轮系统规则并继续处理？"


def main():
    """配置演示账号和知识库，上传准备好的资料并记录后续运行所需参数。

    下游调用 `api`。
    """
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--verify", action="store_true", help="Make three real Java entry-point requests")
    args = parser.parse_args()
    output = ROOT / "logs/context-capacity-demo"
    output.mkdir(parents=True, exist_ok=True)
    username = "ctx_demo_" + time.strftime("%m%d_%H%M%S")
    password = "Demo!" + secrets.token_hex(7)
    client = httpx.Client(base_url=args.base_url, trust_env=False, timeout=120)

    def api(method, path, **kwargs):
        """执行演示资料准备期间指定的 API 操作，并在请求失败时上报具体入口。

        上游为 `main`。
        """
        response = client.request(method, path, **kwargs)
        response.raise_for_status()
        body = response.json()
        if isinstance(body, dict) and "code" in body:
            if body["code"] not in {0, 200}:
                raise RuntimeError(f"Java {path} refused: {body.get('message', body.get('code'))}")
            return body.get("data")
        return body

    api("POST", "/user/register", json={"username": username, "password": password})
    login = api("POST", "/user/login", json={"username": username, "password": password})
    client.headers["Authorization"] = "Bearer " + login["token"]
    defaults = api("GET", "/models/defaults")
    # Store only public model descriptions returned by Java, never an execution snapshot/key.
    (output / "deployment-defaults.json").write_text(json.dumps(defaults, ensure_ascii=False, indent=2), encoding="utf-8")
    common = {"answerPolicy": "general", "kbSelectionMode": "none", "knowledgeBaseIds": [],
              "allowedTools": [], "memoryReadEnabled": False, "memoryWriteEnabled": False}
    normal = api("POST", "/agent/profiles", json=dict(common,
        name="容量演示：普通问题", systemPrompt="你是演示助手。只需礼貌简洁地回答用户。"))
    # Real, mandatory system input; no reduction of production model capacity.
    rules = "必须保留以下全部系统规则。\n" + "\n".join(
        f"规则{chr(0x4e00+i)}：处理资料时必须保留对象范围条件例外和原文引用并核对适用日期，不得删除本条系统规则。"
        for i in range(170))
    capacity = api("POST", "/agent/profiles", json=dict(common,
        name="容量演示：必需内容过长", systemPrompt=rules[:7980]))
    kb_name = "上下文容量演示 " + username
    api("POST", "/knowledge-base", json={"name": kb_name, "description": "本次新增的可删除演示资料，不含真实业务数据"})
    kb = next(item for item in api("GET", "/knowledge-base/list") if item["name"] == kb_name)
    boilerplate = "柏川项目材料归档核对记录仅用于附件存放位置复核，工作人员应妥善保存原始申请材料并保持扫描件清晰完整。"
    text = "\n\n".join(
        f"## 柏川项目材料补齐核对记录{chr(0x4e00+i)}\n\n{FACT}\n\n" + (boilerplate + "\n\n") * 11
        for i in range(24))
    fixture = output / "柏川项目材料补齐演示细则.md"
    fixture.write_text(text, encoding="utf-8")
    receipt = api("POST", "/document/upload", params={"knowledgeBaseId": kb["id"]},
        headers={"Idempotency-Key": username}, files={"file": (fixture.name, fixture.read_bytes(), "text/markdown")})
    document_id = receipt["documentId"]
    for _ in range(120):
        task = api("GET", f"/document/{document_id}/task")
        if str(task.get("status", "")).lower() in {"success", "failed", "cancelled"}:
            break
        time.sleep(1)
    state = {"username": username, "password": password, "baseUrl": args.base_url,
             "normalAgentId": normal["id"], "capacityAgentId": capacity["id"],
             "knowledgeBaseId": kb["id"], "documentId": document_id,
             "taskStatus": task.get("status"), "question": QUESTION}
    (output / "demo-account.json").write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")
    guide = f"""# 本机页面验证（新增演示账号）

页面：http://127.0.0.1:5173　账号：{username}　密码：{password}

本次 Java：{args.base_url}。AI 由该 Java 实例配置决定，需启用 support；脚本不修改服务配置或模型窗口。
文档任务：{task.get('status')}；知识库：{kb_name}；文档：{fixture.name}。

1. Agent 页面选择「容量演示：普通问题」，发送：{NORMAL}
   预期：正常简短回答。
2. 知识问答页面选择 fixed，发送：{QUESTION}
   预期：真实显示整理状态及单次输入估算下降，继续回答生效前为 3 个工作日，保留紧急申请不豁免和原文引用。
3. Agent 页面选择「容量演示：必需内容过长」，发送：{CAPACITY}
   预期：明确提示整理后必需内容仍超出模型单次输入容量，不显示正常完成或无证据。

运行结果以 real-results.json 为准；准备资料或单元测试成功不代表以上三项已通过。
"""
    (output / "页面验证.md").write_text(guide, encoding="utf-8")
    print(json.dumps({"username": username, "taskStatus": task.get("status"), "guide": str(output / "页面验证.md")}, ensure_ascii=False))
    if not args.verify:
        return
    results = {}
    for label, agent, question in [("normal", normal, NORMAL), ("capacity", capacity, CAPACITY)]:
        session = api("POST", "/agent/sessions", json={"agentId": agent["id"]})
        results[label] = api("POST", "/agent/chat", json={"agentId": agent["id"], "sessionId": session["id"], "question": question})
        print(label, results[label].get("outcome"), results[label].get("reasonCode"))
    frames = []
    with client.stream("POST", "/knowledge/ask/stream", json={"question": QUESTION, "pipelineEngine": "fixed"}) as response:
        response.raise_for_status()
        event = "message"
        for line in response.iter_lines():
            if line.startswith("event:"):
                event = line[6:].strip()
            elif line.startswith("data:"):
                frames.append({"event": event, "data": json.loads(line[5:])})
    results["rag"] = frames
    (output / "real-results.json").write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    for frame in frames:
        if frame["event"] in {"context", "done", "error"}:
            data = frame["data"]
            print(frame["event"], json.dumps({key: data.get(key) for key in (
                "status", "outcome", "reasonCode", "inputTokensBefore", "inputTokensAfter", "inputBudget", "answer") if key in data}, ensure_ascii=False))


if __name__ == "__main__":
    main()
