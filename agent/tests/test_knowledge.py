"""知识库（RAG）单元测试：解析、切块、融合排序、格式化。"""

from __future__ import annotations

from app.knowledge.chunker import chunk_document
from app.knowledge.loader import Document, load_documents


def test_load_documents_reads_front_matter():
    docs = load_documents()
    assert docs, "knowledge/ 下应该有文档"

    by_id = {doc.doc_id: doc for doc in docs}
    assert "dorm-west" in by_id
    dorm = by_id["dorm-west"]
    assert dorm.category == "dorm"
    assert dorm.campus == "west"
    assert dorm.title.startswith("西校区")
    # 正文里不该再有 front-matter 残留
    assert not dorm.body.startswith("---")
    assert "1号楼" in dorm.body


def test_load_documents_skips_readme():
    docs = load_documents()
    assert all(not doc.source_path.endswith("README.md") for doc in docs)


def _doc(body: str) -> Document:
    return Document(
        doc_id="t-1", title="测试文档", category="shop", campus="all",
        tags=[], body=body, source_path="memory",
    )


def test_chunk_by_heading_and_keep_context():
    doc = _doc("## 马氏烧烤\n\n营业时间 08:00-21:00。\n\n## 罗森便利店\n\n24 小时。")
    chunks = chunk_document(doc)

    assert len(chunks) == 2
    assert chunks[0].section == "马氏烧烤"
    # 块正文自带"文档标题 / 节标题"，离开原文也能读懂
    assert chunks[0].text.startswith("测试文档 / 马氏烧烤：")
    assert "08:00-21:00" in chunks[0].text


def test_chunk_splits_long_section():
    body = "## 长文\n\n" + "这是一句测试内容。" * 120
    chunks = chunk_document(_doc(body))

    assert len(chunks) > 1
    assert all(len(chunk.text) < 900 for chunk in chunks)
    # 相邻块有重叠，答案不会正好掉在切口
    assert chunks[0].text[-20:] in chunks[1].text or chunks[1].text[:50] in chunks[0].text


def test_chunk_ids_are_stable():
    doc = _doc("## A\n\n内容一。\n\n## B\n\n内容二。")
    first = [chunk.chunk_id for chunk in chunk_document(doc)]
    second = [chunk.chunk_id for chunk in chunk_document(doc)]
    assert first == second


def test_search_formats_with_citations():
    from app.knowledge.search import format_results

    text = format_results([
        {"title": "肥西路周边商铺", "section": "马氏烧烤", "text": "营业时间 08:00-21:00"},
        {"title": "校内快递驿站", "section": "西区小木屋", "text": "08:00-20:00"},
    ])

    assert text.startswith("[1] 肥西路周边商铺 / 马氏烧烤")
    assert "[2] 校内快递驿站 / 西区小木屋" in text


def test_search_without_index_says_not_found():
    """库里没东西时不能编，要明确说没有。"""
    from app.knowledge import search as search_module

    class EmptyStore:
        def keyword_search(self, *args, **kwargs):
            return []

        def all_vectors(self):
            return []

    result = search_module.search_knowledge_db("随便问问", store=EmptyStore())
    assert result == []


def test_embedder_degrades_when_model_missing():
    """模型没下载时 available=False，检索退回纯关键词而不是崩掉。"""
    from app.knowledge.embedding import Embedder

    embedder = Embedder(model_dir="/nonexistent/path")
    assert embedder.available is False
    assert embedder.encode(["测试"]) is None


def test_dsn_parse_without_scheme():
    """连接串没有 mysql:// 前缀，urlparse 会解析错，必须走自定义解析。"""
    from app.knowledge.store import KnowledgeStore

    cfg = KnowledgeStore._parse("mysql:3306/campus_dash?user=root&password=dash123456")
    assert cfg["host"] == "mysql"
    assert cfg["port"] == 3306
    assert cfg["user"] == "root"
    assert cfg["password"] == "dash123456"
    assert cfg["database"] == "campus_dash"
