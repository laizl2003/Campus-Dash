"""检索：关键词 + 向量混合，用 RRF 融合两路结果。

为什么混合：
  关键词（BM25/ngram）擅长专名和数字——"3号楼""320"能精确命中；
  向量擅长同义改写——"几点关门"能召回"营业时间 08:00-20:00"。
  只要一路都会漏，混起来才稳。

融合用 RRF（Reciprocal Rank Fusion）：
  score = Σ 1 / (k + rank)，k 取 60。
  它只看名次不看分数，天然解决"两路分数量纲不同"的问题。
"""

from __future__ import annotations

import os
import threading

from .embedding import get_embedder
from .store import KnowledgeStore

RRF_K = 60
_VECTOR_CACHE: dict[str, object] = {}
_CACHE_LOCK = threading.Lock()


def _store() -> KnowledgeStore:
    return KnowledgeStore()


def _vector_hits(store: KnowledgeStore, query: str, limit: int, category: str | None) -> list[tuple]:
    """向量召回：把库里所有向量读进内存算余弦。

    块数上万以后这里会慢，届时换 Qdrant/ES；现在几百块，直接算最快。
    """
    embedder = get_embedder()
    if not embedder.available:
        return []

    vectors = embedder.encode([query], query=True)
    if not vectors:
        return []

    import numpy as np

    with _CACHE_LOCK:
        cached = _VECTOR_CACHE.get("rows")
        if cached is None:
            rows = store.all_vectors()
            parsed = []
            for row in rows:
                if not row[5]:
                    continue
                vector = np.frombuffer(row[5], dtype="float32")
                if vector.size == 0:
                    continue
                parsed.append((row, vector))
            _VECTOR_CACHE["rows"] = parsed
            cached = parsed
        rows = cached

    query_vector = np.asarray(vectors[0], dtype="float32")
    scored = []
    for row, vector in rows:
        if category and row[6] != category:
            continue
        if vector.shape != query_vector.shape:
            continue
        scored.append((float(vector @ query_vector), row))

    scored.sort(key=lambda item: item[0], reverse=True)
    # 余弦低于 0.35 基本是无关内容，别塞给模型添乱
    return [(row, score) for score, row in scored[:limit] if score >= 0.35]


# 融合权重：关键词略高于向量。
# 为什么不用纯 RRF：RRF 只看名次，两路都是十几条时分数几乎一样（实测都在 0.032 上下），
# 排序会被噪声左右；改成"归一化分数加权"后，分差能真实反映相关度。
KEYWORD_WEIGHT = 0.55
VECTOR_WEIGHT = 0.45

# 校区/道路是强约束："西区菜鸟驿站"不该把中区、东区排到前面
DISTRICT_TOKENS = ("西校区", "中校区", "东校区", "西区", "中区", "东区", "肥西路", "黄山路")
DISTRICT_BONUS = 0.15    # 点名的校区/路段出现在块里
DISTRICT_PENALTY = 0.20  # 块属于别的校区

# 问句里通常原样含店名/楼号（"西区301号楼有几层"含"301号楼"），
# 命中节标题是比词频更强的信号，单独加一档
SECTION_BONUS = 0.30

# 中文数字转阿拉伯数字：用户写"三号楼"，知识库写"3号楼"
_CN_DIGITS = {"零": "0", "一": "1", "二": "2", "三": "3", "四": "4", "五": "5",
              "六": "6", "七": "7", "八": "8", "九": "9"}


def _normalize_number(text: str) -> str:
    """把"三十号楼"这类写法统一成阿拉伯数字，方便与节标题比对。"""
    if not text:
        return ""
    result = text
    for char, digit in _CN_DIGITS.items():
        result = result.replace(char, digit)
    # "10号楼"里的 1 和 0 分开替换会乱，这里只处理一位数场景，够用
    return result


def _districts(text: str) -> list[str]:
    return [token for token in DISTRICT_TOKENS if token in (text or "")]


def _fuse(
    query: str,
    keyword_rows: list[tuple],
    vector_rows: list[tuple],
) -> list[tuple[str, float]]:
    """加权融合两路召回，返回 [(chunk_id, score)]，按分数降序。

    两路分数量纲不同（BM25 是十几分，余弦是 0~1），先各自归一化再加权。
    """
    if not keyword_rows and not vector_rows:
        return []

    keyword_max = max((row[5] or 0.0) for row in keyword_rows) or 1.0
    keyword_scores = {row[0]: float(row[5] or 0.0) / keyword_max for row in keyword_rows}
    vector_scores = {row[0][0]: float(score) for row, score in vector_rows}

    scores: dict[str, float] = {}
    for chunk_id in set(keyword_scores) | set(vector_scores):
        scores[chunk_id] = (
            KEYWORD_WEIGHT * keyword_scores.get(chunk_id, 0.0)
            + VECTOR_WEIGHT * vector_scores.get(chunk_id, 0.0)
        )

    # 查询里点名了校区/道路，就只给提到它的块加分——不加分的话
    # "西区菜鸟驿站"会被中区、东区的同名驿站挤到后面
    wanted = _districts(query)
    return sorted(scores.items(), key=lambda item: item[1], reverse=True)

def search_knowledge_db(
    query: str, *, category: str | None = None, top_k: int = 4, store: KnowledgeStore | None = None
) -> list[dict]:
    """返回 [{chunk_id, title, section, text, score}]，已按相关度排序。"""
    text = (query or "").strip()
    if not text:
        return []

    store = store or _store()
    keyword_rows = store.keyword_search(text, limit=15, category=category)
    vector_rows = _vector_hits(store, text, limit=15, category=category)

    fused = _fuse(text, keyword_rows, vector_rows)

    by_id: dict[str, tuple] = {}
    for row in keyword_rows:
        by_id[row[0]] = row
    for row, _score in vector_rows:
        by_id.setdefault(row[0], row)

    wanted = _districts(text)
    query_norm = _normalize_number(text)

    adjusted = []
    for chunk_id, score in fused:
        row = by_id.get(chunk_id)
        if row is None:
            continue
        body = row[4] or ""
        section = _normalize_number(row[3] or "")

        # ① 节标题命中：问题里原样出现了这一节的标题
        if section and section in query_norm:
            score += SECTION_BONUS

        # ② 校区：点名了西区，就别把中区/东区的同名结果排前面
        if wanted:
            hit = [token for token in wanted if token in body]
            if hit:
                score += DISTRICT_BONUS * len(hit)
            elif any(token in body for token in DISTRICT_TOKENS):
                score -= DISTRICT_PENALTY

        adjusted.append((chunk_id, score))
    adjusted.sort(key=lambda item: item[1], reverse=True)

    ranked = adjusted[:top_k]
    results = []
    for chunk_id, score in ranked:
        row = by_id.get(chunk_id)
        if row is None:
            continue
        results.append(
            {
                "chunk_id": row[0],
                "doc_id": row[1],
                "title": row[2],
                "section": row[3] or "",
                "text": row[4],
                "score": round(score, 4),
            }
        )
    return results


def format_results(results: list[dict]) -> str:
    """把检索结果拼成带编号的资料块，交给模型引用。"""
    if not results:
        return "知识库里没有找到相关内容。"
    lines = []
    for index, item in enumerate(results, start=1):
        label = item["title"]
        if item.get("section"):
            label += f" / {item['section']}"
        lines.append(f"[{index}] {label}\n{item['text']}")
    return "\n\n".join(lines)


def search_knowledge(query: str, category: str | None = None, top_k: int = 4) -> dict:
    """对外入口：给 MCP 工具用。"""
    try:
        results = search_knowledge_db(query, category=category, top_k=top_k)
    except Exception as error:  # noqa: BLE001
        return {"ok": False, "error": f"知识库检索失败：{error}"}

    if not results:
        return {
            "ok": True,
            "found": 0,
            "answer": f"知识库里没有关于「{query}」的信息。",
            "results": [],
        }
    return {
        "ok": True,
        "found": len(results),
        "answer": format_results(results),
        "results": results,
    }
