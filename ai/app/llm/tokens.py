"""估算模型输入的 Token 开销并按预算保留文本前缀。"""

import json


def input_tokens(messages, tools=None, response_format=None) -> int:
    """估算消息、工具描述和响应结构占用的输入 Token，供输入预算校验使用。

    上游为 `ai.app.llm.guard.ModelGate._run`，下游调用 `count_tokens`。
    """
    payload = {"messages": messages}
    if tools:
        payload["tools"] = tools
    if response_format:
        payload["response_format"] = response_format
    return count_tokens(json.dumps(payload, ensure_ascii=False, separators=(",", ":")))


def count_tokens(text: str) -> int:
    """按不同文字和符号的估算规则计量文本 Token，供问答和证据裁剪复用。

    上游为 `ai.app.rag.pipeline.postprocess_query`、`ai.app.rag.postprocess.compress_extractive`。
    """
    tokens = 0
    alpha_run = 0
    for ch in text:
        if "一" <= ch <= "鿿":
            tokens += 1
        elif ch.isalnum():
            alpha_run += 1
        else:
            if alpha_run:
                tokens += (alpha_run + 3) // 4
                alpha_run = 0
    if alpha_run:
        tokens += (alpha_run + 3) // 4
    return max(tokens, 1)


# 从输入文本保留预算内的前缀，返回可直接放入模型提示的字符串。
# 上游为 `ai.app.rag.postprocess.compress_extractive`，下游调用 `_run_tokens`。
def truncate_to_tokens(text: str, max_tokens: int) -> str:
    """按 token 预算截断，**保证** `count_tokens(结果) <= max_tokens`。

    与 `count_tokens` 用同一套规则（CJK 1 字=1 token、拉丁/数字 4 字符≈1 token、其它字符不计），
    所以截断后不会出现「算下来还是超预算」的矛盾。逐字符走，标点本身不计 token，
    但**闭合当前字母数字 run** 才决定能否再放入一个字符。

    这是抽取式压缩的硬预算基础，并与 Java `TokenCounter.truncateToTokens` 对齐。
    """
    if not text or max_tokens <= 0:
        return ""
    out = []
    closed = 0      # 已闭合部分的 token（汉字 + 已结束的字母数字 run）
    run = 0         # 当前未闭合的字母数字 run 长度
    for ch in text:
        if "一" <= ch <= "鿿":
            next_closed = closed + _run_tokens(run) + 1
            next_run = 0
        elif ch.isalnum():
            next_closed = closed
            next_run = run + 1
        else:
            next_closed = closed + _run_tokens(run)
            next_run = 0
        if next_closed + _run_tokens(next_run) > max_tokens:
            break
        closed = next_closed
        run = next_run
        out.append(ch)
    return "".join(out)


def _run_tokens(run: int) -> int:
    """将一段连续 ASCII 字符的长度换算为近似 Token，供 count_tokens 计量。

    上游为 `truncate_to_tokens`。
    """
    return 0 if run == 0 else (run + 3) // 4
