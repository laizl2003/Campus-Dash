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
    # 要求清空任务内容
    CLEAR_DESCRIPTION = "CLEAR_DESCRIPTION"
    # 要求重新展示任务卡片
    SHOW_DRAFT = "SHOW_DRAFT"


INTENT_LABELS: dict[Intent, str] = {
    Intent.APPEND: "补充任务内容",
    Intent.FINISH: "确认不再补充",
    Intent.PUBLISH: "要求发布",
    Intent.EDIT: "要求修改字段",
    Intent.QUERY: "询问草稿信息",
    Intent.CANCEL: "要求取消",
    Intent.CHITCHAT: "无关闲聊",
    Intent.CLEAR_DESCRIPTION: "清空任务内容",
    Intent.SHOW_DRAFT: "重新展示任务卡片",
}

# 模型输出常带的包裹字符，解析前统一剥掉
QUOTE_CHARS = "\"'`*。.!！\n\t "

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
- CLEAR_DESCRIPTION 要求清空任务内容（如"把任务内容删掉""清空备注""不要任务内容了""去掉刚才那些说明"）
- SHOW_DRAFT 要求再看一次任务卡片（如"再给我看下任务""卡片呢""刚才那个单子再发一遍""重新展示一下""看看现在长什么样"）

判断原则：
1. 只要是在描述任务相关的细节，就是 APPEND；
2. 明确表达"提交/发布/确认"是 PUBLISH；
3. 指出某个字段要改是 EDIT；
4. 明确要求删除/清空任务内容（不是删除整个任务）是 CLEAR_DESCRIPTION；
5. 明确要求"再看一次/重新展示"任务卡片是 SHOW_DRAFT；
6. 拿不准时选 APPEND（补充内容不会造成数据损坏）。

重要：只输出标签本身（例如 APPEND），不要解释、不要标点、不要 JSON。"""


def build_intent_prompt(message: str, draft_summary: str) -> str:
    return (
        f"当前草稿：{draft_summary}\n\n"
        f"用户这句话：{message}\n\n"
        "请只输出一个意图标签。"
    )


# ---------------------------------------------------------------- 兜底规则

_PUBLISH_WORDS = ("发布", "提交", "确认发", "就这个", "就这样发", "可以发", "发吧", "下单")
_PUBLISH_EXCLUDE = ("不用发", "别发", "不发", "稍后发", "先不发", "先别")
_FINISH_WORDS = (
    "没有", "没了", "不用", "不需要", "就这些", "就这样", "行了", "可以了", "够了", "好了",
    "没什么", "没啥", "没别的", "没其他", "别的不", "别的没", "就这么多", "暂时没有", "先这样",
    "没补充", "不补充", "不缺", "没要补充",
    )

# 口语确认词单独放：它们太短，只能整句匹配，
# 否则"好的我要五点送到""可以带个小推车"会被误判成结束
_SHORT_FINISH_RAW = (
    "ok", "ok了", "okay", "好的", "好吧", "行吧", "行", "可以", "嗯", "嗯嗯",
    "收到", "明白", "了解", "中", "成", "好", "好了", "可以了", "好嘞",
    "没问题", "ok好的", "行行",
)

_SHORT_FINISH = {w.lower() for w in _SHORT_FINISH_RAW}
_CANCEL_WORDS = ("算了", "不发了", "取消", "别发了", "放弃", "不做了", "先不做")

# 清空任务内容：必须同时出现"删除类动词"和"内容类名词"，
# 否则"删掉取件码"这类会误伤（那其实是在补充或修改）
_CLEAR_VERB = r"(删|去掉|清除|清空|取消|不要|不用|抹掉)"
_CLEAR_NOUN = r"(任务内容|内容|描述|备注|说明|补充的|刚才说的|那些字)"
# 字段词和动词的先后顺序不固定："把金额改成12块"和"改成12块金额"都要命中
_EDIT_FIELD = r"(金额|价格|悬赏|标题|地点|时间|送达|取货|位置|内容|描述)"
_EDIT_VERB = r"(改|换|调整|修改|更新|写错)"
_EDIT_PATTERN = re.compile(rf"{_EDIT_VERB}.{{0,8}}{_EDIT_FIELD}|{_EDIT_FIELD}.{{0,8}}{_EDIT_VERB}")
_QUERY_PATTERN = re.compile(r"(多少钱|什么内容|送到哪|从哪里|几点|现在.*(是|为)|看看|显示|是什么)")
_CHITCHAT_PATTERN = re.compile(
    r"(你是谁|你叫什么|天气|几点钟|讲个笑话|你能做什么|怎么用)"
)
_CLEAR_PATTERN = re.compile(rf"{_CLEAR_VERB}.{{0,6}}{_CLEAR_NOUN}|{_CLEAR_NOUN}.{{0,6}}{_CLEAR_VERB}")

# 重新展示卡片：要有"再看一次"的语义。
# 注意和 QUERY 区分："多少钱"是问某个值，SHOW_DRAFT 是要求把整张卡片再发一遍。
_SHOW_PATTERN = re.compile(
    r"(再看看|再看一下|再看一遍|看一眼|给我看|看看|展示|显示|发一遍|发一下|"
    r"重新来一遍|再来一次|长什么样|什么样了|"
    r"(任务|单子|卡片|草稿).{0,4}(呢|再|一遍|一下)|呢[？?]?$)"
)
# 问具体字段走 QUERY 不走 SHOW_DRAFT。
# 必须是疑问句才算："删掉取件码改成9933"虽然含"取件码"，但那是在下指令。
_QUESTION_TAIL = re.compile(r"(吗|呢|多少|哪|几|什么|是不是|有没有)[？?]?$")
_QUERY_SPECIFIC = re.compile(r"(多少钱|几块|什么时间|几点|送到哪|从哪里|标题是什么)")
# 客套话只有整句都是它时才算闲聊；"尽快还，谢谢"里带任务信息，应算补充内容
_POLITE_WORDS = ("谢谢", "辛苦了", "感谢", "谢谢啦", "多谢", "哈", "你好", "在吗")


def guess_intent_by_rules(message: str) -> Intent:
    """关键词兜底：模型不可用时保证行为可预测。

    用**子串包含**而不是精确等于：
    用户会说"别的没什么要补充的了""那就这样吧谢谢"，
    精确匹配只能认出"没有了"，这类带前后缀的说法会漏掉。

    顺序很重要：先判断"取消"，因为"算了不发了"同时含有否定和发布语义。
    """
    text = message.strip().rstrip("。.！!~ ")
    if not text:
        return Intent.APPEND

    if any(word in text for word in _CANCEL_WORDS):
        return Intent.CANCEL
    # 清空内容要排在 EDIT 前面："把任务内容删掉"含"改/删"语义，但动作是清空
    if _CLEAR_PATTERN.search(text) and not re.search(r"(改成|换成|更新为)", text):
        return Intent.CLEAR_DESCRIPTION
    # "不用发""先别发"是收回发布意图，不能算发布
    if any(word in text for word in _PUBLISH_EXCLUDE):
        return Intent.FINISH
    # 短确认词要求整句匹配，避免"好的我要五点送到"被当成结束
    normalized = text.lower().replace(" ", "")
    if normalized in _SHORT_FINISH:
        return Intent.FINISH
    # 长句里带明确结束语义才算
    if any(word in text for word in _FINISH_WORDS):
        return Intent.FINISH
    if _EDIT_PATTERN.search(text):
        return Intent.EDIT
    if any(word in text for word in _PUBLISH_WORDS):
        return Intent.PUBLISH
    # 问具体字段优先当 QUERY；只有"再看一次"这类才展示卡片
    if _QUERY_SPECIFIC.search(text) or (("取件码" in text or "金额" in text) and _QUESTION_TAIL.search(text)):
        return Intent.QUERY
    if _SHOW_PATTERN.search(text):
        return Intent.SHOW_DRAFT
    if _QUERY_PATTERN.search(text):
        return Intent.QUERY
    if _CHITCHAT_PATTERN.search(text):
        return Intent.CHITCHAT
    # 整句就是客套话才算闲聊；去掉客套词后没有实质内容才判
    if _is_only_polite(text):
        return Intent.CHITCHAT
    return Intent.APPEND


def _is_only_polite(text: str) -> bool:
    """整句只由客套词和标点组成时返回 True。

    "谢谢你" -> True；"尽快还，谢谢" -> False（带任务信息）。
    """
    stripped = text
    hit = False
    for word in _POLITE_WORDS:
        if word in stripped:
            hit = True
        stripped = stripped.replace(word, "")
    stripped = stripped.strip("，,。.！!~ 、你您们")
    return hit and not stripped


def normalize_intent(raw: str) -> Intent | None:
    """把模型输出解析成 Intent，解析不出返回 None。

    模型输出不总是干净的标签：可能带解释、带标点、甚至一段无意义字符。
    这里做三层尝试：直接解析 -> 整段文本里搜标签 -> 放弃。
    找不到时返回 None，交给规则兜底，不会误判成 APPEND。
    """
    if not raw:
        return None

    # 先去掉控制字符（推理模型偶尔会吐大量 \r）
    text = re.sub(r"[\x00-\x1f]+", " ", raw).strip()
    if not text:
        return None

    text = text.strip(QUOTE_CHARS)

    # 1) 模型返回 JSON
    if text.startswith("{"):
        try:
            data = json.loads(text)
            text = str(data.get("intent", "")).strip()
        except json.JSONDecodeError:
            pass

    # 2) 首个 token 就是标签（"APPEND"、"publish"、"PUBLISH: 因为..."）
    token = re.split(r"[\s:，,]", text, maxsplit=1)[0].upper()
    try:
        return Intent(token)
    except ValueError:
        pass

    # 3) 整段文本里搜完整标签，取最先出现的
    upper = text.upper()
    found = [(upper.find(i.value), i) for i in Intent if upper.find(i.value) != -1]
    if found:
        found.sort(key=lambda pair: pair[0])
        return found[0][1]

    return None
