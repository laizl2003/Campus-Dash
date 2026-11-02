"""知识库检索评测：量一下 Hit@k，改切块/换模型后跑一遍看趋势。

用法：
  python scripts/eval_kb.py          # 默认 k=3
  python scripts/eval_kb.py --k 5

评测集写死在 CASES 里：问题 + 期望命中的关键词（出现在 title/section 里算命中）。
新增知识后在这里补几条，比"凭感觉觉得还行"靠谱。
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.knowledge.search import search_knowledge_db  # noqa: E402

CASES = [
    ("西区菜鸟驿站几点关门", "西区小木屋"),
    ("肥西路马氏烧烤营业到几点", "马氏烧烤"),
    ("中区综合馆有什么场地", "中区综合馆"),
    ("东区301号楼有几层", "301号楼"),
    ("校医院几点开门", "校医院"),
    ("黄山路东北王餐厅在哪", "东北王餐厅"),
    ("打印店在哪", "打印店"),
    ("西区有几个食堂", "食堂"),
    ("中区游泳馆开到几点", "游泳馆"),
    ("东区羽毛球馆怎么预约", "羽毛球馆"),
    ("西区健身房在几楼", "健身房"),
    ("东区南门几点关门", "东区南门"),
    ("肥西路小吃摊几点出摊", "小吃摊"),
    ("中区宿舍楼有几层", "中校区学生宿舍"),
    ("外卖车能进校吗", "校园大门"),
]


def main() -> int:
    k = 3
    if "--k" in sys.argv:
        k = int(sys.argv[sys.argv.index("--k") + 1])

    hits = 0
    top1 = 0
    for question, expected in CASES:
        results = search_knowledge_db(question, top_k=k)
        labels = [f"{item['title']} / {item['section']}" for item in results]
        ok = any(expected in label for label in labels)
        first = bool(labels) and expected in labels[0]
        hits += ok
        top1 += first
        mark = "PASS" if first else ("HIT " if ok else "FAIL")
        print(f"{mark}  {question}  ->  {labels[0] if labels else '-'}")

    total = len(CASES)
    print(f"\nTop1   = {top1}/{total} = {top1 / total:.0%}")
    print(f"Hit@{k} = {hits}/{total} = {hits / total:.0%}")
    return 0 if hits == total else 1


if __name__ == "__main__":
    raise SystemExit(main())
