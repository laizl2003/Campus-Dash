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
import re
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

    # 两路都可能一条没召回（"今天天气怎么样"），max() 空序列会抛异常
    keyword_max = max(((row[5] or 0.0) for row in keyword_rows), default=0.0) or 1.0
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

# ---------------------------------------------------------------- 置信度

# 置信度用**绝对分**算，不能用融合分：
# 融合分里的关键词项是"除以本轮最高分"归一化的，第一名永远拿满分，
# 于是"今天天气怎么样"这种毫无关系的问句也能拿到 0.55（实测）。
# 这里改成 0.6*余弦 + 0.4*(BM25/4)，两者都是绝对量，无关问句会掉到 0.2 以下。
HIGH_CONFIDENCE = 0.62
MEDIUM_CONFIDENCE = 0.57
# 评测集实测分布（见 scripts/eval_kb.py 的 50 条 + 5 条应拒答）：
#   正例 0.59~0.80，负例 0.11~0.55，0.57 是两者之间的空档

# 查询里的口语成分，展开时换成知识库的说法
_QUERY_SYNONYMS = [
    ("几点关门", "营业时间"),
    ("几点开门", "营业时间"),
    ("几点", "营业时间"),
    ("什么时候", "营业时间"),
    ("关门", "营业时间"),
    ("开门", "营业时间"),
    ("在哪", "位置"),
    ("怎么走", "位置"),
    ("有什么", ""),
    ("哪些", ""),
]

_ALIAS_LINE = re.compile(r"别名[:：]\s*([^\n]+)")


def expand_queries(query: str, limit: int = 3) -> list[str]:
    """把一句话展开成多个检索式（多路召回）。

    为什么不用模型改写：工具调用本来就多一次模型往返，再加一次会明显变慢。
    这里用"口语→书面语"的替换 + 关键词切分，成本为零、结果可预期，
    实测对"几点关门"这类问法提升明显。
    """
    text = (query or "").strip()
    if not text:
        return []

    variants = [text]
    normalized = text
    for spoken, formal in _QUERY_SYNONYMS:
        if spoken in normalized:
            normalized = normalized.replace(spoken, formal)
    normalized = " ".join(normalized.split())
    if normalized and normalized != text:
        variants.append(normalized.strip())

    # 去掉追问句式后再来一次："中区综合馆有哪些场馆" -> "中区综合馆"
    core = re.sub(r"(都有哪些|有哪些|有什么|是哪些)\S*$", "", text)
    core = re.sub(r"(在哪里|在哪|怎么走|几点关门|几点开门|营业时间|多少钱|怎么样|吗|呢|？|\?)\s*$", "", core)
    core = core.strip()
    if core and core not in variants and len(core) >= 2:
        variants.append(core)

    # 去掉校区前缀再来一次："西区学专超市" -> "学专超市"。
    # 为什么要单独做：InnoDB 的相关度对长查询会衰减，
    # "西区学专超市"在商店块上只有 0.19 分，反而输给只含"西区"的宿舍块（0.29）。
    for prefix in ("西校区", "中校区", "东校区", "西区", "中区", "东区"):
        if core.startswith(prefix) and len(core) > len(prefix) + 1:
            trimmed = core[len(prefix):].strip()
            if trimmed and trimmed not in variants:
                variants.append(trimmed)
            break

    return variants[:limit]


def _alias_matches(text: str, chunk_text: str) -> str | None:
    """如果 text 正好是这一节的别名，返回这一节的正式名。"""
    match = _ALIAS_LINE.search(chunk_text or "")
    if not match:
        return None
    aliases = [item.strip() for item in match.group(1).split("、") if item.strip()]
    return text if text in aliases else None


def normalize_place(text: str, top_k: int = 5) -> dict | None:
    """把口语地名归一成知识库里的正式名（"三食" -> "西区学生食堂"）。

    只认**别名表里明确写过**的说法：
    宁可归一不了保持原样，也不能把用户说的地点改成别的楼。
    """
    value = (text or "").strip()
    if not value:
        return None

    try:
        results = search_knowledge_db(value, top_k=top_k, expand=False)
    except Exception:  # noqa: BLE001
        return None

    for item in results:
        if _alias_matches(value, item["text"]):
            canonical = (item["section"] or item["title"]).strip()
            if canonical and canonical != value:
                return {"original": value, "canonical": canonical, "title": item["title"]}
    return None

def _merge_results(rankings: list[list[dict]], top_k: int) -> list[dict]:
    """多路检索结果按 RRF 合并：同一块被越多查询命中，排得越前。"""
    scores: dict[str, float] = {}
    by_id: dict[str, dict] = {}
    for ranking in rankings:
        for rank, item in enumerate(ranking):
            key = item["chunk_id"]
            scores[key] = scores.get(key, 0.0) + 1.0 / (RRF_K + rank + 1)
            by_id.setdefault(key, item)
    ordered = sorted(scores.items(), key=lambda pair: pair[1], reverse=True)[:top_k]
    merged = []
    for chunk_id, score in ordered:
        item = dict(by_id[chunk_id])
        item["score"] = round(score, 4)
        merged.append(item)
    return merged


def search_knowledge_db(
    query: str, *, category: str | None = None, top_k: int = 4, store: KnowledgeStore | None = None,
    expand: bool = True,
) -> list[dict]:
    """返回 [{chunk_id, title, section, text, score, confidence}]，已按相关度排序。

    expand=True 时先做多路召回（原句 + 去口语 + 去疑问词），再合并；
    单路命中已经很高时不做展开，省一次检索。
    """
    text = (query or "").strip()
    if not text:
        return []

    store = store or _store()

    def _one(q: str) -> list[dict]:
        keyword_rows = store.keyword_search(q, limit=15, category=category)
        vector_rows = _vector_hits(store, q, limit=15, category=category)
        fused = _fuse(q, keyword_rows, vector_rows)

        by_id: dict[str, tuple] = {}
        for row in keyword_rows:
            by_id[row[0]] = row
        for row, _score in vector_rows:
            by_id.setdefault(row[0], row)

        wanted = _districts(q)
        query_norm = _normalize_number(q)
        adjusted = []
        for chunk_id, score in fused:
            row = by_id.get(chunk_id)
            if row is None:
                continue
            body = row[4] or ""
            section = _normalize_number(row[3] or "")
            if section and section in query_norm:
                score += SECTION_BONUS
            if wanted:
                hit = [token for token in wanted if token in body]
                if hit:
                    score += DISTRICT_BONUS * len(hit)
                elif any(token in body for token in DISTRICT_TOKENS):
                    score -= DISTRICT_PENALTY
            adjusted.append((chunk_id, score))
        adjusted.sort(key=lambda item: item[1], reverse=True)

        # 置信度要用绝对量，所以把每个块的余弦 / BM25 一起带下去
        cos_map = {item[0][0]: float(item[1]) for item in vector_rows}
        bm25_map = {row[0]: float(row[5] or 0.0) for row in keyword_rows}

        results = []
        for chunk_id, score in adjusted[:top_k]:
            row = by_id.get(chunk_id)
            if row is None:
                continue
            cos = cos_map.get(chunk_id, 0.0)
            bm25 = bm25_map.get(chunk_id, 0.0)
            absolute, level = confidence_of(cos, bm25)
            # 命中别名（"三食"）是确定性信号，直接当高置信
            if _alias_matches(text, row[4]):
                level = "high"
                absolute = max(absolute, HIGH_CONFIDENCE)
            results.append({
                "chunk_id": row[0],
                "doc_id": row[1],
                "title": row[2],
                "section": row[3] or "",
                "text": row[4],
                "score": round(score, 4),
                "confidence": level,
                "confidence_score": absolute,
            })
        return results

    results = _one(text)

    # 多路召回：首轮不够自信时才展开，避免每次都多查几遍
    if expand and (not results or results[0].get("confidence") != "high"):
        extra_queries = [q for q in expand_queries(text) if q != text]
        if extra_queries:
            # 展开只用来"补召回"，不改原句的排序：
            # 先放原句结果，再把变体里没出现过的新块追加到后面。
            # 早先用 RRF 混合时，宿舍块因为同时命中"西区""东区"等多个变体，
            # 反而把用户真正要的商店挤下去了（实测 Top1 从 94% 掉到 90%）。
            merged = list(results)
            seen = {item["chunk_id"] for item in merged}
            for question in extra_queries:
                for item in _one(question):
                    if item["chunk_id"] not in seen:
                        merged.append(item)
                        seen.add(item["chunk_id"])
            return merged[:top_k]

    return results


def confidence_of(cos: float, bm25: float) -> tuple[float, str]:
    """把余弦和 BM25 合成一个绝对分，再分档。"""
    normalized = 0.6 * max(cos, 0.0) + 0.4 * min(max(bm25, 0.0) / 4.0, 1.0)
    if normalized >= HIGH_CONFIDENCE:
        return round(normalized, 4), "high"
    if normalized >= MEDIUM_CONFIDENCE:
        return round(normalized, 4), "medium"
    return round(normalized, 4), "low"

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
    """对外入口：给 MCP 工具用。带置信度，低置信直接拒答。"""
    try:
        results = search_knowledge_db(query, category=category, top_k=top_k)
    except Exception as error:  # noqa: BLE001
        return {"ok": False, "error": f"知识库检索失败：{error}"}

    if not results:
        return {"ok": True, "found": 0, "confidence": "low",
                "answer": f"知识库里没有关于「{query}」的信息。", "results": []}

    confidence = results[0].get("confidence", "medium")
    if confidence == "low":
        # 检索分数太低：与其让模型硬答，不如明确说没有
        return {"ok": True, "found": 0, "confidence": "low",
                "answer": f"知识库里没有找到关于「{query}」的可靠信息。", "results": []}

    answer = format_results(results)
    if confidence == "medium":
        answer += "\n\n（以上资料匹配度一般，关键信息建议现场确认）"

    return {
        "ok": True,
        "found": len(results),
        "confidence": confidence,
        "answer": answer,
        "results": results,
    }
