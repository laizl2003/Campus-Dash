"""标题回填：只在没有其他字段可解释这句话时才兜底。"""

from __future__ import annotations

from app.agent.extractor import Extractor
from app.models import TaskDraft
from app.service import ChatService


class DistractingExtractor(Extractor):
    """模拟模型：回答标题时顺手把地点也抽出来，导致 patch 非空。"""

    def extract(self, message: str, draft: TaskDraft) -> dict[str, object]:
        if draft.type is None:
            return {"type": "DELIVERY"}
        if "3号楼502" in message:
            return {"location_to": "3号楼502"}
        return {}


def test_empty_patch_falls_back_to_title():
    """模型什么都没抽到时，把这句话当标题填上。"""
    service = ChatService(DistractingExtractor())
    session = service.create_session()
    session.draft = TaskDraft(
        type="DELIVERY", location_from="菜鸟驿站", reward_cents=800,
    )
    reply = service.chat(session.id, "取快递到5号楼")
    assert reply["draft"]["title"] == "取快递到5号楼"


def test_patch_with_fields_is_not_forced_into_title():
    """patch 已经解释这句话（这里被当成地点）时，不硬塞成标题。

    "为什么这样是对的"：模型在追问标题时会收到专门的提示，优先由它给出 title；
    规则层只在完全没抽到东西时兜底，避免把"菜鸟驿站送到3号楼502"这种句子误当标题。
    """
    service = ChatService(DistractingExtractor())
    session = service.create_session()
    session.draft = TaskDraft(type="DELIVERY", reward_cents=800)
    reply = service.chat(session.id, "送到3号楼502")
    assert reply["draft"]["title"] is None
    assert reply["draft"]["location_to"] == "3号楼502"


def test_type_answer_does_not_become_title():
    service = ChatService(DistractingExtractor())
    session = service.create_session()
    reply = service.chat(session.id, "跑腿配送")
    assert reply["draft"]["type"] == "DELIVERY"
    assert reply["draft"]["title"] is None
