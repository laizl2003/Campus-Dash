"""LangGraph 发单状态机。

图结构
------
    START
      -> extract        抽取本轮字段，合并进草稿
      -> check          计算缺失槽位
      -> ask_user       有缺失：生成追问，回到等待输入
      -> ready          无缺失：产出可确认的草稿
      -> END

为什么不用"让 LLM 自己决定下一步"
--------------------------------
业务要求是"缺字段必须问、问完必须能发"。如果让模型自由 ReAct，
可能出现漏问、重复问、甚至自己脑补字段。这里把控制流固定在代码里，
模型只做单点抽取，所以行为可预测、可测试。

状态如何跨轮保存
--------------
每轮对话传入上一轮的 draft，先合并再判断缺失，
所以用户只回答"8 块"时，之前的地点、时间不会被清空。
真正的多会话持久化（Redis/MySQL）放在后续里程碑，不影响本 Demo。
"""

from __future__ import annotations

import re
from typing import TypedDict

from langgraph.graph import END, START, StateGraph

from .extractor import TYPE_KEYWORDS, Extractor, guess_start_time, guess_reward_cents
from .intent import UNCLEAR_REPLY, Intent, guess_intent_by_rules
from ..models import (
    READY_HINT,
    SLOT_QUESTIONS,
    Slot,
    TaskDraft,
    append_description,
    is_complete,
    is_no_more,
    merge_draft,
    next_question,
    required_slots,
)


class AgentState(TypedDict, total=False):
    message: str
    draft: TaskDraft
    missing: list[Slot]
    question: str | None
    status: str
    extracted: dict[str, object]
    # 本轮是否真的把用户输入并入了任务内容
    appended: bool
    # READY 状态下识别出的意图
    intent: Intent | None
    # 本轮是否清空了任务内容
    cleared: bool
    # 本轮是否要求重新展示卡片
    show_draft: bool
    # 本轮意图是否无法确定
    unclear: bool


class AgentReply(TypedDict, total=False):
    status: str
    message: str
    draft: dict[str, object]
    missing: list[str]
    question: str | None
    intent: str | None
    can_publish: bool
    cleared: bool
    show_draft: bool
    unclear: bool


def build_graph(extractor: Extractor):
    """编译后的图可以直接 invoke(state)，也可以在测试里逐步驱动。"""

    def extract_node(state: AgentState) -> AgentState:
        current = state["draft"]
        message = state.get("message", "")

        # 必填项已经齐了：先判断用户这句话想做什么，再决定怎么处理。
        # 之前这里是"不管说什么都追加到任务内容"，导致"发布吧""改金额"也变成任务内容。
        if not required_slots(current):
            intent = _classify(extractor, message, current)

            if intent == Intent.FINISH:
                return {"draft": current, "extracted": {}, "appended": False, "intent": intent}
            if intent == Intent.PUBLISH:
                # 只标记"用户想发布"，真正调用发布接口由前端按钮或后续工具执行
                return {"draft": current, "extracted": {}, "appended": False, "intent": intent}
            if intent == Intent.EDIT:
                # 修改意图交给抽取器解析成字段 patch，而不是塞进描述
                try:
                    patch = extractor.extract(message, current)
                except Exception:
                    patch = {}
                updated = merge_draft(current, patch)
                return {"draft": updated, "extracted": patch, "appended": False, "intent": intent}
            if intent in (Intent.QUERY, Intent.CHITCHAT, Intent.CANCEL, Intent.UNCLEAR):
                # 这几类不改草稿，只回话。
                # UNCLEAR 尤其重要：宁可回固定引导，也不把看不懂的内容写进任务。
                return {"draft": current, "extracted": {}, "appended": False, "intent": intent}
            if intent == Intent.SHOW_DRAFT:
                # 要求再看一次卡片：不改草稿，只让前端重新插一张卡片
                return {
                    "draft": current,
                    "extracted": {},
                    "appended": False,
                    "show_draft": True,
                    "intent": intent,
                }
            if intent == Intent.CLEAR_DESCRIPTION:
                # 清空任务内容，其余字段保持不动
                return {
                    "draft": current.model_copy(update={"description": None}),
                    "extracted": {},
                    "appended": False,
                    "cleared": True,
                    "intent": intent,
                }
            # APPEND：把这句话并入任务内容，并做去口语化整理
            summarized = _summarize(extractor, current.description, message)
            updated = current.model_copy(update={"description": summarized[:300] if summarized else None}) \
                if summarized else append_description(current, message)
            change = updated.description != current.description
            return {"draft": updated, "extracted": {}, "appended": change, "intent": intent}
        # 本轮是补齐必填字段：首次进入 READY，不带 appended 标记
        # 这样文案是"信息已完整"，而不是"保持现状"

        try:
            patch = extractor.extract(message, current)
        except Exception:
            # 抽取失败不能中断对话：本轮当作"没有新增信息"，仍然继续追问
            patch = {}
        # 追问阶段也允许模型顺带抽出任务细节（description）。
        # 用户常说"明天下午三点，快递有点大需要小推车"，
        # 时间进 start_time、细节进 description，不该整句塞进某一个字段。
        draft = merge_draft(current, patch)
        # 用户回答追问时，可能只给一个值；只回填"当前正在追问"的那个槽位。
        # 顺序必须是：先合并抽取结果 -> 用"上一轮"的缺失追问回填。
        # 如果用合并后的重新计算，刚通过抽取填好类型的这一轮会被当成"在问标题"，
        # 于是"跑腿配送"被写进 title。
        # 只有当模型确实给出了描述时才跳过裸回答回填；
        # 否则"快递比较大"这句还是会被当成当前追问的裸回答（比如地点），造成错位。
        draft = _apply_pending_answer(
            state.get("message", ""),
            draft,
            state.get("missing", []),
            patch,
        )
        return {"draft": draft, "extracted": patch}

    def check_node(state: AgentState) -> AgentState:
        draft = state["draft"]
        missing = required_slots(draft)
        return {"draft": draft, "missing": missing}

    def ask_node(state: AgentState) -> AgentState:
        missing = state["missing"]
        return {
            "status": "ASKING",
            "question": next_question(missing),
            "missing": missing,
        }

    def ready_node(state: AgentState) -> AgentState:
        # 保留 None：字段抽取分支没设置 appended，说明是首次完整，
        # 不能默认成 False（那会显示成"保持现状"）
        return {
            "status": "READY",
            "question": None,
            "missing": [],
            "appended": state.get("appended"),
            "intent": state.get("intent"),
            "cleared": state.get("cleared"),
            "show_draft": state.get("show_draft"),
        }

    def route_after_check(state: AgentState) -> str:
        return "ready" if not state["missing"] else "ask"

    graph = StateGraph(AgentState)
    graph.add_node("extract", extract_node)
    graph.add_node("check", check_node)
    graph.add_node("ask_user", ask_node)
    graph.add_node("ready", ready_node)

    graph.add_edge(START, "extract")
    graph.add_edge("extract", "check")
    graph.add_conditional_edges("check", route_after_check, {"ask": "ask_user", "ready": "ready"})
    graph.add_edge("ask_user", END)
    graph.add_edge("ready", END)
    return graph.compile()


def _summarize(extractor: Extractor, existing: str | None, message: str) -> str | None:
    """让模型把任务内容整理成书面描述。

    模型不可用或返回空时返回 None，调用方退回原文拼接，
    这样"整理"是增强项，不是单点故障。
    """
    try:
        return extractor.summarize_description(existing, message)
    except Exception:
        return None


def _classify(extractor: Extractor, message: str, draft: TaskDraft) -> Intent:
    """识别意图：优先用模型，拿不到就用关键词兜底。

    模型只负责"这是哪类意图"，不负责执行，所以即使模型判错，
    最坏结果也只是把一句话归错类，不会破坏草稿数据。
    """
    try:
        intent = extractor.classify_intent(message, draft)
        if intent is not None:
            return intent
    except Exception:
        pass
    return guess_intent_by_rules(message)


def _is_type_answer(text: str) -> bool:
    """用户只报类型（"跑腿配送""帮买""其他帮忙"）时，不要把它当标题。"""
    cleaned = text.strip()
    labels = {"跑腿配送", "跑腿", "帮买", "代排队", "排队", "其他帮忙", "其他"}
    return cleaned in labels or (len(cleaned) <= 6 and any(cleaned == label for label in labels))


# 金额片段：8块 / 8 元 / 15.5块钱 / 悬赏8块
_MONEY_IN_TITLE = re.compile(r"[，,、\s]*(?:悬赏|酬劳|给)?\s*\d+(?:\.\d{1,2})?\s*(?:元|块|块钱|块钱左右)(?:钱)?")
# 时间片段：明天下午三点 / 后天上午10点 / 11月1日
_TIME_IN_TITLE = re.compile(
    r"[，,、\s]*(?:今天|明天|后天|大后天)?\s*(?:上午|下午|中午|晚上)?\s*"
    r"(?:\d{1,2}\s*[:：]\s*\d{2}|\d{1,2}\s*点(?:\s*\d{1,2}\s*分)?)"
)
# 句首时间："明天下午三点帮我去…" -> "帮我去…"
# 天、时段、时刻三段都是可选的，但至少要有一段，否则会把标题开头吃掉
_DAY_WORD = r"(?:今天|明天|后天|大后天)"
_PERIOD_WORD = r"(?:上午|下午|中午|晚上)"
_CLOCK = r"(?:\d{1,2}\s*[:：]\s*\d{2}|[0-9一二两三四五六七八九十]{1,3}\s*点(?:\s*[0-9一二三四五六七八九十]{1,3}\s*分)?|\d{1,2}\s*月\s*\d{1,2}\s*[日号])"

_LEADING_TIME = re.compile(
    rf"^(?:{_DAY_WORD})?\s*(?:{_PERIOD_WORD})?\s*(?:{_CLOCK})?[，,、\s]*"
)

# 尾部寒暄/语气
_TAIL_NOISE = re.compile(r"[，,、\s]*(?:谢谢|麻烦了|拜托了|可以吗|行吗|好吗)\s*[。.!！]?$")


def clean_title(text: str) -> str:
    """从用户整句里提炼标题。

    用户常说"帮我取快递，8块"，整句当标题会把金额也写进去，
    列表里显示成"帮我取快递，8块"，信息重复且难看。
    这里剥掉金额、时间和尾部寒暄，只留事件本身。
    """
    title = text.strip()
    title = _MONEY_IN_TITLE.sub("", title)
    title = _TIME_IN_TITLE.sub("", title)
    # 时间常在句首（"明天下午三点帮我去取快递，8块"），单独处理开头。
    # 只有真的匹配到时间片段才剥离，避免把普通标题开头吃掉。
    stripped = _LEADING_TIME.sub("", title)
    if stripped != title and len(stripped) >= 2:
        title = stripped
    title = _TAIL_NOISE.sub("", title)
    title = re.sub(r"[，,、\s]{2,}", "，", title).strip("，,、 。.!！")
    # 剥完太短说明原句主要是时间和金额，退回原文
    return title if len(title) >= 2 else text.strip()[:64]


def _patch_covers_message(patch: dict[str, object]) -> bool:
    """判断模型是否已经理解这句话。

    只要抽出任意字段，就说明模型读懂了这一轮的意思，
    此时把整句再按"裸回答"回填会污染已被正确解析的字段。
    """
    return any(value not in (None, "") for value in patch.values())


def _looks_like_title(text: str) -> bool:
    """判断一句话更像"任务标题"还是"单纯的地点/描述回答"。

    规则：短句且包含任务动词关键词（取/送/买/排队…）视为标题。
    用户在回答"从哪里取货"时不会说"取快递到3号楼"，
    所以这个判定能把标题和多轮追问的裸回答区分开。
    """
    if not 2 <= len(text) <= 64:
        return False
    keywords = {keyword for _, words in TYPE_KEYWORDS for keyword in words}
    return any(keyword in text for keyword in keywords)


def _apply_pending_answer(
    message: str,
    draft: TaskDraft,
    missing: list[Slot],
    patch: dict[str, object] = {},
) -> TaskDraft:
    """把"裸回答"回填到当前追问的槽位。

    例子：上一轮问"送到哪里？"，用户只回"3 号楼 502"，
    抽取器可能给不出 location_to，这里兜底把它填进去。

    约束：
    - 只处理纯文本槽位（地点/描述），金额和时间必须交给抽取器解析，
      否则"8块"会被当成地点；
    - 类型和标题不做盲填：用户回答"跑腿配送"是选类型，不是写标题，
      盲填会把类型短语变成任务标题，并让后续地点全部错位。
    """
    text = message.strip()
    if not text or not missing:
        return draft

    # 模型已经从这句话里抽出了字段（含 description）时，不再做裸回答回填。
    # 否则"明天下午三点，快递有点大"会把整句塞进 start_time 或 location_from，
    # 覆盖掉模型正确解析出的值。
    if _patch_covers_message(patch):
        return draft

    target = missing[0]
    # 这句话只是"上报类型"（如"跑腿配送"）时，不做任何裸回答回填。
    # patch 为空不代表用户在报类型：类型已在上一轮填过，本轮抽取器就不会再返回它，
    # 所以这里必须按文本本身判断，而不是看 patch 是否为空。
    if _is_type_answer(text):
        return draft
    # 问标题时，提炼标题而不是整句照抄
    if target == Slot.TITLE and not draft.title:
        return draft.model_copy(update={"title": clean_title(text)[:64]})
    # 已经在问地点，但这句话带任务动词（如"取快递到3号楼"），
    # 说明用户还在补充任务本身，不能当地址用；地址通常是纯名词短语。
    if _looks_like_title(text):
        if not draft.title:
            return draft.model_copy(update={"title": clean_title(text)[:64]})
        return draft
    if target == Slot.LOCATION_FROM and not draft.location_from:
        return draft.model_copy(update={"location_from": text})
    if target == Slot.LOCATION_TO and not draft.location_to:
        return draft.model_copy(update={"location_to": text})
    if target == Slot.DESCRIPTION and not draft.description:
        return draft.model_copy(update={"description": text})
    return draft


# 信息完整后的三种回复文案
READY_FIRST_MESSAGE = "信息已完整，下面是任务草稿。还需要补充任务内容吗？直接回复补充说明，或点「确认发布」提交。"
READY_APPENDED_MESSAGE = "任务内容添加成功，还需要补充任务内容吗？直接回复补充说明，或点「确认发布」提交。"
READY_STOP_MESSAGE = "好的，任务内容保持现状。点「确认发布」即可提交。"


# 按意图区分的回复文案
INTENT_MESSAGES: dict[Intent, str] = {
    Intent.PUBLISH: "好的，点「确认发布」即可提交。",
    Intent.EDIT: "已按你说的更新草稿，确认无误后点「确认发布」。",
    Intent.QUERY: "下面是当前草稿，需要调整哪里可以直接说。",
    Intent.CANCEL: "好的，这次任务先不发了。需要的话可以重新描述。",
    Intent.CHITCHAT: "我是校园跑腿助手，帮你把需求整理成任务。要继续补充任务内容，还是点「确认发布」？",
    Intent.FINISH: READY_STOP_MESSAGE,
    Intent.CLEAR_DESCRIPTION: "已清空任务内容，其他信息保持不变。需要补充可以继续告诉我。",
    Intent.SHOW_DRAFT: "这是当前的任务卡片，需要调整哪里可以直接说。",
    Intent.UNCLEAR: UNCLEAR_REPLY,
}


def draft_to_reply(
    status: str,
    draft: TaskDraft,
    missing: list[Slot],
    question: str | None,
    appended: bool | None = None,
    intent: Intent | None = None,
) -> AgentReply:
    if status == "READY":
        if intent in (Intent.PUBLISH, Intent.EDIT, Intent.QUERY, Intent.CANCEL,
                      Intent.CHITCHAT, Intent.CLEAR_DESCRIPTION, Intent.SHOW_DRAFT,
                      Intent.UNCLEAR):
            text = INTENT_MESSAGES[intent]
        elif intent == Intent.FINISH:
            text = READY_STOP_MESSAGE
        elif appended:
            text = READY_APPENDED_MESSAGE
        elif appended is False:
            text = READY_STOP_MESSAGE
        else:
            text = READY_FIRST_MESSAGE
    else:
        text = question or "还需要补充一些信息。"
    return {
        "status": status,
        "message": text,
        "draft": draft.model_dump(mode="json"),
        "missing": [slot.value for slot in missing],
        "question": question,
        # 前端据此决定显示草稿卡片还是纯文本回话
        "intent": intent.value if intent else None,
        # READY + PUBLISH 时前端可以直接调发布，不用再让用户点一次
        "can_publish": bool(status == "READY" and intent is not Intent.CANCEL),
        "cleared": bool(intent is Intent.CLEAR_DESCRIPTION),
        # 前端据此在聊天窗里再插一张相同的任务卡片
        "show_draft": bool(intent is Intent.SHOW_DRAFT),
        # 意图不明确：前端可以据此把固定引导做成可点选项
        "unclear": bool(intent is Intent.UNCLEAR),
    }


def question_for(slot: Slot) -> str:
    return SLOT_QUESTIONS[slot]


__all__ = ["AgentReply", "AgentState", "build_graph", "draft_to_reply", "is_complete", "question_for"]
