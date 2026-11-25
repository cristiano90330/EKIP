"""验证结构块切片、长度边界和定位字段的保留。"""

from ai.app.chunker.structured_chunker import chunk_blocks
from ai.app.parser.blocks import Block


def test_heading_path_and_no_cross_section():
    """验证 `chunks[0]['headingPath']` 等于 `'3.1 部署'`，且 `all((c['headingPath'] in ('3.1 部署', '3.1 部署 > 3.1.2 验证') for c in chunks))` 成立。"""
    blocks = [Block(type="heading", text="3.1 部署", level=1),
              Block(type="paragraph", text="甲" * 100, page=1),
              Block(type="heading", text="3.1.2 验证", level=2),
              Block(type="paragraph", text="乙" * 100, page=2)]
    chunks = chunk_blocks(blocks, chunk_size=200, chunk_overlap=0)
    assert chunks[0]["headingPath"] == "3.1 部署"
    assert all(c["headingPath"] in ("3.1 部署", "3.1 部署 > 3.1.2 验证") for c in chunks)


def test_table_chunk_carries_table_id_row():
    """验证 `chunks[0]['sourceType']` 等于 `'table'`，且 `chunks[0]['tableId']` 等于 `'s1'`。"""
    blocks = [Block(type="table", text="", table_id="s1",
                    rows=[["h1", "h2"], ["a", "b"], ["c", "d"]], page=3)]
    chunks = chunk_blocks(blocks, chunk_size=1000, chunk_overlap=0)
    assert chunks[0]["sourceType"] == "table"
    assert chunks[0]["tableId"] == "s1"
    assert chunks[0]["rowIndex"] == 2       # 第 2 行起始（1-based，表头不计数）
    assert chunks[0]["pageNo"] == 3


def test_overlap_carries_tail_into_next_chunk():
    # 两个长段落、chunk_size 较小、overlap>0：溢出后应把上一块尾段带进下一块（滑动窗口）
    """验证 `chunks[0]['content']` 等于 `'甲' * 100`，且 `chunks[1]['content'].startswith('甲' * 50)` 成立。"""
    blocks = [Block(type="paragraph", text="甲" * 100, page=1),
              Block(type="paragraph", text="乙" * 100, page=1)]
    chunks = chunk_blocks(blocks, chunk_size=200, chunk_overlap=50)
    assert chunks[0]["content"] == "甲" * 100
    # 上一块尾部"甲"*50 应作为新块开头，与新文本合并
    assert chunks[1]["content"].startswith("甲" * 50)
    assert "乙" * 100 in chunks[1]["content"]


def test_skip_level_heading_path_does_not_concat_siblings():
    # 跳级标题 H1→H3→H3：第二个 H3 与栈顶 H3 同级，应弹掉再压入，
    # 不得把两个兄弟 H3 串联成 "H1 > H3 > H3"（本章 3.1 P1-7）
    """验证 `'H1 > H3a'` 属于 `paths`，且 `'H1 > H3b'` 属于 `paths`。"""
    blocks = [Block(type="heading", text="H1", level=1),
              Block(type="heading", text="H3a", level=3),
              Block(type="paragraph", text="甲" * 50, page=1),
              Block(type="heading", text="H3b", level=3),
              Block(type="paragraph", text="乙" * 50, page=2)]
    chunks = chunk_blocks(blocks, chunk_size=200, chunk_overlap=0)
    paths = [c["headingPath"] for c in chunks]
    assert "H1 > H3a" in paths
    assert "H1 > H3b" in paths
    assert "H1 > H3a > H3b" not in paths


def test_heading_path_recovers_after_level_drop():
    # H1→H2→H1：回到 H1 时 H2 应被弹掉，路径不残留已结束的子节
    """验证 `'第一章 > 1.1'` 属于 `paths`，且 `'第二章'` 属于 `paths`。"""
    blocks = [Block(type="heading", text="第一章", level=1),
              Block(type="heading", text="1.1", level=2),
              Block(type="paragraph", text="甲" * 50, page=1),
              Block(type="heading", text="第二章", level=1),
              Block(type="paragraph", text="乙" * 50, page=2)]
    chunks = chunk_blocks(blocks, chunk_size=200, chunk_overlap=0)
    paths = [c["headingPath"] for c in chunks]
    assert "第一章 > 1.1" in paths
    assert "第二章" in paths
    assert "第一章 > 1.1 > 第二章" not in paths
