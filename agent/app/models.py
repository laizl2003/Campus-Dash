"""Agent 领域模型：任务草稿、槽位定义与校验规则。

这里的 schema 同时服务三件事：
1. 约束 LLM 的结构化输出（模型只能返回这些字段）；
2. 驱动槽位追问（缺哪个字段就问哪个）；
3. 作为调用 Java 发布接口的请求体。
"""

from __future__ import annotations

from datetime import datetime
from enum import Enum
from typing import Literal

from pydantic import BaseModel, Field, field_validator

# 与主项目 ErrandType 保持一致
ErrandType = Literal["DELIVERY", "BUY", "QUEUE", "OTHER"]

# DELIVERY/BUY/QUEUE 需要地点与时间；OTHER 只需要标题 + 任务内容
STRUCTURED_TYPES: frozenset[str] = frozenset({"DELIVERY", "BUY", "QUEUE"})

TYPE_LABELS: dict[str, str] = {
    "DELIVERY": "跑腿配送",
    "BUY": "帮买",
    "QUEUE": "代排队",
    "OTHER": "其他帮忙",
}


class Slot(str, Enum):
    """可追问的槽位。顺序即追问优先级。"""

    TYPE = "type"
    TITLE = "title"
    LOCATION_FROM = "location_from"
    LOCATION_TO = "location_to"
    START_TIME = "start_time"
    REWARD = "reward_cents"
    DESCRIPTION = "description"


# 追问时给用户看的问题文案
SLOT_QUESTIONS: dict[Slot, str] = {
    Slot.TYPE: "这是哪一类任务？跑腿配送、帮买、代排队，还是其他帮忙？",
    Slot.TITLE: "这件任务用一句话怎么描述？我会把它作为任务标题。",
    Slot.LOCATION_FROM: "从哪里取货或开始？例如：菜鸟驿站（东门）。",
    Slot.LOCATION_TO: "送到哪里？例如：3 号楼 502。",
    Slot.START_TIME: "希望什么时候开始？可以写“今天下午 3 点”或具体日期时间。",
    Slot.REWARD: "悬赏金额是多少？例如 8 元。",
    Slot.DESCRIPTION: "还有其他要补充的内容吗？没有可以直接回复“没有”。",
}


class TaskDraft(BaseModel):
    """任务草稿。字段全部可空，因为它是"逐步填充"的中间态。"""

    type: ErrandType | None = None
    title: str | None = Field(default=None, max_length=64)
    location_from: str | None = Field(default=None, max_length=128)
    location_to: str | None = Field(default=None, max_length=128)
    start_time: datetime | None = None
    reward_cents: int | None = Field(default=None, gt=0)
    description: str | None = Field(default=None, max_length=300)

    @field_validator("title", "location_from", "location_to", "description")
    @classmethod
    def _strip(cls, value: str | None) -> str | None:
        if value is None:
            return None
        text = value.strip()
        return text or None

    def filled(self) -> dict[str, object]:
        return {key: value for key, value in self.model_dump().items() if value not in (None, "")}


def required_slots(draft: TaskDraft) -> list[Slot]:
    """计算当前还缺哪些必填槽位。

    这是"追问到什么程度算完"的唯一判定入口：
    - 类型未知时，只追问类型；
    - 类型确定后，OTHER 不要求地点/时间，其余三种类型要求；
    - 描述始终是选填，不进必填列表。
    """
    if draft.type is None:
        return [Slot.TYPE]

    missing: list[Slot] = []
    if not draft.title:
        missing.append(Slot.TITLE)
    if draft.type in STRUCTURED_TYPES:
        if not draft.location_from:
            missing.append(Slot.LOCATION_FROM)
        if not draft.location_to:
            missing.append(Slot.LOCATION_TO)
        if draft.start_time is None:
            missing.append(Slot.START_TIME)
    if draft.reward_cents is None:
        missing.append(Slot.REWARD)
    return missing


def is_complete(draft: TaskDraft) -> bool:
    return not required_slots(draft)


def merge_draft(current: TaskDraft, patch: dict[str, object]) -> TaskDraft:
    """把本轮抽取结果合并进草稿。

    规则：只覆盖非空值，已有的信息不会被"没提到"冲掉。
    这样多轮追问时，用户只回答一个字段也不会丢之前的输入。
    """
    data = current.model_dump()
    for key, value in patch.items():
        if key not in data:
            continue
        if value is None or value == "":
            continue
        data[key] = value
    return TaskDraft.model_validate(data)


# 一次追问最多列几项，太多会让用户不知从何答起
MAX_QUESTIONS_PER_TURN = 3


def next_question(missing: list[Slot]) -> str | None:
    """根据缺失槽位生成追问。

    缺一个就问一句；缺多个时合并成一条清单一次问全，
    用户往往能一口气答完（"菜鸟驿站送到3号楼502，明天下午三点"），
    比来回问三轮体验好很多。
    """
    if not missing:
        return None
    if len(missing) == 1:
        return SLOT_QUESTIONS[missing[0]]

    chosen = missing[:MAX_QUESTIONS_PER_TURN]
    lines = [SLOT_QUESTIONS[slot].rstrip("。") for slot in chosen]
    body = "\n".join(f"· {line}" for line in lines)
    extra = len(missing) - len(chosen)
    tail = f"\n（还有 {extra} 项，先回答这些即可。）" if extra > 0 else ""
    return f"还需要这些信息，可以一起告诉我：\n{body}{tail}"


# 信息齐全后，允许用户继续补充"任务内容"。
# 这个状态不阻塞发布，只是给用户一个继续完善的入口。
READY_HINT = "还需要补充任务内容吗？直接回复补充说明，或点「确认发布」提交。"

# 用户表示"没有了/不用了"时，视为不再追加
NO_MORE_HINTS = (
    "没有", "没有了", "不用", "没了", "不需要", "就这些", "就这样",
    "无", "行了", "可以了", "够了", "好了",
)


def is_no_more(message: str) -> bool:
    """判断用户是否表示"不再补充"。"""
    text = message.strip().rstrip("。.！!~ ")
    return text in NO_MORE_HINTS


def append_description(current: TaskDraft, extra: str) -> TaskDraft:
    """把用户补充的内容追加到任务描述。

    已有描述时用分号拼接，并限制在 300 字以内（与后端字段长度一致）。
    """
    text = extra.strip()
    if not text:
        return current
    merged = f"{current.description}；{text}" if current.description else text
    return current.model_copy(update={"description": merged[:300]})
