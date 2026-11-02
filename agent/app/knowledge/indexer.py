"""建索引：把 knowledge/ 下的文件同步进 MySQL。

两种触发方式：
  1. 启动时后台线程跑一次（容器里不用手工操作，改了文件重启即生效）；
  2. 脚本单独跑（scripts/build_kb.py --force），离线批量重建。

增量策略：比对 content_hash，只重建变了的文档；文件删了就从库里删掉。
"""

from __future__ import annotations

import logging
import threading

from .chunker import chunk_document
from .embedding import get_embedder
from .loader import load_documents
from .store import KnowledgeStore

log = logging.getLogger("knowledge")


def rebuild(force: bool = False, store: KnowledgeStore | None = None) -> dict:
    """同步知识库，返回统计信息。"""
    store = store or KnowledgeStore()
    documents = load_documents()
    existing = store.documents()

    changed = [doc for doc in documents if force or existing.get(doc.doc_id) != doc.content_hash]
    removed = [doc_id for doc_id in existing if doc_id not in {doc.doc_id for doc in documents}]

    for doc_id in removed:
        store.delete_document(doc_id)

    embedder = get_embedder()
    embedded = 0
    for doc in changed:
        chunks = chunk_document(doc)
        vectors = None
        if embedder.available:
            vectors = embedder.encode([chunk.text for chunk in chunks])
            if vectors:
                embedded += len(vectors)
        store.replace_chunks(doc.doc_id, chunks, vectors)
        store.upsert_document(doc)

    docs, chunks_total = store.count()
    stats = {
        "documents": docs,
        "chunks": chunks_total,
        "changed": len(changed),
        "removed": len(removed),
        "embedded": embedded,
        "vector_ready": embedder.available,
    }
    log.info("知识库同步完成 %s", stats)
    return stats


def rebuild_in_background() -> None:
    """启动时后台跑，失败也不影响主服务。"""
    def _run() -> None:
        try:
            rebuild()
        except Exception as error:  # noqa: BLE001
            log.warning("知识库同步失败：%s", error)

    threading.Thread(target=_run, name="kb-indexer", daemon=True).start()
