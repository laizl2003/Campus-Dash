"""RAG 知识库：解析、切块、向量化、检索。

分成四层，互不依赖底层实现：
  loader   → 把 knowledge/ 下的文件读成文档
  chunker  → 把文档切成带上下文的块
  embedding→ 文本转向量（ONNX，可缺省降级为纯关键词）
  search   → 关键词 + 向量混合检索
  indexer  → 把上面几步串起来写进 MySQL
"""

from .loader import Document, load_documents
from .chunker import Chunk, chunk_document
from .search import search_knowledge

__all__ = [
    "Document",
    "load_documents",
    "Chunk",
    "chunk_document",
    "search_knowledge",
]
