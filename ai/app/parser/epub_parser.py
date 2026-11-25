"""根据 EPUB 阅读顺序遍历章节 HTML，提取标题、正文和表格。"""

import posixpath
import zipfile
from typing import List
from xml.etree import ElementTree

from bs4 import BeautifulSoup

from ai.app.parser.blocks import Block, BlockType

_CONTAINER_NS = {"c": "urn:oasis:names:tc:opendocument:xmlns:container"}
_OPF_NS = {"opf": "http://www.idpf.org/2007/opf"}
_HEADINGS = {"h1": 1, "h2": 2, "h3": 3, "h4": 4, "h5": 5, "h6": 6}
_CONTAINERS = {"body", "div", "section", "article", "main", "header", "footer", "blockquote"}


def _spine_documents(archive: zipfile.ZipFile) -> List[str]:
    """从 EPUB 清单与阅读序列解析章节文档路径，供提取阶段按书中顺序读取。

    上游为 `extract_blocks`。
    """
    container = ElementTree.fromstring(archive.read("META-INF/container.xml"))
    rootfile = container.find(".//c:rootfile", _CONTAINER_NS)
    if rootfile is None:
        raise ValueError("EPUB 缺少 META-INF/container.xml 的 rootfile")
    opf_path = rootfile.get("full-path")
    opf = ElementTree.fromstring(archive.read(opf_path))
    base = posixpath.dirname(opf_path)
    manifest = {
        item.get("id"): posixpath.join(base, item.get("href"))
        for item in opf.findall(".//opf:manifest/opf:item", _OPF_NS)
    }
    documents: List[str] = []
    for ref in opf.findall(".//opf:spine/opf:itemref", _OPF_NS):
        path = manifest.get(ref.get("idref"))
        if path and path in archive.namelist():
            documents.append(path)
    return documents


def _table_rows(table) -> List[List[str]]:
    """将章节 HTML 表格转成文本单元格行，供正文遍历加入表格块。

    上游为 `_walk`。
    """
    rows: List[List[str]] = []
    for row in table.find_all("tr"):
        cells = [cell.get_text(" ", strip=True) for cell in row.find_all(["td", "th"])]
        if any(cells):
            rows.append(cells)
    return rows


def _walk(node, blocks: List[Block]) -> None:
    """递归读取章节节点，将标题、正文和表格块追加到传入列表。

    上游为 `extract_blocks`，下游调用 `ai.app.parser.blocks.Block`、`_table_rows`。
    """
    for child in node.children:
        name = getattr(child, "name", None)
        if not name:
            continue
        if name in _HEADINGS:
            text = child.get_text(" ", strip=True)
            if text:
                blocks.append(Block(type=BlockType.HEADING, text=text, level=_HEADINGS[name]))
        elif name == "table":
            rows = _table_rows(child)
            if rows:
                blocks.append(Block(type=BlockType.TABLE, text="",
                                    table_id=f"t{len([b for b in blocks if b.type == BlockType.TABLE]) + 1}",
                                    rows=rows))
        elif name in ("ul", "ol"):
            items = [item.get_text(" ", strip=True) for item in child.find_all("li", recursive=False)]
            items = [item for item in items if item]
            if items:
                blocks.append(Block(type=BlockType.LIST, text="\n".join(items)))
        elif name in _CONTAINERS:
            _walk(child, blocks)
        elif name in ("p", "pre", "dt", "dd"):
            text = child.get_text(" ", strip=True)
            if text:
                blocks.append(Block(type=BlockType.PARAGRAPH, text=text))
        elif name in ("img", "image", "link", "meta", "title", "style", "script"):
            continue
        else:
            text = child.get_text(" ", strip=True)
            if text:
                blocks.append(Block(type=BlockType.PARAGRAPH, text=text))


def extract_blocks(path: str) -> List[Block]:
    """打开 EPUB 归档并按阅读顺序解析章节，返回可统一切分的结构块。

    上游为 `ai.app.parser.pipeline._parse_by_ext`，下游调用 `_spine_documents`、`_walk`。
    """
    blocks: List[Block] = []
    with zipfile.ZipFile(path) as archive:
        for document in _spine_documents(archive):
            soup = BeautifulSoup(archive.read(document), "html.parser")
            body = soup.body or soup
            _walk(body, blocks)
    return blocks
