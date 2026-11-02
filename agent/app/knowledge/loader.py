"""读取 knowledge/ 下的 Markdown，解析成文档。

格式约定（见 knowledge/README.md）：
  ---
  id: shop-fatixi
  title: 肥西路周边商铺
  category: shop
  campus: outside
  tags: [商铺, 餐饮]
  updated_at: 2026-11-02
  ---

  # 一级标题

  ## 某一节

  正文……

只依赖标准库，不引 yaml：字段就那六个，自己写解析更省事也更好排错。
"""

from __future__ import annotations

import hashlib
import os
import re
from dataclasses import dataclass, field
from pathlib import Path

KNOWLEDGE_DIR = Path(os.getenv("AGENT_KB_DIR", Path(__file__).resolve().parents[2] / "knowledge"))


@dataclass
class Document:
    """一篇知识文档。"""

    doc_id: str
    title: str
    category: str
    campus: str
    tags: list[str]
    body: str
    source_path: str
    content_hash: str = field(default="")

    def __post_init__(self) -> None:
        if not self.content_hash:
            self.content_hash = hashlib.sha256(self.body.encode("utf-8")).hexdigest()


_FRONT_MATTER = re.compile(r"^---\s*\n(.*?)\n---\s*\n", re.S)


def _parse_front_matter(text: str) -> tuple[dict[str, object], str]:
    """解析 YAML front-matter；没有就返回空字典。"""
    match = _FRONT_MATTER.match(text)
    if not match:
        return {}, text

    meta: dict[str, object] = {}
    for line in match.group(1).splitlines():
        if ":" not in line:
            continue
        key, _, raw = line.partition(":")
        key = key.strip()
        raw = raw.strip()
        if not key:
            continue
        if raw.startswith("[") and raw.endswith("]"):
            meta[key] = [item.strip() for item in raw[1:-1].split(",") if item.strip()]
        else:
            meta[key] = raw
    return meta, text[match.end():]


def _as_tags(value: object) -> list[str]:
    if isinstance(value, list):
        return [str(item) for item in value]
    if isinstance(value, str) and value:
        return [value]
    return []


def load_file(path: Path) -> Document | None:
    """读一个 Markdown 文件；缺 id 时用文件名兜底。"""
    try:
        text = path.read_text(encoding="utf-8")
    except OSError:
        return None

    meta, body = _parse_front_matter(text)
    body = body.strip()
    if not body:
        return None

    doc_id = str(meta.get("id") or path.stem)
    return Document(
        doc_id=doc_id,
        title=str(meta.get("title") or path.stem),
        category=str(meta.get("category") or "general"),
        campus=str(meta.get("campus") or "all"),
        tags=_as_tags(meta.get("tags")),
        body=body,
        source_path=str(path),
    )


def load_documents(root: Path | str | None = None) -> list[Document]:
    """递归读取目录下所有 .md（跳过 README 与隐藏文件）。"""
    base = Path(root) if root else KNOWLEDGE_DIR
    if not base.exists():
        return []

    documents: list[Document] = []
    for path in sorted(base.rglob("*.md")):
        if path.name.startswith(".") or path.name.upper() == "README.MD":
            continue
        doc = load_file(path)
        if doc is not None:
            documents.append(doc)
    return documents
