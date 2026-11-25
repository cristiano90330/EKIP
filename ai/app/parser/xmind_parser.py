"""读取 XMind 归档里的 JSON 或 XML 主题树，将层级和文本转为结构块。"""

import json
import re
import zipfile
from typing import List
from xml.etree import ElementTree

from ai.app.parser.blocks import Block, BlockType

_DEFAULT_SHEET_NAME = re.compile(r"^Sheet\s*\d+$", re.IGNORECASE)


def _heading(depth: int, text: str) -> Block:
    """将主题深度与文本转换为保留层级的标题块。

    上游为 `_walk_json_topic`，下游调用 `ai.app.parser.blocks.Block`。
    """
    return Block(type=BlockType.HEADING, text=text, level=min(max(depth, 1), 6))


def _walk_json_topic(topic: dict, depth: int, blocks: List[Block]) -> None:
    """递归遍历 JSON 主题树，将标题和备注追加到传入块列表。

    上游为 `_parse_content_json`，下游调用 `_heading`、`ai.app.parser.blocks.Block`。
    """
    title = (topic.get("title") or "").strip()
    if title:
        blocks.append(_heading(depth, title))
    note = ((topic.get("notes") or {}).get("plain") or {}).get("content")
    if note and note.strip():
        blocks.append(Block(type=BlockType.PARAGRAPH, text=note.strip()))
    children = topic.get("children") or {}
    for group in children.values():
        if isinstance(group, list):
            for child in group:
                if isinstance(child, dict):
                    _walk_json_topic(child, depth + 1, blocks)


def _parse_content_json(raw: bytes) -> List[Block]:
    """将归档中的 JSON 内容解析为按主题顺序排列的结构块。

    上游为 `extract_blocks`，下游调用 `_heading`、`_walk_json_topic`。
    """
    payload = json.loads(raw.decode("utf-8"))
    blocks: List[Block] = []
    sheets = payload if isinstance(payload, list) else [payload]
    for sheet in sheets:
        if not isinstance(sheet, dict):
            continue
        title = (sheet.get("title") or "").strip()
        if title and not _DEFAULT_SHEET_NAME.match(title):
            blocks.append(_heading(1, title))
        root = sheet.get("rootTopic")
        if isinstance(root, dict):
            _walk_json_topic(root, 1, blocks)
    return blocks


def _local(tag: str) -> str:
    """从 XML 标签名中截取本地名称，供不同命名空间的 XMind 元素复用解析逻辑。

    上游为 `_walk_xml_topic`、`_parse_content_xml`。
    """
    return tag.rsplit("}", 1)[-1]


def _walk_xml_topic(topic, depth: int, blocks: List[Block]) -> None:
    """递归遍历 XML 主题节点，把标题、正文和子主题追加到结果列表。

    上游为 `_parse_content_xml`，下游调用 `_local`、`_heading`。
    """
    title = ""
    note = ""
    children = []
    for child in list(topic):
        name = _local(child.tag)
        if name == "title":
            title = (child.text or "").strip()
        elif name == "notes":
            # <notes><plain>正文</plain></notes>
            for sub in child.iter():
                if _local(sub.tag) == "plain" and (sub.text or "").strip():
                    note = sub.text.strip()
        elif name == "children":
            for topics in list(child):
                if _local(topics.tag) == "topics":
                    children.extend([t for t in list(topics) if _local(t.tag) == "topic"])
    if title:
        blocks.append(_heading(depth, title))
    if note:
        blocks.append(Block(type=BlockType.PARAGRAPH, text=note))
    for child in children:
        _walk_xml_topic(child, depth + 1, blocks)


def _parse_content_xml(raw: bytes) -> List[Block]:
    """解码归档中的 XML 内容并返回按主题层级提取的结构块。

    上游为 `extract_blocks`，下游调用 `_local`、`_heading`。
    """
    root = ElementTree.fromstring(raw)
    blocks: List[Block] = []
    for sheet in root.iter():
        if _local(sheet.tag) != "sheet":
            continue
        title = ""
        topic = None
        for child in list(sheet):
            name = _local(child.tag)
            if name == "title":
                title = (child.text or "").strip()
            elif name == "topic":
                topic = child
        if title and not _DEFAULT_SHEET_NAME.match(title):
            blocks.append(_heading(1, title))
        if topic is not None:
            _walk_xml_topic(topic, 1, blocks)
    return blocks


def extract_blocks(path: str) -> List[Block]:
    """从 XMind 归档选择可读取的内容版本，返回主题标题和附属文本块。

    上游为 `ai.app.parser.pipeline._parse_by_ext`，下游调用 `_parse_content_json`、`_parse_content_xml`。
    """
    with zipfile.ZipFile(path) as archive:
        names = set(archive.namelist())
        if "content.json" in names:
            return _parse_content_json(archive.read("content.json"))
        if "content.xml" in names:
            return _parse_content_xml(archive.read("content.xml"))
    raise ValueError("XMind 文件缺少 content.json / content.xml")
