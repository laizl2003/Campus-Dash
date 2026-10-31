"""标题自动总结 + 代排队只要排队地点。"""

from __future__ import annotations

from app.agent.extractor import Extractor
from app.models import (
    Slot, TaskDraft, compose_title, next_question, purpose_of, required_slots,
)
from app.service import ChatService


class TypeExtractor(Extractor):
    """只认类型，其余交给规则/兜底，用来验证"标题不用回答"。"""

    def extract(
        self, message: str, draft: TaskDraft, history: list[dict] | None = None
    ) -> dict[str, object]:
        patch: dict[str, object] = {}
        if draft.type is None:
            if "外卖" in message or "快递" in message:
                patch["type"] = "DELIVERY"
            elif "排队" in message:
                patch["type"] = "QUEUE"
        # 悬赏也顺手解析，否则会一直卡在追问金额
        if "5块" in message:
            patch["reward_cents"] = 500
        return patch


def test_purpose_strips_polite_prefix_and_tail():
    assert purpose_of("帮忙取外卖") == "取外卖"
    assert purpose_of("帮我把书还到图书馆，5块") == "把书还到图书馆"
    assert purpose_of("送外卖") == "送外卖"


def test_title_adds_location_when_known():
    assert compose_title(TaskDraft(title_base="取外卖")) == "取外卖"
    assert compose_title(TaskDraft(title_base="取外卖", location_from="西区东门")) == "西区东门取外卖"


def test_title_does_not_repeat_location():
    draft = TaskDraft(title_base="西区东门取外卖", location_from="西区东门")
    assert compose_title(draft) == "西区东门取外卖"


def test_title_is_never_required():
    draft = TaskDraft(type="DELIVERY", location_from="A", location_to="B", reward_cents=800)
    assert Slot.TITLE not in required_slots(draft)


def test_first_sentence_becomes_title():
    """用户开头说"送外卖"，标题就自动是它，不再被追问标题。"""
    service = ChatService(TypeExtractor())
    session = service.create_session()
    reply = service.chat(session.id, "送外卖")
    assert reply["draft"]["title"] == "送外卖"
    assert "title" not in reply["missing"]
    assert "任务标题是什么" not in reply["message"]


def test_title_grows_with_location():
    service = ChatService(TypeExtractor())
    session = service.create_session()
    service.chat(session.id, "帮忙取外卖")
    reply = service.chat(session.id, "西区东门")
    assert reply["draft"]["title"] == "西区东门取外卖"


def test_queue_only_needs_one_location():
    draft = TaskDraft(type="QUEUE", reward_cents=500)
    assert required_slots(draft) == [Slot.LOCATION_FROM]


def test_queue_question_mentions_queuing():
    text = next_question([Slot.LOCATION_FROM], draft=TaskDraft(type="QUEUE"))
    assert "在哪里排队" in text
    assert "取货" not in text


def test_queue_complete_without_delivery_location():
    service = ChatService(TypeExtractor())
    session = service.create_session()
    reply = service.chat(session.id, "帮我代排队，5块")
    assert reply["draft"]["type"] == "QUEUE"
    reply = service.chat(session.id, "一食堂二楼窗口")
    assert reply["status"] == "READY"
    assert reply["draft"]["location_from"] == "一食堂二楼窗口"
    assert reply["draft"]["location_to"] is None
