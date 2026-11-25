"""从既有演示环境发起长资料问答并整理引用与容量观测。"""

import argparse
import json
import secrets
import time
from pathlib import Path

import httpx

ROOT = Path(__file__).resolve().parents[2]
RULE = "澄岚项目样品复核材料补齐细则：自2031年4月1日起，缺失材料应在7个工作日内补齐；生效前的补齐期限为4个工作日。紧急申请不豁免，仍须遵守当期补齐期限。"
RECEIPT = "澄岚项目样品复核归档时，负责人应同时保存电子回执和书面审批单。"
QUESTIONS = {
    "ordinary": "澄岚项目样品复核归档时，负责人应保存哪两种凭据？请附引用。",
    "complex": "截至2031年3月31日，澄岚项目样品复核材料缺失时应在几个工作日内补齐？紧急申请是否豁免？请说明日期依据并附原文引用。",
    "missing": "澄岚项目样品复核的年度采购预算是多少万元？请附引用。",
}
CONCISE_QUESTIONS = {
    "ordinary": QUESTIONS["ordinary"],
    "complex": "截至2031年3月31日，澄岚项目样品复核材料缺失时应在几个工作日内补齐？紧急申请是否豁免？",
    "missing": "澄岚项目样品复核由哪家供应商承接？",
}
TOPICS = [
    ("封面核对", "封面用于识别文件所属的归档主题，题名应与移交清单对应；题名模糊时先查证原始申请，不凭口头印象补写。"),
    ("影像清晰", "扫描影像需能辨识正文与签字，出现折痕遮挡时保留原图并重新采集；处理图像仅用于提升可读性，不能改动原文。"),
    ("附件排序", "附件按形成关系编排，正文所提附件应与实际文件相互对应；附件名称不清晰时将核对说明附在目录中。"),
    ("移交交接", "移交双方共同核对实体与目录，交接说明记录尚待查证的事项；接收人不能把未核实的情况标记为已确认。"),
    ("签章核对", "签章区域应完整可见，缺少印章影像时向原形成部门核对；电子副本不得代替对原始签章的确认。"),
    ("目录维护", "目录保留原始题名和责任部门，修改题名时附修订说明；不能用便于搜索的简称覆盖正式文件名称。"),
    ("原件保管", "原件放入对应案卷，借用时登记交接人与用途；原件返回后重新核对完整性，异常情况写入案卷说明。"),
    ("版本辨识", "草稿和正式件分别标识，正式件以签发状态核对；发现相同题名但正文不同的文件时保留差异说明。"),
    ("授权阅读", "阅读权限按业务职责申请，接收文件不自动取得再次分发权限；需要转交时先核对接收对象的使用范围。"),
    ("离线载体", "离线载体附文件目录并登记交接关系，载体标签与所含文件对应；发现标签错误时保留原标签的更正记录。"),
    ("检索命名", "检索别名应能追溯正式题名，避免只使用个人习惯简称；别名用于查找资料，不能作为正文内容的替代依据。"),
    ("错误更正", "录入错误按原始材料查证后更正，更正说明保留原字段和原因；无法查明的字段标识待核实，不能猜测填写。"),
    ("借阅登记", "借阅登记区分借出与复制，写明文件去向和使用目的；阅读结束后确认文件完整，并记录尚未归还的附属材料。"),
    ("密封包装", "包装应保护纸质原件与封签，拆封后保留拆封说明；重新包装不能掩盖原封签已有的破损或污损。"),
    ("归档环境", "纸质案卷远离潮湿和强光，放置位置与目录一致；发现保存环境异常时记录受影响文件并安排核对。"),
    ("外部来函", "外部来函保留收件渠道与原始附件，正文引用其他文件时保留可追溯名称；不将转述内容当作已核验的原文。"),
    ("补充说明", "补充说明注明所解释的原始事项，并与原件共同归档；说明只能解释形成背景，不能覆盖正式签发的业务规则。"),
    ("交叉索引", "相关案卷可建立交叉索引，索引写明对应题名与位置；索引用于定位原文，不把另一案卷的结论复制成当前事实。"),
    ("电子校验", "电子文件接收后核对可读性，发现损坏时保留原文件并联系移交人；修复副本应标明来源和处理方式。"),
    ("形成部门", "形成部门负责说明文件背景，归档人员核对说明与正文是否一致；职责变更时保留原形成部门的标识。"),
    ("问询记录", "问询应记录原问题和实际答复，答复存在条件时完整保留；口头答复需要补充可核验的书面材料。"),
    ("重复副本", "相同内容的副本标明对应原件，不因副本数量较多而提高其效力；正文有差异的文件分别保存并说明来源。"),
    ("阅读批注", "个人阅读批注与正式正文分开保存，批注中的推测需标识；未经签发的批注不能当作正式处理依据。"),
    ("归卷说明", "归卷说明描述文件关系与整理过程，发现遗漏时说明核查路径；归卷完成不代表文件中的业务事项已办结。"),
]


def corpus():
    """构造演示所需的长文档和事实片段，供提问结果中的原文引用核对。

    上游为 `main`。
    """
    sections = ["# 澄岚项目样品复核归档与材料补齐手册", "## 业务规则\n\n" + RULE,
                "## 归档凭据\n\n" + RECEIPT]
    for title, text in TOPICS:
        # Distinct paragraphs, no repeated padding removed by unique_paragraphs().
        sections.append(f"## 澄岚项目样品复核：{title}\n\n{text}\n\n"
                        f"本节的核查对象为{title}。经办人员应围绕该对象检查文件来源、形成过程和当前保存状态，"
                        f"将涉及{title}的疑问交给负责部门确认。交接说明应具体指向实际原件，不将推测填写为核查结论。"
                        f"关于{title}形成的说明与相关案卷共同保管，供后续阅读者对照正文还原处理过程。"
                        f"若{title}所需信息无法从现有材料确认，记录尚缺的证据及联系路径；核查记录只说明已完成的检查，"
                        "不能据此推导样品复核的补齐期限、适用日期、紧急申请政策或采购预算。\n\n"
                        f"{title}的经办说明还应描述核查时实际看到的材料，例如题名所在位置、附件与正文的对应关系、"
                        f"移交清单中如何登记{title}以及保存位置如何定位。记录应使没有参与交接的人也能找到原始依据。"
                        f"遇到{title}存在歧义时，先列明不同解释各自依据的正文，交由形成部门确认；确认结果与问题一并保留。"
                        f"任何关于{title}的内部讨论、临时标记和个人备注，都应与正式签发文本区分。"
                        "发现对照材料缺失时，可以说明暂时无法核实及后续查证办法，不使用同类项目经验补齐事实。"
                        f"阅读{title}归档记录的人应回到相关原件核对上下文，完整保留文件中已经写明的限制，不把整理完成等同于业务审批通过。")
    return "\n\n".join(sections) + "\n"


def main():
    """准备本机演示的语料和提问，向 Java 入口发出问答并记录证据、耗时及容量整理事件。

    下游调用 `api`、`corpus`。
    """
    p = argparse.ArgumentParser()
    p.add_argument("--base-url", default="http://127.0.0.1:8080")
    p.add_argument("--state", type=Path, help="Reuse this run's ignored account file")
    p.add_argument("--complex-top-k", type=int, choices=range(1, 21), default=5)
    p.add_argument("--question-profile", choices=["original", "concise"], default="original")
    p.add_argument("--case", choices=["prepare", "ordinary", "complex", "missing", "all"], default="all")
    args = p.parse_args()
    client = httpx.Client(base_url=args.base_url, trust_env=False, timeout=120)

    def api(method, path, **kwargs):
        """在当前演示账号下调用选定业务接口，返回解包后的业务响应。

        上游为 `main`。
        """
        r = client.request(method, path, **kwargs)
        r.raise_for_status()
        body = r.json()
        if isinstance(body, dict) and "code" in body:
            if body["code"] not in {0, 200}:
                raise RuntimeError(f"Java refused {path}: {body.get('message')}")
            return body.get("data")
        return body

    if args.state:
        state = json.loads(args.state.read_text(encoding="utf-8"))
        folder = args.state.resolve().parent
    else:
        folder = ROOT / "logs/daily-demo" / time.strftime("%Y%m%d-%H%M%S")
        folder.mkdir(parents=True)
        state = {"username": "daily_" + time.strftime("%m%d_%H%M%S"),
                 "password": "Demo!" + secrets.token_hex(7), "baseUrl": args.base_url}
        api("POST", "/user/register", json={k: state[k] for k in ("username", "password")})
    login = api("POST", "/user/login", json={k: state[k] for k in ("username", "password")})
    client.headers["Authorization"] = "Bearer " + login["token"]
    if "documentId" not in state:
        defaults = api("GET", "/models/defaults")
        (folder / "defaults.json").write_text(json.dumps(defaults, ensure_ascii=False, indent=2), encoding="utf-8")
        name = "澄岚新资料演示 " + state["username"]
        api("POST", "/knowledge-base", json={"name": name, "description": "合成演示资料；本轮新增，可单独删除"})
        kb = next(k for k in api("GET", "/knowledge-base/list") if k["name"] == name)
        state["knowledgeBaseId"] = kb["id"]
        fixture = folder / "澄岚项目样品复核手册.md"
        fixture.write_text(corpus(), encoding="utf-8")
        receipt = api("POST", "/document/upload", params={"knowledgeBaseId": kb["id"]},
                      headers={"Idempotency-Key": state["username"]},
                      files={"file": (fixture.name, fixture.read_bytes(), "text/markdown")})
        state["documentId"] = receipt["documentId"]
        (folder / "account.json").write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")
    for _ in range(120):
        task = api("GET", f"/document/{state['documentId']}/task")
        status = str(task.get("status", "")).lower()
        if status in {"success", "failed", "cancelled"}:
            break
        time.sleep(1)
    print(json.dumps({"state": str(folder / "account.json"), "task": status}, ensure_ascii=False), flush=True)
    if status != "success":
        raise RuntimeError("Document ingestion did not succeed; see Java task state")
    if args.case == "prepare":
        return
    questions = CONCISE_QUESTIONS if args.question_profile == "concise" else QUESTIONS
    labels = list(questions) if args.case == "all" else [args.case]
    failed = []
    original = next(folder.glob("*.md")).read_text(encoding="utf-8")
    for label in labels:
        request_id = "daily-" + folder.name + "-" + args.question_profile + "-" + label + "-" + secrets.token_hex(3)
        started = time.monotonic()
        frames, event = [], "message"
        with client.stream("POST", "/knowledge/ask/stream", headers={"X-Request-ID": request_id},
                           json={"question": questions[label], "pipelineEngine": "fixed",
                                 "topK": args.complex_top_k if label == "complex" else 5}) as response:
            response.raise_for_status()
            for line in response.iter_lines():
                if line.startswith("event:"):
                    event = line[6:].strip()
                elif line.startswith("data:"):
                    frames.append({"event": event, "data": json.loads(line[5:])})
        done = next((f["data"] for f in reversed(frames) if f["event"] == "done"), {})
        terminal = done or next((f["data"] for f in reversed(frames) if f["event"] == "error"), {})
        answer = done.get("answer", "")
        sources = done.get("evidenceSources", [])
        checks = {"done_without_error": bool(done) and not any(f["event"] == "error" for f in frames),
                  "outcome": done.get("outcome") == ("no_evidence" if label == "missing" else "success"),
                  "quotes_verbatim": all(all(span in original for span in s["content"].split("\n\n") if span.strip()) for s in sources),
                  "source_scope": all(s["documentId"] == state["documentId"] and s["knowledgeBaseId"] == state["knowledgeBaseId"] for s in sources),
                  "public_sources": not sources if label == "missing" else bool(sources)}
        if label == "missing":
            checks["no_answer"] = not answer
            # A failed coverage review is not proof that the corpus lacks an answer.
            checks["absence_reason"] = done.get("reasonCode") in {"support_incomplete", "no_match"}
        if label == "ordinary":
            checks["answer"] = "电子回执" in answer and "书面审批单" in answer
        if label == "complex":
            checks["answer"] = ("4" in answer or "四" in answer) and ("不豁免" in answer or "不享有豁免" in answer or "不予豁免" in answer or "不能豁免" in answer)
            checks["batch_executed"] = any(f["event"] == "context" and f["data"].get("method") == "batch_extract"
                                           and f["data"].get("batchCount", 0) >= 2 for f in frames)
        result = {"requestId": request_id, "elapsedMs": round((time.monotonic() - started) * 1000),
                  "question": questions[label], "questionProfile": args.question_profile,
                  "topK": args.complex_top_k if label == "complex" else 5, "checks": checks, "frames": frames}
        (folder / f"{label}-{request_id[-6:]}.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps({"case": label, "requestId": request_id, "elapsedMs": result["elapsedMs"],
                          "outcome": terminal.get("outcome"), "reasonCode": terminal.get("reasonCode"),
                          "answer": answer, "checks": checks}, ensure_ascii=False), flush=True)
        if not all(checks.values()):
            failed.append(label)
    if failed:
        raise SystemExit("Checks failed: " + ", ".join(failed))


if __name__ == "__main__":
    main()
