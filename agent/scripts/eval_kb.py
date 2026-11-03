"""知识库检索评测：Top1 / Hit@k / 拒答正确率。

用法：
  python scripts/eval_kb.py          # 默认 k=3
  python scripts/eval_kb.py --k 5

三条指标：
  Top1  —— 正确文档是不是第一条（用户实际看到的就是它）
  Hit@k —— 前 k 条里有没有（衡量召回）
  拒答   —— 问知识库里没有的东西时，必须明确说没有，不能硬答
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.knowledge.search import search_knowledge, search_knowledge_db  # noqa: E402

# (问题, 期望命中的关键词) —— 关键词出现在 title 或 section 里算命中
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
    ("三食几点关门", "西区学生食堂"),
    ("桃李苑在哪里", "桃李苑"),
    ("东区美食广场有什么吃的", "东区美食广场"),
    ("小木屋能取什么快递", "西区小木屋"),
    ("星座餐厅是清真吗", "星座餐厅"),
    ("中区夜宵部几点开", "中区夜宵部"),
    ("西区学专超市几点关门", "学专超市"),
    ("东区超市在哪", "东区学专超市"),
    ("西区打印店价格", "西区打印店"),
    ("东区打印店能洗证件照吗", "东区打印店"),
    ("西区西苑餐厅有什么", "西苑餐厅"),
    ("力学楼在哪", "力学"),
    ("第三教学楼有几个座", "第三教学楼"),
    ("信智学部在哪栋楼", "信智学部"),
    ("校医院怎么走", "校医院"),
    ("中区北门出去是什么路", "中区北门"),
    ("西区操场几点关灯", "西区操场"),
    ("中区综合馆台球多少钱", "中区综合馆"),
    ("西区有没有健身房", "西区健身房"),
    ("东区活动中心有什么", "东区活动中心"),
    ("西区宿舍门禁几点", "西校区学生宿舍"),
    ("东区宿舍是哪几栋", "301"),
    ("肥西路罗森几点关门", "罗森"),
    ("黄山路老马羊肉汤", "老马羊肉汤"),
    ("老地方川菜有包间吗", "老地方川菜"),
    ("蜜雪冰城在哪", "蜜雪冰城"),
    ("瑞幸咖啡几点开门", "瑞幸咖啡"),
    ("尼克斯汉堡贵吗", "尼克斯汉堡"),
    ("好想来零食店", "好想来零食店"),
    ("肥西路菜鸟驿站", "肥西路"),
    ("西区芳华餐厅怎么样", "芳华餐厅"),
    ("西区教工食堂学生能去吗", "教工食堂"),
    ("金桔园是清真食堂吗", "金桔园"),
    ("东区茶餐厅有什么", "茶餐厅"),
    ("中区宿舍门禁", "中校区学生宿舍"),
]

# 知识库里没有的问题：必须拒答（返回 found=0）
NEGATIVE_CASES = [
    "今天天气怎么样",
    "怎么申请助学贷款",
    "校长办公室电话多少",
    "明天会下雨吗",
    "程序员怎么面试",
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

    refused = 0
    for question in NEGATIVE_CASES:
        result = search_knowledge(question)
        ok = result.get("found") == 0
        refused += ok
        print(("PASS" if ok else "FAIL") + "  [应拒答] " + question + "  ->  found=" + str(result.get("found")))

    total = len(CASES)
    print()
    print(f"Top1   = {top1}/{total} = {top1 / total:.0%}")
    print(f"Hit@{k} = {hits}/{total} = {hits / total:.0%}")
    print(f"拒答   = {refused}/{len(NEGATIVE_CASES)} = {refused / len(NEGATIVE_CASES):.0%}")
    return 0 if hits == total else 1


if __name__ == "__main__":
    raise SystemExit(main())
