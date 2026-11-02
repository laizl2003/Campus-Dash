"""把文档切成检索用的块。

为什么按标题切而不是按字数硬切：
  标题本身就是语义边界，"## 马氏烧烤"下的三行字天然是一个完整答案；
  按 500 字硬切会把"营业时间"和店名切到两个块里，检索到也没用。

两个细节：
  1. 每个块开头补上「文档标题 / 节标题」，让块离开原文也能自解释；
  2. 超长节按句子再分，相邻块留 50 字重叠，避免答案正好落在切口。
"""

from __future__ import annotations

import re
from dataclasses import dataclass

from .loader import Document

MAX_CHARS = 500
OVERLAP = 50

_HEADING = re.compile(r"^##\s+(.+)$", re.M)


@dataclass
class Chunk:
    """一个可检索的最小单元。"""

    chunk_id: str
    doc_id: str
    title: str
    section: str
    category: str
    campus: str
    text: str


def _split_long(text: str) -> list[str]:
    """按句子切分超长段落，带重叠。"""
    if len(text) <= MAX_CHARS:
        return [text]

    sentences = re.split(r"(?<=[。！？；\n])", text)
    pieces: list[str] = []
    current = ""
    for sentence in sentences:
        if not sentence:
            continue
        if len(current) + len(sentence) > MAX_CHARS and current:
            pieces.append(current)
            current = current[-OVERLAP:] + sentence if OVERLAP else sentence
        else:
            current += sentence
    if current:
        pieces.append(current)
    return pieces or [text]


def chunk_document(doc: Document) -> list[Chunk]:
    """按二级标题切块；没有二级标题时整篇当一块。"""
    body = doc.body
    # 去掉一级标题（标题已经在 metadata 里，重复会稀释向量）
    body = re.sub(r"^#\s+.*$", "", body, count=1, flags=re.M).strip()

    sections: list[tuple[str, str]] = []
    matches = list(_HEADING.finditer(body))
    if not matches:
        sections.append(("", body))
    else:
        head = body[: matches[0].start()].strip()
        if head:
            sections.append(("", head))
        for index, match in enumerate(matches):
            end = matches[index + 1].start() if index + 1 < len(matches) else len(body)
            sections.append((match.group(1).strip(), body[match.end(): end].strip()))

    chunks: list[Chunk] = []
    for order, (section, content) in enumerate(sections):
        if not content:
            continue
        for piece_index, piece in enumerate(_split_long(content)):
            # 标题路径写进块正文：检索时模型看到的是完整上下文
            prefix = f"{doc.title} / {section}：" if section else f"{doc.title}："
            text = prefix + piece.strip()
            chunk_id = f"{doc.doc_id}#{order}" + (f"-{piece_index}" if piece_index else "")
            chunks.append(
                Chunk(
                    chunk_id=chunk_id,
                    doc_id=doc.doc_id,
                    title=doc.title,
                    section=section,
                    category=doc.category,
                    campus=doc.campus,
                    text=text,
                )
            )
    return chunks
