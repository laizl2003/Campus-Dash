"""入口路由：这句话该走发单、查询、接单还是领券。

先用关键词做确定性判断（快、可测），命中不了再让模型判一次；
模型不可用时退回 task，保持 v1 行为不变。
"""

from __future__ import annotations

import re
from enum import Enum


class Route(str, Enum):
    TASK = "task"      # 发单（v1 状态机）
    QUERY = "query"    # 查数据
    GRAB = "grab"      # 接单
    COUPON = "coupon"  # 领券
    CANCEL = "cancel"  # 取消已发布的任务
    SETTLE = "settle"  # 确认结算已送达的任务


# 领券：必须同时出现"券"和领取动作
_COUPON_ACTION = ("领", "领取", "收了", "薅")
# 接单：出现接/抢 + 单
_GRAB_WORDS = ("接单", "接个单", "接一单", "接两单", "接单吧", "抢单", "帮我接", "去接")
_GRAB_PATTERN = re.compile(r"(接|抢)\s*[一两二三四五12345]?\s*单")
# 取消：既要出现"取消"，又要指向某个已发布的任务（避免和"取消这一单草稿"混淆）
_CANCEL_WORDS = ("取消", "撤销", "退掉")
_CANCEL_TARGETS = ("订单", "那一单", "这单", "单子", "发布过", "已发布", "发过的", "任务")
# 结算：确认收货/结算，只针对"我发布的、已送达"的单
_SETTLE_WORDS = ("结算", "确认收货", "确认完成", "收货", "结账", "确认一下")
_SETTLE_TARGETS = ("订单", "任务", "单", "送达", "送到")
# 查询：出现这些词就问数据，而不是发单
_QUERY_WORDS = (
    "余额", "钱包", "多少钱", "信用", "排名", "排行榜", "我的任务", "我发了",
    "发过哪些", "优惠券", "我的券", "几张券", "还剩多少", "账户",
    # 平台侧数据：广场/大厅/可接的单，这些词跟"当前草稿"没有歧义
    "广场", "大厅", "可接", "能接", "有什么单", "有哪些单", "可领",
)
# 明显的发单动词：命中后即使带了"多少"也按发单处理
_TASK_VERBS = ("取", "送", "买", "带", "排队", "代取", "代买", "帮", "拿")


def guess_route(message: str) -> Route | None:
    """关键词规则；返回 None 表示规则判断不了。"""
    text = (message or "").strip()
    if not text:
        return None

    if any(word in text for word in _CANCEL_WORDS) and any(t in text for t in _CANCEL_TARGETS):
        return Route.CANCEL
    if any(word in text for word in _SETTLE_WORDS) and any(t in text for t in _SETTLE_TARGETS):
        return Route.SETTLE
    if "券" in text and any(word in text for word in _COUPON_ACTION):
        return Route.COUPON
    if any(word in text for word in _GRAB_WORDS) or _GRAB_PATTERN.search(text):
        return Route.GRAB

    has_query_word = any(word in text for word in _QUERY_WORDS)
    if has_query_word:
        # "帮我取快递多少钱" 这种是发单 + 问价，交给发单链路
        has_task_verb = any(verb in text for verb in _TASK_VERBS) and "我" not in text[:2]
        if not has_task_verb:
            return Route.QUERY
        return None

    return None


ROUTER_PROMPT = """判断用户这句话属于哪一类，只输出一个词：
task（发布跑腿任务）、query（查询余额/任务/信用/排名/优惠券）、grab（接单）、coupon（领取优惠券）。
不确定就输出 task。

示例：
"我还有多少钱" -> query
"帮我接两单" -> grab
"把能领的券都领了" -> coupon
"帮我取个快递" -> task"""


# 出现这些标记才值得问一次模型；否则直接当发单处理。
# 否则"取消""帮我取快递"这种也会白白等一次模型（慢的时候要几十秒）。
_NEEDS_LLM = ("?", "？", "吗", "多少", "几", "查", "看看", "什么", "哪些", "怎么", "接", "领", "券", "余额", "信用", "排名")


def route_message(message: str, extractor=None, draft=None) -> Route:
    """规则优先；含糊且像提问时才问模型；模型不可用退回 task。"""
    route = guess_route(message)
    if route is not None:
        return _draft_aware(route, message, draft)

    text = message or ""
    if not any(mark in text for mark in _NEEDS_LLM):
        return Route.TASK

    if extractor is None:
        return Route.TASK
    try:
        answer = extractor.classify_text(ROUTER_PROMPT, message)
    except Exception:  # noqa: BLE001
        return Route.TASK
    if not answer:
        return Route.TASK
    value = answer.strip().lower()
    for route in Route:
        if route.value in value:
            return _draft_aware(route, message, draft)
    return Route.TASK


# 明确指向"账户/平台数据"的词；草稿已就绪时靠它区分"查余额"和"看当前这单多少钱"
_ACCOUNT_WORDS = (
    "余额", "钱包", "信用", "排名", "排行榜", "券", "我的任务", "我发",
    "账户", "我还", "我接", "我领",
    # 明确在看平台，而不是在改手上这一单
    "广场", "大厅", "可接", "能接", "有什么单", "有哪些单", "可领",
)


def _draft_aware(route: Route, message: str, draft) -> Route:
    """草稿已经就绪时，模糊的问句优先理解成"看当前这一单"，而不是查账户。"""
    if draft is not None and route is Route.CANCEL:
        # 手上这一单还没发出去，"取消"就是丢掉草稿，别去动已发布的单
        try:
            from ..models import required_slots
            pending = draft.model_dump(exclude_none=True)
        except Exception:  # noqa: BLE001
            pending = {}
        if any(value not in (None, "", [], {}) for key, value in pending.items() if key != "title"):
            return Route.TASK
        return route
    if route is not Route.QUERY or draft is None:
        return route
    from ..models import required_slots
    try:
        ready = not required_slots(draft)
    except Exception:  # noqa: BLE001
        return route
    if ready and not any(word in message for word in _ACCOUNT_WORDS):
        return Route.TASK
    return route

