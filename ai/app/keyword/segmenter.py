"""将输入文本转换为可进入 PostgreSQL 全文检索的词元。"""

import jieba

# 常见功能词/停用词：避免它们 OR 匹配所有 chunk 把排序冲淡
STOPWORDS = {
    "的", "了", "是", "在", "有", "和", "与", "及", "或", "就", "都", "也", "很", "这",
    "那", "吗", "呢", "啊", "吧", "哦", "嗯", "个", "对", "从", "到", "把", "被", "让",
    "要", "会", "能", "可以", "应该", "什么", "怎么", "如何", "哪些", "怎样", "哪个",
    "一个", "一下", "以及", "或者", "等", "我", "你", "他", "她", "它", "我们",
    "你们", "他们", "请问", "麻烦", "关于", "对于", "其中", "另外",
}


def segment(text: str) -> tuple[list[str], str]:
    """对正文或查询文本做中文分词，返回以空格连接的词元供 PostgreSQL 全文索引使用。

    上游为 `ai.app.chunker.structured_chunker._seg_text`、`ai.app.retrieval.retriever._segment_query`。
    """
    raw = [w.strip() for w in jieba.cut(text) if w.strip()]
    words = [w for w in raw if len(w) >= 2 and w not in STOPWORDS]
    if not words:
        words = raw
    return words, " | ".join(words)
