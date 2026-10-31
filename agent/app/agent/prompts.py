"""提示词模板。

要点：
1. 明确列出可用字段与约束，避免模型编造字段；
2. 把"当前已收集的草稿"与"正在追问的槽位"告诉模型，让它只回答本轮新增信息；
3. 强制 JSON 输出，不接受解释性文字；
4. 明确"没提到就不要输出"，防止模型补默认值（补空白字段会让追问失效）。
"""

from __future__ import annotations

import json
from datetime import datetime

from ..models import STRUCTURED_TYPES, TYPE_LABELS, Slot, TaskDraft

SYSTEM_PROMPT = """你是 CampusDash 校园跑腿平台的任务信息抽取助手。

你的唯一职责：从用户这一句话里，抽取出**本轮新出现**的任务字段，输出 JSON。

字段说明：
- type: 任务类型，只能是 DELIVERY(跑腿配送) / BUY(帮买) / QUEUE(代排队) / OTHER(其他帮忙)
- title: 任务标题，一句话，最多 64 字
- location_from: 取货地点（DELIVERY/BUY/QUEUE 需要）
- location_to: 送达地点（DELIVERY/BUY/QUEUE 需要）
- start_time: 开始时间，ISO-8601 格式，例如 2026-11-01T15:00:00
- reward_cents: 悬赏金额，**整数，单位分**，例如 8 元写作 800
- description: 任务描述，补充细节，最多 300 字

严格规则：
1. 只输出用户这句话里**明确说了**的字段，没提到就**不要出现在 JSON 里**。
2. 不要臆造地点、时间、金额；不确定就不要给。
3. 金额要换算成分：8 块 -> 800，15.5 元 -> 1550。
4. 时间是未来时间，相对说法要结合下面的"当前时间"换算成绝对时间。
5. 如果用户只是回答一个地点（例如"3 号楼 502"），就只填对应的地点字段。
6. 只输出 JSON 对象本身，不要 markdown 代码块，不要任何解释文字。"""


def build_user_prompt(
    message: str,
    draft: TaskDraft,
    pending: list[Slot],
    now: datetime | None = None,
) -> str:
    """把当前上下文拼成一条用户消息。"""
    current = now or datetime.now()
    filled = {k: v for k, v in draft.model_dump(mode="json").items() if v not in (None, "")}
    pending_text = "、".join(slot.value for slot in pending) or "无"

    schema_hint = {
        "type": "DELIVERY | BUY | QUEUE | OTHER",
        "title": "string",
        "location_from": "string",
        "location_to": "string",
        "start_time": "ISO-8601 datetime",
        "reward_cents": "integer (分)",
        "description": "string",
    }

    return (
        f"当前时间：{current.strftime('%Y-%m-%d %H:%M:%S')}（星期{'一二三四五六日'[current.weekday()]}）\n"
        f"已收集字段：{json.dumps(filled, ensure_ascii=False)}\n"
        f"当前正在追问：{pending_text}\n"
        f"可选字段：{json.dumps(schema_hint, ensure_ascii=False)}\n"
        f"类型中文对照：{json.dumps(TYPE_LABELS, ensure_ascii=False)}\n"
        f"结构化类型（需要地点和时间）：{json.dumps(sorted(STRUCTURED_TYPES))}\n\n"
        f"用户这句话：{message}\n\n"
        f"请只输出本轮新增字段的 JSON。"
    )
