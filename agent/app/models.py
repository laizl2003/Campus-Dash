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
    EXPIRED_AT = "expired_at"
    REWARD = "reward_cents"
    DESCRIPTION = "description"


# 追问文案：统一成问句，并以问号结尾
SLOT_QUESTIONS: dict[Slot, str] = {
    Slot.TYPE: "这是哪一类任务（跑腿配送 / 帮买 / 代排队 / 其他帮忙）？",
    Slot.TITLE: "任务标题是什么？用一句话说清要做的事。",
    Slot.LOCATION_FROM: "从哪里取货或开始？",
    Slot.LOCATION_TO: "送到哪里？",
    Slot.EXPIRED_AT: "截止时间是什么时候？",
    Slot.REWARD: "悬赏金额是多少？",
    Slot.DESCRIPTION: "还有其他要补充的内容吗？",
}

# 每个问句后面的补充说明，不提问、只给例子，避免和问句混在一起
SLOT_HINTS: dict[Slot, str] = {
    Slot.TYPE: "",
    Slot.TITLE: "",
    Slot.LOCATION_FROM: "如：菜鸟驿站东门",
    Slot.LOCATION_TO: "如：3 号楼 502",
    Slot.EXPIRED_AT: "可选，如：明天下午 3 点",
    Slot.REWARD: "如：8 元",
    Slot.DESCRIPTION: "",
}

# 追问末尾固定挂着的一行：引导用户补充任务细节。
# 在必填字段填完之前一直存在，让用户知道随时可以补充、而且该补充什么。
DESCRIPTION_PROMPT_LINE = "任务细节的描述？（可选，如：外卖有汤，不要撒了）"


class TaskDraft(BaseModel):
    """任务草稿。字段全部可空，因为它是"逐步填充"的中间态。"""

    type: ErrandType | None = None
    title: str | None = Field(default=None, max_length=64)
    location_from: str | None = Field(default=None, max_length=128)
    location_to: str | None = Field(default=None, max_length=128)
    expired_at: datetime | None = None
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
        # 截止时间是选填：不填代表任务永久有效，不参与必填判定
    if draft.reward_cents is None:
        missing.append(Slot.REWARD)
    return missing


def is_complete(draft: TaskDraft) -> bool:
    return not required_slots(draft)


# 一旦有值就不再被后续轮次自动改写的字段。
# 标题最典型：用户第三轮提到"外卖"，模型会把"取快递"改成"取外卖送到3号楼502"，
# 但用户其实只是在描述细节。标题只应由明确的修改意图（EDIT）变更。
LOCKED_FIELDS: frozenset[str] = frozenset({"title", "type"})


def merge_draft(
    current: TaskDraft,
    patch: dict[str, object],
    *,
    allow_locked: bool = False,
) -> TaskDraft:
    """把本轮抽取结果合并进草稿。

    规则：
    1. 只覆盖非空值，已有的信息不会被"没提到"冲掉；
    2. title/type 默认锁定：已有值就不动，除非 allow_locked=True（EDIT 意图）。

    这样多轮追问时，用户只回答一个字段不会丢之前的输入，
    也不会因为顺口提了别的词就把标题改掉。
    """
    data = current.model_dump()
    for key, value in patch.items():
        if key not in data:
            continue
        if value is None or value == "":
            continue
        if not allow_locked and key in LOCKED_FIELDS and data.get(key):
            continue
        data[key] = value
    return TaskDraft.model_validate(data)


# 一次追问最多列几项，太多会让用户不知从何答起
MAX_QUESTIONS_PER_TURN = 3


def _slot_line(slot: Slot) -> str:
    """单个槽位的一行：问句 + 例子（例子单独一段，不抢问句的焦点）。"""
    question = SLOT_QUESTIONS[slot]
    hint = SLOT_HINTS.get(slot, "")
    return f"· {question}（{hint}）" if hint else f"· {question}"


def next_question(
    missing: list[Slot],
    include_description_hint: bool = True,
    include_expired_hint: bool = True,
    draft: TaskDraft | None = None,
) -> str | None:
    """根据缺失槽位生成追问。

    规则：
    1. 每一行都是问句，以问号结尾，格式统一；
    2. 缺多个必填字段时合并成清单，一次问全；
    3. 倒数第二行固定是"截止时间"——选填，但多数任务用得上；
    4. 最后一行固定是"任务细节描述"，同样选填。

    certified 必填只有 title/地点/悬赏：截止时间与细节都不参与
    required_slots 判定，用户不提就当作"永久有效 / 无补充"。
    """
    # 没有缺失字段就不追问（此时已进入 READY，走意图分支）
    if not missing:
        return None

    # description 是选填，不参与"还缺什么"的清单，只作为末尾提示
    required_missing = [slot for slot in missing if slot is not Slot.DESCRIPTION]
    if not required_missing and not include_description_hint and not include_expired_hint:
        return None

    lines: list[str] = []
    if required_missing:
        chosen = required_missing[:MAX_QUESTIONS_PER_TURN]
        lines = [_slot_line(slot) for slot in chosen]
        extra = len(required_missing) - len(chosen)
        if extra > 0:
            # 剩余项数紧跟必填清单，避免插在"截止时间/细节"这两个选填提示之间
            lines.append(f"（还有 {extra} 项必填，回答完这些再继续）")

    # 截止时间固定在倒数第二行：它是选填，但多数任务需要，放在细节提示之前
    if include_expired_hint and not (draft and draft.expired_at):
        lines.append(_slot_line(Slot.EXPIRED_AT))

    if include_description_hint:
        lines.append(f"· {DESCRIPTION_PROMPT_LINE}")

    if not lines:
        return None
    if len(lines) == 1:
        # 只剩细节提示时，单独成句，不加前缀
        return lines[0].lstrip("· ").strip()
    return "还需要这些信息，可以一起告诉我：\n" + "\n".join(lines)


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
