"""意图识别：信息补齐之后，判断用户这句话想做什么。

为什么需要它
------------
之前只要字段齐了，用户说什么都被塞进任务内容。结果"确认发布""改一下金额"
这类指令也变成了任务内容，语义完全错位。

设计要点
--------
1. 意图由**模型**判断（语言理解本来就是模型的强项），
   但**动作由程序执行**：模型只输出一个标签，不直接改数据。
2. 模型不可用或返回非法值时，退回关键词兜底，保证对话不中断。
3. 意图是开放集合，后续接"工具调用"时只需在 Intent 里加一项，
   再在 agent/graph.py 的对应分支里实现，不影响现有流程。
"""

from __future__ import annotations

import json
import re
from enum import Enum


class Intent(str, Enum):
    """READY 状态下用户可能的意图。"""

    # 补充任务细节（默认）
    APPEND = "APPEND"
    # 明确表示不再补充
    FINISH = "FINISH"
    # 要求提交/发布
    PUBLISH = "PUBLISH"
    # 想修改某个已有字段
    EDIT = "EDIT"
    # 询问当前草稿内容
    QUERY = "QUERY"
    # 取消这次任务
    CANCEL = "CANCEL"
    # 与任务无关的闲聊或提问
    CHITCHAT = "CHITCHAT"


INTENT_LABELS: dict[Intent, str] = {
    Intent.APPEND: "补充任务内容",
    Intent.FINISH: "确认不再补充",
    Intent.PUBLISH: "要求发布",
    Intent.EDIT: "要求修改字段",
    Intent.QUERY: "询问草稿信息",
    Intent.CANCEL: "要求取消",
    Intent.CHITCHAT: "无关闲聊",
}

INTENT_SYSTEM_PROMPT = """你是 CampusDash 校园跑腿助手的意图分类器。

用户的任务信息已经收集完整，当前处于"待确认发布"状态。
请判断用户这句话属于哪一类意图，只输出一个英文标签。

可选标签：
- APPEND   补充任务细节（如"快递比较大""麻烦下午五点前送到""轻拿轻放"）
- FINISH   表示不再补充（如"没有了""就这些""可以了""不用了"）
- PUBLISH  要求提交或发布（如"发布吧""确认发布""提交""就这个"）
- EDIT     要求修改已有字段（如"把金额改成12块""送达地点写错了""标题换个说法"）
- QUERY    询问当前草稿信息（如"现在是什么内容""多少钱""送到哪")
- CANCEL   要求取消这次任务（如"算了""不发了""取消"）
- CHITCHAT 与任务无关的话（如"你是谁""今天天气怎么样"）

判断原则：
1. 只要是在描述任务相关的细节，就是 APPEND；
2. 明确表达"提交/发布/确认"是 PUBLISH；
3. 指出某个字段要改是 EDIT；
4. 拿不准时选 APPEND（补充内容不会造成数据损坏）。

只输出标签本身，不要解释。"""


def build_intent_prompt(message: str, draft_summary: str) -> str:
    return (
        f"当前草稿：{draft_summary}\n\n"
        f"用户这句话：{message}\n\n"
        "请只输出一个意图标签。"
    )


# ---------------------------------------------------------------- 兜底规则

_PUBLISH_WORDS = ("发布", "提交", "确认发", "就这个", "就这样发", "可以发", "发吧", "下单")
_FINISH_WORDS = ("没有", "没有了", "不用", "没了", "不需要", "就这些", "行了", "可以了", "够了", "好了")
_CANCEL_WORDS = ("算了", "不发了", "取消", "别发了", "放弃")
# 字段词和动词的先后顺序不固定："把金额改成12块"和"改成12块金额"都要命中
_EDIT_FIELD = r"(金额|价格|悬赏|标题|地点|时间|送达|取货|位置|内容|描述)"
_EDIT_VERB = r"(改|换|调整|修改|更新|写错)"
_EDIT_PATTERN = re.compile(rf"{_EDIT_VERB}.{{0,8}}{_EDIT_FIELD}|{_EDIT_FIELD}.{{0,8}}{_EDIT_VERB}")
_QUERY_PATTERN = re.compile(r"(多少钱|什么内容|送到哪|从哪里|几点|现在.*(是|为)|看看|显示|是什么)")
_CHITCHAT_PATTERN = re.compile(r"(你是谁|你叫什么|天气|几点钟|讲个笑话|你能做什么|怎么用)")


def guess_intent_by_rules(message: str) -> Intent:
    """关键词兜底：模型不可用时保证行为可预测。

    顺序很重要：先判断"取消"再判断"发布"，
    因为"算了不发了"里同时含有否定和发布语义。
    """
    text = message.strip().rstrip("。.！!~ ")
    if not text:
        return Intent.APPEND

    if any(word in text for word in _CANCEL_WORDS):
        return Intent.CANCEL
    if any(word in text for word in _FINISH_WORDS):
        return Intent.FINISH
    if _EDIT_PATTERN.search(text):
        return Intent.EDIT
    if any(word in text for word in _PUBLISH_WORDS):
        return Intent.PUBLISH
    if _QUERY_PATTERN.search(text):
        return Intent.QUERY
    if _CHITCHAT_PATTERN.search(text):
        return Intent.CHITCHAT
    return Intent.APPEND


def normalize_intent(raw: str) -> Intent | None:
    """把模型输出解析成 Intent，非法值返回 None。"""
    if not raw:
        return None
    text = raw.strip().strip('"\'`*。.!！')
    # 模型可能输出 "APPEND: 因为..." 或 json
    if text.startswith("{"):
        try:
            data = json.loads(text)
            text = str(data.get("intent", "")).strip()
        except json.JSONDecodeError:
            return None
    token = re.split(r"[\s:，,]", text, maxsplit=1)[0].upper()
    try:
        return Intent(token)
    except ValueError:
        return None
