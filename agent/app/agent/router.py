"""入口路由：这句话该走发单、查询、接单还是领券。

先用关键词做确定性判断（快、可测），命中不了再让模型判一次；
模型不可用时退回 task，保持 v1 行为不变。
"""

from __future__ import annotations

import os
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
    "我接", "接了哪些", "接的单", "接过的",
    "我发布的", "发布的", "进行中", "正在进行", "已结算", "任务列表",
    "发过哪些", "优惠券", "我的券", "几张券", "还剩多少", "账户",
    # 平台侧数据：广场/大厅/可接的单，这些词跟"当前草稿"没有歧义
    "广场", "大厅", "可接", "能接", "有什么单", "有哪些单", "可领",
    # 校园知识库：问位置/营业时间/设施，走 search_knowledge
    "几点", "营业时间", "开门", "关门", "在哪", "怎么走", "几层", "几间",
    "有几个", "场馆", "多少层", "哪栋", "食堂", "超市", "驿站", "打印店", "健身房",
    "游泳馆", "校医院", "宿舍楼", "教学楼", "餐厅", "推荐", "好吃", "哪家",
    "吃饭", "附近",
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
        # "我还有多少钱"这种以"我"开头的问句是在问账户，不是发单；
        # 但"帮我去食堂带份饭"是发单（虽然第二个字也是"我"），所以只看开头
        has_task_verb = any(verb in text for verb in _TASK_VERBS) and not text.startswith("我")
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
"帮我取个快递" -> task
"西区菜鸟驿站几点关门" -> query
"中区综合馆有哪些场馆" -> query
"东区301号楼有几层" -> query
"帮我带一份食堂的饭" -> task
"有没有东区推荐的餐厅" -> query
"附近哪家打印店开着" -> query"""


# 同时有"提问标记"和"业务关键词"时，优先问模型（否则"取消""帮我取快递"会白等一次调用）。
# 注意：ALWAYS_ASK_MODEL=True 时这条快通道已经不拦了，保留它给关闭开关的场景用。
_NEEDS_LLM = (
    "有没有", "推荐", "哪家", "?", "？", "吗", "多少", "几", "查", "看看",
    "什么", "哪些", "怎么", "接", "领", "券", "余额", "信用", "排名",
)


# 规则没命中时是否一律问模型。
# 之前只在句子里有"？/吗/什么"这类提问标记时才问，导致「我发布的正在进行的任务」
# （没有标记）直接落进发单链路，而加个"看看"又能正确走查询——同一个意图两种行为。
# 现在默认总是问一次，用 AGENT_ROUTE_ALWAYS_LLM=false 可退回旧行为（省一次调用）。
ALWAYS_ASK_MODEL = os.getenv("AGENT_ROUTE_ALWAYS_LLM", "true").strip().lower() not in (
    "0", "false", "no", "off",
)

# 填槽阶段的"裸回答"不值得问模型：这些句子一定是任务参数，不是意图
_MONEY_ONLY = re.compile(r"^\d+(?:\.\d+)?\s*(?:元|块|块钱|毛)?$")


def _looks_like_slot_answer(text: str, draft) -> bool:
    """判断这句话是不是在回答追问（"3号楼502""8块""明天下午三点"）。

    这类句子规则能看清，问模型只会让每一轮多等 1~3 秒。
    """
    if draft is None:
        return False
    from ..models import looks_like_place, required_slots

    try:
        if not required_slots(draft):
            return False  # 字段已经齐了，不需要再填槽
    except Exception:  # noqa: BLE001
        return False

    value = text.strip()
    if len(value) > 24:
        return False
    if _MONEY_ONLY.match(value):
        return True
    if any(mark in value for mark in ("?", "？", "吗", "什么", "哪", "多少")):
        return False
    return looks_like_place(value)


def route_message(message: str, extractor=None, draft=None) -> Route:
    """规则优先；规则没命中就问模型；模型不可用退回 task。"""
    route = guess_route(message)
    if route is not None:
        return _draft_aware(route, message, draft, extractor)

    text = message or ""
    if extractor is None:
        return Route.TASK

    if not ALWAYS_ASK_MODEL and not any(mark in text for mark in _NEEDS_LLM):
        return Route.TASK
    # 填槽时的裸回答：跳过模型，省一次往返
    if _looks_like_slot_answer(text, draft):
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
            return _draft_aware(route, message, draft, extractor)
    return Route.TASK

# 快速通道：明确指向"平台数据"的词
_PLATFORM_WORDS = (
    "余额", "钱包", "信用", "排名", "排行榜", "券", "我的任务", "我发",
    "我发布的", "进行中", "正在进行",
    "账户", "我还", "我接", "我领", "广场", "大厅", "可接", "能接",
    "有什么单", "有哪些单", "可领",
)

# 快速通道：明确在说"手上这一单"的词
_DRAFT_WORDS = (
    "多少钱", "金额", "改一下", "改成", "送到哪", "取货", "送达", "标题",
    "截止", "这一单", "这单", "当前这单", "草稿",
)


def judge_subject(message: str, draft, extractor=None) -> str:
    """判断这句话的主语是"平台"还是"当前草稿"，返回 PLATFORM / DRAFT / UNSURE。

    词表先兜确定的情况，剩下的交给模型——靠词表穷举每类数据是补不完的，
    漏一个词就会把"看广场"误判成"看这单"。
    """
    text = message or ""
    is_platform = any(word in text for word in _PLATFORM_WORDS)
    is_draft = any(word in text for word in _DRAFT_WORDS)
    if is_platform and not is_draft:
        return "PLATFORM"
    if is_draft and not is_platform:
        return "DRAFT"
    if extractor is None:
        # 词表都命中说不清时，平台词更具体，按平台处理（原来是这么做的）
        return "PLATFORM" if is_platform else "UNSURE"

    from .prompts import SUBJECT_JUDGE_PROMPT

    try:
        answer = extractor.classify_text(SUBJECT_JUDGE_PROMPT, f"用户这句话：{text}")
    except Exception:  # noqa: BLE001
        return "UNSURE"
    if not answer:
        return "UNSURE"
    value = answer.strip().upper()
    if "PLATFORM" in value:
        return "PLATFORM"
    if "DRAFT" in value:
        return "DRAFT"
    return "UNSURE"

def _draft_aware(route: Route, message: str, draft, extractor=None) -> Route:
    """草稿非空时，判断这句问句到底在问平台还是问手上这一单。

    以前只靠词表白名单，漏词就误判（"看看广场有什么单"会被压回发单）；
    现在词表只做快速通道，判不出来让模型看主语，UNSURE 按保守策略留在发单。
    """
    if draft is not None and route is Route.CANCEL:
        # 手上这一单还没发出去，"取消"就是丢掉草稿，别去动已发布的单
        try:
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
        # 看"草稿里有没有填过东西"，而不是"还缺不缺字段"（空草稿也缺字段）
        has_draft = any(
            value not in (None, "", [], {}) for key, value in draft.model_dump().items()
            if key not in ("title", "title_base")
        )
    except Exception:  # noqa: BLE001
        return route
    if not has_draft:
        return route

    subject = judge_subject(message, draft, extractor)
    return Route.QUERY if subject == "PLATFORM" else Route.TASK

