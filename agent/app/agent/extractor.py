"""LLM 抽取器接口、规则兜底与空实现。

设计说明
--------
1. 抽取器只负责"从自然语言里读出字段"，不负责追问、不负责发布。
2. 追问由状态机（graph.py）根据缺失槽位决定，不交给模型自由发挥，
   这样"缺字段必问、问几次、问哪个"是确定且可测试的。
3. extractor 返回的必须是 JSON 字典；模型返回非法内容时抛 ExtractionError，
   由上层降级为"本轮没抽取到新信息"，不会写坏草稿。

三种实现
--------
- RuleExtractor   确定性关键词兜底，没有模型也能跑通 Demo（当前默认）
- LLMExtractor    留给真实模型，方法体留空，接上后替换即可
- EmptyExtractor  什么都不抽，用于测试最"笨"的情况
"""

from __future__ import annotations

import re
from abc import ABC, abstractmethod
from datetime import datetime, timedelta

from ..models import STRUCTURED_TYPES, TaskDraft


class ExtractionError(RuntimeError):
    """模型输出无法解析成字段字典。"""


class Extractor(ABC):
    """把一句自然语言转成任务字段 patch。"""

    @abstractmethod
    def extract(self, message: str, draft: TaskDraft) -> dict[str, object]:
        raise NotImplementedError


TYPE_KEYWORDS: list[tuple[str, tuple[str, ...]]] = [
    ("DELIVERY", ("跑腿", "配送", "快递", "取件", "外卖", "送餐", "拿")),
    ("BUY", ("帮买", "买", "采购", "超市", "奶茶", "咖啡", "带一份", "带饭")),
    ("QUEUE", ("排队", "代排", "占座", "取号", "打饭")),
    ("OTHER", ("其他", "别的", "随便", "还书", "帮忙", "借")),
]

REWARD_PATTERN = re.compile(r"(\d+(?:\.\d{1,2})?)\s*(?:元|块|rmb|人民币)", re.IGNORECASE)


def guess_type(message: str) -> str | None:
    """按关键词猜类型；命中多个时按优先级取第一个。"""
    for errand_type, keywords in TYPE_KEYWORDS:
        if any(keyword in message for keyword in keywords):
            return errand_type
    return None


def guess_reward_cents(message: str) -> int | None:
    match = REWARD_PATTERN.search(message)
    if not match:
        return None
    cents = round(float(match.group(1)) * 100)
    return cents if cents > 0 else None


CN_HOURS: dict[str, int] = {
    "一": 1, "二": 2, "两": 2, "三": 3, "四": 4, "五": 5,
    "六": 6, "七": 7, "八": 8, "九": 9, "十": 10,
    "十一": 11, "十二": 12, "十三": 13, "十四": 14,
}


def _cn_hour(token: str) -> int | None:
    """把"三""十一"这类中文小时转成数字。"""
    return CN_HOURS.get(token)


def guess_start_time(message: str, now: datetime | None = None) -> datetime | None:
    """只处理 Demo 里最常见的几种说法，真实解析交给 LLM。"""
    base = now or datetime.now()
    day_offset = 0
    if "后天" in message:
        day_offset = 2
    elif "明天" in message:
        day_offset = 1
    elif "今天" in message:
        day_offset = 0

    hour: int | None = None
    minute = 0
    match = re.search(r"(\d{1,2})\s*[:：]\s*(\d{2})", message)
    if match:
        hour, minute = int(match.group(1)), int(match.group(2))
    else:
        match = re.search(r"(下午|晚上|中午|上午)?\s*(\d{1,2}|[一二两三四五六七八九十]{1,3})\s*点(?:\s*(\d{1,2})\s*分)?", message)
        if match:
            period, raw_hour = match.group(1), match.group(2)
            hour = int(raw_hour) if raw_hour.isdigit() else _cn_hour(raw_hour)
            minute = int(match.group(3)) if match.group(3) else 0
            if hour is None:
                return None
            if period in ("下午", "晚上") and hour < 12:
                hour += 12
            if period == "中午" and hour < 11:
                hour += 12

    has_day = day_offset > 0 or "今天" in message
    if hour is None and not has_day:
        return None
    if not 0 <= (hour or 0) <= 23:
        return None

    target = (base + timedelta(days=day_offset)).replace(
        hour=hour if hour is not None else 9, minute=minute, second=0, microsecond=0
    )
    return target


def looks_like_title(message: str) -> bool:
    text = message.strip()
    return 2 <= len(text) <= 64 and not REWARD_PATTERN.fullmatch(text)


class RuleExtractor(Extractor):
    """确定性兜底抽取：类型、金额、时间用关键词和正则。

    它不是要替代模型，而是保证"模型未接入时 Demo 仍然可跑"，
    同时给模型抽取提供一个可对比的基线。
    """

    def extract(self, message: str, draft: TaskDraft) -> dict[str, object]:
        patch: dict[str, object] = {}
        if draft.type is None:
            guessed = guess_type(message)
            if guessed:
                patch["type"] = guessed
        reward = guess_reward_cents(message)
        if reward is not None:
            patch["reward_cents"] = reward
        start = guess_start_time(message)
        if start is not None:
            patch["start_time"] = start
        return patch


class EmptyExtractor(Extractor):
    """空实现：永远返回空 patch，用于测试最差情况。"""

    def extract(self, message: str, draft: TaskDraft) -> dict[str, object]:
        return {}


class LLMExtractor(Extractor):
    """真实模型抽取器（待实现）。

    实现要点：
    1. 把 TaskDraft 的 JSON Schema 和当前草稿一起放进提示词；
    2. 要求模型只输出 JSON，不要解释；
    3. 用 TaskDraft.model_validate 二次校验，失败则抛 ExtractionError；
    4. 只返回模型明确给出的字段，不要补默认值。
    """

    def __init__(self, base_url: str, api_key: str, model: str) -> None:
        self.base_url = base_url
        self.api_key = api_key
        self.model = model

    def extract(self, message: str, draft: TaskDraft) -> dict[str, object]:
        raise NotImplementedError("接入 LLM 后在这里实现：调用 OpenAI 兼容接口并返回字段 patch")


def default_extractor() -> Extractor:
    """Demo 默认用规则兜底；接入模型后改成 LLMExtractor 即可。"""
    return RuleExtractor()
