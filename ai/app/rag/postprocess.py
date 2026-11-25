"""对召回证据做多样性选择、父块扩展与原文摘取，并记录压缩统计。"""

import logging
from dataclasses import dataclass, field, replace

from ai.app.llm.tokens import count_tokens, truncate_to_tokens
from ai.app.retrieval.retriever import Candidate

logger = logging.getLogger("ekip.rag.postprocess")

# 句子边界（与 Java ExtractiveContextCompressor.splitSentences 一致）
_SENTENCE_END = ("。", "！", "？", "；", "\n")

VALID_COMPRESSION_MODES = ("none", "extractive")


# ---------------- MMR ----------------

def _strip_ws(text: str) -> str:
    """移除输入中的排版空白，供文本相似度算法比较稳定的字符片段。

    上游为 `char_bigrams`。
    """
    return "".join(ch for ch in (text or "") if not ch.isspace())


# 生成文本中连续两个字符的去重集合，用于重复候选和问题相关性判断。
# 上游为 `mmr_select`，下游调用 `_strip_ws`。
def char_bigrams(text: str) -> set[str]:
    """字符二元组（折叠空白后）。中文天然适合这种粗粒度去重口径。

    与 Java `MmrSelector.charBigrams` 一致；空/单字符内容返回空集 → Jaccard 计 0。
    """
    clean = _strip_ws(text)
    return {clean[i:i + 2] for i in range(len(clean) - 1)}


def jaccard(a: set[str], b: set[str]) -> float:
    """用两个词元集合的交集与并集计算相似分，空集合返回零。

    上游为 `mmr_select`。
    """
    if not a or not b:
        return 0.0
    inter = len(a & b)
    union = len(a) + len(b) - inter
    return 0.0 if union == 0 else inter / union


def _relevance(c: Candidate) -> float:
    """从候选提取可用于 MMR 的相关性分数，并将分数限制为零到一。

    上游为 `mmr_select`。
    """
    if c.score is None:
        return 0.0
    return max(0.0, min(1.0, float(c.score)))


# 在保留对查询相关性的同时惩罚候选间重复内容，返回选出的 Top-K。
# 上游为 `apply_postprocess`，下游调用 `char_bigrams`、`jaccard`。
def mmr_select(candidates: list[Candidate], top_k: int, lam: float) -> list[Candidate]:
    """MMR 贪婪选择：既相关、又彼此不重复的 Top-K（对齐 Java `MmrSelector.select`）。

    首个选相关分最高者；此后每次选 `argmax_i [λ·rel(i) − (1−λ)·max_{j∈已选} Jaccard(i,j)]`。
    `λ=1` 退化为纯按分取 Top-K；候选数 ≤ top_k 时**原序返回**（去重无意义）。
    复杂度 O(n²·top_k)——n 是 recall-n（20 量级），可接受。
    """
    if not candidates or top_k <= 0:
        return []
    n = len(candidates)
    if top_k >= n:
        return list(candidates)

    bigrams = [char_bigrams(c.content) for c in candidates]
    selected = [False] * n
    out: list[Candidate] = []
    for _ in range(top_k):
        best, best_val = -1, float("-inf")
        for i in range(n):
            if selected[i]:
                continue
            max_sim = 0.0
            bi = bigrams[i]
            for j in range(n):
                if selected[j]:
                    sim = jaccard(bi, bigrams[j])
                    if sim > max_sim:
                        max_sim = sim
            val = lam * _relevance(candidates[i]) - (1.0 - lam) * max_sim
            if val > best_val:
                best_val, best = val, i
        if best < 0:
            break
        selected[best] = True
        out.append(candidates[best])
    return out


# ---------------- 抽取式压缩 ----------------

@dataclass
class CompressionStats:
    """压缩前后 token 与压缩比（`ratio = after/before`，before<=0 时记 1.0，与 Java 一致）。"""
    mode: str = "none"
    tokens_before: int = 0
    tokens_after: int = 0
    candidates: list[Candidate] = field(default_factory=list)

    @property
    def ratio(self) -> float:
        """用压缩后与压缩前的 Token 估值计算保留比例，供响应诊断显示。"""
        if self.tokens_before <= 0:
            return 1.0
        return self.tokens_after / self.tokens_before


def _split_sentences(text: str) -> list[str]:
    """按明确句末标点切分正文，保留句子原始文本供抽取式裁剪。

    上游为 `compress_extractive`。
    """
    out, start = [], 0
    for i, ch in enumerate(text or ""):
        if ch in _SENTENCE_END:
            s = text[start:i + 1].strip()
            if s:
                out.append(s)
            start = i + 1
    if start < len(text or ""):
        tail = text[start:].strip()
        if tail:
            out.append(tail)
    return out


def _keep_relevant_sentences(sentences: list[str], query_bigrams: set[str]) -> list[str]:
    """按查询词元选择候选句，完全无命中时保留开头作为有界回退。

    上游为 `compress_extractive`，下游调用 `char_bigrams`。
    """
    kept = [s for s in sentences if query_bigrams & char_bigrams(s)]
    if not kept and sentences:
        kept = [sentences[0]]
    return kept


# 在总 Token 预算内逐候选摘取相关原文，返回裁剪后的候选和压缩统计。
# 上游为 `ai.app.rag.pipeline.postprocess_query`，下游调用 `ai.app.llm.tokens.count_tokens`、`char_bigrams`。
def compress_extractive(query: str, candidates: list[Candidate], token_budget: int) -> CompressionStats:
    """抽取式压缩：按句与 query 的相关度保留句子，逐句累计直到 token 预算用尽（对齐 Java）。

    **硬预算**：`tokens_after <= token_budget` 是保证而非「尽量」——
    放不下的句子按剩余预算**截断**（首句也受约束），预算耗尽即停止处理后续候选。
    既不返回空上下文，也不违反下游「压完一定 ≤ budget」的假设。

    压缩后候选保留 id/documentId/knowledgeBaseId 等来源字段，来源可回溯。
    """
    if not candidates:
        return CompressionStats(mode="extractive")

    tokens_before = sum(count_tokens(c.content) for c in candidates)
    query_bigrams = char_bigrams(query)
    remaining = max(token_budget, 0)
    compressed: list[Candidate] = []

    for c in candidates:
        if remaining <= 0:
            break                       # 硬预算：用尽即停，后续候选整块丢弃
        kept = _keep_relevant_sentences(_split_sentences(c.content), query_bigrams)
        parts: list[str] = []
        for s in kept:
            t = count_tokens(s)
            if t <= remaining:
                parts.append(s)
                remaining -= t
                continue
            head = truncate_to_tokens(s, remaining)
            if head:
                parts.append(head)
                remaining -= count_tokens(head)
            break
        if parts:
            compressed.append(_with_content(c, "".join(parts)))

    return CompressionStats(
        mode="extractive",
        tokens_before=tokens_before,
        tokens_after=sum(count_tokens(c.content) for c in compressed),
        candidates=compressed,
    )


def _with_content(src: Candidate, content: str) -> Candidate:
    """用裁剪后的正文生成候选副本，保留原有文档和切片定位信息。

    上游为 `compress_extractive`。
    """
    return replace(src, content=content)


# 按查询选项对候选应用多样性、父块扩展和压缩，返回整理结果及可选统计。
# 上游为 `ai.app.rag.pipeline._postprocess`，下游调用 `mmr_select`、`compress_extractive`。
def apply_postprocess(
    candidates: list[Candidate],
    *,
    query: str,
    top_k: int,
    token_budget: int,
    version: str,
    mmr_enabled: bool,
    mmr_lambda: float,
    compression_mode: str,
) -> tuple[list[Candidate], CompressionStats | None]:
    """按 Java 的顺序做后处理：**MMR（仅 rerank 档）→ 抽取式压缩**。

    返回 `(最终候选, 压缩统计或 None)`。`compression_mode` 非法时抛 `ValueError`
    （与 version 的 fail-fast 同风格：宁可响亮失败，也不要静默按 none 跑）。
    """
    result = candidates
    if mmr_enabled and version in ("hybrid-rerank", "hybrid-rerank-rewrite"):
        result = mmr_select(result, top_k, mmr_lambda)

    if compression_mode == "none":
        return result, None
    if compression_mode not in VALID_COMPRESSION_MODES:
        raise ValueError(
            f"非法 compression.mode={compression_mode!r}（合法值 {list(VALID_COMPRESSION_MODES)}）"
        )
    stats = compress_extractive(query, result, token_budget)
    return stats.candidates, stats
