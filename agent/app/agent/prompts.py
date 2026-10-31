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

# 短期记忆上限：最近 8 条消息、每条 120 字，防止 prompt 随对话膨胀
MAX_HISTORY_MESSAGES = 8
MAX_HISTORY_CHARS = 120

SYSTEM_PROMPT = """你是 CampusDash 校园跑腿平台的任务信息抽取助手。

你的唯一职责：从用户这一句话里，抽取出**本轮新出现**的任务字段，输出 JSON。

你会看到"最近对话"作为短期记忆，用来理解省略和指代（如"就那个""改成刚才说的"）。
判断规则：
1. 后面的说法与前面的**冲突**时，一律以**靠后的说法**为准，不要保留旧值；
2. 后面的说法是对前面的**补充**时，合并处理，不要丢弃已确认的信息；
3. "已收集字段"是当前事实，若本轮这句话与它冲突，以本轮这句话为准。

字段分两类，务必分清，不要串味：

【一、结构化字段】用户的"任务参数"。**即使这些字段之前已经填好，用户现在要改，也要照实返回新值**
（不要因为"已经填过"就忽略他的话，也不要把改动内容写进 description）：

- type: 任务类型，只能是 DELIVERY(跑腿配送) / BUY(帮买) / QUEUE(代排队) / OTHER(其他帮忙)
- title: 任务标题。**通常不要返回**——系统会用"任务目的 + 地点"自动生成；
  只有当用户明确说"标题改成…"时才给新标题
- location_from: 取货地点（DELIVERY/BUY/QUEUE 需要）
- location_to: 送达地点（DELIVERY/BUY/QUEUE 需要）。用户说"改送到…/送到…别送…"时给新值
- reward_cents: 悬赏金额，**整数，单位分**，例如 8 元写作 800。用户说"加钱到…/改成 12 块"时给新值
- expired_at: 任务截止时间（超过该时刻无人接单则任务过期），ISO-8601 格式，
  例如 2026-11-01T15:00:00。用户说"截止…/…点前送到/改成…"时给新值；
  **这是选填项**——用户从头到尾没提，就不要填，表示任务永久有效

【二、任务描述 description】只放**不属于上面任何字段、但和任务有关的补充信息**，
最多 300 字。例如："外卖有汤，不要撒了""书比较重，麻烦带个小推车""到了打电话别敲门"。
- 用户一句话里既有字段修改又有补充说明时，**两部分都要返回**：
  字段进对应字段，剩下的进 description。
  例："标题改成代取快递，另外外卖有汤别撒了"
  → {"title": "代取快递", "description": "外卖有汤，不要撒了"}
- 绝对不要把地点、金额、时间、标题这类字段值写进 description。
- 用户只是说"谢谢/好的"这种没有信息的话，就返回空对象 {}。

类型判断原则：
- DELIVERY(跑腿配送)：**只要涉及"替人把某样东西拿到/送到"就算**，包括取快递、带饭、
  代取物品，也包括还书、送材料、归还东西——它们本质上都是从某地取到物品再送到另一地点。
  **重点**："帮忙拿外卖""帮我拿个快递""顺手把餐带上来"这类"帮忙拿/取/带"的说法一律算 DELIVERY，
  哪怕用户没说要送到哪里（地点缺失是追问的事，不是类型问题）。
  "拿外卖""取外卖""带外卖"都算，不要因为出现"外卖"就归到 BUY——
  BUY 是"还没买、需要跑腿先掏钱买"，外卖通常已经下单付款，属于配送。
- BUY(帮买)：需要跑腿的人**先花钱买**，再由发单同学付钱或报销的（买奶茶、买药、超市代购）。
- QUEUE(代排队)：核心价值是"替人等待"（食堂排队、打印室排队、占座）。
  **代排队没有取货地/送达地**，用户说的排队位置放 location_from，不要填 location_to。
- OTHER(其他帮忙)：**确实不涉及任何物品搬运**的一次性帮忙（帮忙带句话、帮忙看管物品、
  帮忙拍照、帮忙占位）。注意："帮忙"两个字不代表 OTHER——"帮忙拿外卖"仍然是 DELIVERY。

判断优先级：先看有没有"取 → 送"的搬运关系，有就是 DELIVERY；
再看是不是需要采购付款，是就是 BUY；再看是不是以等待为主，是就是 QUEUE；
都不符合才选 OTHER。

严格规则：
1. 只输出用户这句话里**明确说了**的字段，没提到就**不要出现在 JSON 里**。
   唯一的例外是用户表达"改"的意思时——那说明他就是要把这个字段换成新值。
2. 不要臆造地点、时间、金额；不确定就不要给。
3. 金额要换算成分：8 块 -> 800，15.5 元 -> 1550。
4. 时间是未来时间，相对说法要结合下面的"当前时间"换算成绝对时间。
   用户说的"明天三点前/几点送达/截止到某时"都算 expired_at，而不是开始时间。
5. 如果用户只是回答一个地点（例如"3 号楼 502"），就只填对应的地点字段。
6. 只输出 JSON 对象本身，不要 markdown 代码块，不要任何解释文字。"""


# 任务内容规范化：去掉口语，只留事件描述
DESCRIPTION_SYSTEM_PROMPT = """你是校园跑腿任务的内容整理助手。

把用户提供的内容改写成**书面化、客观、可直接给接单同学看**的任务说明。

要求：
1. 去掉口语和语气词：嗯、啊、吧、呢、那个、就是、然后、我的天、麻烦你一下 等；
2. 去掉与任务执行无关的个人情绪和寒暄：谢谢、辛苦了、不好意思、拜托了 等；
3. 去掉对话痕迹（"刚说的""上面那条""补充一下"），只保留事实；
4. 保留所有**执行任务必需**的信息：取件码、门牌号、物品特征、时间、联系方式之外的注意事项；
5. 用陈述句，不加"请""帮我"这类称呼，一句话说清一件事；
6. 多条信息用中文分号"；"分隔；
7. 不要编造原文没有的信息，不要加标题、不要加解释；
8. 最多 300 字，超长时优先保留关键事实。

只输出整理后的任务内容本身，不要任何前缀或说明。"""


def build_description_prompt(existing: str | None, message: str) -> str:
    """把已有内容与本轮新内容一起交给模型整理。"""
    parts = []
    if existing:
        parts.append(f"已有任务内容：{existing}")
    parts.append(f"用户新补充：{message}")
    parts.append("请输出整理后的完整任务内容。")
    return "\n".join(parts)


def _history_block(history: list[dict] | None) -> str:
    """把当前会话的最近几轮拼成短期记忆。

    只取最近几条、每条截断，避免 prompt 随对话变长；
    越靠后的说法越新，system prompt 里已约定冲突时以靠后的为准。
    """
    if not history:
        return ""
    lines: list[str] = []
    for item in history[-MAX_HISTORY_MESSAGES:]:
        role = item.get("role")
        content = str(item.get("content") or "").strip()
        if not content:
            continue
        speaker = "用户" if role == "user" else "助手"
        lines.append(f"{speaker}：{content[:MAX_HISTORY_CHARS]}")
    if not lines:
        return ""
    return "最近对话（越靠后越新）：\n" + "\n".join(lines) + "\n\n"


def _pending_hint(pending: list[Slot]) -> str:
    """针对"正在追问的槽位"给一句额外说明。

    没有这句时，用户回答"取快递到3号楼502"，模型会认为这是一句地点描述，
    只返回 location_to，标题就一直空着、追问循环停不下来（实测踩过）。
    """
    values = {slot.value for slot in pending}
    # 只有"其他都齐了、就差标题"时才这么提示。
    # 如果同时还在问地点，用户这轮多半是在答地点，
    # 无条件提示会把"菜鸟驿站送到3号楼502"整句当成标题（实测踩过）。
    if values == {"title"}:
        return ("注意：除标题外的信息都已收集齐全，本轮只差 title。"
                "用户这句话就是任务标题，请把它填进 title。\n\n")
    return ""


def build_user_prompt(
    message: str,
    draft: TaskDraft,
    pending: list[Slot],
    now: datetime | None = None,
    history: list[dict] | None = None,
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
        "expired_at": "ISO-8601 datetime（选填，任务截止时间）",
        "reward_cents": "integer (分)",
        "description": "string",
    }

    return (
        f"当前时间：{current.strftime('%Y-%m-%d %H:%M:%S')}（星期{'一二三四五六日'[current.weekday()]}）\n"
        f"{_history_block(history)}"
        f"已收集字段：{json.dumps(filled, ensure_ascii=False)}\n"
        f"当前正在追问：{pending_text}\n"
        f"可选字段：{json.dumps(schema_hint, ensure_ascii=False)}\n"
        f"类型中文对照：{json.dumps(TYPE_LABELS, ensure_ascii=False)}\n"
        f"结构化类型（需要地点和时间）：{json.dumps(sorted(STRUCTURED_TYPES))}\n\n"
        f"用户这句话：{message}\n\n"
        f"{_pending_hint(pending)}"
        f"请只输出本轮新增字段的 JSON。"
    )
