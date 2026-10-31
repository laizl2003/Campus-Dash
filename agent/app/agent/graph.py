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

from typing import TypedDict

from langgraph.graph import END, START, StateGraph

from .extractor import TYPE_KEYWORDS, Extractor, guess_start_time, guess_reward_cents
from ..models import (
    SLOT_QUESTIONS,
    Slot,
    TaskDraft,
    is_complete,
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


class AgentReply(TypedDict):
    status: str
    message: str
    draft: dict[str, object]
    missing: list[str]
    question: str | None


def build_graph(extractor: Extractor):
    """编译后的图可以直接 invoke(state)，也可以在测试里逐步驱动。"""

    def extract_node(state: AgentState) -> AgentState:
        current = state["draft"]
        try:
            patch = extractor.extract(state.get("message", ""), current)
        except Exception:
            # 抽取失败不能中断对话：本轮当作"没有新增信息"，仍然继续追问
            patch = {}
        draft = merge_draft(current, patch)
        # 用户回答追问时，可能只给一个值；只回填"当前正在追问"的那个槽位。
        # 顺序必须是：先合并抽取结果 -> 用"上一轮"的缺失追问回填。
        # 如果用合并后的重新计算，刚通过抽取填好类型的这一轮会被当成"在问标题"，
        # 于是"跑腿配送"被写进 title。
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
        return {"status": "READY", "question": None, "missing": []}

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


def _is_type_answer(text: str) -> bool:
    """用户只报类型（"跑腿配送""帮买""其他帮忙"）时，不要把它当标题。"""
    cleaned = text.strip()
    labels = {"跑腿配送", "跑腿", "帮买", "代排队", "排队", "其他帮忙", "其他"}
    return cleaned in labels or (len(cleaned) <= 6 and any(cleaned == label for label in labels))


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

    target = missing[0]
    # 这句话只是"上报类型"（如"跑腿配送"）时，不做任何裸回答回填。
    # patch 为空不代表用户在报类型：类型已在上一轮填过，本轮抽取器就不会再返回它，
    # 所以这里必须按文本本身判断，而不是看 patch 是否为空。
    if _is_type_answer(text):
        return draft
    # 问标题时，整句话就是标题；问其他槽位时，如果回答带任务动词，
    # 说明用户仍在描述任务本身，先补标题，不盲填成地点。
    if not draft.title and _looks_like_title(text) and target != Slot.LOCATION_FROM:
        return draft.model_copy(update={"title": text})
    if target == Slot.TITLE and not draft.title:
        return draft.model_copy(update={"title": text})
    if target == Slot.LOCATION_FROM and not draft.location_from:
        return draft.model_copy(update={"location_from": text})
    if target == Slot.LOCATION_TO and not draft.location_to:
        return draft.model_copy(update={"location_to": text})
    if target == Slot.DESCRIPTION and not draft.description:
        return draft.model_copy(update={"description": text})
    return draft


def draft_to_reply(status: str, draft: TaskDraft, missing: list[Slot], question: str | None) -> AgentReply:
    if status == "READY":
        text = "信息已完整，下面是任务草稿，请确认后发布。"
    else:
        text = question or "还需要补充一些信息。"
    return {
        "status": status,
        "message": text,
        "draft": draft.model_dump(mode="json"),
        "missing": [slot.value for slot in missing],
        "question": question,
    }


def question_for(slot: Slot) -> str:
    return SLOT_QUESTIONS[slot]


__all__ = ["AgentReply", "AgentState", "build_graph", "draft_to_reply", "is_complete", "question_for"]
